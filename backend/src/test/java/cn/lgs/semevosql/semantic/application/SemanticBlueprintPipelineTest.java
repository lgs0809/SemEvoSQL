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
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection())).thenReturn(candidates);
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
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection())).thenReturn(candidates);
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
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection())).thenReturn(candidates);
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
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection())).thenReturn(candidates);
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
		when(planner.candidates(eq(12L), eq(18L), anyCollection(), anyCollection())).thenReturn(candidates);
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
