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
import java.util.List;

/** Converts dialect-specific EXPLAIN output into one database-independent cost assessment. */
public interface DialectQueryCostEstimator {

	boolean supports(String dialect);

	QueryCostEstimate estimate(ResultSetBO explainResult);

	record QueryCostEstimate(long estimatedScanRows, long estimatedIntermediateRows, long estimatedJoinRows,
			long estimatedSortRows, long estimatedAggregateRows, double estimatedCost, boolean fullTableScan,
			List<String> expensiveOperators, List<String> warnings) {

		public QueryCostEstimate {
			expensiveOperators = List.copyOf(expensiveOperators == null ? List.of() : expensiveOperators);
			warnings = List.copyOf(warnings == null ? List.of() : warnings);
		}

		public static QueryCostEstimate unknown(String warning) {
			return new QueryCostEstimate(0, 0, 0, 0, 0, 0, false, List.of(), List.of(warning));
		}
	}

}
