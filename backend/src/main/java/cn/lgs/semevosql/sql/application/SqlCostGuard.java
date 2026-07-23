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
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.sql.application.DialectQueryCostEstimator.QueryCostEstimate;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies deterministic resource limits before the generated query reaches a production
 * datasource. This is a system policy and never invents business filters.
 */
@Component
@RequiredArgsConstructor
public class SqlCostGuard {

	private static final Pattern CARTESIAN_JOIN = Pattern.compile(
			"(?is)\\b(?:cross\\s+join|join\\s+[^\\s,()]++(?:\\s+(?:as\\s+)?[a-zA-Z_][\\w$]*)?\\s*(?=(?:join|where|group|order|limit|$)))");

	private static final Pattern DATE_LITERAL = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");

	private static final List<String> ESTIMATED_ROW_KEYS = List.of("rows", "plan rows", "plan_rows", "estimated rows",
			"estimated_rows", "cardinality");

	private final List<DialectQueryCostEstimator> costEstimators;

	public CostAssessment validateSql(String sql, Collection<String> referencedTables, Collection<String> timeColumns,
			SemEvoSQLProperties.SqlExecutionPolicy policy) {
		int tableCount = referencedTables == null ? 0 : referencedTables.size();
		if (tableCount > policy.getMaxJoinTables()) {
			throw new SqlGuardViolationException(
					"Query references " + tableCount + " tables; configured maximum is " + policy.getMaxJoinTables());
		}
		if (CARTESIAN_JOIN.matcher(sql).find()) {
			throw new SqlGuardViolationException("Cartesian or conditionless JOIN is not allowed");
		}
		if (policy.isRequireTimeFilter() && !hasTimeFilter(sql, timeColumns)) {
			throw new SqlGuardViolationException("A semantic time-column filter is required by query policy");
		}
		long literalRangeDays = literalDateRangeDays(sql);
		if (literalRangeDays > policy.getMaxTimeRangeDays()) {
			throw new SqlGuardViolationException("Literal date range is " + literalRangeDays
					+ " days; configured maximum is " + policy.getMaxTimeRangeDays());
		}
		return new CostAssessment(tableCount, 0, 0, 0, 0, 0, 0, false, List.of(), List.of());
	}

	public CostAssessment validateExplain(ResultSetBO explainResult, int tableCount,
			SemEvoSQLProperties.SqlExecutionPolicy policy, String dialect) {
		DialectQueryCostEstimator estimator = costEstimators.stream()
			.filter(candidate -> candidate.supports(dialect))
			.findFirst()
			.orElse(null);
		if (estimator != null) {
			return validateEstimate(estimator.estimate(explainResult), tableCount, policy);
		}
		return validateLegacyExplain(explainResult, tableCount, policy);
	}

	/** Backward-compatible entry point used by older tests/callers without a dialect. */
	public CostAssessment validateExplain(ResultSetBO explainResult, int tableCount,
			SemEvoSQLProperties.SqlExecutionPolicy policy) {
		return validateLegacyExplain(explainResult, tableCount, policy);
	}

	private CostAssessment validateEstimate(QueryCostEstimate estimate, int tableCount,
			SemEvoSQLProperties.SqlExecutionPolicy policy) {
		if (estimate.estimatedScanRows() > policy.getMaxEstimatedRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimate.estimatedScanRows()
					+ " scanned rows; configured maximum is " + policy.getMaxEstimatedRows() + diagnosticSuffix(estimate));
		}
		if (policy.getMaxEstimatedIntermediateRows() > 0
				&& estimate.estimatedIntermediateRows() > policy.getMaxEstimatedIntermediateRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimate.estimatedIntermediateRows()
					+ " intermediate rows; configured maximum is " + policy.getMaxEstimatedIntermediateRows()
					+ diagnosticSuffix(estimate));
		}
		if (policy.getMaxEstimatedJoinRows() > 0 && estimate.estimatedJoinRows() > policy.getMaxEstimatedJoinRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimate.estimatedJoinRows()
					+ " rows produced by a join; configured maximum is " + policy.getMaxEstimatedJoinRows()
					+ diagnosticSuffix(estimate));
		}
		if (policy.getMaxEstimatedSortRows() > 0 && estimate.estimatedSortRows() > policy.getMaxEstimatedSortRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimate.estimatedSortRows()
					+ " rows entering sort/filesort; configured maximum is " + policy.getMaxEstimatedSortRows()
					+ diagnosticSuffix(estimate));
		}
		if (policy.getMaxEstimatedAggregateRows() > 0
				&& estimate.estimatedAggregateRows() > policy.getMaxEstimatedAggregateRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimate.estimatedAggregateRows()
					+ " rows handled by aggregation/grouping; configured maximum is " + policy.getMaxEstimatedAggregateRows()
					+ diagnosticSuffix(estimate));
		}
		if (policy.getMaxEstimatedCost() > 0 && estimate.estimatedCost() > policy.getMaxEstimatedCost()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimated cost is " + estimate.estimatedCost()
					+ "; configured maximum is " + policy.getMaxEstimatedCost() + diagnosticSuffix(estimate));
		}
		if (estimate.fullTableScan()) {
			if (policy.isRejectFullTableScan()) {
				throw new SqlCostGuardViolationException("EXPLAIN reports a full table scan" + diagnosticSuffix(estimate));
			}
			if (policy.getMaxFullScanRows() > 0 && estimate.estimatedScanRows() > policy.getMaxFullScanRows()) {
				throw new SqlCostGuardViolationException("EXPLAIN reports a full table scan over approximately "
						+ estimate.estimatedScanRows() + " rows; configured full-scan maximum is "
						+ policy.getMaxFullScanRows() + diagnosticSuffix(estimate));
			}
		}
		List<String> warnings = new ArrayList<>(estimate.warnings());
		if (estimate.fullTableScan()) {
			warnings.add("EXPLAIN reports a full table scan");
		}
		return new CostAssessment(tableCount, estimate.estimatedScanRows(), estimate.estimatedIntermediateRows(),
				estimate.estimatedJoinRows(), estimate.estimatedSortRows(), estimate.estimatedAggregateRows(), estimate.estimatedCost(),
				estimate.fullTableScan(), estimate.expensiveOperators(), List.copyOf(warnings));
	}

	private String diagnosticSuffix(QueryCostEstimate estimate) {
		return estimate.expensiveOperators().isEmpty() ? ""
				: "; expensive operators=" + String.join(",", estimate.expensiveOperators());
	}

	private CostAssessment validateLegacyExplain(ResultSetBO explainResult, int tableCount,
			SemEvoSQLProperties.SqlExecutionPolicy policy) {
		if (explainResult == null || explainResult.getData() == null) {
			return new CostAssessment(tableCount, 0, 0, 0, 0, 0, 0, false, List.of(),
					List.of("EXPLAIN returned no structured plan"));
		}
		long estimatedRows = 0;
		boolean fullTableScan = false;
		List<String> warnings = new ArrayList<>();
		for (Map<String, String> row : explainResult.getData()) {
			estimatedRows = saturatedAdd(estimatedRows, extractEstimatedRows(row));
			fullTableScan |= isFullTableScan(row);
		}
		if (estimatedRows > policy.getMaxEstimatedRows()) {
			throw new SqlCostGuardViolationException("EXPLAIN estimates " + estimatedRows
					+ " scanned rows; configured maximum is " + policy.getMaxEstimatedRows());
		}
		if (fullTableScan) {
			if (policy.isRejectFullTableScan()) {
				throw new SqlCostGuardViolationException("EXPLAIN reports a full table scan");
			}
			if (policy.getMaxFullScanRows() > 0 && estimatedRows > policy.getMaxFullScanRows()) {
				throw new SqlCostGuardViolationException("EXPLAIN reports a full table scan over approximately "
						+ estimatedRows + " rows; configured full-scan maximum is " + policy.getMaxFullScanRows());
			}
			warnings.add("EXPLAIN reports a full table scan");
		}
		return new CostAssessment(tableCount, estimatedRows, estimatedRows, 0, 0, 0, 0, fullTableScan, List.of(),
				List.copyOf(warnings));
	}

	private boolean hasTimeFilter(String sql, Collection<String> timeColumns) {
		return SqlTimePredicateGuard.hasTimeFilter(sql, timeColumns);
	}

	private long literalDateRangeDays(String sql) {
		Matcher matcher = DATE_LITERAL.matcher(sql);
		LocalDate minimum = null;
		LocalDate maximum = null;
		while (matcher.find()) {
			LocalDate value = LocalDate.parse(matcher.group(1));
			minimum = minimum == null || value.isBefore(minimum) ? value : minimum;
			maximum = maximum == null || value.isAfter(maximum) ? value : maximum;
		}
		return minimum == null || maximum == null ? 0 : ChronoUnit.DAYS.between(minimum, maximum);
	}

	private long extractEstimatedRows(Map<String, String> row) {
		for (Map.Entry<String, String> entry : row.entrySet()) {
			String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
			if (ESTIMATED_ROW_KEYS.contains(key)) {
				try {
					return Math.max(0, Long.parseLong(entry.getValue().replace(",", "").trim()));
				}
				catch (RuntimeException ignored) {
					return 0;
				}
			}
		}
		return 0;
	}

	private boolean isFullTableScan(Map<String, String> row) {
		return row.entrySet().stream().anyMatch(entry -> {
			String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
			String value = entry.getValue() == null ? "" : entry.getValue().trim().toLowerCase(Locale.ROOT);
			return (key.equals("type") || key.equals("access type") || key.equals("access_type"))
					&& (value.equals("all") || value.equals("seq scan"));
		});
	}

	private long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

	public record CostAssessment(int tableCount, long estimatedRows, long estimatedIntermediateRows, long estimatedJoinRows,
			long estimatedSortRows, long estimatedAggregateRows, double estimatedCost, boolean fullTableScan,
			List<String> expensiveOperators, List<String> warnings) {

		public CostAssessment(int tableCount, long estimatedRows, boolean fullTableScan, List<String> warnings) {
			this(tableCount, estimatedRows, estimatedRows, 0, 0, 0, 0, fullTableScan, List.of(), warnings);
		}
	}

}
