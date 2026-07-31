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
