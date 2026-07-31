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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlResultValidatorTest {

	private final SqlResultValidator validator = new SqlResultValidator();

	@Test
	void strictModeStillRequiresDeterministicSemanticPlanAliases() {
		SqlResultValidator.ValidationResult result = validator.validate(advancedResult(), plan(), 1000,
				ValidationMode.STRICT_SEMANTIC_PLAN);

		assertFalse(result.valid());
		assertTrue(result.errors().contains("Expected result column is missing: paid_amount"));
		assertTrue(result.errors().contains("Expected result column is missing: pay_time_month"));
	}

	@Test
	void advancedExecutionAllowsPlannerDerivedAndDisplayColumnsWithoutWeakeningRowSafety() {
		SqlResultValidator.ValidationResult result = validator.validate(advancedResult(), plan(), 1000,
				ValidationMode.ADVANCED_EXECUTION);

		assertTrue(result.valid());
		assertTrue(result.warnings().contains("SQL completed successfully but returned no rows"));
	}

	@Test
	void advancedExecutionStillRequiresMinimumExpectedColumnsForNonEmptyResults() {
		ResultSetBO renamedMetric = ResultSetBO.builder()
			.column(List.of("pay_time_month", "current_paid_amount", "month_over_month_growth_rate"))
			.data(List.of(Map.of("pay_time_month", "2026-08-01", "current_paid_amount", "120",
					"month_over_month_growth_rate", "20")))
			.build();

		SqlResultValidator.ValidationResult result = validator.validate(renamedMetric, plan(), 1000,
				ValidationMode.ADVANCED_EXECUTION);

		assertFalse(result.valid());
		assertTrue(result.errors().contains("Expected result column is missing: paid_amount"));
	}

	@Test
	void advancedExecutionAllowsPlannerToOverrideTypedPlanLimitAndOrdering() {
		ResultSetBO resultSet = ResultSetBO.builder()
			.column(List.of("paid_at_day", "effective_paid_amount", "previous_day_effective_paid_amount"))
			.data(List.of(
					Map.of("paid_at_day", "2026-08-01", "effective_paid_amount", "100", "previous_day_effective_paid_amount", ""),
					Map.of("paid_at_day", "2026-08-02", "effective_paid_amount", "120", "previous_day_effective_paid_amount", "100"),
					Map.of("paid_at_day", "2026-08-03", "effective_paid_amount", "110", "previous_day_effective_paid_amount", "120")))
			.build();
		SemanticBlueprint physicalShapeGuess = SemanticBlueprint.builder()
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("effective_paid_amount")
				.modelCode("qw_bench_order")
				.businessName("有效支付金额")
				.expression("SUM(paid_amount - refund_amount)")
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
			.expectedResult(SemanticBlueprint.ExpectedResultShape.builder()
				.columns(List.of("effective_paid_amount", "paid_at_day"))
				.maxRows(1)
				.tabular(true)
				.build())
			.limit(1)
			.build();

		SqlResultValidator.ValidationResult strict = validator.validate(resultSet, physicalShapeGuess, 1000,
				ValidationMode.STRICT_SEMANTIC_PLAN);
		SqlResultValidator.ValidationResult advanced = validator.validate(resultSet, physicalShapeGuess, 1000,
				ValidationMode.ADVANCED_EXECUTION);

		assertFalse(strict.valid());
		assertTrue(strict.errors().stream().anyMatch(error -> error.contains("expected maximum")));
		assertTrue(strict.errors().stream().anyMatch(error -> error.contains("typed-plan limit")));
		assertTrue(advanced.valid());
		assertTrue(advanced.errors().isEmpty());
		assertTrue(advanced.warnings().stream().noneMatch(warning -> warning.contains("typed-plan order")));
	}

	@Test
	void advancedExecutionStillRejectsMalformedResultMetadata() {
		ResultSetBO malformed = ResultSetBO.builder()
			.column(List.of("月份", "月份"))
			.data(List.of(Map.of("月份", "2025-01")))
			.build();

		SqlResultValidator.ValidationResult result = validator.validate(malformed, plan(), 1000,
				ValidationMode.ADVANCED_EXECUTION);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(error -> error.contains("duplicate output column")));
	}

	private ResultSetBO advancedResult() {
		return ResultSetBO.builder()
			.column(List.of("月份", "实付金额", "上月实付金额", "环比增长率"))
			.data(List.of())
			.build();
	}

	private SemanticBlueprint plan() {
		return SemanticBlueprint.builder()
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("paid_amount")
				.modelCode("pay_order")
				.businessName("实付金额")
				.expression("SUM(pay_amount)")
				.aggregation("SUM")
				.build()))
			.groupBy(List.of(SemanticBlueprint.GroupSelection.builder()
				.modelCode("pay_order")
				.columnName("pay_time")
				.expression("DATE_TRUNC('month', pay_time)")
				.alias("pay_time_month")
				.timeBucketGranularity("MONTH")
				.build()))
			.expectedResult(SemanticBlueprint.ExpectedResultShape.builder()
				.columns(List.of("paid_amount", "pay_time_month"))
				.maxRows(100)
				.tabular(true)
				.build())
			.limit(100)
			.build();
	}

}
