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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.PlannerReasoningProperties;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.PlannerProfile;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService.SemanticPlanningBudgetExceededException;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Metric;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Model;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SemanticPlanningDeadlineTest {

	@Test
	void malformedInitialResponseDoesNotStartRepairWithoutMinimumRemainingBudget() {
		SemanticDocumentExtractionClient client = mock(SemanticDocumentExtractionClient.class);
		SemanticPlanningProperties properties = properties(90, 2, 35);
		doAnswer(invocation -> {
			Thread.sleep(70);
			return call("not-json");
		}).when(client)
			.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING), anyString(), anyString(), any(Duration.class));
		SemanticBlueprintGenerationService planner = planner(client, properties);

		assertThatThrownBy(() -> planner.planDecision("count orders", candidates(), List.<RetrievalHit>of(),
				QueryCaseHints.empty(), QueryCaseHints.empty(), PlannerProfile.CONFIGURED))
			.isInstanceOf(SemanticPlanningBudgetExceededException.class)
			.hasMessageContaining("insufficient for repair");
		verify(client, times(1)).complete(eq(ModelCallPurpose.SEMANTIC_PLANNING), anyString(), anyString(),
				any(Duration.class));
	}

	@Test
	void fastInitialAndRepairCompleteWithinOneSharedDeadline() {
		SemanticDocumentExtractionClient client = mock(SemanticDocumentExtractionClient.class);
		AtomicInteger calls = new AtomicInteger();
		doAnswer(invocation -> call(calls.incrementAndGet() == 1 ? "not-json" : resolvedJson())).when(client)
			.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING), anyString(), anyString(), any(Duration.class));
		SemanticBlueprintGenerationService planner = planner(client, properties(1_000, 2, 20));

		var decision = planner.planDecision("count orders", candidates(), List.<RetrievalHit>of(), QueryCaseHints.empty(),
				QueryCaseHints.empty(), PlannerProfile.CONFIGURED, System.currentTimeMillis() + 900);

		assertThat(decision.outcome()).isInstanceOf(SemanticPlanningOutcome.Resolved.class);
		assertThat(decision.modelCalls()).hasSize(2);
		assertThat(decision.planningSession().startedModelCalls()).isEqualTo(2);
	}

	private SemanticBlueprintGenerationService planner(SemanticDocumentExtractionClient client,
			SemanticPlanningProperties properties) {
		PlannerReasoningProperties reasoning = new PlannerReasoningProperties();
		reasoning.setEnabled(false);
		return new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class), client, reasoning, properties,
				null);
	}

	private SemanticPlanningProperties properties(long totalMs, int calls, long repairMinimumMs) {
		SemanticPlanningProperties properties = new SemanticPlanningProperties();
		properties.setTotalBudgetMs(totalMs);
		properties.setMaxModelCalls(calls);
		properties.setMinimumRepairBudgetMs(repairMinimumMs);
		return properties;
	}

	private ModelCallResult call(String response) {
		return new ModelCallResult("call", ModelCallPurpose.SEMANTIC_PLANNING, response, 1, 1);
	}

	private String resolvedJson() {
		return """
				{"status":"RESOLVED","modelCodes":["orders"],"metricCodes":["order_count"],
				 "dimensionCodes":[],"ruleCodes":[],"relationshipCodes":[],"grainCodes":[],
				 "computationCapabilities":["AGGREGATION"],"enumBindings":[],"filters":[],"confidence":0.9}
				""";
	}

	private SemanticCandidateSet candidates() {
		Model model = Model.builder()
			.modelCode("orders")
			.physicalTable("orders")
			.status(SemanticAssetStatus.ENABLED)
			.build();
		Metric metric = Metric.builder()
			.modelCode("orders")
			.metricCode("order_count")
			.businessName("Order count")
			.expression("COUNT(*)")
			.status(SemanticAssetStatus.ENABLED)
			.build();
		return new SemanticCandidateSet(1L, 1L, "catalog", Set.of("orders"), List.of(model), List.of(metric), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}
}
