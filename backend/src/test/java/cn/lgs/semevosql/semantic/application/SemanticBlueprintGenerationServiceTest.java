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

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.CrossSourceRelationship;
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticBlueprintGenerationServiceTest {

    @Test void missingMetricQuestionOffersEveryExactNameSuggestionWithoutMergingTheirMeanings() {
        var supplied=candidates(time("created_at","created_at")).withProjectSuggestions(List.of(
            new SemanticCandidateSet.ProjectSuggestion(44,3,"确认指标","全部订单金额的一半",null,"TEXT_ONLY"),
            new SemanticCandidateSet.ProjectSuggestion(45,1,"确认指标","已支付订单金额的一半",null,"TEXT_ONLY")));
        String response="""
            {"status":"NEEDS_CLARIFICATION","projectCandidateSelections":[],"clarification":{
              "issueType":"METRIC_MISSING","rawExpression":"确认指标","question":"请补充定义",
              "options":[{"code":"OTHER","label":"补充业务口径"},{"code":"CANCEL","label":"取消"}]}}
            """;
        var outcome=(SemanticPlanningOutcome.ClarificationRequired)SemanticBlueprintGenerationService.projectSuggestionOutcome("查询确认指标",response,supplied);
        assertThat(outcome.options()).extracting(SemanticPlanningOutcome.Option::code)
            .containsExactly("PROJECT_CANDIDATE_44_3","PROJECT_CANDIDATE_45_1","OTHER","CANCEL");
        assertThat(outcome.options()).extracting(SemanticPlanningOutcome.Option::label)
            .contains("全部订单金额的一半","已支付订单金额的一半");
        assertThat(SemanticBlueprintGenerationService.projectSuggestionOutcome("查询别的指标",response,supplied)).isNull();
        assertThat(SemanticBlueprintGenerationService.projectSuggestionOutcome("查询确认指标",
            response.replace("METRIC_MISSING","SEMANTIC_AMBIGUITY"),supplied)).isNull();
    }

    @Test void unpublishedSuggestionMustRouteToConfirmationEvenWhenModelClaimsResolved() {
        var candidates=candidates(time("created_at","created_at")).withProjectSuggestions(List.of(
            new SemanticCandidateSet.ProjectSuggestion(44,3,"确认指标","完整确认公式及适用范围。","orders","TEXT_ONLY")));
        String selected="{\"status\":\"RESOLVED\",\"metricCodes\":[\"order_count\"],\"projectCandidateSelections\":[{\"candidateId\":44,\"contentRevision\":3,\"rawExpression\":\"确认指标\"}]}";
        var outcome=SemanticBlueprintGenerationService.projectSuggestionOutcome("请统计确认指标",selected,candidates);
        var question=org.junit.jupiter.api.Assertions.assertInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class,outcome);
        assertThat(question.rawExpression()).isEqualTo("确认指标");
        assertThat(question.options().get(0).code()).isEqualTo("PROJECT_CANDIDATE_44_3");
        assertThat(question.options().get(0).assetKey()).isNull();
        for(String bad:List.of(selected.replace("44","45"),selected.replace("Revision\":3","Revision\":2"),selected.replace("Expression\":\"确认指标","Expression\":\"另一个名称")))
            assertThatThrownBy(()->SemanticBlueprintGenerationService.projectSuggestionOutcome("请统计确认指标",bad,candidates)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SemanticBlueprintGenerationService.projectSuggestionOutcome("统计订单数","{\"status\":\"RESOLVED\",\"projectCandidateSelections\":[]}",candidates)).isNull();
    }

    @Test void privateSelectionAcceptsOnlyExplicitSuppliedIdentities() {
        var original=candidates(time("created_at","created_at"));
        var reference=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.BindingDependency.builder().source("USER").sourceRecordId(44L).build();
        var candidates=new SemanticCandidateSet(original.projectId(),original.projectVersionId(),original.catalogHash(),original.physicalTables(),
            original.models(),original.metrics(),original.dimensions(),original.enumValues(),original.querySelectableRules(),original.mandatoryGovernanceRules(),
            original.planningPolicies(),original.relationships(),original.grains(),original.timeColumns(),original.filterableColumns(),original.retrievalEvidence(),List.of(reference));
        assertThat(SemanticBlueprintGenerationService.personalDefinitionIds(OfflineCatalogProtocol.parse("{\"personalDefinitionIds\":[44]}").path("personalDefinitionIds"),candidates)).containsExactly(44L);
        for(String value:List.of("[45]","[44,44]","[\"44\"]","44"))assertThatThrownBy(()->SemanticBlueprintGenerationService.personalDefinitionIds(
            OfflineCatalogProtocol.parse("{\"personalDefinitionIds\":"+value+"}").path("personalDefinitionIds"),candidates)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SemanticBlueprintGenerationService.personalDefinitionIds(OfflineCatalogProtocol.parse("{}").path("personalDefinitionIds"),candidates)).isEmpty();
    }

    @Test void missingBusinessFactCanAskNaturallyWithoutInventingAnAssetOrFormula() {
        var planner=new SemanticBlueprintGenerationService(org.mockito.Mockito.mock(cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.class),null);
        var candidates=candidates(time("created_at","created_at"));
        var outcome=planner.explicitNonResolvedOutcome("""
            {"status":"NEEDS_CLARIFICATION","clarification":{"issueType":"METRIC_FILTER_INCOMPLETE",
            "question":"这个指标应包含哪些业务状态？","options":[],"reason":"现有定义未确认统计人群。"}}
            """,candidates);
        assertThat(outcome).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
        assertThat(((SemanticPlanningOutcome.ClarificationRequired)outcome).options()).isEmpty();
        assertThatThrownBy(()->planner.explicitNonResolvedOutcome("""
            {"status":"NEEDS_CLARIFICATION","clarification":{"question":"选择一个口径",
            "options":[{"code":"made_up","label":"凭空定义","assetType":"METRIC","assetKey":"secret"}]}}
            """,candidates)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside governed");
        assertThatThrownBy(()->planner.explicitNonResolvedOutcome("""
            {"status":"NEEDS_CLARIFICATION","clarification":{"question":"选择一个公式",
            "options":[{"code":"invented","label":"任意新公式"}]}}
            """,candidates)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invent");
    }

	@Test
	void genericTemporalGroupingWithMultipleGovernedTimeAxesRequiresClarification() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedTimeAxis(candidates,
				QueryCaseHints.empty(), new ComputationIntent(Set.of(Capability.TIME_BUCKET)));

		assertThat(outcome).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
		SemanticPlanningOutcome.ClarificationRequired clarification = (SemanticPlanningOutcome.ClarificationRequired) outcome;
		assertThat(clarification.options()).extracting(SemanticPlanningOutcome.Option::assetKey)
			.containsExactly("created_at", "paid_at");
	}

	@Test
	void plannerSelectedTimeDimensionCannotSilentlyResolveGenericUserAmbiguity() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));
		QueryCaseHints binding = new QueryCaseHints(Set.of("orders"), Set.of("order_count"), Set.of("paid_at"), Set.of(),
				Set.of(), Set.of(), List.of(), "CURRENT_QUERY", List.of(), 1.0d, Map.of());

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedTimeAxis(candidates, binding,
				new ComputationIntent(Set.of(Capability.TIME_BUCKET)));

		assertThat(outcome).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
	}

	@Test
	void explicitBusinessTimeAxisDoesNotTriggerGenericFallback() {
		SemanticCandidateSet candidates = candidates(time("created_at", "created_at"), time("paid_at", "paid_at"));
		QueryCaseHints binding = new QueryCaseHints(Set.of("orders"), Set.of(), Set.of("paid_at"), Set.of(), Set.of(),
				Set.of(), List.of(), new QueryCaseHints.TimeBindingHint("paid_at", "orders", "paid_at", "QUERY", 1.0d),
				true, "CURRENT_QUERY", List.of(), 1.0d, Map.of());

		SemanticPlanningOutcome outcome = SemanticBlueprintGenerationService.unresolvedTimeAxis(candidates, binding,
				new ComputationIntent(Set.of(Capability.TIME_BUCKET)));

		assertThat(outcome).isNull();
	}

	@Test
	void scalarCompositionAcceptsPlannerDeclaredGovernedMetricCalculation() {
		QueryCaseHints.ResultCompositionHint composition = SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"difference = ABS(order_count - golden_order_count)", Set.of("order_count", "golden_order_count"));

		assertThat(composition.type()).isEqualTo("SCALAR");
		assertThat(composition.calculationExpression()).isEqualTo("difference=ABS(order_count-golden_order_count)");
	}

	@Test
	void scalarCompositionRejectsMetricsThatWereNotSelectedByPlanner() {
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"difference=order_count-unknown_metric", Set.of("order_count", "golden_order_count")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("only selected metric codes");
	}

	@Test
	void scalarCompositionRejectsArbitraryFunctionsAndOperators() {
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.validateResultComposition("SCALAR",
				"ratio=order_count/golden_order_count", Set.of("order_count", "golden_order_count")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("only one binary + or - expression");
	}

	@Test
	void computationRequirementsPreserveTopNAndPeriodComparisonSemanticsWithoutSqlStructure() throws Exception {
		var mapper = JsonUtil.getObjectMapper();
		ComputationIntent intent = SemanticBlueprintGenerationService.computationIntent(
				mapper.readTree("[\"AGGREGATION\",\"PERIOD_COMPARISON\",\"ORDERING\",\"LIMIT\"]"),
				mapper.readTree("""
						[
						  {"capability":"PERIOD_COMPARISON","metricCode":"paid_amount","grain":"month","mode":"previous_period_rate"},
						  {"capability":"ORDERING","mode":"highest","basis":"period_comparison"},
						  {"capability":"LIMIT","limit":3,"scope":"global","basis":"ordering"}
						]
						"""), Set.of("paid_amount"));

		assertThat(intent.capabilities()).contains(Capability.AGGREGATION, Capability.PERIOD_COMPARISON, Capability.ORDERING,
				Capability.LIMIT);
		assertThat(intent.requirements()).hasSize(3);
		assertThat(intent.requirements().get(0).grain()).isEqualTo("MONTH");
		assertThat(intent.requirements().get(0).mode()).isEqualTo("PREVIOUS_PERIOD_RATE");
		assertThat(intent.requirements().get(1).mode()).isEqualTo("HIGHEST");
		assertThat(intent.requirements().get(1).basis()).isEqualTo("PERIOD_COMPARISON");
		assertThat(intent.requirements().get(2).limit()).isEqualTo(3);
		assertThat(intent.requirements().get(2).basis()).isEqualTo("ORDERING");
	}

	@Test
	void computationIntentRemainsBackwardCompatibleWithCapabilityOnlySnapshots() throws Exception {
		ComputationIntent restored = JsonUtil.getObjectMapper()
			.readValue("{\"capabilities\":[\"AGGREGATION\"]}", ComputationIntent.class);

		assertThat(restored.capabilities()).containsExactly(Capability.AGGREGATION);
		assertThat(restored.requirements()).isEmpty();
	}

	@Test
	void computationRequirementRejectsMetricOutsideCurrentSemanticSelection() throws Exception {
		var mapper = JsonUtil.getObjectMapper();
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"),
				mapper.readTree("[{\"capability\":\"PERIOD_COMPARISON\",\"metricCode\":\"refund_amount\"}]"),
				Set.of("paid_amount"))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metricCode must be selected");
	}

	@Test
	void computationRequirementRejectsInvalidLimitAndSqlLikeSemanticTokens() throws Exception {
		var mapper = JsonUtil.getObjectMapper();
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"),
				mapper.readTree("[{\"capability\":\"LIMIT\",\"limit\":0}]"), Set.of("paid_amount")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and 10000");
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"),
				mapper.readTree("[{\"capability\":\"LIMIT\",\"limit\":3.5}]"), Set.of("paid_amount")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be an integer");
		assertThatThrownBy(() -> SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"),
				mapper.readTree("[{\"capability\":\"PERIOD_COMPARISON\",\"mode\":\"LAG(paid_amount) OVER\"}]"),
				Set.of("paid_amount"))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be a semantic token");
	}

	@Test
	void crossSourceRelationshipBecomesGovernedPlannerRelationshipWithoutPhysicalJoinSemantics() {
		CrossSourceRelationship crossSource = CrossSourceRelationship.builder()
			.relationshipCode("pay_to_order")
			.leftModelCode("pay_order")
			.leftKey("user_id")
			.rightModelCode("orders")
			.rightKey("customer_id")
			.cardinality(RelationshipCardinality.MANY_TO_MANY)
			.evidence("published multi-source policy")
			.status(SemanticAssetStatus.ENABLED)
			.build();

		SemanticCatalogSnapshot.Relationship relationship = SemanticBlueprintGenerationService.plannerRelationship(1L, 2L,
				crossSource);

		assertThat(relationship.getRelationshipCode()).isEqualTo("pay_to_order");
		assertThat(relationship.getSourceModelCode()).isEqualTo("pay_order");
		assertThat(relationship.getTargetModelCode()).isEqualTo("orders");
		assertThat(relationship.getJoinType()).isEqualTo("CROSS_SOURCE_MERGE");
		assertThat(relationship.getJoinCondition()).isEqualTo("pay_order.user_id = orders.customer_id");
	}

	private SemanticCandidateSet candidates(SemanticCatalogSnapshot.Dimension... dimensions) {
		return new SemanticCandidateSet(1L, 1L, "hash", Set.of("orders"), List.of(), List.of(), List.of(dimensions),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}

	private SemanticCatalogSnapshot.Dimension time(String code, String column) {
		return SemanticCatalogSnapshot.Dimension.builder()
			.modelCode("orders")
			.dimensionCode(code)
			.businessName(code)
			.columnName(column)
			.dimensionType("TIME")
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}
    @Test void paginationParametersAreStrictAndReachTheFrozenPlan() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var intent=SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[\"OFFSET\",\"LIMIT\"]"),
            mapper.readTree("[{\"capability\":\"OFFSET\",\"offset\":2000000000},{\"capability\":\"LIMIT\",\"limit\":20}]"),java.util.Set.of());
        var plan=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.builder().orderBy(java.util.List.of(
            cn.lgs.semevosql.semantic.domain.SemanticBlueprint.OrderSelection.builder().expression("id").direction("ASC").build())).build();
        SemanticBlueprintPipeline.reconcileComputationIntent(plan,intent);
        assertThat(plan.getOffset()).isEqualTo(2000000000L);assertThat(plan.getLimit()).isEqualTo(20);
        for(String bad:java.util.List.of("-1","1.5","\"2\"","9223372036854775808","null")) {
            assertThatThrownBy(()->SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"),
                mapper.readTree("[{\"capability\":\"OFFSET\",\"offset\":"+bad+"}]"),java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->SemanticBlueprintPipeline.reconcileComputationIntent(plan,
            new cn.lgs.semevosql.semantic.domain.ComputationIntent(java.util.Set.of(cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability.OFFSET))))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
