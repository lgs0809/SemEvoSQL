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
package cn.lgs.semevosql.sql.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DialectQueryCostEstimatorTest {

	@Test
	void mysqlJsonExplainCapturesScanJoinSortAndFullScanIndependently() {
		String json = """
				{
				  "query_block": {
				    "cost_info": {"query_cost": "43210.50"},
				    "ordering_operation": {
				      "using_filesort": true,
				      "nested_loop": [
				        {"table": {
				          "table_name": "orders",
				          "access_type": "range",
				          "rows_examined_per_scan": 100000,
				          "rows_produced_per_join": 100000,
				          "cost_info": {"prefix_cost": "1000.0"}
				        }},
				        {"table": {
				          "table_name": "items",
				          "access_type": "ALL",
				          "rows_examined_per_scan": 500000,
				          "rows_produced_per_join": 25000000,
				          "cost_info": {"prefix_cost": "43000.0"}
				        }}
				      ]
				    }
				  }
				}
				""";

		DialectQueryCostEstimator.QueryCostEstimate estimate = new MySqlQueryCostEstimator()
			.estimate(result("EXPLAIN", json));

		assertEquals(600000L, estimate.estimatedScanRows());
		assertEquals(25000000L, estimate.estimatedIntermediateRows());
		assertEquals(25000000L, estimate.estimatedJoinRows());
		assertEquals(25000000L, estimate.estimatedSortRows());
		assertEquals(43210.50D, estimate.estimatedCost());
		assertTrue(estimate.fullTableScan());
		assertTrue(estimate.expensiveOperators().contains("NESTED_LOOP"));
		assertTrue(estimate.expensiveOperators().contains("FILESORT"));
	}

	@Test
	void postgresJsonExplainWalksEntirePlanInsteadOfTrustingTopLevelLimit() {
		DialectQueryCostEstimator.QueryCostEstimate estimate = new PostgreSqlQueryCostEstimator()
			.estimate(result("QUERY PLAN", postgresExpensiveLimitPlan()));

		assertEquals(83000000L, estimate.estimatedScanRows());
		assertEquals(83000000L, estimate.estimatedIntermediateRows());
		assertEquals(25000000L, estimate.estimatedSortRows());
		assertEquals(1400000D, estimate.estimatedCost());
		assertTrue(estimate.fullTableScan());
		assertTrue(estimate.expensiveOperators().contains("SEQ_SCAN"));
		assertTrue(estimate.expensiveOperators().contains("SORT"));
	}

	@Test
	void postgresTracksJoinAndAggregateOperatorRows() {
		String json = """
				[{
				  "Plan": {
				    "Node Type": "Aggregate",
				    "Plan Rows": 12000000,
				    "Total Cost": 900000,
				    "Plans": [{
				      "Node Type": "Hash Join",
				      "Plan Rows": 18000000,
				      "Total Cost": 800000,
				      "Plans": [
				        {"Node Type":"Index Scan","Plan Rows":2000000,"Total Cost":200000},
				        {"Node Type":"Index Scan","Plan Rows":3000000,"Total Cost":300000}
				      ]
				    }]
				  }
				}]
				""";

		DialectQueryCostEstimator.QueryCostEstimate estimate = new PostgreSqlQueryCostEstimator()
			.estimate(result("QUERY PLAN", json));

		assertEquals(5000000L, estimate.estimatedScanRows());
		assertEquals(18000000L, estimate.estimatedJoinRows());
		assertEquals(12000000L, estimate.estimatedAggregateRows());
		assertTrue(estimate.expensiveOperators().contains("HASH_JOIN"));
		assertTrue(estimate.expensiveOperators().contains("AGGREGATE"));
	}

	@Test
	void costGuardRejectsExpensivePlanEvenWhenTopLevelLimitIsOnlyOneThousandRows() {
		SemEvoSQLProperties.SqlExecutionPolicy policy = new SemEvoSQLProperties.SqlExecutionPolicy();
		SqlCostGuard guard = new SqlCostGuard(List.of(new PostgreSqlQueryCostEstimator()));

		SqlCostGuardViolationException error = assertThrows(SqlCostGuardViolationException.class,
				() -> guard.validateExplain(result("QUERY PLAN", postgresExpensiveLimitPlan()), 1, policy, "postgresql"));

		assertTrue(error.getMessage().contains("83000000"));
		assertTrue(error.getMessage().contains("10000000"));
	}

	@Test
	void costGuardRejectsJoinExplosionEvenWhenScanRowsStayUnderGlobalLimit() {
		String json = """
				[{
				  "Plan": {
				    "Node Type":"Limit",
				    "Plan Rows":100,
				    "Total Cost":500000,
				    "Plans":[{
				      "Node Type":"Hash Join",
				      "Plan Rows":25000000,
				      "Total Cost":490000,
				      "Plans":[
				        {"Node Type":"Index Scan","Plan Rows":1000000,"Total Cost":100000},
				        {"Node Type":"Index Scan","Plan Rows":1000000,"Total Cost":100000}
				      ]
				    }]
				  }
				}]
				""";
		SemEvoSQLProperties.SqlExecutionPolicy policy = new SemEvoSQLProperties.SqlExecutionPolicy();
		policy.setMaxEstimatedIntermediateRows(30000000L);
		SqlCostGuard guard = new SqlCostGuard(List.of(new PostgreSqlQueryCostEstimator()));

		SqlCostGuardViolationException error = assertThrows(SqlCostGuardViolationException.class,
				() -> guard.validateExplain(result("QUERY PLAN", json), 2, policy, "postgresql"));

		assertTrue(error.getMessage().contains("25000000"));
		assertTrue(error.getMessage().contains("join"));
	}

	@Test
	void costRejectionRemainsRetryableForDurableRepairPolicyInsteadOfLegacyLocalCounter() {
		SqlValidationResult result = new SqlValidationClassifier()
			.classify(new SqlCostGuardViolationException("EXPLAIN estimates 83000000 scanned rows"), 100);

		assertEquals(SqlValidationDecision.RETRYABLE, result.decision());
		assertTrue(result.retryAllowed());
		assertEquals("sql-generate", result.allowedReturnNode());
		assertTrue(result.message().startsWith("QUERY_COST_EXCEEDED:"));
	}

	private String postgresExpensiveLimitPlan() {
		return """
				[
				  {
				    "Plan": {
				      "Node Type": "Limit",
				      "Plan Rows": 1000,
				      "Total Cost": 1400000.0,
				      "Plans": [
				        {
				          "Node Type": "Sort",
				          "Plan Rows": 25000000,
				          "Total Cost": 1390000.0,
				          "Plans": [
				            {
				              "Node Type": "Seq Scan",
				              "Relation Name": "pay_order",
				              "Plan Rows": 83000000,
				              "Total Cost": 1200000.0
				            }
				          ]
				        }
				      ]
				    }
				  }
				]
				""";
	}

	private ResultSetBO result(String column, String value) {
		return ResultSetBO.builder().column(List.of(column)).data(List.of(Map.of(column, value))).build();
	}

}
