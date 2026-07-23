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
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Parses PostgreSQL EXPLAIN (FORMAT JSON, COSTS TRUE) without executing the query. */
@Component
public class PostgreSqlQueryCostEstimator implements DialectQueryCostEstimator {

	@Override
	public boolean supports(String dialect) {
		if (dialect == null) {
			return false;
		}
		String normalized = dialect.trim().toLowerCase(Locale.ROOT);
		return normalized.equals("postgresql") || normalized.equals("postgres") || normalized.equals("hologress");
	}

	@Override
	public QueryCostEstimate estimate(ResultSetBO explainResult) {
		JsonNode root = ExplainJsonSupport.firstJsonCell(explainResult);
		if (root == null) {
			return QueryCostEstimate.unknown("PostgreSQL EXPLAIN JSON could not be parsed");
		}
		JsonNode plan = root.isArray() && !root.isEmpty() ? root.get(0).path("Plan") : root.path("Plan");
		if (plan == null || plan.isMissingNode() || plan.isNull()) {
			return QueryCostEstimate.unknown("PostgreSQL EXPLAIN JSON does not contain a Plan tree");
		}
		Accumulator accumulator = new Accumulator();
		visitPlan(plan, accumulator);
		return new QueryCostEstimate(accumulator.scanRows, accumulator.maxIntermediateRows, accumulator.maxJoinRows,
				accumulator.maxSortRows, accumulator.maxAggregateRows, accumulator.maxCost, accumulator.fullScan,
				List.copyOf(accumulator.operators), List.copyOf(accumulator.warnings));
	}

	private void visitPlan(JsonNode node, Accumulator accumulator) {
		if (node == null || !node.isObject()) {
			return;
		}
		String nodeType = node.path("Node Type").asText("").trim();
		String normalizedType = nodeType.toLowerCase(Locale.ROOT);
		long rows = ExplainJsonSupport.longValue(node, "Plan Rows");
		long workers = ExplainJsonSupport.longValue(node, "Workers Planned");
		long effectiveRows = normalizedType.contains("parallel") && workers > 0 ? saturatedMultiply(rows, workers + 1) : rows;
		accumulator.maxIntermediateRows = Math.max(accumulator.maxIntermediateRows, effectiveRows);
		accumulator.maxCost = Math.max(accumulator.maxCost, ExplainJsonSupport.doubleValue(node, "Total Cost"));
		if (normalizedType.contains("scan")) {
			accumulator.scanRows = ExplainJsonSupport.saturatedAdd(accumulator.scanRows, effectiveRows);
		}
		if (normalizedType.equals("seq scan") || normalizedType.equals("parallel seq scan")) {
			accumulator.fullScan = true;
			accumulator.operators.add("SEQ_SCAN");
		}
		if (normalizedType.contains("sort")) {
			accumulator.operators.add("SORT");
			accumulator.maxSortRows = Math.max(accumulator.maxSortRows, effectiveRows);
		}
		if (normalizedType.contains("aggregate") || normalizedType.contains("group")) {
			accumulator.operators.add("AGGREGATE");
			accumulator.maxAggregateRows = Math.max(accumulator.maxAggregateRows, effectiveRows);
		}
		if (normalizedType.contains("nested loop")) {
			accumulator.operators.add("NESTED_LOOP");
			accumulator.maxJoinRows = Math.max(accumulator.maxJoinRows, effectiveRows);
		}
		if (normalizedType.contains("hash join")) {
			accumulator.operators.add("HASH_JOIN");
			accumulator.maxJoinRows = Math.max(accumulator.maxJoinRows, effectiveRows);
		}
		if (normalizedType.contains("merge join")) {
			accumulator.operators.add("MERGE_JOIN");
			accumulator.maxJoinRows = Math.max(accumulator.maxJoinRows, effectiveRows);
		}
		if (normalizedType.contains("materialize")) {
			accumulator.operators.add("MATERIALIZE");
		}
		if (normalizedType.contains("cte scan")) {
			accumulator.operators.add("CTE_SCAN");
		}
		if (workers > 0) {
			accumulator.warnings.add("PostgreSQL plan uses parallel workers; row estimates are conservatively worker-adjusted");
		}
		JsonNode children = node.path("Plans");
		if (children.isArray()) {
			children.forEach(child -> visitPlan(child, accumulator));
		}
	}

	private long saturatedMultiply(long left, long right) {
		if (left <= 0 || right <= 0) {
			return 0;
		}
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static final class Accumulator {

		private long scanRows;

		private long maxIntermediateRows;

		private long maxJoinRows;

		private long maxSortRows;

		private long maxAggregateRows;

		private double maxCost;

		private boolean fullScan;

		private final Set<String> operators = new LinkedHashSet<>();

		private final Set<String> warnings = new LinkedHashSet<>();

	}

}
