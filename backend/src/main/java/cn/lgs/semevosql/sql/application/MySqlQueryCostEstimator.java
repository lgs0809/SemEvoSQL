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

/** Parses MySQL EXPLAIN FORMAT=JSON without executing the query. */
@Component
public class MySqlQueryCostEstimator implements DialectQueryCostEstimator {

	@Override
	public boolean supports(String dialect) {
		return "mysql".equalsIgnoreCase(dialect);
	}

	@Override
	public QueryCostEstimate estimate(ResultSetBO explainResult) {
		JsonNode root = ExplainJsonSupport.firstJsonCell(explainResult);
		if (root == null) {
			return QueryCostEstimate.unknown("MySQL EXPLAIN JSON could not be parsed");
		}
		Accumulator accumulator = new Accumulator();
		visit(root, accumulator, "");
		return new QueryCostEstimate(accumulator.scanRows, accumulator.maxIntermediateRows, accumulator.maxJoinRows,
				accumulator.maxSortRows, accumulator.maxAggregateRows, accumulator.maxCost, accumulator.fullScan,
				List.copyOf(accumulator.operators), List.copyOf(accumulator.warnings));
	}

	private long visit(JsonNode node, Accumulator accumulator, String parentKey) {
		if (node == null) {
			return 0;
		}
		if (node.isObject()) {
			long scanned = ExplainJsonSupport.longValue(node, "rows_examined_per_scan");
			long produced = ExplainJsonSupport.longValue(node, "rows_produced_per_join");
			long subtreeRows = Math.max(scanned, produced);
			accumulator.scanRows = ExplainJsonSupport.saturatedAdd(accumulator.scanRows, scanned);
			accumulator.maxIntermediateRows = Math.max(accumulator.maxIntermediateRows, produced);
			accumulator.maxCost = Math.max(accumulator.maxCost, ExplainJsonSupport.doubleValue(node, "query_cost"));
			accumulator.maxCost = Math.max(accumulator.maxCost, ExplainJsonSupport.doubleValue(node, "prefix_cost"));
			String accessType = node.path("access_type").asText("").trim().toLowerCase(Locale.ROOT);
			if ("all".equals(accessType)) {
				accumulator.fullScan = true;
				accumulator.operators.add("FULL_TABLE_SCAN");
			}
			if (node.path("using_temporary_table").asBoolean(false)) {
				accumulator.operators.add("TEMPORARY_TABLE");
			}
			var fields = node.fields();
			while (fields.hasNext()) {
				var entry = fields.next();
				subtreeRows = Math.max(subtreeRows, visit(entry.getValue(), accumulator, entry.getKey()));
			}
			if (node.path("using_filesort").asBoolean(false) || "ordering_operation".equals(parentKey)) {
				accumulator.operators.add("FILESORT");
				accumulator.maxSortRows = Math.max(accumulator.maxSortRows, subtreeRows);
			}
			if ("grouping_operation".equals(parentKey) || "duplicates_removal".equals(parentKey)) {
				accumulator.operators.add("AGGREGATE");
				accumulator.maxAggregateRows = Math.max(accumulator.maxAggregateRows, subtreeRows);
			}
			if ("nested_loop".equals(parentKey)) {
				accumulator.operators.add("NESTED_LOOP");
				accumulator.maxJoinRows = Math.max(accumulator.maxJoinRows, Math.max(produced, subtreeRows));
			}
			return subtreeRows;
		}
		if (node.isArray()) {
			long subtreeRows = 0;
			if ("nested_loop".equals(parentKey)) {
				accumulator.operators.add("NESTED_LOOP");
			}
			for (JsonNode child : node) {
				subtreeRows = Math.max(subtreeRows, visit(child, accumulator, parentKey));
			}
			if ("nested_loop".equals(parentKey)) {
				accumulator.maxJoinRows = Math.max(accumulator.maxJoinRows, subtreeRows);
			}
			return subtreeRows;
		}
		return 0;
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
