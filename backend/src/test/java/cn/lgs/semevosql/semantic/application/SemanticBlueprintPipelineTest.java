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
package cn.lgs.semevosql.semantic.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlanningDecision;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlannerProfile;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningRequest;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService.PlanningRecall;
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticBlueprintPipelineTest {

    @Test void interruptedDecisionsRetainAllModelCallsWithoutResolvingOrExecutingAPlan() {
        for(var outcome:List.<SemanticPlanningOutcome>of(
                new SemanticPlanningOutcome.Rejected("INVALID_GOVERNED_SELECTION","invalid selection"),
                new SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING","Confirm?",List.of(),"source"))) {
            var catalog=mock(SemanticCatalogApplicationService.class);
            var planner=mock(SemanticBlueprintGenerationService.class);
            var examples=mock(ValidatedQueryExampleService.class);
            var request=request();
            when(catalog.recallPlanning(12L,18L,request.query(),20))
                .thenReturn(new PlanningRecall(List.of("left_orders"),List.of()));
            when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(candidates());
            when(examples.recallHints(eq(12L),eq(18L),eq("catalog"),eq(request.query()),anyInt()))
                .thenReturn(QueryCaseHints.empty());
            var calls=List.of(new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("first",
                    cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING,"private response",1,21),
                new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("repair",
                    cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING,"private repair",1,34));
            when(planner.planDecision(eq(request.query()),any(),anyCollection(),any(),any(),eq(PlannerProfile.CONFIGURED)))
                .thenReturn(new PlanningDecision(outcome,calls));
            var pipeline=new SemanticBlueprintPipeline(catalog,planner,examples);
            assertThatThrownBy(()->pipeline.plan(request)).satisfies(error -> {
                var retained=error instanceof SemanticPlanningRejectedException rejected ? rejected.modelCalls()
                    : ((SemanticPlanningClarificationRequiredException)error).modelCalls();
                assertThat(retained).containsExactlyElementsOf(calls);
            });
            verify(catalog,org.mockito.Mockito.never()).buildBlueprint(any(),any(),anyString(),anyCollection(),any());
        }
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void fourRecallSourcesStartTogetherAndSharedSuggestionsRemainSeparateFromExecutableAssets() throws Exception {
        var catalog=mock(SemanticCatalogApplicationService.class);
        var planner=mock(SemanticBlueprintGenerationService.class);
        var examples=mock(ValidatedQueryExampleService.class);
        var history=mock(cn.lgs.semevosql.learning.QueryCaseHistoryService.class);
        var own=mock(cn.lgs.semevosql.clarification.PersonalDefinitionRetrievalService.class);
        var shared=mock(cn.lgs.semevosql.clarification.ProjectDefinitionCandidateService.class);
        var input=new cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryInput("run","attempt",12L,18L,"catalog",
            "alice","total and daily","daily","task-2","root-snapshot",0);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var release=new java.util.concurrent.CountDownLatch(1);
        var historyFuture=new java.util.concurrent.CompletableFuture<cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryContext>();
        var context=cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryContext.empty();
        var suggestion=new SemanticCandidateSet.ProjectSuggestion(91,2,"custom total","The confirmed full meaning","left","TEXT_ONLY");
        when(own.retrieve(12L,"alice","daily")).thenAnswer(call->{ready.countDown();org.junit.jupiter.api.Assertions.assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return List.of();});
        when(shared.retrieve(12L,18L,"alice","daily")).thenAnswer(call->{ready.countDown();org.junit.jupiter.api.Assertions.assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return List.of(suggestion);});
        when(history.startPlanning(input)).thenReturn(historyFuture);when(history.existingContext(input)).thenReturn(context);
        when(catalog.recallPlanning(12L,18L,"daily",20)).thenAnswer(call->{
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));
            verify(history).startPlanning(input);historyFuture.complete(context);release.countDown();
            return new PlanningRecall(List.of("left_orders"),List.of());
        });
        var base=candidates();
        when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(base);
        when(planner.planDecision(eq("daily"),any(),anyCollection(),any(),any(),eq(PlannerProfile.CONFIGURED),
            org.mockito.ArgumentMatchers.isNull(),eq(""))).thenReturn(new PlanningDecision(
                new SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING","Confirm?",List.of(),"suggestion","custom total"),List.of()));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var pipeline=new SemanticBlueprintPipeline(catalog,planner,examples,history);
            pipeline.definitionRecall(own,shared,executor);
            org.junit.jupiter.api.Assertions.assertThrows(SemanticPlanningClarificationRequiredException.class,()->pipeline.plan(
                new PlanningRequest(12L,18L,"catalog","daily",null,List.of(),QueryCaseHints.empty(),20,5,null,"alice",null,input)));
            verify(planner).planDecision(eq("daily"),argThat(slice -> slice.projectSuggestions().equals(List.of(suggestion))
                && slice.metrics().equals(base.metrics()) && slice.confirmedDefinitions().isEmpty()),anyCollection(),any(),any(),
                eq(PlannerProfile.CONFIGURED),org.mockito.ArgumentMatchers.isNull(),eq(""));
            verify(planner).candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),argThat(models->models.contains("left")));
            org.mockito.Mockito.verifyNoInteractions(examples);
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void todoHistoryStartsAlongsideCatalogRecallAndItsModelsReachCandidateAssembly() {
        var catalog=mock(SemanticCatalogApplicationService.class);
        var planner=mock(SemanticBlueprintGenerationService.class);
        var examples=mock(ValidatedQueryExampleService.class);
        var history=mock(cn.lgs.semevosql.learning.QueryCaseHistoryService.class);
        var input=new cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryInput("run","attempt",12L,18L,"catalog",
            "alice","total and daily","daily","task-2","root-snapshot",0);
        var hints=hints(null);
        var context=new cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryContext("frozen request and task trajectory",hints,Map.of("task-2:revision","snapshot"));
        var pending=new java.util.concurrent.CompletableFuture<cn.lgs.semevosql.learning.QueryCaseHistoryService.HistoryContext>();
        when(history.startPlanning(input)).thenReturn(pending);
        when(history.existingContext(input)).thenReturn(context);
        when(catalog.recallPlanning(12L,18L,"daily",20)).thenAnswer(call->{
            verify(history).startPlanning(input);
            pending.complete(context); // Joining history before catalog recall would deadlock this test.
            return new PlanningRecall(List.of("left_orders"),List.of());
        });
        when(planner.physicalTablesForHistoricalModels(12L,18L,hints.modelCodes())).thenReturn(List.of("right_orders"));
        var candidates=candidates();
        when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(candidates);
        when(planner.planDecision(eq("daily"),eq(candidates),anyCollection(),eq(hints),any(),eq(PlannerProfile.CONFIGURED),
                org.mockito.ArgumentMatchers.isNull(),eq(context.prompt())))
            .thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(hints),List.of()));
        when(catalog.buildBlueprint(eq(12L),eq(18L),eq("daily"),anyCollection(),eq(hints)))
            .thenReturn(SemanticBlueprint.builder().executable(true).build());
        var result=new SemanticBlueprintPipeline(catalog,planner,examples,history).plan(new PlanningRequest(
            12L,18L,"catalog","daily",null,List.of(),QueryCaseHints.empty(),20,5,null,"alice",null,input));
        assertThat(result.historyContext()).isEqualTo(context);
        verify(planner).candidates(eq(12L),eq(18L),argThat(tables -> tables.containsAll(List.of("left_orders","right_orders"))),anyCollection(),argThat(models -> models.containsAll(hints.modelCodes())));
        org.mockito.Mockito.verifyNoInteractions(examples);
    }

    @Test void questionAndConfirmedConstraintsRemainSeparateAcrossTheApplicationPipeline() {
        var catalog=mock(SemanticCatalogApplicationService.class);
        var planner=mock(SemanticBlueprintGenerationService.class);
        var examples=mock(ValidatedQueryExampleService.class);
        String question="计算自定义业务指标";
        String confirmed=question+"\n已确认：该指标是基础金额的一半。";
        var candidates=candidates();
        var binding=hints(null);
        when(examples.recallHints(eq(12L),eq(18L),eq("catalog"),eq(confirmed),
            org.mockito.ArgumentMatchers.isNull(),eq("alice"),eq(5))).thenReturn(QueryCaseHints.empty());
        when(catalog.recallPlanning(12L,18L,confirmed,20)).thenReturn(new PlanningRecall(List.of("left_orders"),List.of()));
        when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(candidates);
        when(planner.planDecision(eq(new SemanticPlanningInput(question,confirmed)),eq(candidates),anyCollection(),any(),any(),
                eq(PlannerProfile.CONFIGURED),org.mockito.ArgumentMatchers.isNull(),eq("")))
            .thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(binding),List.of()));
        when(catalog.buildBlueprint(eq(12L),eq(18L),eq(confirmed),anyCollection(),eq(binding)))
            .thenReturn(SemanticBlueprint.builder().executable(true).build());
        var result=new SemanticBlueprintPipeline(catalog,planner,examples).plan(new PlanningRequest(12L,18L,"catalog",confirmed,
            null,List.of(),QueryCaseHints.empty(),20,5,null,"alice",null,null,List.of(),question));
        assertThat(result.plan().isExecutable()).isTrue();
        verify(planner).planDecision(eq(new SemanticPlanningInput(question,confirmed)),eq(candidates),anyCollection(),any(),any(),
            eq(PlannerProfile.CONFIGURED),org.mockito.ArgumentMatchers.isNull(),eq(""));
    }

    @Test void originalDefinitionIntentAndAcceptedMeaningReachTheSamePlannerWithoutChangingAnswerCoverage() {
        var catalog=mock(SemanticCatalogApplicationService.class);
        var planner=mock(SemanticBlueprintGenerationService.class);
        var examples=mock(ValidatedQueryExampleService.class);
        String question="查询一月自定义金额";
        String definition="自定义金额是所有订单金额减五元。";
        String original=question+"。"+definition+"请以后记住。";
        var receipt=new SemanticPlanningInput.DefinitionConfirmation("自定义金额",definition,"USER","question","alice");
        var candidates=candidates();
        when(catalog.recallPlanning(12L,18L,question,20)).thenReturn(new PlanningRecall(List.of("left_orders"),List.of()));
        when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(candidates);
        when(examples.recallHints(eq(12L),eq(18L),eq("catalog"),eq(question),
            org.mockito.ArgumentMatchers.isNull(),eq("alice"),eq(5))).thenReturn(QueryCaseHints.empty());
        var expected=new SemanticPlanningInput(question,question,original,List.of(receipt));
        when(planner.planDecision(eq(expected),eq(candidates),anyCollection(),any(),any(),eq(PlannerProfile.CONFIGURED),
            org.mockito.ArgumentMatchers.isNull(),eq(""))).thenReturn(new PlanningDecision(
                new SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING","确认剩余时间口径",List.of(),"事实仍不完整"),List.of()));
        assertThatThrownBy(()->new SemanticBlueprintPipeline(catalog,planner,examples).plan(new PlanningRequest(
            12L,18L,"catalog",question,null,List.of(),QueryCaseHints.empty(),20,5,null,"alice",null,null,List.of(),
            question,original,List.of(receipt)))).isInstanceOf(SemanticPlanningClarificationRequiredException.class);
        verify(planner).planDecision(eq(expected),eq(candidates),anyCollection(),any(),any(),eq(PlannerProfile.CONFIGURED),
            org.mockito.ArgumentMatchers.isNull(),eq(""));
        verify(catalog,org.mockito.Mockito.never()).buildBlueprint(any(),any(),anyString(),anyCollection(),any());
    }

	@Test
	void periodComparisonBaselineDoesNotBecomeRelativeObservationFilter() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.timeRange(SemanticBlueprint.TimeRangeSelection.builder()
				.modelCode("orders")
				.timeColumn("paid_at")
				.relativeExpression("PREVIOUS_MONTH")
				.granularity("MONTH")
				.build())
			.build();

		SemanticBlueprintPipeline.reconcileComputationIntent(plan,
				new ComputationIntent(Set.of(Capability.PERIOD_COMPARISON, Capability.TIME_BUCKET)));

		assertThat(plan.getTimeRange()).isNull();
	}

	@Test
	void explicitObservationFilterIsPreservedAlongsidePeriodComparison() {
		SemanticBlueprint.TimeRangeSelection timeRange = SemanticBlueprint.TimeRangeSelection.builder()
			.modelCode("orders")
			.timeColumn("paid_at")
			.relativeExpression("PREVIOUS_MONTH")
			.granularity("MONTH")
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder().timeRange(timeRange).build();

		SemanticBlueprintPipeline.reconcileComputationIntent(plan,
				new ComputationIntent(Set.of(Capability.PERIOD_COMPARISON, Capability.TIME_FILTER)));

		assertThat(plan.getTimeRange()).isSameAs(timeRange);
	}

	@Test
	void deterministicResolutionFailureGetsOneGovernedSemanticRepair() {
		SemanticCatalogApplicationService catalogService = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintGenerationService planner = mock(SemanticBlueprintGenerationService.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		SemanticBlueprintPipeline pipeline = new SemanticBlueprintPipeline(catalogService, planner, examples);
		SemanticCandidateSet candidates = candidates();
		QueryCaseHints initial = hints(null);
		QueryCaseHints repaired = hints(new QueryCaseHints.ResultCompositionHint("SCALAR", "gap=ABS(left_count-right_count)"));
		SemanticBlueprint invalid = SemanticBlueprint.builder()
			.executable(false)
			.validationErrors(List.of("A published merge policy is required for a multi-source query"))
			.build();
		SemanticBlueprint valid = SemanticBlueprint.builder().executable(true).build();
		PlanningRequest request = request();

		when(catalogService.recallPlanning(12L, 18L, request.query(), request.recallLimit()))
			.thenReturn(new PlanningRecall(List.of("left_orders", "right_orders"), List.of()));
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection(), anyCollection())).thenReturn(candidates);
		when(examples.recallHints(eq(12L), eq(18L), eq("catalog"), eq(request.query()), anyInt()))
			.thenReturn(QueryCaseHints.empty());
		when(planner.planDecision(eq(request.query()), eq(candidates), anyCollection(), any(QueryCaseHints.class),
				any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED)))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(initial), List.of()));
		when(catalogService.buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(), any(QueryCaseHints.class)))
			.thenReturn(invalid, valid);
		when(planner.repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString()))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(repaired), List.of()));

		SemanticBlueprintPipeline.PlanningResult result = pipeline.plan(request);

		assertThat(result.plan()).isSameAs(valid);
		assertThat(result.binding().resultComposition()).isEqualTo(repaired.resultComposition());
		verify(planner, times(1)).repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString());
		verify(catalogService, times(2)).buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(),
				any(QueryCaseHints.class));
	}

	@Test
	void deterministicResolutionExceptionGetsOneGovernedSemanticRepair() {
		SemanticCatalogApplicationService catalogService = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintGenerationService planner = mock(SemanticBlueprintGenerationService.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		SemanticBlueprintPipeline pipeline = new SemanticBlueprintPipeline(catalogService, planner, examples);
		SemanticCandidateSet candidates = candidates();
		QueryCaseHints initial = hints(null);
		QueryCaseHints repaired = hints(new QueryCaseHints.ResultCompositionHint("SCALAR", "gap=ABS(left_count-right_count)"));
		SemanticBlueprint valid = SemanticBlueprint.builder().executable(true).build();
		PlanningRequest request = request();

		when(catalogService.recallPlanning(12L, 18L, request.query(), request.recallLimit()))
			.thenReturn(new PlanningRecall(List.of("left_orders", "right_orders"), List.of()));
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection(), anyCollection())).thenReturn(candidates);
		when(examples.recallHints(eq(12L), eq(18L), eq("catalog"), eq(request.query()), anyInt()))
			.thenReturn(QueryCaseHints.empty());
		when(planner.planDecision(eq(request.query()), eq(candidates), anyCollection(), any(QueryCaseHints.class),
				any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED)))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(initial), List.of()));
		when(catalogService.buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(), any(QueryCaseHints.class)))
			.thenThrow(new IllegalArgumentException("Invalid SCALAR resultComposition shape"))
			.thenReturn(valid);
		when(planner.repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED),
				eq("Invalid SCALAR resultComposition shape")))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(repaired), List.of()));

		SemanticBlueprintPipeline.PlanningResult result = pipeline.plan(request);

		assertThat(result.plan()).isSameAs(valid);
		assertThat(result.binding().resultComposition()).isEqualTo(repaired.resultComposition());
		verify(planner, times(1)).repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED),
				eq("Invalid SCALAR resultComposition shape"));
		verify(catalogService, times(2)).buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(),
				any(QueryCaseHints.class));
	}

	@Test
	void plannerSelectedCandidateModelsAreIncludedInBlueprintMaterialization() {
		SemanticCatalogApplicationService catalogService = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintGenerationService planner = mock(SemanticBlueprintGenerationService.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		SemanticBlueprintPipeline pipeline = new SemanticBlueprintPipeline(catalogService, planner, examples);
		SemanticCandidateSet candidates = candidates();
		QueryCaseHints binding = hints(new QueryCaseHints.ResultCompositionHint("SCALAR", "gap=ABS(left_count-right_count)"));
		SemanticBlueprint valid = SemanticBlueprint.builder().executable(true).build();
		PlanningRequest request = request();

		when(catalogService.recallPlanning(12L, 18L, request.query(), request.recallLimit()))
			.thenReturn(new PlanningRecall(List.of("left_orders"), List.of()));
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection(), anyCollection())).thenReturn(candidates);
		when(examples.recallHints(eq(12L), eq(18L), eq("catalog"), eq(request.query()), anyInt()))
			.thenReturn(QueryCaseHints.empty());
		when(planner.planDecision(eq(request.query()), eq(candidates), anyCollection(), any(QueryCaseHints.class),
				any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED)))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(binding), List.of()));
		when(catalogService.buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(), eq(binding)))
			.thenReturn(valid);

		SemanticBlueprintPipeline.PlanningResult result = pipeline.plan(request);

		assertThat(result.plan()).isSameAs(valid);
		verify(catalogService).buildBlueprint(eq(12L), eq(18L), eq(request.query()),
				argThat(tables -> tables.contains("left_orders") && tables.contains("right_orders")), eq(binding));
	}

	@Test
	void deterministicResolutionRepairStillFailsClosedWhenPlanRemainsInvalid() {
		SemanticCatalogApplicationService catalogService = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintGenerationService planner = mock(SemanticBlueprintGenerationService.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		SemanticBlueprintPipeline pipeline = new SemanticBlueprintPipeline(catalogService, planner, examples);
		SemanticCandidateSet candidates = candidates();
		QueryCaseHints initial = hints(null);
		QueryCaseHints repaired = hints(new QueryCaseHints.ResultCompositionHint("SCALAR", null));
		SemanticBlueprint invalid = SemanticBlueprint.builder()
			.executable(false)
			.validationErrors(List.of("multi-source policy remains invalid"))
			.build();
		PlanningRequest request = request();

		when(catalogService.recallPlanning(12L, 18L, request.query(), request.recallLimit()))
			.thenReturn(new PlanningRecall(List.of("left_orders", "right_orders"), List.of()));
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection(), anyCollection())).thenReturn(candidates);
		when(examples.recallHints(eq(12L), eq(18L), eq("catalog"), eq(request.query()), anyInt()))
			.thenReturn(QueryCaseHints.empty());
		when(planner.planDecision(eq(request.query()), eq(candidates), anyCollection(), any(QueryCaseHints.class),
				any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED)))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(initial), List.of()));
		when(catalogService.buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(), any(QueryCaseHints.class)))
			.thenReturn(invalid);
		when(planner.repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString()))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(repaired), List.of()));

		assertThatThrownBy(() -> pipeline.plan(request))
			.isInstanceOf(SemanticPlanningRejectedException.class)
			.hasMessageContaining("remains non-executable after semantic repair");
		verify(planner, times(1)).repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString());
	}

	@Test
	void deterministicResolutionExceptionStillFailsClosedAfterOneRepair() {
		SemanticCatalogApplicationService catalogService = mock(SemanticCatalogApplicationService.class);
		SemanticBlueprintGenerationService planner = mock(SemanticBlueprintGenerationService.class);
		ValidatedQueryExampleService examples = mock(ValidatedQueryExampleService.class);
		SemanticBlueprintPipeline pipeline = new SemanticBlueprintPipeline(catalogService, planner, examples);
		SemanticCandidateSet candidates = candidates();
		QueryCaseHints initial = hints(null);
		QueryCaseHints repaired = hints(new QueryCaseHints.ResultCompositionHint("SCALAR", null));
		PlanningRequest request = request();

		when(catalogService.recallPlanning(12L, 18L, request.query(), request.recallLimit()))
			.thenReturn(new PlanningRecall(List.of("left_orders", "right_orders"), List.of()));
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection(), anyCollection())).thenReturn(candidates);
		when(examples.recallHints(eq(12L), eq(18L), eq("catalog"), eq(request.query()), anyInt()))
			.thenReturn(QueryCaseHints.empty());
		when(planner.planDecision(eq(request.query()), eq(candidates), anyCollection(), any(QueryCaseHints.class),
				any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED)))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(initial), List.of()));
		when(catalogService.buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(), any(QueryCaseHints.class)))
			.thenThrow(new IllegalArgumentException("Invalid SCALAR resultComposition shape"))
			.thenThrow(new IllegalArgumentException("SCALAR shape remains invalid"));
		when(planner.repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString()))
			.thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(repaired), List.of()));

		assertThatThrownBy(() -> pipeline.plan(request))
			.isInstanceOf(SemanticPlanningRejectedException.class)
			.hasMessageContaining("remains non-executable after semantic repair")
			.hasMessageContaining("SCALAR shape remains invalid");
		verify(planner, times(1)).repairAfterResolutionFailure(eq(request.query()), eq(candidates), anyCollection(),
				any(QueryCaseHints.class), any(QueryCaseHints.class), eq(PlannerProfile.CONFIGURED), anyString());
		verify(catalogService, times(2)).buildBlueprint(eq(12L), eq(18L), eq(request.query()), anyCollection(),
				any(QueryCaseHints.class));
	}

	private PlanningRequest request() {
		return new PlanningRequest(12L, 18L, "catalog", "compare independent counts", List.of(), QueryCaseHints.empty(),
				20, 10);
	}

    @Test void incompleteObservationFilterUsesExistingSemanticRepairBeforeApproval() {
        var catalog=mock(SemanticCatalogApplicationService.class);
        var planner=mock(SemanticBlueprintGenerationService.class);
        var examples=mock(ValidatedQueryExampleService.class);
        var supplied=candidates();var binding=hints(null);var request=request();
        var intent=new ComputationIntent(Set.of(Capability.TIME_FILTER,Capability.TIME_BUCKET));
        var missing=SemanticBlueprint.builder().executable(true).build();
        var fixed=SemanticBlueprint.builder().executable(true).timeRange(
            SemanticBlueprint.TimeRangeSelection.builder().modelCode("left").timeColumn("observed_at")
                .startInclusive("2026-01-01T00:00").endExclusive("2026-04-01T00:00").build()).build();
        when(catalog.recallPlanning(12L,18L,request.query(),request.recallLimit()))
            .thenReturn(new PlanningRecall(List.of("left_orders"),List.of()));
        when(planner.candidates(eq(12L),eq(18L),anyCollection(),anyCollection(),anyCollection())).thenReturn(supplied);
        when(examples.recallHints(eq(12L),eq(18L),eq("catalog"),eq(request.query()),anyInt()))
            .thenReturn(QueryCaseHints.empty());
        when(planner.planDecision(eq(request.query()),eq(supplied),anyCollection(),any(),any(),eq(PlannerProfile.CONFIGURED)))
            .thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(binding,intent),List.of()));
        when(catalog.buildBlueprint(eq(12L),eq(18L),eq(request.query()),anyCollection(),any()))
            .thenReturn(missing,fixed);
        when(planner.repairAfterResolutionFailure(eq(request.query()),eq(supplied),anyCollection(),any(),any(),
            eq(PlannerProfile.CONFIGURED),org.mockito.ArgumentMatchers.contains("TIME_FILTER was requested")))
            .thenReturn(new PlanningDecision(new SemanticPlanningOutcome.Resolved(binding,intent),List.of()));
        var result=new SemanticBlueprintPipeline(catalog,planner,examples).plan(request);
        assertThat(result.plan()).isSameAs(fixed);
        verify(planner,times(1)).repairAfterResolutionFailure(eq(request.query()),eq(supplied),anyCollection(),any(),any(),
            eq(PlannerProfile.CONFIGURED),org.mockito.ArgumentMatchers.contains("TIME_FILTER was requested"));
    }

    @Test void missingIntervalCannotBeSatisfiedByGroupingOrNonTemporalFilter() {
        var plan=SemanticBlueprint.builder().executable(true).computationIntent(
            new ComputationIntent(Set.of(Capability.TIME_FILTER,Capability.TIME_BUCKET)))
            .groupBy(List.of(SemanticBlueprint.GroupSelection.builder().modelCode("left")
                .columnName("observed_at").timeBucketGranularity("MONTH").build()))
            .filters(List.of(SemanticBlueprint.FilterSelection.builder().modelCode("left")
                .columnName("status").operator("EQ").value("PAID").build())).build();
        assertThatThrownBy(()->SemanticBlueprintPipeline.requireResolvedTimeFilter(plan,candidates()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TIME_FILTER was requested");
    }

	private SemanticCandidateSet candidates() {
		return new SemanticCandidateSet(12L, 18L, "catalog", Set.of("left_orders", "right_orders"),
				List.of(
					SemanticCatalogSnapshot.Model.builder().modelCode("left").physicalTable("left_orders")
						.status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Model.builder().modelCode("right").physicalTable("right_orders")
						.status(SemanticAssetStatus.ENABLED).build()),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of());
	}

	private QueryCaseHints hints(QueryCaseHints.ResultCompositionHint composition) {
		return new QueryCaseHints(Set.of("left", "right"), Set.of("left_count", "right_count"), Set.of(), Set.of(), Set.of(),
				Set.of(), List.of(), List.of(), List.of(), null, true, "LLM_SEMANTIC_PLANNER", List.of(), 0.9,
				Map.of("semanticPlanner", 0.9), composition);
	}

}
