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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.FilterBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Builds the explicitly governed portion of the Semantic Blueprint. */
@Service
public class SemanticBlueprintEnricher {

	private static final Set<String> SUPPORTED_LITERAL_FILTER_OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT",
			"LTE", "IN", "IS_NULL", "IS_NOT_NULL");

	private static final Set<String> SUPPORTED_TIME_GROUP_GRANULARITIES = Set.of("DAY", "MONTH", "YEAR");

	private static final Pattern LIMIT_PATTERN = Pattern.compile(
			"(?:top|limit|前|最多)\\s*(\\d{1,5}|[一二两三四五六七八九十百]{1,4})", Pattern.CASE_INSENSITIVE);

	private static final Pattern RANKED_ENTITY_COUNT_PATTERN = Pattern.compile(
			"(?:最高|最低|最大|最小|最多|最少|top|bottom|从高到低|从低到高|降序|升序|排名|前|后).*?(\\d{1,5}|[一二两三四五六七八九十百]{1,4})\\s*(?:笔(?:订单|交易|记录)?|条(?:记录)?|行|记录|单|个订单)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern AGGREGATE_EXPRESSION = Pattern
		.compile("(?i)^\\s*(?:sum|count|avg|average|min|max)\\s*\\(");

	private static final Pattern ROW_LEVEL_AGGREGATE_EXPRESSION = Pattern
		.compile("(?is)^\\s*(?:sum|avg|average|min|max)\\s*\\((.+)\\)\\s*$");

	private static final String ABSOLUTE_RANGE_CONNECTOR_EXPRESSION = "(?:至|到|~|～|—|–|to|through|until)";

	private static final Pattern CHINESE_ABSOLUTE_DATE = Pattern
		.compile("(?<!\\d)(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月(?:\\s*(\\d{1,2})\\s*[日号])?");

	private static final Pattern CHINESE_INHERITED_YEAR_RANGE = Pattern.compile(
			"(?<!\\d)(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月(?:\\s*(\\d{1,2})\\s*[日号])?\\s*"
					+ ABSOLUTE_RANGE_CONNECTOR_EXPRESSION
					+ "\\s*(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月(?:\\s*(\\d{1,2})\\s*[日号])?",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ISO_ABSOLUTE_DATE = Pattern
		.compile("(?<!\\d)(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?(?!\\d)");

	private static final Pattern ISO_INHERITED_YEAR_RANGE = Pattern.compile(
			"(?<!\\d)(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?\\s*"
					+ ABSOLUTE_RANGE_CONNECTOR_EXPRESSION
					+ "\\s*(?:(\\d{4})[-/.])?(\\d{1,2})(?:[-/.](\\d{1,2}))?(?!\\d)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ABSOLUTE_RANGE_CONNECTOR = Pattern
		.compile("(?i)^\\s*" + ABSOLUTE_RANGE_CONNECTOR_EXPRESSION + "\\s*$");

	private static final Pattern SYSTEM_CURRENT_TIME_CONTEXT = Pattern.compile(
			"[\\[（(]?\\s*(?:当前时间|current\\s+time)\\s*[:：]\\s*\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s*[\\]）)]?",
			Pattern.CASE_INSENSITIVE);

	public List<SemanticBlueprint.MetricSelection> metricsForIntent(String query,
			List<SemanticBlueprint.MetricSelection> metrics) {
		if (!rowLevelRanking(normalize(query))) {
			return metrics == null ? List.of() : List.copyOf(metrics);
		}
		return (metrics == null ? List.<SemanticBlueprint.MetricSelection>of() : metrics).stream()
			.map(metric -> SemanticBlueprint.MetricSelection.builder()
				.metricCode(metric.getMetricCode())
				.modelCode(metric.getModelCode())
				.businessName(metric.getBusinessName())
				.expression(rowLevelMetricExpression(metric.getExpression()))
				.aggregation("NONE")
				.unit(metric.getUnit())
				.timeColumn(metric.getTimeColumn())
				.filterExpression(metric.getFilterExpression())
				.additiveType(metric.getAdditiveType())
				.build())
			.toList();
	}

	public IrDetails enrich(SemanticCatalogSnapshot snapshot, String query,
			List<SemanticBlueprint.ModelSelection> models, List<SemanticBlueprint.MetricSelection> metrics,
			List<SemanticBlueprint.DimensionSelection> dimensions, List<SemanticBlueprint.GrainSelection> grains) {
		return enrich(snapshot, query, models, metrics, dimensions, grains, QueryCaseHints.empty());
	}

	public IrDetails enrich(SemanticCatalogSnapshot snapshot, String query,
			List<SemanticBlueprint.ModelSelection> models, List<SemanticBlueprint.MetricSelection> metrics,
			List<SemanticBlueprint.DimensionSelection> dimensions, List<SemanticBlueprint.GrainSelection> grains,
			QueryCaseHints caseHints) {
		QueryCaseHints hints = caseHints == null ? QueryCaseHints.empty() : caseHints;
		String normalized = normalize(query);
		Map<String, SemanticCatalogSnapshot.Column> columns = new LinkedHashMap<>();
		for (SemanticCatalogSnapshot.Column column : snapshot.getColumns()) {
			if (column.getStatus() == SemanticAssetStatus.ENABLED) {
				columns.put(key(column.getModelCode(), column.getColumnName()), column);
			}
		}
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<SemanticBlueprint.ProjectionSelection> projections = new ArrayList<>();
		List<SemanticBlueprint.GroupSelection> groups = new ArrayList<>();
		boolean rowLevelRanking = rowLevelRanking(normalized);
		List<SemanticBlueprint.DimensionSelection> selectedDimensions = dimensions.stream()
			.filter(dimension -> !metricBackedScalarDimension(normalized, dimension, metrics))
			.filter(dimension -> !bucketedTimeAxisDimension(hints.timeBinding(), dimension))
			.toList();
		for (SemanticBlueprint.DimensionSelection dimension : selectedDimensions) {
			SemanticCatalogSnapshot.Column column = columns
				.get(key(dimension.getModelCode(), dimension.getColumnName()));
			if (column != null && !Boolean.TRUE.equals(column.getAllowProjection())) {
				errors.add("Column governance denies projection: "
						+ key(dimension.getModelCode(), dimension.getColumnName()));
				continue;
			}
			String expression = firstText(dimension.getExpression(), dimension.getColumnName());
			projections.add(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode(dimension.getModelCode())
				.columnName(dimension.getColumnName())
				.expression(expression)
				.alias(dimension.getDimensionCode())
				.projectionType("DIMENSION")
				.masked(column != null && !"NONE".equalsIgnoreCase(column.getMaskingPolicy()))
				.build());
			if (!rowLevelRanking) {
				groups.add(SemanticBlueprint.GroupSelection.builder()
					.modelCode(dimension.getModelCode())
					.columnName(dimension.getColumnName())
					.expression(expression)
					.alias(dimension.getDimensionCode())
					.build());
			}
		}
		if (rowLevelRanking) {
			addRowIdentifierProjection(projections, models, metrics, grains, columns, errors);
		}
		for (SemanticBlueprint.MetricSelection metric : metrics) {
			projections.add(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode(metric.getModelCode())
				.expression(metricExpression(metric))
				.alias(metric.getMetricCode())
				.projectionType("METRIC")
				.masked(false)
				.build());
		}

		List<SemanticBlueprint.EnumResolution> enumResolutions = new ArrayList<>();
		List<SemanticBlueprint.FilterSelection> filters = new ArrayList<>();
		Set<String> selectedModelCodes = models.stream()
			.map(SemanticBlueprint.ModelSelection::getModelCode)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		for (SemanticCatalogSnapshot.EnumValue value : snapshot.getEnumValues()) {
			if (hints.strictAssetBinding() || value.getStatus() != SemanticAssetStatus.ENABLED
					|| !selectedModelCodes.contains(value.getModelCode())) {
				continue;
			}
			String matched = matchedEnumText(normalized, value);
			if (matched == null || metricOwnsEnumPhrase(normalized, matched, metrics)) {
				continue;
			}
			SemanticCatalogSnapshot.Column column = columns.get(key(value.getModelCode(), value.getColumnName()));
			if (column == null || !Boolean.TRUE.equals(column.getAllowFilter())
					|| !Boolean.TRUE.equals(column.getAllowSendToLlm())) {
				errors.add("Column governance denies enum filtering or LLM exposure: "
						+ key(value.getModelCode(), value.getColumnName()));
				continue;
			}
			addEnumResolution(enumResolutions, filters, value, matched,
					normalize(value.getValueCode()).equals(matched) ? 1.0 : 0.95);
		}
		for (EnumBindingHint hint : hints.enumBindings()) {
			String rawText = normalize(hint.rawText());
			if (rawText.isBlank() || !normalized.contains(rawText) || !selectedModelCodes.contains(hint.modelCode())
					|| enumResolutions.stream().anyMatch(existing -> sameEnum(existing, hint))) {
				continue;
			}
			SemanticCatalogSnapshot.EnumValue current = snapshot.getEnumValues()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> Objects.equals(value.getModelCode(), hint.modelCode())
						&& Objects.equals(value.getColumnName(), hint.columnName())
						&& Objects.equals(value.getValueCode(), hint.valueCode()))
				.findFirst()
				.orElse(null);
			if (current == null) {
				continue;
			}
			SemanticCatalogSnapshot.Column column = columns.get(key(current.getModelCode(), current.getColumnName()));
			if (column == null || !Boolean.TRUE.equals(column.getAllowFilter())
					|| !Boolean.TRUE.equals(column.getAllowSendToLlm())) {
				errors.add("Column governance denies recalled enum filtering or LLM exposure: "
						+ key(current.getModelCode(), current.getColumnName()));
				continue;
			}
			addEnumResolution(enumResolutions, filters, current, rawText, Math.min(0.90, hint.confidence()));
			warnings.add("Revalidated historical enum binding for current Catalog: " + rawText + " -> "
					+ current.getValueCode());
		}
		for (FilterBindingHint hint : hints.filterBindings()) {
			if (!selectedModelCodes.contains(hint.modelCode())) {
				errors.add("Literal filter references a semantic model outside the selected plan: " + hint.modelCode());
				continue;
			}
			SemanticCatalogSnapshot.Column column = columns.get(key(hint.modelCode(), hint.columnName()));
			if (column == null || !Boolean.TRUE.equals(column.getAllowFilter())
					|| !Boolean.TRUE.equals(column.getAllowSendToLlm())) {
				errors.add("Column governance denies literal filtering or LLM exposure: "
						+ key(hint.modelCode(), hint.columnName()));
				continue;
			}
			String operator = Objects.toString(hint.operator(), "").trim().toUpperCase(Locale.ROOT);
			if (!SUPPORTED_LITERAL_FILTER_OPERATORS.contains(operator)) {
				errors.add("Unsupported governed literal filter operator: " + hint.operator());
				continue;
			}
			boolean nullPredicate = "IS_NULL".equals(operator) || "IS_NOT_NULL".equals(operator);
			if (nullPredicate) {
				if (hint.value() != null) {
					errors.add("Governed " + operator + " filter must not carry a literal value");
					continue;
				}
			}
			else if ("IN".equals(operator)) {
				if (!(hint.value() instanceof List<?> values) || values.isEmpty()) {
					errors.add("Governed IN literal filter requires a non-empty value list");
					continue;
				}
			}
			else if (hint.value() == null || hint.value() instanceof java.util.Collection<?>) {
				errors.add("Governed scalar literal filter requires one scalar value");
				continue;
			}
			boolean duplicate = filters.stream()
				.anyMatch(existing -> Objects.equals(existing.getModelCode(), hint.modelCode())
						&& Objects.equals(existing.getColumnName(), hint.columnName())
						&& Objects.equals(existing.getOperator(), operator)
						&& Objects.equals(existing.getValue(), hint.value()));
			if (!duplicate) {
				filters.add(SemanticBlueprint.FilterSelection.builder()
					.modelCode(hint.modelCode())
					.columnName(hint.columnName())
					.expression(hint.columnName())
					.operator(operator)
					.value(hint.value())
					.valueType("LITERAL")
					.required(true)
					.build());
			}
		}

		Set<String> timeRelevantModelCodes = timeRelevantModelCodes(metrics, selectedDimensions, selectedModelCodes);
		boolean timeIntentCoveredByGovernedFilter = hasGovernedTimeFilter(hints, columns);
		boolean timeRangeCoveredByGovernedFilter = hasGovernedTimeLiteralFilter(hints, columns);
		SemanticBlueprint.TimeRangeSelection timeRange = timeRangeCoveredByGovernedFilter ? null
				: timeRange(normalized, metrics, timeRelevantModelCodes, columns, hints);
		if (containsTimeRangeIntent(normalized) && timeRange == null && !timeIntentCoveredByGovernedFilter) {
			if (hints.strictAssetBinding()) {
				errors.add("Strict semantic binding requires an explicit governed timeBinding or governed time-column filter for time intent");
			}
			else {
				warnings.add("Time intent was detected but the selected semantic models have no governed time binding/filter");
			}
		}
		addTimeGrouping(hints.timeBinding(), columns, projections, groups, errors);
		int limit = limit(normalized);
		List<SemanticBlueprint.OrderSelection> order = order(normalized, metrics);
		List<String> resultColumns = projections.stream()
			.map(SemanticBlueprint.ProjectionSelection::getAlias)
			.filter(StringUtils::hasText)
			.toList();
		String grain = rowLevelRanking ? rowRankingGrain(metrics, grains)
				: expectedResultGrain(selectedDimensions, grains, metrics, hints.timeBinding());
		SemanticBlueprint.ExpectedResultShape expected = SemanticBlueprint.ExpectedResultShape.builder()
			.columns(resultColumns)
			.grain(grain)
			.maxRows(limit)
			.tabular(true)
			.chartable(!metrics.isEmpty() && !selectedDimensions.isEmpty())
			.build();
		String compilerMode = projections.isEmpty() ? "CONSTRAINED_GENERATION" : "DETERMINISTIC";
		if (projections.isEmpty()) {
			warnings.add("No governed projection was resolved; constrained generation is required");
		}
		return new IrDetails(List.copyOf(projections), List.copyOf(selectedDimensions), List.copyOf(filters),
				List.copyOf(enumResolutions), timeRange, List.copyOf(groups), List.copyOf(order), limit, expected,
				compilerMode, List.copyOf(warnings), List.copyOf(errors));
	}

	private void addTimeGrouping(QueryCaseHints.TimeBindingHint timeBinding,
			Map<String, SemanticCatalogSnapshot.Column> columns,
			List<SemanticBlueprint.ProjectionSelection> projections, List<SemanticBlueprint.GroupSelection> groups,
			List<String> errors) {
		if (timeBinding == null || !StringUtils.hasText(timeBinding.groupGranularity())) {
			return;
		}
		String granularity = timeBinding.groupGranularity().toUpperCase(Locale.ROOT);
		if (!SUPPORTED_TIME_GROUP_GRANULARITIES.contains(granularity)) {
			errors.add("Unsupported governed time grouping granularity: " + timeBinding.groupGranularity());
			return;
		}
		SemanticCatalogSnapshot.Column column = columns.get(key(timeBinding.modelCode(), timeBinding.columnName()));
		if (column == null || column.getRole() != SemanticColumnRole.TIME
				|| !Boolean.TRUE.equals(column.getAllowProjection())) {
			errors.add("Column governance denies time grouping projection: "
					+ key(timeBinding.modelCode(), timeBinding.columnName()));
			return;
		}
		String expression = canonicalTimeBucketExpression(timeBinding.columnName(), granularity);
		String alias = timeBinding.columnName() + "_" + granularity.toLowerCase(Locale.ROOT);
		boolean projectionExists = projections.stream().anyMatch(projection -> Objects.equals(projection.getModelCode(),
				timeBinding.modelCode()) && Objects.equals(projection.getAlias(), alias));
		if (!projectionExists) {
			projections.add(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode(timeBinding.modelCode())
				.columnName(timeBinding.columnName())
				.expression(expression)
				.alias(alias)
				.projectionType("TIME_BUCKET")
				.timeBucketGranularity(granularity)
				.masked(false)
				.build());
		}
		boolean groupExists = groups.stream().anyMatch(group -> Objects.equals(group.getModelCode(), timeBinding.modelCode())
				&& Objects.equals(group.getAlias(), alias));
		if (!groupExists) {
			groups.add(SemanticBlueprint.GroupSelection.builder()
				.modelCode(timeBinding.modelCode())
				.columnName(timeBinding.columnName())
				.expression(expression)
				.alias(alias)
				.timeBucketGranularity(granularity)
				.build());
		}
	}

	private String canonicalTimeBucketExpression(String columnName, String granularity) {
		return switch (granularity) {
			case "DAY" -> "DATE(" + columnName + ")";
			case "MONTH" -> "DATE_TRUNC('month', " + columnName + ")";
			case "YEAR" -> "DATE_TRUNC('year', " + columnName + ")";
			default -> throw new IllegalArgumentException("Unsupported governed time grouping granularity: " + granularity);
		};
	}

	private String expectedResultGrain(List<SemanticBlueprint.DimensionSelection> dimensions,
			List<SemanticBlueprint.GrainSelection> grains, List<SemanticBlueprint.MetricSelection> metrics,
			QueryCaseHints.TimeBindingHint timeBinding) {
		List<String> components = new ArrayList<>(dimensions.stream()
			.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
			.filter(StringUtils::hasText)
			.toList());
		if (timeBinding != null && StringUtils.hasText(timeBinding.groupGranularity())) {
			components.add(timeBinding.columnName() + "_" + timeBinding.groupGranularity().toLowerCase(Locale.ROOT));
		}
		if (!components.isEmpty()) {
			return String.join(",", components);
		}
		return !metrics.isEmpty() ? "SCALAR"
				: grains.stream().findFirst().map(SemanticBlueprint.GrainSelection::getGrainCode).orElse("SCALAR");
	}

	private void addRowIdentifierProjection(List<SemanticBlueprint.ProjectionSelection> projections,
			List<SemanticBlueprint.ModelSelection> models, List<SemanticBlueprint.MetricSelection> metrics,
			List<SemanticBlueprint.GrainSelection> grains, Map<String, SemanticCatalogSnapshot.Column> columns,
			List<String> errors) {
		Set<String> metricModels = metrics.stream().map(SemanticBlueprint.MetricSelection::getModelCode)
			.filter(StringUtils::hasText).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		String modelCode = metricModels.size() == 1 ? metricModels.iterator().next()
				: models.stream().map(SemanticBlueprint.ModelSelection::getModelCode).filter(StringUtils::hasText).findFirst()
					.orElse(null);
		if (!StringUtils.hasText(modelCode)) {
			errors.add("Row-level ranking requires one governed semantic model");
			return;
		}
		SemanticBlueprint.GrainSelection grain = grains.stream().filter(value -> modelCode.equals(value.getModelCode()))
			.findFirst().orElse(null);
		String keyColumn = grain == null ? null : firstKeyColumn(grain.getKeyColumns());
		SemanticCatalogSnapshot.Column column = columns.get(key(modelCode, keyColumn));
		if (!StringUtils.hasText(keyColumn) || column == null || !Boolean.TRUE.equals(column.getAllowProjection())) {
			errors.add("Row-level ranking requires a governed projectable grain key for model: " + modelCode);
			return;
		}
		boolean duplicate = projections.stream().anyMatch(value -> modelCode.equals(value.getModelCode())
				&& keyColumn.equals(value.getColumnName()));
		if (!duplicate) {
			projections.add(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode(modelCode)
				.columnName(keyColumn)
				.expression(keyColumn)
				.alias(keyColumn)
				.projectionType("IDENTIFIER")
				.masked(!"NONE".equalsIgnoreCase(column.getMaskingPolicy()))
				.build());
		}
	}

	private String firstKeyColumn(String keyColumns) {
		if (!StringUtils.hasText(keyColumns)) {
			return null;
		}
		return java.util.Arrays.stream(keyColumns.split("[,，]"))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse(null);
	}

	private String rowRankingGrain(List<SemanticBlueprint.MetricSelection> metrics,
			List<SemanticBlueprint.GrainSelection> grains) {
		Set<String> models = metrics.stream().map(SemanticBlueprint.MetricSelection::getModelCode)
			.filter(StringUtils::hasText).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (models.size() != 1) {
			return "ROW";
		}
		String modelCode = models.iterator().next();
		return grains.stream().filter(value -> modelCode.equals(value.getModelCode()))
			.map(SemanticBlueprint.GrainSelection::getGrainCode).findFirst().orElse("ROW");
	}

	private void addEnumResolution(List<SemanticBlueprint.EnumResolution> resolutions,
			List<SemanticBlueprint.FilterSelection> filters, SemanticCatalogSnapshot.EnumValue value, String inputText,
			double confidence) {
		resolutions.add(SemanticBlueprint.EnumResolution.builder()
			.modelCode(value.getModelCode())
			.columnName(value.getColumnName())
			.inputText(inputText)
			.valueCode(value.getValueCode())
			.businessName(value.getBusinessName())
			.confidence(confidence)
			.build());
		filters.add(SemanticBlueprint.FilterSelection.builder()
			.modelCode(value.getModelCode())
			.columnName(value.getColumnName())
			.expression(value.getColumnName())
			.operator("EQ")
			.value(value.getValueCode())
			.valueType("ENUM")
			.required(true)
			.build());
	}

	private boolean sameEnum(SemanticBlueprint.EnumResolution existing, EnumBindingHint hint) {
		return Objects.equals(existing.getModelCode(), hint.modelCode())
				&& Objects.equals(existing.getColumnName(), hint.columnName())
				&& Objects.equals(existing.getValueCode(), hint.valueCode());
	}

	private boolean hasGovernedTimeFilter(QueryCaseHints hints,
			Map<String, SemanticCatalogSnapshot.Column> columns) {
		for (FilterBindingHint hint : hints.filterBindings()) {
			SemanticCatalogSnapshot.Column column = columns.get(key(hint.modelCode(), hint.columnName()));
			if (column != null && column.getRole() == SemanticColumnRole.TIME
					&& Boolean.TRUE.equals(column.getAllowFilter())) {
				return true;
			}
		}
		return false;
	}

	private boolean hasGovernedTimeLiteralFilter(QueryCaseHints hints,
			Map<String, SemanticCatalogSnapshot.Column> columns) {
		for (FilterBindingHint hint : hints.filterBindings()) {
			SemanticCatalogSnapshot.Column column = columns.get(key(hint.modelCode(), hint.columnName()));
			String operator = Objects.toString(hint.operator(), "").trim().toUpperCase(Locale.ROOT);
			if (column != null && column.getRole() == SemanticColumnRole.TIME
					&& Boolean.TRUE.equals(column.getAllowFilter()) && hint.value() != null
					&& !"IS_NULL".equals(operator) && !"IS_NOT_NULL".equals(operator)) {
				return true;
			}
		}
		return false;
	}

	private Set<String> timeRelevantModelCodes(List<SemanticBlueprint.MetricSelection> metrics,
			List<SemanticBlueprint.DimensionSelection> dimensions, Set<String> selectedModelCodes) {
		Set<String> metricModels = metrics.stream()
			.map(SemanticBlueprint.MetricSelection::getModelCode)
			.filter(StringUtils::hasText)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (!metricModels.isEmpty()) {
			return metricModels;
		}
		Set<String> dimensionModels = dimensions.stream()
			.map(SemanticBlueprint.DimensionSelection::getModelCode)
			.filter(StringUtils::hasText)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return dimensionModels.isEmpty() ? selectedModelCodes : dimensionModels;
	}

	private SemanticBlueprint.TimeRangeSelection timeRange(String query,
			List<SemanticBlueprint.MetricSelection> metrics, Set<String> selectedModelCodes,
			Map<String, SemanticCatalogSnapshot.Column> columns, QueryCaseHints hints) {
		String relative = relativeRange(query);
		AbsoluteTimeRange absolute = absoluteRange(query);
		if (relative == null && absolute == null) {
			return null;
		}
		if (hints.strictAssetBinding() && hints.timeBinding() == null) {
			return null;
		}
		if (hints.timeBinding() != null) {
			String rawText = normalize(hints.timeBinding().rawText());
			if (!rawText.isBlank() && query.contains(rawText)) {
				SemanticCatalogSnapshot.Column hinted = columns
					.get(key(hints.timeBinding().modelCode(), hints.timeBinding().columnName()));
				if (hinted == null || hinted
					.getRole() != cn.lgs.semevosql.semantic.domain.SemanticColumnRole.TIME
						|| !Boolean.TRUE.equals(hinted.getAllowFilter())) {
					return null;
				}
				return timeRangeSelection(hints.timeBinding().modelCode(), hints.timeBinding().columnName(), relative,
						absolute, granularity(query));
			}
		}
		if (selectedModelCodes.size() != 1) {
			return null;
		}
		String modelCode = selectedModelCodes.iterator().next();
		Set<String> metricTimeColumns = metrics.stream()
			.filter(metric -> modelCode.equals(metric.getModelCode()))
			.map(SemanticBlueprint.MetricSelection::getTimeColumn)
			.filter(StringUtils::hasText)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (metricTimeColumns.size() > 1) {
			return null;
		}
		String timeColumn = metricTimeColumns.stream().findFirst().orElse(null);
		if (!StringUtils.hasText(timeColumn)) {
			List<SemanticCatalogSnapshot.Column> candidates = columns.values()
				.stream()
				.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(column -> modelCode.equals(column.getModelCode()))
				.filter(column -> column
					.getRole() == cn.lgs.semevosql.semantic.domain.SemanticColumnRole.TIME)
				.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
				.toList();
			if (candidates.size() != 1) {
				return null;
			}
			timeColumn = candidates.get(0).getColumnName();
		}
		SemanticCatalogSnapshot.Column governed = columns.get(key(modelCode, timeColumn));
		if (governed != null && !Boolean.TRUE.equals(governed.getAllowFilter())) {
			return null;
		}
		return timeRangeSelection(modelCode, timeColumn, relative, absolute, granularity(query));
	}

	private SemanticBlueprint.TimeRangeSelection timeRangeSelection(String modelCode, String timeColumn, String relative,
			AbsoluteTimeRange absolute, String granularity) {
		SemanticBlueprint.TimeRangeSelection.TimeRangeSelectionBuilder builder = SemanticBlueprint.TimeRangeSelection
			.builder()
			.modelCode(modelCode)
			.timeColumn(timeColumn)
			.timeZone("SYSTEM")
			.granularity(granularity);
		if (absolute != null) {
			builder.startInclusive(absolute.startInclusive().atStartOfDay().toString())
				.endExclusive(absolute.endExclusive().atStartOfDay().toString());
		}
		else {
			builder.relativeExpression(relative);
		}
		return builder.build();
	}

	private AbsoluteTimeRange absoluteRange(String query) {
		Matcher chineseRange = CHINESE_INHERITED_YEAR_RANGE.matcher(query);
		if (chineseRange.find()) {
			return inheritedYearRange(chineseRange);
		}
		Matcher isoRange = ISO_INHERITED_YEAR_RANGE.matcher(query);
		if (isoRange.find()) {
			return inheritedYearRange(isoRange);
		}
		List<AbsoluteDateToken> dates = new ArrayList<>();
		collectAbsoluteDates(CHINESE_ABSOLUTE_DATE.matcher(query), dates);
		collectAbsoluteDates(ISO_ABSOLUTE_DATE.matcher(query), dates);
		dates.sort(java.util.Comparator.comparingInt(AbsoluteDateToken::startIndex));
		if (dates.isEmpty()) {
			return null;
		}
		if (dates.size() >= 2) {
			AbsoluteDateToken start = dates.get(0);
			AbsoluteDateToken end = dates.get(1);
			String connector = query.substring(start.endIndex(), end.startIndex());
			if (ABSOLUTE_RANGE_CONNECTOR.matcher(connector).matches()) {
				if (end.endExclusive().isBefore(start.start())) {
					return null;
				}
				return new AbsoluteTimeRange(start.start(), end.endExclusive());
			}
		}
		AbsoluteDateToken first = dates.get(0);
		return new AbsoluteTimeRange(first.start(), first.endExclusive());
	}

	private AbsoluteTimeRange inheritedYearRange(Matcher matcher) {
		try {
			int startYear = Integer.parseInt(matcher.group(1));
			int startMonth = Integer.parseInt(matcher.group(2));
			String startDayText = matcher.group(3);
			String endYearText = matcher.group(4);
			int endYear = endYearText == null ? startYear : Integer.parseInt(endYearText);
			int endMonth = Integer.parseInt(matcher.group(5));
			String endDayText = matcher.group(6);
			LocalDate start = startDayText == null ? LocalDate.of(startYear, startMonth, 1)
					: LocalDate.of(startYear, startMonth, Integer.parseInt(startDayText));
			LocalDate endStart = endDayText == null ? LocalDate.of(endYear, endMonth, 1)
					: LocalDate.of(endYear, endMonth, Integer.parseInt(endDayText));
			LocalDate endExclusive = endDayText == null ? endStart.plusMonths(1) : endStart.plusDays(1);
			if (endExclusive.isBefore(start)) {
				return null;
			}
			return new AbsoluteTimeRange(start, endExclusive);
		}
		catch (DateTimeException | NumberFormatException ignored) {
			return null;
		}
	}

	private void collectAbsoluteDates(Matcher matcher, List<AbsoluteDateToken> dates) {
		while (matcher.find()) {
			try {
				int year = Integer.parseInt(matcher.group(1));
				int month = Integer.parseInt(matcher.group(2));
				String dayText = matcher.group(3);
				LocalDate start = dayText == null ? LocalDate.of(year, month, 1)
						: LocalDate.of(year, month, Integer.parseInt(dayText));
				LocalDate endExclusive = dayText == null ? start.plusMonths(1) : start.plusDays(1);
				dates.add(new AbsoluteDateToken(start, endExclusive, matcher.start(), matcher.end()));
			}
			catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed absolute date candidates and continue scanning the query.
			}
		}
	}

	private String relativeRange(String query) {
		Map<String, List<String>> ranges = new LinkedHashMap<>();
		ranges.put("CURRENT_DAY", List.of("今天", "今日", "today"));
		ranges.put("PREVIOUS_DAY", List.of("昨天", "昨日", "yesterday"));
		ranges.put("CURRENT_WEEK", List.of("本周", "this week"));
		ranges.put("PREVIOUS_WEEK", List.of("上周", "last week"));
		ranges.put("CURRENT_MONTH", List.of("本月", "这个月", "this month"));
		ranges.put("PREVIOUS_MONTH", List.of("上月", "上个月", "last month"));
		ranges.put("CURRENT_YEAR", List.of("今年", "本年", "this year"));
		ranges.put("PREVIOUS_YEAR", List.of("去年", "上一年", "last year"));
		for (Map.Entry<String, List<String>> entry : ranges.entrySet()) {
			if (entry.getValue().stream().anyMatch(query::contains)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private String granularity(String query) {
		if (List.of("按月", "每月", "monthly", "month over").stream().anyMatch(query::contains)) {
			return "MONTH";
		}
		if (List.of("按周", "每周", "weekly").stream().anyMatch(query::contains)) {
			return "WEEK";
		}
		if (List.of("按年", "每年", "yearly", "annual").stream().anyMatch(query::contains)) {
			return "YEAR";
		}
		return "DAY";
	}

	private boolean containsTimeRangeIntent(String query) {
		String userSemanticQuery = SYSTEM_CURRENT_TIME_CONTEXT.matcher(Objects.toString(query, "")).replaceAll(" ");
		return relativeRange(userSemanticQuery) != null || absoluteRange(userSemanticQuery) != null;
	}

	private int limit(String query) {
		Matcher matcher = LIMIT_PATTERN.matcher(query);
		if (matcher.find()) {
			return boundedLimit(matcher.group(1));
		}
		matcher = RANKED_ENTITY_COUNT_PATTERN.matcher(query);
		if (matcher.find()) {
			return boundedLimit(matcher.group(1));
		}
		return 100;
	}

	private int boundedLimit(String text) {
		int value = parseCount(text);
		return Math.max(1, Math.min(value, 10000));
	}

	private int parseCount(String text) {
		if (text == null || text.isBlank()) {
			return 100;
		}
		if (text.chars().allMatch(Character::isDigit)) {
			return Integer.parseInt(text);
		}
		Map<Character, Integer> digits = Map.of('一', 1, '二', 2, '两', 2, '三', 3, '四', 4, '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);
		if ("十".equals(text)) {
			return 10;
		}
		if (text.contains("十")) {
			String[] parts = text.split("十", -1);
			int tens = parts[0].isEmpty() ? 1 : digits.getOrDefault(parts[0].charAt(0), 0);
			int ones = parts.length < 2 || parts[1].isEmpty() ? 0 : digits.getOrDefault(parts[1].charAt(0), 0);
			return tens * 10 + ones;
		}
		if (text.length() == 1) {
			return digits.getOrDefault(text.charAt(0), 100);
		}
		return 100;
	}

	private boolean rowLevelRanking(String query) {
		return RANKED_ENTITY_COUNT_PATTERN.matcher(query).find();
	}

	private List<SemanticBlueprint.OrderSelection> order(String query,
			List<SemanticBlueprint.MetricSelection> metrics) {
		if (metrics.isEmpty()) {
			return List.of();
		}
		String direction = null;
		if (List.of("top", "最高", "最多", "最大", "排名", "前", "从高到低", "降序", "倒序").stream()
			.anyMatch(query::contains)) {
			direction = "DESC";
		}
		else if (List.of("最低", "最少", "最小", "bottom", "从低到高", "升序", "正序").stream()
			.anyMatch(query::contains)) {
			direction = "ASC";
		}
		if (direction == null) {
			return List.of();
		}
		SemanticBlueprint.MetricSelection sortMetric = metrics.get(0);
		int latestMention = -1;
		for (SemanticBlueprint.MetricSelection metric : metrics) {
			int mention = lastMetricMention(query, metric);
			if (mention > latestMention) {
				latestMention = mention;
				sortMetric = metric;
			}
		}
		return List.of(SemanticBlueprint.OrderSelection.builder()
			.expression(sortMetric.getMetricCode())
			.direction(direction)
			.nulls("LAST")
			.build());
	}

	private int lastMetricMention(String query, SemanticBlueprint.MetricSelection metric) {
		String businessName = Objects.toString(metric.getBusinessName(), "");
		String metricCode = Objects.toString(metric.getMetricCode(), "");
		int businessMention = StringUtils.hasText(businessName) ? query.lastIndexOf(businessName) : -1;
		int codeMention = StringUtils.hasText(metricCode) ? query.lastIndexOf(metricCode) : -1;
		return Math.max(businessMention, codeMention);
	}

	private String rowLevelMetricExpression(String expression) {
		if (!StringUtils.hasText(expression)) {
			return expression;
		}
		Matcher matcher = ROW_LEVEL_AGGREGATE_EXPRESSION.matcher(expression);
		return matcher.matches() ? matcher.group(1).trim() : expression;
	}

	private String metricExpression(SemanticBlueprint.MetricSelection metric) {
		String expression = firstText(metric.getExpression(), metric.getMetricCode());
		if (!StringUtils.hasText(metric.getAggregation()) || "NONE".equalsIgnoreCase(metric.getAggregation())) {
			return expression;
		}
		if (AGGREGATE_EXPRESSION.matcher(expression).find()) {
			return expression;
		}
		String aggregation = metric.getAggregation().toUpperCase(Locale.ROOT);
		if ("COUNT_DISTINCT".equals(aggregation)) {
			return "COUNT(DISTINCT " + expression + ")";
		}
		return aggregation + "(" + expression + ")";
	}

	static boolean bucketedTimeAxisDimension(QueryCaseHints.TimeBindingHint timeBinding,
			SemanticBlueprint.DimensionSelection dimension) {
		return timeBinding != null && StringUtils.hasText(timeBinding.groupGranularity())
				&& Objects.equals(timeBinding.modelCode(), dimension.getModelCode())
				&& Objects.equals(timeBinding.columnName(), dimension.getColumnName());
	}

	private boolean metricBackedScalarDimension(String query, SemanticBlueprint.DimensionSelection dimension,
			List<SemanticBlueprint.MetricSelection> metrics) {
		if (!StringUtils.hasText(dimension.getColumnName()) || explicitGroupingIntent(query, dimension)) {
			return false;
		}
		Pattern column = Pattern
			.compile("(?i)(?<![a-zA-Z0-9_$])" + Pattern.quote(dimension.getColumnName()) + "(?![a-zA-Z0-9_$])");
		return metrics.stream()
			.filter(metric -> Objects.equals(metric.getModelCode(), dimension.getModelCode()))
			.map(SemanticBlueprint.MetricSelection::getExpression)
			.filter(StringUtils::hasText)
			.anyMatch(expression -> column.matcher(expression).find());
	}

	private boolean explicitGroupingIntent(String query, SemanticBlueprint.DimensionSelection dimension) {
		for (String value : List.of(Objects.toString(dimension.getBusinessName(), ""),
				Objects.toString(dimension.getDimensionCode(), ""))) {
			String normalized = normalize(value);
			if (!normalized.isBlank() && (query.contains("按" + normalized) || query.contains("每" + normalized)
					|| query.contains("by " + normalized))) {
				return true;
			}
		}
		return false;
	}

	private String matchedEnumText(String query, SemanticCatalogSnapshot.EnumValue value) {
		List<String> candidates = new ArrayList<>();
		candidates.add(Objects.toString(value.getValueCode(), ""));
		candidates.add(Objects.toString(value.getBusinessName(), ""));
		if (StringUtils.hasText(value.getAliases())) {
			candidates.addAll(List.of(value.getAliases().split("[,，;；\\n]")));
		}
		for (String candidate : candidates) {
			String normalized = normalize(candidate);
			if (!normalized.isBlank() && query.contains(normalized)) {
				return normalized;
			}
		}
		return null;
	}

	private boolean metricOwnsEnumPhrase(String query, String matched,
			List<SemanticBlueprint.MetricSelection> metrics) {
		return metrics.stream().filter(metric -> StringUtils.hasText(metric.getFilterExpression())).anyMatch(metric -> {
			String businessName = normalize(metric.getBusinessName());
			String metricCode = normalize(metric.getMetricCode());
			return !businessName.isBlank() && businessName.contains(matched) && query.contains(businessName)
					|| !metricCode.isBlank() && metricCode.contains(matched) && query.contains(metricCode);
		});
	}

	private String firstText(String first, String second) {
		return StringUtils.hasText(first) ? first : second;
	}

	private String normalize(String value) {
		return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT)
			.trim()
			.replaceAll("\\s+", " ");
	}

	private String key(String first, String second) {
		return Objects.toString(first, "") + "::" + Objects.toString(second, "");
	}

	private record AbsoluteTimeRange(LocalDate startInclusive, LocalDate endExclusive) {
	}

	private record AbsoluteDateToken(LocalDate start, LocalDate endExclusive, int startIndex, int endIndex) {
	}

	public record IrDetails(List<SemanticBlueprint.ProjectionSelection> projections,
			List<SemanticBlueprint.DimensionSelection> dimensions, List<SemanticBlueprint.FilterSelection> filters,
			List<SemanticBlueprint.EnumResolution> enumResolutions, SemanticBlueprint.TimeRangeSelection timeRange,
			List<SemanticBlueprint.GroupSelection> groupBy, List<SemanticBlueprint.OrderSelection> orderBy, int limit,
			SemanticBlueprint.ExpectedResultShape expectedResult, String compilerMode, List<String> warnings,
			List<String> errors) {
	}

}
