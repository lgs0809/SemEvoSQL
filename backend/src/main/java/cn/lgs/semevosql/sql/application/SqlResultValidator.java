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

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Performs deterministic structural and typed-plan-aware checks on SQL results. */
@Component
public class SqlResultValidator {

	public ValidationResult validate(ResultSetBO resultSet, SemanticBlueprint plan, int configuredMaxRows) {
		return validate(resultSet, plan, configuredMaxRows, ValidationMode.STRICT_SEMANTIC_PLAN);
	}

	public ValidationResult validate(ResultSetBO resultSet, SemanticBlueprint plan, int configuredMaxRows,
			ValidationMode mode) {
		ValidationMode effectiveMode = mode == null ? ValidationMode.STRICT_SEMANTIC_PLAN : mode;
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		if (resultSet == null) {
			return ValidationResult.rejected(List.of("SQL returned no result object"), List.of());
		}
		if (hasText(resultSet.getErrorMsg())) {
			errors.add("SQL result contains an error: " + resultSet.getErrorMsg());
		}

		List<String> columns = resultSet.getColumn() == null ? List.of() : resultSet.getColumn();
		List<Map<String, String>> rows = resultSet.getData() == null ? List.of() : resultSet.getData();
		validateColumns(columns, rows, errors);
		if (rows.isEmpty()) {
			warnings.add("SQL completed successfully but returned no rows");
		}
		if (configuredMaxRows > 0 && rows.size() >= configuredMaxRows) {
			warnings.add("Result reached the configured maximum row count and may be truncated: " + configuredMaxRows);
		}

		if (plan != null) {
			validateExpectedShape(columns, rows, plan, effectiveMode, errors, warnings);
			if (!safe(plan.getMetrics()).isEmpty()) {
				validateMetricColumns(columns, rows, plan.getMetrics(), errors, warnings);
				validateGroupingUniqueness(columns, rows, plan.getGroupBy(), effectiveMode, errors, warnings);
			}
		}
		return errors.isEmpty() ? ValidationResult.accepted(warnings) : ValidationResult
			.rejected(List.copyOf(new LinkedHashSet<>(errors)), List.copyOf(new LinkedHashSet<>(warnings)));
	}

	private void validateExpectedShape(List<String> columns, List<Map<String, String>> rows, SemanticBlueprint plan,
			ValidationMode mode, List<String> errors, List<String> warnings) {
		SemanticBlueprint.ExpectedResultShape expected = plan.getExpectedResult();
		if (expected != null) {
			// Expected columns are the minimum semantic output contract for every execution path. Advanced SQL may add
			// planner-owned derived columns, but a non-empty result must not silently rename/drop governed outputs that
			// downstream review, learning and report generation use to prove semantic alignment.
			if (mode == ValidationMode.STRICT_SEMANTIC_PLAN || !rows.isEmpty()) {
				for (String expectedColumn : safe(expected.getColumns())) {
					if (hasText(expectedColumn) && findOutputColumn(columns, expectedColumn) == null) {
						errors.add("Expected result column is missing: " + expectedColumn);
					}
				}
			}
			if (mode == ValidationMode.STRICT_SEMANTIC_PLAN && expected.getMaxRows() != null && expected.getMaxRows() > 0
					&& rows.size() > expected.getMaxRows()) {
				errors.add("Result row count exceeds typed-plan expected maximum: " + expected.getMaxRows());
			}
		}
		if (mode == ValidationMode.STRICT_SEMANTIC_PLAN && plan.getLimit() != null && plan.getLimit() > 0
				&& rows.size() > plan.getLimit()) {
			errors.add("Result row count exceeds typed-plan limit: " + plan.getLimit());
		}
		boolean aggregateWithoutGrouping = !safe(plan.getMetrics()).isEmpty() && safe(plan.getDimensions()).isEmpty()
				&& safe(plan.getGroupBy()).isEmpty();
		if (mode == ValidationMode.STRICT_SEMANTIC_PLAN && aggregateWithoutGrouping && rows.size() > 1) {
			errors.add("Aggregate query without grouping returned more than one row");
		}
		if (mode == ValidationMode.STRICT_SEMANTIC_PLAN && !safe(plan.getOrderBy()).isEmpty() && rows.size() > 1) {
			validateOrdering(columns, rows, plan.getOrderBy(), warnings);
		}
	}

	private void validateOrdering(List<String> columns, List<Map<String, String>> rows,
			List<SemanticBlueprint.OrderSelection> ordering, List<String> warnings) {
		for (SemanticBlueprint.OrderSelection order : safe(ordering)) {
			String outputColumn = findOutputColumn(columns, order.getExpression());
			if (outputColumn == null) {
				warnings.add("Cannot deterministically validate ordering expression: " + order.getExpression());
				continue;
			}
			String direction = hasText(order.getDirection()) ? order.getDirection().trim().toUpperCase(Locale.ROOT) : "ASC";
			String previous = null;
			for (Map<String, String> row : rows) {
				String current = row == null ? null : row.get(outputColumn);
				if (!hasText(previous) || !hasText(current)) {
					previous = current;
					continue;
				}
				int comparison = compareValues(previous, current);
				if (("DESC".equals(direction) && comparison < 0) || (!"DESC".equals(direction) && comparison > 0)) {
					warnings.add("Result ordering does not match typed-plan order for column: " + outputColumn);
					break;
				}
				previous = current;
			}
		}
	}

	private int compareValues(String left, String right) {
		if (isNumeric(left) && isNumeric(right)) {
			return decimal(left).compareTo(decimal(right));
		}
		return left.compareTo(right);
	}

	private BigDecimal decimal(String value) {
		String normalized = value.trim().replace(",", "");
		if (normalized.endsWith("%")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return new BigDecimal(normalized);
	}

	private void validateColumns(List<String> columns, List<Map<String, String>> rows, List<String> errors) {
		if (!rows.isEmpty() && columns.isEmpty()) {
			errors.add("Result rows exist but column metadata is empty");
			return;
		}
		Set<String> normalizedColumns = new HashSet<>();
		for (String column : columns) {
			String normalized = normalizeName(column);
			if (normalized.isBlank()) {
				errors.add("Result contains a blank column name");
			}
			else if (!normalizedColumns.add(normalized)) {
				errors.add("Result contains duplicate output column: " + column);
			}
		}
		for (int index = 0; index < rows.size(); index++) {
			Map<String, String> row = rows.get(index);
			if (row == null) {
				errors.add("Result contains a null row at index " + index);
				continue;
			}
			for (String column : columns) {
				if (!row.containsKey(column)) {
					errors.add("Result row " + index + " is missing column: " + column);
				}
			}
		}
	}

	private void validateMetricColumns(List<String> columns, List<Map<String, String>> rows,
			List<SemanticBlueprint.MetricSelection> metrics, List<String> errors, List<String> warnings) {
		for (SemanticBlueprint.MetricSelection metric : safe(metrics)) {
			String outputColumn = findOutputColumn(columns, metric.getMetricCode(), metric.getBusinessName());
			if (outputColumn == null) {
				warnings.add("Cannot identify output column for published metric: " + metric.getMetricCode());
				continue;
			}
			boolean observedValue = false;
			String aggregation = metric.getAggregation() == null ? "" : metric.getAggregation().toUpperCase(Locale.ROOT);
			for (int index = 0; index < rows.size(); index++) {
				String value = rows.get(index).get(outputColumn);
				if (!hasText(value)) {
					continue;
				}
				observedValue = true;
				if (!isNumeric(value)) {
					errors.add("Metric output is not numeric: " + metric.getMetricCode() + " at row " + index);
					break;
				}
				BigDecimal numeric = decimal(value);
				if (aggregation.contains("COUNT") && numeric.stripTrailingZeros().scale() > 0) {
					errors.add("Count metric output is not an integer: " + metric.getMetricCode() + " at row " + index);
					break;
				}
				String unit = metric.getUnit() == null ? "" : metric.getUnit().toLowerCase(Locale.ROOT);
				if ((unit.contains("percent") || unit.contains("percentage") || "%".equals(unit))
						&& (numeric.compareTo(BigDecimal.ZERO) < 0 || numeric.compareTo(new BigDecimal("100")) > 0)) {
					errors.add("Percentage metric output is outside [0,100]: " + metric.getMetricCode() + " at row " + index);
					break;
				}
			}
			if (!rows.isEmpty() && !observedValue && !nullableEmptyAggregate(aggregation)) {
				warnings.add("Metric output is null/blank for every returned row: " + metric.getMetricCode());
			}
		}
	}

	private void validateGroupingUniqueness(List<String> columns, List<Map<String, String>> rows,
			List<SemanticBlueprint.GroupSelection> groups, ValidationMode mode, List<String> errors, List<String> warnings) {
		List<String> groupingColumns = new ArrayList<>();
		for (SemanticBlueprint.GroupSelection group : safe(groups)) {
			String outputColumn = findOutputColumn(columns, group.getAlias(), group.getColumnName(), group.getExpression());
			if (outputColumn == null) {
				if (mode == ValidationMode.STRICT_SEMANTIC_PLAN) {
					warnings.add("Cannot identify output column for typed-plan grouping: " + group.getAlias());
				}
				return;
			}
			groupingColumns.add(outputColumn);
		}
		if (groupingColumns.isEmpty()) {
			return;
		}
		Set<List<String>> seen = new HashSet<>();
		for (Map<String, String> row : rows) {
			List<String> key = groupingColumns.stream().map(row::get).toList();
			if (!seen.add(key)) {
				errors.add("Result contains duplicate selected-dimension combination: " + key);
				return;
			}
		}
	}

	private String findOutputColumn(List<String> columns, String... candidates) {
		for (String candidate : candidates) {
			if (!hasText(candidate)) {
				continue;
			}
			String normalizedCandidate = normalizeName(candidate);
			for (String column : columns) {
				if (normalizeName(column).equals(normalizedCandidate)) {
					return column;
				}
			}
		}
		return null;
	}

	private boolean nullableEmptyAggregate(String aggregation) {
		return switch (aggregation) {
			case "SUM", "AVG", "AVERAGE", "MIN", "MAX" -> true;
			default -> false;
		};
	}

	private boolean isNumeric(String value) {
		String normalized = value.trim().replace(",", "");
		if (normalized.endsWith("%")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			new BigDecimal(normalized);
			return true;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private String normalizeName(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\p{IsHan}]", "");
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public enum ValidationMode {
		/** Exact result aliases/grain expected from deterministic compiler, replay and Query Pattern paths. */
		STRICT_SEMANTIC_PLAN,
		/** Planner-driven SQL may add or rename derived columns while preserving governed semantic bindings. */
		ADVANCED_EXECUTION
	}

	public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {

		public static ValidationResult accepted(List<String> warnings) {
			return new ValidationResult(true, List.of(), List.copyOf(warnings));
		}

		public static ValidationResult rejected(List<String> errors, List<String> warnings) {
			return new ValidationResult(false, List.copyOf(errors), List.copyOf(warnings));
		}
	}

}
