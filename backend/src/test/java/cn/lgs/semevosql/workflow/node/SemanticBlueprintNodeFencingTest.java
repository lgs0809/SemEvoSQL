/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.CATALOG_HASH;
import static cn.lgs.semevosql.constant.Constant.FORCE_SEMANTIC_REPLAN;
import static cn.lgs.semevosql.constant.Constant.INPUT_KEY;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService.BindingContext;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.optimization.RuntimeOptimizationService;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningResult;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.trajectory.TrajectoryAnalysisService;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SemanticBlueprintNodeFencingTest {
    @Test void previousReviewedTaskIsPassedAsContextWithoutBecomingAMandatoryBinding() {
        var catalogs=mock(SemanticCatalogApplicationService.class);var pipeline=mock(SemanticBlueprintPipeline.class);
        var examples=mock(ValidatedQueryExampleService.class);var runs=mock(QueryRunService.class);
        var fence=mock(RunExecutionFenceService.class);var snapshots=mock(ExecutionSnapshotService.class);
        var trajectories=mock(TrajectoryAnalysisService.class);var optimizations=mock(RuntimeOptimizationService.class);
        var fingerprints=mock(ConversationContextDependencyFingerprintService.class);var clarifications=mock(RuntimeClarificationService.class);
        var bindings=mock(RuntimeSemanticBindingService.class);var tasks=mock(QueryTaskRepository.class);
        var node=new SemanticBlueprintNode(catalogs,pipeline,examples,runs,fence,snapshots,trajectories,optimizations,fingerprints,clarifications,bindings,tasks);
        var empty=new BindingContext(List.of(),QueryCaseHints.empty(),List.of());
        when(bindings.merge(any())).thenReturn(empty);
        when(tasks.enabled("run-1")).thenReturn(true);
        when(tasks.list("run-1")).thenReturn(List.of(
            new cn.lgs.semevosql.task.QueryTask("first",0,"收入金额",List.of(),cn.lgs.semevosql.task.QueryTask.TaskStatus.DONE),
            new cn.lgs.semevosql.task.QueryTask("second",1,"支出金额",List.of(),cn.lgs.semevosql.task.QueryTask.TaskStatus.ACTIVE)));
        var previous=SemanticBlueprint.builder().models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("credits").build()))
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("credit_amount").build())).build();
        when(tasks.plan("run-1","first")).thenReturn(previous);
        var plan=SemanticBlueprint.builder().executable(true).build();
        var trace=new SemanticBlueprintPipeline.PlanningTrace("fixture-planning","fixture-hash",java.util.Set.of(),0,0,0,0,0,0,0,0,0,0);
        when(pipeline.plan(any())).thenReturn(new PlanningResult(plan,null,QueryCaseHints.empty(),QueryCaseHints.empty(),trace));
        node.apply(new OverAllState(Map.of(INPUT_KEY,"支出金额",PROJECT_ID,1L,PROJECT_VERSION_ID,2L,RUN_ID,"run-1",
            ATTEMPT_ID,"attempt-1",FORCE_SEMANTIC_REPLAN,true,cn.lgs.semevosql.constant.Constant.TODO_ENABLED,true,
            cn.lgs.semevosql.constant.Constant.ACTIVE_TODO_ID,"second",cn.lgs.semevosql.constant.Constant.ORIGINAL_REQUEST,"收入金额和支出金额")));
        verify(pipeline).plan(org.mockito.ArgumentMatchers.argThat(request -> request.requiredHints().emptyHints()
            && request.previousTaskHints().metricCodes().equals(java.util.Set.of("credit_amount"))
            && request.requestQuestion().equals("支出金额") && request.currentUserMessage().equals("收入金额和支出金额")));
        verify(tasks).savePlan("run-1","second",plan);
    }

    @Test void selectedPrivateDifferencePausesBeforePlanApprovalAndPublicChoiceDoesNot() {
        var catalogs=mock(SemanticCatalogApplicationService.class);var pipeline=mock(SemanticBlueprintPipeline.class);
        var examples=mock(ValidatedQueryExampleService.class);var runs=mock(QueryRunService.class);
        var fence=mock(RunExecutionFenceService.class);var snapshots=mock(ExecutionSnapshotService.class);
        var trajectories=mock(TrajectoryAnalysisService.class);var optimizations=mock(RuntimeOptimizationService.class);
        var fingerprints=mock(ConversationContextDependencyFingerprintService.class);var clarifications=mock(RuntimeClarificationService.class);
        var bindings=mock(RuntimeSemanticBindingService.class);var tasks=mock(QueryTaskRepository.class);
        var updates=mock(cn.lgs.semevosql.clarification.PersonalPublicDefinitionService.class);
        var difference=mock(cn.lgs.semevosql.clarification.PersonalPublicDefinitionService.Difference.class);
        var node=new SemanticBlueprintNode(catalogs,pipeline,examples,runs,fence,snapshots,trajectories,optimizations,fingerprints,clarifications,bindings,tasks);
        node.personalPublicDefinitions(updates);
        var privateRef=SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER").sourceRecordId(4L).build();
        var plan=SemanticBlueprint.builder().executable(true).bindingDependencies(List.of(privateRef)).build();
        var empty=new BindingContext(List.of(),QueryCaseHints.empty(),List.of());
        when(bindings.merge(any())).thenReturn(empty);
        var trace=new SemanticBlueprintPipeline.PlanningTrace("fixture-planning","fixture-hash",java.util.Set.of(),0,0,0,0,0,0,0,0,0,0);
        when(pipeline.plan(any())).thenReturn(new PlanningResult(plan,null,QueryCaseHints.empty(),QueryCaseHints.empty(),trace));
        when(updates.nextSelected(1L,2L,"alice",List.of(privateRef),"run-1")).thenReturn(java.util.Optional.of(difference));
        when(clarifications.createPersonalPublicQuestion("run-1","我的金额",difference)).thenReturn(
            cn.lgs.semevosql.clarification.RuntimeClarification.builder().clarificationId("choice-1").build());
        var state=new OverAllState(Map.of(INPUT_KEY,"我的金额",PROJECT_ID,1L,PROJECT_VERSION_ID,2L,
            RUN_ID,"run-1",ATTEMPT_ID,"attempt-1",FORCE_SEMANTIC_REPLAN,true,cn.lgs.semevosql.constant.Constant.PRINCIPAL_ID,"alice"));
        assertThatThrownBy(()->node.apply(state)).isInstanceOf(cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException.class);
        var ordered=org.mockito.Mockito.inOrder(pipeline,updates,clarifications);
        ordered.verify(pipeline).plan(any());
        ordered.verify(updates).nextSelected(1L,2L,"alice",List.of(privateRef),"run-1");
        ordered.verify(clarifications).createPersonalPublicQuestion("run-1","我的金额",difference);
        verify(runs,never()).appendEvent(anyString(),anyString(),org.mockito.ArgumentMatchers.eq("SEMANTIC_PLAN_SNAPSHOT"),anyString(),anyString(),anyString(),anyString());
        org.mockito.Mockito.verifyNoInteractions(snapshots,tasks);
        plan.setBindingDependencies(List.of());
        when(updates.nextSelected(1L,2L,"alice",List.of(),"run-1")).thenReturn(java.util.Optional.empty());
        var result=node.apply(state);
        org.assertj.core.api.Assertions.assertThat(result.get(cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN)).isSameAs(plan);
        verify(runs).appendEvent(anyString(),anyString(),org.mockito.ArgumentMatchers.eq("SEMANTIC_PLAN_SNAPSHOT"),anyString(),anyString(),anyString(),anyString());
        verify(clarifications,org.mockito.Mockito.times(1)).createPersonalPublicQuestion(anyString(),anyString(),any());
        verify(pipeline,org.mockito.Mockito.times(2)).plan(org.mockito.ArgumentMatchers.argThat(request->request.personalSelectionPending()&&request.requiredHints().emptyHints()));
    }

	@Test
	void slowPlannerReturningAfterTerminalTimeoutCannotPersistSemanticEffects() throws Exception {
		SemanticCatalogApplicationService catalogs = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintPipeline pipeline = mock(SemanticBlueprintPipeline.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		QueryRunService runs = mock(QueryRunService.class);
		ExecutionSnapshotService snapshots = mock(ExecutionSnapshotService.class);
		TrajectoryAnalysisService trajectories = mock(TrajectoryAnalysisService.class);
		RuntimeOptimizationService optimizations = mock(RuntimeOptimizationService.class);
		ConversationContextDependencyFingerprintService fingerprints = mock(
				ConversationContextDependencyFingerprintService.class);
		RuntimeClarificationService clarifications = mock(RuntimeClarificationService.class);
		RuntimeSemanticBindingService bindings = mock(RuntimeSemanticBindingService.class);
		QueryTaskRepository tasks = mock(QueryTaskRepository.class);
		RunExecutionFenceService fence = new RunExecutionFenceService(runs);
		SemanticBlueprintNode node = new SemanticBlueprintNode(catalogs, pipeline, examples, runs, fence, snapshots,
				trajectories, optimizations, fingerprints, clarifications, bindings, tasks);

		AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.RUNNING);
		when(runs.instanceId()).thenReturn("instance-a");
		when(runs.get("run-1")).thenAnswer(ignored -> QueryRun.builder()
			.runId("run-1")
			.status(status.get())
			.attemptId("attempt-1")
			.ownerInstance("instance-a")
			.build());
		when(fingerprints.fingerprint("run-1", "count orders")).thenReturn("context");
		BindingContext empty = new BindingContext(List.of(), QueryCaseHints.empty(), List.of());
		when(bindings.resolve(any(), any(), any(), anyString())).thenReturn(empty);
		when(bindings.merge(any())).thenReturn(empty);
		when(clarifications.resolvedBindingContext("run-1", 1L, 2L)).thenReturn(empty);

		CountDownLatch plannerStarted = new CountDownLatch(1);
		CountDownLatch allowLateReturn = new CountDownLatch(1);
		when(pipeline.plan(any())).thenAnswer(ignored -> {
			plannerStarted.countDown();
			if (!allowLateReturn.await(2, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test planner did not resume");
			}
			SemanticBlueprint plan = SemanticBlueprint.builder().executable(true).build();
			return new PlanningResult(plan, null, QueryCaseHints.empty(), QueryCaseHints.empty(), null);
		});

		OverAllState state = new OverAllState(Map.of(INPUT_KEY, "count orders", PROJECT_ID, 1L, PROJECT_VERSION_ID, 2L,
				CATALOG_HASH, "catalog", RUN_ID, "run-1", ATTEMPT_ID, "attempt-1", FORCE_SEMANTIC_REPLAN, true,
				RUN_DEADLINE_EPOCH_MILLIS, System.currentTimeMillis() + 5_000));
		var future = java.util.concurrent.CompletableFuture.runAsync(() -> node.apply(state));
		if (!plannerStarted.await(2, TimeUnit.SECONDS)) {
			throw new IllegalStateException("test planner did not start");
		}
		status.set(RunStatus.FAILED);
		allowLateReturn.countDown();

		assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
			.hasCauseInstanceOf(LateRunResultDroppedException.class);
		verify(examples, never()).recordHintUsage(anyString(), any());
		verify(runs, never()).appendEvent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyString());
		verify(tasks, never()).savePlan(anyString(), anyString(), any());
	}
}
