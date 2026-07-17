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
package cn.lgs.semevosql.service.graph.Context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Deterministic business memory extracted from a completed SemEvoSQL turn. */
public record ConversationTurnSummary(int schemaVersion, String canonicalQuery, List<AssetFact> models,
		List<AssetFact> metrics, List<AssetFact> dimensions, List<FilterFact> filters, TimeRangeFact timeRange,
		List<String> groupBy, List<ClarificationFact> clarifications, ResultFact result, String plannerSummary) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public ConversationTurnSummary {
		models = immutable(models);
		metrics = immutable(metrics);
		dimensions = immutable(dimensions);
		filters = immutable(filters);
		groupBy = immutable(groupBy);
		clarifications = immutable(clarifications);
	}

	public static ConversationTurnSummary fallback(String question, String plannerSummary) {
		return new ConversationTurnSummary(CURRENT_SCHEMA_VERSION, question, List.of(), List.of(), List.of(), List.of(),
				null, List.of(), List.of(), null, plannerSummary);
	}

	public String searchableText(String userQuestion) {
		return Stream
			.of(userQuestion, canonicalQuery, assetText(models), assetText(metrics), assetText(dimensions),
					filterText(), timeRange == null ? "" : timeRange.searchableText(),
					groupBy == null ? "" : String.join(" ", groupBy), clarificationText(),
					result == null ? "" : result.searchableText(), plannerSummary)
			.filter(Objects::nonNull)
			.collect(java.util.stream.Collectors.joining(" "));
	}

	private String filterText() {
		return filters.stream().map(FilterFact::searchableText).collect(java.util.stream.Collectors.joining(" "));
	}

	private String clarificationText() {
		return clarifications.stream()
			.map(ClarificationFact::searchableText)
			.collect(java.util.stream.Collectors.joining(" "));
	}

	private static String assetText(List<AssetFact> values) {
		return values.stream().map(AssetFact::searchableText).collect(java.util.stream.Collectors.joining(" "));
	}

	private static <T> List<T> immutable(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	public record AssetFact(String code, String businessName) {

		public String searchableText() {
			return Objects.toString(code, "") + " " + Objects.toString(businessName, "");
		}
	}

	public record FilterFact(String modelCode, String columnName, String operator, Object value) {

		public String searchableText() {
			return String.join(" ", Objects.toString(modelCode, ""), Objects.toString(columnName, ""),
					Objects.toString(operator, ""), Objects.toString(value, ""));
		}
	}

	public record TimeRangeFact(String modelCode, String timeColumn, String startInclusive, String endExclusive,
			String relativeExpression, String timeZone, String granularity) {

		public String searchableText() {
			return String.join(" ", Objects.toString(modelCode, ""), Objects.toString(timeColumn, ""),
					Objects.toString(startInclusive, ""), Objects.toString(endExclusive, ""),
					Objects.toString(relativeExpression, ""), Objects.toString(timeZone, ""),
					Objects.toString(granularity, ""));
		}
	}

	public record ClarificationFact(String issueType, String assetType, String assetKey, String rawExpression,
			String resolvedValue) {

		public String searchableText() {
			return String.join(" ", Objects.toString(issueType, ""), Objects.toString(assetType, ""),
					Objects.toString(assetKey, ""), Objects.toString(rawExpression, ""),
					Objects.toString(resolvedValue, ""));
		}
	}

	public record ResultFact(String artifactId, String artifactType, long rowCount, List<String> columns,
			Map<String, Object> scalarValues) {

		public ResultFact {
			columns = columns == null ? List.of() : List.copyOf(columns);
			scalarValues = scalarValues == null ? Map.of() : Map.copyOf(scalarValues);
		}

		public String searchableText() {
			return String.join(" ", Objects.toString(artifactType, ""), String.join(" ", columns),
					Objects.toString(scalarValues, ""));
		}
	}

}
