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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticConsistencyNodeTest {

	@Test
	void advancedExecutionStructureGuardRejectsMissingPlannerWindowOperator() {
		assertFalse(SemanticConsistencyNode
			.advancedExecutionStructureErrors("Use LAG with PARTITION BY created_at_month", "SELECT amount FROM t")
			.isEmpty());
		assertTrue(SemanticConsistencyNode.advancedExecutionStructureErrors("Use LAG with PARTITION BY created_at_month",
				"SELECT LAG(amount) OVER (PARTITION BY created_at_month ORDER BY paid_at_week) FROM t")
			.isEmpty());
	}

	@Test
	void advancedConsistencyReviewsModelAuthoredSemanticSqlInsteadOfSystemMaterializationSql() {
		String semanticSql = "SELECT METRIC('o.effective_paid_amount') AS effective_paid_amount FROM qw_bench_order o";
		String physicalSql = "WITH __qw_model_qw_bench_order AS (SELECT channel_code, paid_at, paid_amount, refund_amount FROM qw_bench_order) SELECT * FROM __qw_model_qw_bench_order";

		assertThat(SemanticConsistencyNode.consistencyReviewSql("SEMANTIC_SQL", semanticSql, physicalSql))
			.isEqualTo(semanticSql);
		assertThat(SemanticConsistencyNode.consistencyReviewSql("DETERMINISTIC", semanticSql, physicalSql))
			.isEqualTo(physicalSql);
	}

	@Test
	void advancedConsistencyKeepsGovernedSemanticsButDropsPlannerOwnedPhysicalShape() throws Exception {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("effective_paid_amount")
				.modelCode("qw_bench_order")
				.expression("paid_amount - refund_amount")
				.aggregation("SUM")
				.build()))
			.groupBy(List.of(SemanticBlueprint.GroupSelection.builder()
				.modelCode("qw_bench_order")
				.columnName("paid_at")
				.expression("DATE(paid_at)")
				.alias("paid_at_day")
				.timeBucketGranularity("DAY")
				.build()))
			.orderBy(List.of(SemanticBlueprint.OrderSelection.builder()
				.expression("effective_paid_amount")
				.direction("DESC")
				.build()))
			.expectedResult(SemanticBlueprint.ExpectedResultShape.builder().maxRows(1).tabular(true).build())
			.limit(1)
			.build();

		var advanced = JsonUtil.getObjectMapper().readTree(SemanticConsistencyNode.serializeSemanticPlan(plan, true));
		assertTrue(advanced.has("metrics"));
		assertTrue(advanced.has("groupBy"));
		assertFalse(advanced.has("orderBy"));
		assertFalse(advanced.has("limit"));
		assertFalse(advanced.has("expectedResult"));

		var strict = JsonUtil.getObjectMapper().readTree(SemanticConsistencyNode.serializeSemanticPlan(plan, false));
		assertTrue(strict.has("orderBy"));
		assertTrue(strict.has("limit"));
		assertTrue(strict.has("expectedResult"));
	}

}
