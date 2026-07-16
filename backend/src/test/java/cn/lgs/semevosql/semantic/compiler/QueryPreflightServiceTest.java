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
package cn.lgs.semevosql.semantic.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlExecutionGuard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryPreflightServiceTest {

	private final QueryPreflightService planner = new QueryPreflightService();

	@Test
	void expandsGovernedModelAsCteWithoutFlatteningComplexSql() {
		String semanticSql = """
				WITH monthly AS (
				  SELECT DATE_TRUNC('month', pay_time) AS month, SUM(pay_amount) AS amount
				  FROM orders
				  GROUP BY 1
				)
				SELECT month, amount, LAG(amount) OVER (ORDER BY month) AS previous_amount
				FROM monthly
				ORDER BY month
				""";

		QueryPreflightService.PreflightResult result = planner.preflight(semanticSql, catalog(), plan(), 7, "postgresql");

		assertTrue(result.physicalSql().contains("\"__qw_model_orders\" AS ("));
		assertTrue(result.physicalSql().contains("FROM \"trade\".\"pay_order_v3\""));
		assertTrue(result.physicalSql().contains("LAG(amount) OVER (ORDER BY month)"));
		assertTrue(result.physicalSql().contains("monthly AS ("));
		assertEquals(java.util.Set.of("orders"), result.semanticModelCodes());
		assertFalse(result.legacyPhysicalPassthrough());
	}

	@Test
	void expandsPublishedFilteredMetricDeterministically() {
		QueryPreflightService.PreflightResult result = planner.preflight(
				"SELECT METRIC('orders.sales_amount') AS sales FROM orders", catalog(), plan(), 7, "postgresql");

		assertTrue(result.physicalSql().contains(
				"SUM(CASE WHEN orders.\"status\" = 'PAID' THEN orders.\"pay_amount\" ELSE 0 END)"));
	}

	@Test
	void modelQualifiedMetricResolvesTheUniqueSqlAliasInsideAnAggregateCte() {
		var result=planner.preflight("""
			WITH totals AS (SELECT METRIC('orders.sales_amount') AS amount FROM orders AS o)
			SELECT amount FROM totals
			""",catalog(),plan(),7,"postgresql");
		assertTrue(result.physicalSql().contains("SUM(CASE WHEN o.\"status\" = 'PAID' THEN o.\"pay_amount\" ELSE 0 END)"));
		assertFalse(result.physicalSql().contains("METRIC("));
	}

	@Test
	void modelQualifierCannotChooseAmongMultipleBindingsOrAuthorizeAnotherModelAlias() {
		var ambiguous=assertThrows(QueryPreflightException.class,()->planner.preflight(
			"SELECT (SELECT METRIC('orders.sales_amount') FROM orders o) AS first_amount, (SELECT METRIC('x.sales_amount') FROM orders x) AS second_amount",
			catalog(),plan(),7,"postgresql"));
		assertEquals("SEMANTIC_MODEL_ALIAS_AMBIGUOUS",ambiguous.code());
		var wrong=assertThrows(QueryPreflightException.class,()->planner.preflight(
			"SELECT METRIC('c.sales_amount') FROM orders o JOIN customers c ON RELATIONSHIP('order_customer')",
			catalog(),plan(),7,"postgresql"));
		assertEquals("SEMANTIC_METRIC_NOT_FOUND",wrong.code());
	}

	@Test
	void expandsCountDistinctAndArithmeticSumMetrics() {
		QueryPreflightService.PreflightResult result = planner.preflight("""
				SELECT METRIC('orders.distinct_customer_count') AS customers,
				       METRIC('orders.effective_paid_amount') AS effective_amount
				FROM orders
				""", catalog(), plan(), 7, "postgresql");

		assertTrue(result.physicalSql().contains("COUNT(DISTINCT orders.\"customer_id\")"));
		assertTrue(result.physicalSql().contains("SUM(orders.\"pay_amount\" - orders.\"refund_amount\")"));
	}

	@Test
	void governedMetricWorksInsideWindowAndCteWithoutInventingWindowDsl() {
		QueryPreflightService.PreflightResult result = planner.preflight("""
				WITH monthly AS (
				  SELECT DATE_TRUNC('month', o.pay_time) AS month,
				         METRIC('o.sales_amount') AS amount
				  FROM orders o
				  GROUP BY 1
				)
				SELECT month, amount, LAG(amount) OVER (ORDER BY month) AS previous_amount
				FROM monthly
				""", catalog(), plan(), 7, "postgresql");

		assertTrue(result.physicalSql().contains("SUM(CASE WHEN o.\"status\" = 'PAID' THEN o.\"pay_amount\" ELSE 0 END)"));
		assertTrue(result.physicalSql().contains("LAG(amount) OVER (ORDER BY month)"));
	}

	@Test
	void expandsPinnedRelationshipAndModelAliasesDeterministically() {
		QueryPreflightService.PreflightResult result = planner.preflight("""
				SELECT c.province, METRIC('o.sales_amount') AS sales
				FROM orders o
				JOIN customers c ON RELATIONSHIP('order_customer')
				GROUP BY c.province
				""", catalog(), plan(), 7, "postgresql");

		assertTrue(result.physicalSql().contains("o.\"customer_id\" = c.\"customer_id\""));
		assertTrue(result.physicalSql().contains("JOIN \"__qw_model_customers\" AS c ON"));
		assertFalse(result.physicalSql().contains("RELATIONSHIP("));
	}

	@Test
	void rejectsDirectModelJoinThatBypassesPublishedRelationship() {
		QueryPreflightException error = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("""
						SELECT c.province
						FROM orders o
						JOIN customers c ON o.customer_id = c.customer_id
						""", catalog(), plan(), 7, "postgresql"));

		assertEquals("SEMANTIC_RELATIONSHIP_REQUIRED", error.code());
	}

	@Test
	void rejectsDoubleAggregationOfGovernedMetric() {
		QueryPreflightException error = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("SELECT SUM(METRIC('orders.sales_amount')) FROM orders", catalog(), plan(), 7,
						"postgresql"));

		assertEquals("SEMANTIC_METRIC_DOUBLE_AGGREGATION", error.code());
	}

	@Test
	void rejectsModelOutsideFrozenSemanticCatalogBeforeDatabaseAccess() {
		QueryPreflightException error = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("SELECT id FROM salary_private", catalog(), plan(), 7, "postgresql"));

		assertEquals("SEMANTIC_MODEL_NOT_FOUND", error.code());
	}

	@Test
	void rejectsDirectGovernedModelColumnHallucinationBeforeDatabaseAccess() {
		QueryPreflightException error = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("SELECT orders.missing_amount FROM orders", catalog(), plan(), 7, "postgresql"));

		assertEquals("SEMANTIC_COLUMN_NOT_FOUND", error.code());
		assertTrue(error.getMessage().contains("missing_amount"));
	}

	@Test
	void nonProjectableColumnIsHiddenButStillAvailableToPublishedMetricInternally() {
		QueryPreflightException directProjection = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("SELECT o.internal_cost FROM orders o", catalog(), plan(), 7, "postgresql"));
		assertEquals("SEMANTIC_COLUMN_NOT_FOUND", directProjection.code());

		QueryPreflightService.PreflightResult metric = planner.preflight(
				"SELECT METRIC('o.internal_cost_sum') AS cost FROM orders o", catalog(), plan(), 7, "postgresql");
		assertTrue(metric.physicalSql().contains("o.\"__qw_internal_internal_cost\""));
		assertTrue(metric.physicalSql().contains("internal_cost_cent AS \"__qw_internal_internal_cost\""));
	}

	@Test
	void internalModelCteKeepsPhysicalTableVisibleToAstGuardWhenModelAndTableHaveSameName() {
		SemanticCatalogSnapshot sameNameCatalog = catalog();
		sameNameCatalog.getModels().stream().filter(model -> "orders".equals(model.getModelCode())).findFirst().orElseThrow()
			.setPhysicalTable("orders");
		SemanticBlueprint sameNamePlan = plan();
		sameNamePlan.getModels().stream().filter(model -> "orders".equals(model.getModelCode())).findFirst().orElseThrow()
			.setPhysicalTable("orders");

		QueryPreflightService.PreflightResult result = planner.preflight(
				"SELECT METRIC('orders.sales_amount') AS sales FROM orders", sameNameCatalog, sameNamePlan, 7, "mysql");
		SqlExecutionGuard.GuardResult guard = new SqlExecutionGuard().validate(result.physicalSql(), "mysql", Set.of("orders"),
				"");

		assertTrue(result.physicalSql().contains("`__qw_model_orders` AS ("));
		assertTrue(result.physicalSql().contains("FROM `__qw_model_orders` AS `orders`"));
		assertEquals(Set.of("orders"), guard.referencedTables());
	}

	@Test
	void preservesMultipleTimeAxesAndOnlyWarnsAboutNullableColumns() {
		QueryPreflightService.PreflightResult result = planner.preflight("""
				WITH by_time AS (
				  SELECT DATE_TRUNC('month', o.created_time) AS order_month,
				         DATE_TRUNC('week', o.pay_time) AS pay_week,
				         METRIC('o.effective_paid_amount') AS amount
				  FROM orders o
				  WHERE o.completed_time >= DATE '2026-01-01'
				  GROUP BY 1, 2
				)
				SELECT order_month, pay_week, amount,
				       LAG(amount) OVER (PARTITION BY order_month ORDER BY pay_week) AS previous_week_amount
				FROM by_time
				""", catalog(), plan(), 7, "postgresql");

		assertTrue(result.warnings().stream().anyMatch(value -> value.contains("column=created_time role=TIME")));
		assertTrue(result.warnings().stream().anyMatch(value -> value.contains("column=pay_time role=TIME")));
		assertTrue(result.warnings().stream().anyMatch(value -> value.contains("column=completed_time role=TIME")));
		assertTrue(result.physicalSql().contains("PARTITION BY order_month ORDER BY pay_week"));
		assertTrue(result.physicalSql().contains("completed_time"));
		assertFalse(result.physicalSql().toLowerCase().contains("is not null"));
	}

	@Test
	void rejectsPhysicalTableBypassOnSemanticSqlPath() {
		QueryPreflightException error = assertThrows(QueryPreflightException.class,
				() -> planner.preflight("SELECT pay_amount FROM trade.pay_order_v3", catalog(), plan(), 7, "postgresql"));

		assertEquals("SEMANTIC_PHYSICAL_BYPASS_FORBIDDEN", error.code());
	}

	@Test
	void qualifiedPhysicalTableCannotBecomeLogicalModelThroughItsBasename() {
		var sameName = catalog();
		sameName.getModels().get(0).setPhysicalTable("public.orders");
		for (String table : List.of("public.orders", "\"public\".\"orders\"")) {
			var error = assertThrows(QueryPreflightException.class,
					() -> planner.preflight("SELECT pay_amount FROM " + table, sameName, plan(), 7, "postgresql"));
			assertEquals("SEMANTIC_PHYSICAL_BYPASS_FORBIDDEN", error.code());
		}
		var governed = planner.preflight("SELECT pay_amount FROM orders", sameName, plan(), 7, "postgresql");
		assertTrue(governed.physicalSql().contains("FROM \"__qw_model_orders\""));
	}

	@Test
	void qualifiedPhysicalTableCannotHideBehindAUserCteWithTheSameBasename() {
		var sameName = catalog();
		sameName.getModels().get(0).setPhysicalTable("public.staging");
		var error = assertThrows(QueryPreflightException.class, () -> planner.preflight(
				"WITH staging AS (SELECT pay_amount FROM orders) SELECT pay_amount FROM public.staging",
				sameName, plan(), 7, "postgresql"));
		assertEquals("SEMANTIC_PHYSICAL_BYPASS_FORBIDDEN", error.code());
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"q_7795e1d68c7542cea70be6021d1369d0", "q_7795e1d68c7542cea70be6021d1369d0_2", "sales"})
	void wrongConfirmedAliasIsRejectedBeforeAnyPhysicalSqlCanBeExecuted(String wrongAlias) {
		var plan = confirmedOutputPlan();
		var error = assertThrows(QueryPreflightException.class, () -> planner.preflight(
				"SELECT METRIC('orders.sales_amount') AS " + wrongAlias + " FROM orders", catalog(), plan, 7, "postgresql"));
		assertEquals("SEMANTIC_RESULT_OUTPUT_MISSING", error.code());
		assertTrue(error.getMessage().contains("q_7795e1d68c7542cea70be6021d1369d0_1"));
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
		"SELECT METRIC('orders.sales_amount') AS q_7795e1d68c7542cea70be6021d1369d0_1 FROM orders",
		"WITH t AS (SELECT METRIC('orders.sales_amount') AS q_7795e1d68c7542cea70be6021d1369d0_1 FROM orders) SELECT t.q_7795e1d68c7542cea70be6021d1369d0_1 FROM t",
		"SELECT METRIC('orders.sales_amount') AS \"q_7795e1d68c7542cea70be6021d1369d0_1\" FROM orders UNION ALL SELECT METRIC('orders.sales_amount') AS other FROM orders"
	})
	void finalSelectOrFirstUnionBranchPreservesExactConfirmedAlias(String sql) {
		var result = planner.preflight(sql, catalog(), confirmedOutputPlan(), 7, "postgresql");
		assertTrue(result.physicalSql().contains("q_7795e1d68c7542cea70be6021d1369d0_1"));
	}

	private SemanticBlueprint confirmedOutputPlan() {
		var plan = plan();
		plan.setResultContract(new cn.lgs.semevosql.semantic.domain.SemanticResultContract(Set.of(), List.of(), List.of(
				new cn.lgs.semevosql.semantic.domain.SemanticResultContract.QueryMeasure(
						"q_7795e1d68c7542cea70be6021d1369d0_1", "业务计算", "7795e1d6-8c75-42ce-a70b-e6021d1369d0",
						1L, "只用于本次的实际已确认含义", "frozen-test-hash"))));
		return plan;
	}

	private SemanticCatalogSnapshot catalog() {
		return SemanticCatalogSnapshot.builder()
			.models(List.of(model("orders", "trade.pay_order_v3"), model("customers", "trade.customer_v2")))
			.columns(List.of(column("orders", "customer_id", "usr_no"), column("orders", "pay_amount", "amt_cent / 100.0"),
					column("orders", "refund_amount", "refund_cent / 100.0"), timeColumn("orders", "created_time", "created_at"),
					timeColumn("orders", "pay_time", "paid_at"), timeColumn("orders", "completed_time", "completed_at"),
					column("orders", "status", "pay_status"), hiddenColumn("orders", "internal_cost", "internal_cost_cent"),
					column("customers", "customer_id", "customer_no"), column("customers", "province", "province_name")))
			.metrics(List.of(metric("orders", "sales_amount", "SUM(pay_amount)", "SUM", "status = 'PAID'"),
					metric("orders", "distinct_customer_count", "COUNT(DISTINCT customer_id)", "COUNT_DISTINCT", ""),
					metric("orders", "effective_paid_amount", "pay_amount - refund_amount", "SUM", ""),
					metric("orders", "internal_cost_sum", "SUM(internal_cost)", "SUM", "")))
			.relationships(List.of(SemanticCatalogSnapshot.Relationship.builder()
				.relationshipCode("order_customer")
				.sourceModelCode("orders")
				.targetModelCode("customers")
				.cardinality(RelationshipCardinality.MANY_TO_ONE)
				.joinType("INNER")
				.joinCondition("orders.customer_id = customers.customer_id")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.build();
	}

	private SemanticCatalogSnapshot.Model model(String code, String table) {
		return SemanticCatalogSnapshot.Model.builder()
			.modelCode(code)
			.physicalTable(table)
			.datasourceId(7)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private SemanticCatalogSnapshot.Column column(String model, String name, String expression) {
		return SemanticCatalogSnapshot.Column.builder()
			.modelCode(model)
			.columnName(name)
			.expression(expression)
			.allowProjection(true)
			.allowAggregation(true)
			.allowFilter(true)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private SemanticCatalogSnapshot.Column timeColumn(String model, String name, String expression) {
		return SemanticCatalogSnapshot.Column.builder()
			.modelCode(model)
			.columnName(name)
			.expression(expression)
			.role(SemanticColumnRole.TIME)
			.nullable(true)
			.allowProjection(true)
			.allowAggregation(true)
			.allowFilter(true)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private SemanticCatalogSnapshot.Column hiddenColumn(String model, String name, String expression) {
		return SemanticCatalogSnapshot.Column.builder()
			.modelCode(model)
			.columnName(name)
			.expression(expression)
			.allowProjection(false)
			.allowAggregation(true)
			.allowFilter(true)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private SemanticCatalogSnapshot.Metric metric(String model, String code, String expression, String aggregation,
			String filter) {
		return SemanticCatalogSnapshot.Metric.builder()
			.modelCode(model)
			.metricCode(code)
			.expression(expression)
			.aggregation(aggregation)
			.filterExpression(filter)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

	private SemanticBlueprint plan() {
		return SemanticBlueprint.builder()
			.models(List.of(SemanticBlueprint.ModelSelection.builder()
				.modelCode("orders")
				.physicalTable("trade.pay_order_v3")
				.datasourceId(7)
				.build(), SemanticBlueprint.ModelSelection.builder()
					.modelCode("customers")
					.physicalTable("trade.customer_v2")
					.datasourceId(7)
					.build()))
			.metrics(List.of(planMetric("orders", "sales_amount", "SUM(pay_amount)", "SUM", "status = 'PAID'"),
					planMetric("orders", "distinct_customer_count", "COUNT(DISTINCT customer_id)", "COUNT_DISTINCT", ""),
					planMetric("orders", "effective_paid_amount", "pay_amount - refund_amount", "SUM", ""),
					planMetric("orders", "internal_cost_sum", "SUM(internal_cost)", "SUM", "")))
			.relationships(List.of(SemanticBlueprint.RelationshipSelection.builder()
				.relationshipCode("order_customer")
				.sourceModelCode("orders")
				.targetModelCode("customers")
				.cardinality(RelationshipCardinality.MANY_TO_ONE)
				.joinType("INNER")
				.joinCondition("orders.customer_id = customers.customer_id")
				.build()))
			.executable(true)
			.build();
	}

	private SemanticBlueprint.MetricSelection planMetric(String model, String code, String expression, String aggregation,
			String filter) {
		return SemanticBlueprint.MetricSelection.builder()
			.modelCode(model)
			.metricCode(code)
			.expression(expression)
			.aggregation(aggregation)
			.filterExpression(filter)
			.build();
	}

}
