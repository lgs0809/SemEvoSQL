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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Pure plan/hint/asset comparison rules used by semantic replay. */
@Component
class SemanticReplayPlanComparator {

	QueryCaseHints goldenHints(Map<String, Object> expected, String caseId) {
		return new QueryCaseHints(stringSet(expected.get("modelCodes")), stringSet(expected.get("metricCodes")),
				stringSet(expected.get("dimensionCodes")), stringSet(expected.get("grainCodes")),
				stringSet(expected.get("relationshipCodes")), stringSet(expected.get("ruleCodes")),
				expectedEnumBindings(expected.get("enumBindings"), caseId), text(expected.get("intentType")),
				List.of(caseId), 1, Map.of("golden", 1d));
	}

	List<String> compareGoldenPlan(Map<String, Object> expected, SemanticBlueprint plan) {
		List<String> errors = new ArrayList<>(plan.getValidationErrors());
		requireContained(errors, "model", new ArrayList<>(stringSet(expected.get("modelCodes"))),
				plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).collect(Collectors.toSet()));
		requireContained(errors, "metric", new ArrayList<>(stringSet(expected.get("metricCodes"))),
				plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).collect(Collectors.toSet()));
		requireContained(errors, "dimension", new ArrayList<>(stringSet(expected.get("dimensionCodes"))),
				plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "relationship", new ArrayList<>(stringSet(expected.get("relationshipCodes"))),
				plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()));
		for (EnumBindingHint binding : expectedEnumBindings(expected.get("enumBindings"), "golden")) {
			boolean found = plan.getEnumResolutions()
				.stream()
				.anyMatch(actual -> Objects.equals(binding.modelCode(), actual.getModelCode())
						&& Objects.equals(binding.columnName(), actual.getColumnName())
						&& Objects.equals(binding.valueCode(), actual.getValueCode()));
			if (!found) {
				errors.add("Missing enum binding: " + binding.modelCode() + ":" + binding.columnName() + ":"
						+ binding.valueCode());
			}
		}
		if (!plan.isExecutable()) {
			errors.add("Golden IR is not executable");
		}
		return List.copyOf(errors);
	}

	List<String> comparePlans(SemanticBlueprint source, SemanticBlueprint target) {
		List<String> errors = new ArrayList<>(target.getValidationErrors());
		requireContained(errors, "metric",
				source.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(),
				target.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).collect(Collectors.toSet()));
		requireContained(errors, "dimension",
				source.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList(),
				target.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()));
		requireContained(errors, "relationship",
				source.getRelationships().stream().map(SemanticBlueprint.RelationshipSelection::getRelationshipCode).toList(),
				target.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()));
		Map<String, Object> sourceComputation = computationShape(source);
		Map<String, Object> targetComputation = computationShape(target);
		if (!Objects.equals(sourceComputation, targetComputation)) {
			errors.add("Computation requirements changed: expected=" + sourceComputation + ", actual=" + targetComputation);
		}
		for (SemanticBlueprint.EnumResolution expected : source.getEnumResolutions()) {
			boolean found = target.getEnumResolutions()
				.stream()
				.anyMatch(actual -> Objects.equals(expected.getModelCode(), actual.getModelCode())
						&& Objects.equals(expected.getColumnName(), actual.getColumnName())
						&& Objects.equals(expected.getValueCode(), actual.getValueCode()));
			if (!found) {
				errors.add("Missing enum binding: " + expected.getModelCode() + ":" + expected.getColumnName() + ":"
						+ expected.getValueCode());
			}
		}
		return List.copyOf(errors);
	}

	void preserveComputationIntent(SemanticBlueprint source, SemanticBlueprint target) {
		if (source != null && target != null) {
			target.setComputationIntent(source.getComputationIntent());
		}
	}

	List<String> comparePlanningPolicyPlans(SemanticBlueprint source, SemanticBlueprint target) {
		List<String> errors = new ArrayList<>(target.getValidationErrors());
		Map<String, Object> expected = planningPolicyShape(source);
		Map<String, Object> actual = planningPolicyShape(target);
		for (Map.Entry<String, Object> entry : expected.entrySet()) {
			Object observed = actual.get(entry.getKey());
			if (!Objects.equals(entry.getValue(), observed)) {
				errors.add("Planning policy changed " + entry.getKey() + ": expected=" + entry.getValue() + ", actual="
						+ observed);
			}
		}
		return List.copyOf(errors);
	}

	Map<String, Object> planningPolicyShape(SemanticBlueprint plan) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("models", plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).sorted().toList());
		shape.put("metrics", plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).sorted().toList());
		shape.put("dimensions",
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).sorted().toList());
		shape.put("grains", plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).sorted().toList());
		shape.put("relationships",
				plan.getRelationships().stream().map(SemanticBlueprint.RelationshipSelection::getRelationshipCode).sorted().toList());
		shape.put("rules", plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).sorted().toList());
		shape.put("enumResolutions", plan.getEnumResolutions()
			.stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getValueCode(), ""))
			.sorted()
			.toList());
		shape.put("filters", plan.getFilters()
			.stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getOperator(), "")
					+ ":" + Objects.toString(value.getValue(), "") + ":" + Objects.toString(value.getValueType(), ""))
			.sorted()
			.toList());
		shape.put("groupBy", plan.getGroupBy()
			.stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getColumnName(), "")
					+ ":" + Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getAlias(), "")
					+ ":" + Objects.toString(value.getTimeBucketGranularity(), ""))
			.toList());
		shape.put("orderBy", plan.getOrderBy()
			.stream()
			.map(value -> Objects.toString(value.getExpression(), "") + ":" + Objects.toString(value.getDirection(), "")
					+ ":" + Objects.toString(value.getNulls(), ""))
			.toList());
		shape.put("projections", plan.getProjections()
			.stream()
			.map(value -> Objects.toString(value.getModelCode(), "") + ":" + Objects.toString(value.getExpression(), "")
					+ ":" + Objects.toString(value.getAlias(), "") + ":" + Objects.toString(value.getProjectionType(), ""))
			.toList());
		shape.put("timeRange", replayTimeRangeShape(plan.getTimeRange()));
		shape.put("expectedResult", expectedResultShape(plan.getExpectedResult()));
		shape.put("computation", computationShape(plan));
		shape.put("compilerMode", Objects.toString(plan.getCompilerMode(), ""));
		shape.put("limit", plan.getLimit());
		return Map.copyOf(shape);
	}

	QueryCaseHints hints(SemanticBlueprint plan, String caseId) {
		List<QueryCaseHints.FilterBindingHint> literalFilters = plan.getFilters()
			.stream()
			.filter(value -> "LITERAL".equalsIgnoreCase(value.getValueType()))
			.map(value -> new QueryCaseHints.FilterBindingHint("", value.getModelCode(), value.getColumnName(),
					value.getOperator(), value.getValue(), caseId, 1))
			.toList();
		QueryCaseHints.TimeBindingHint timeBinding = timeBinding(plan, caseId);
		return new QueryCaseHints(
				plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).collect(Collectors.toSet()),
				plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).collect(Collectors.toSet()),
				plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()),
				plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).collect(Collectors.toSet()),
				plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.collect(Collectors.toSet()),
				plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).collect(Collectors.toSet()),
				plan.getEnumResolutions()
					.stream()
					.map(value -> new EnumBindingHint(value.getInputText(), value.getModelCode(), value.getColumnName(),
							value.getValueCode(), caseId, 1))
					.toList(),
				literalFilters, List.of(), timeBinding, true, intent(plan), List.of(caseId), 1, Map.of());
	}

	List<String> missingAssets(SemanticCatalogSnapshot catalog, List<Map<String, Object>> refs) {
		Set<String> available = assetKeys(catalog);
		return refs.stream()
			.map(value -> text(value.get("asset_type")) + ":" + text(value.get("asset_key")))
			.filter(value -> !available.contains(value))
			.toList();
	}

	@SuppressWarnings("unchecked")
	List<String> missingExpected(SemanticCatalogSnapshot catalog, Map<String, Object> expected) {
		List<String> missing = new ArrayList<>();
		checkExpected((List<Object>) expected.getOrDefault("modelCodes", List.of()),
				catalog.getModels().stream().map(SemanticCatalogSnapshot.Model::getModelCode).collect(Collectors.toSet()),
				"MODEL", missing);
		checkExpected((List<Object>) expected.getOrDefault("metricCodes", List.of()),
				catalog.getMetrics().stream().map(SemanticCatalogSnapshot.Metric::getMetricCode).collect(Collectors.toSet()),
				"METRIC", missing);
		checkExpected((List<Object>) expected.getOrDefault("dimensionCodes", List.of()),
				catalog.getDimensions()
					.stream()
					.map(SemanticCatalogSnapshot.Dimension::getDimensionCode)
					.collect(Collectors.toSet()),
				"DIMENSION", missing);
		return missing;
	}

	private Map<String, Object> computationShape(SemanticBlueprint plan) {
		if (plan == null || plan.getComputationIntent() == null) {
			return Map.of("capabilities", List.of(), "requirements", List.of());
		}
		return Map.of("capabilities",
				plan.getComputationIntent().capabilities().stream().map(Enum::name).sorted().toList(), "requirements",
				plan.getComputationIntent().canonicalRequirements());
	}

	Map<String, Object> planShape(SemanticBlueprint plan) {
		return Map.of("metrics", plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(),
				"dimensions", plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.toList(),
				"relationships", plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList(),
				"compilerMode", plan.getCompilerMode(), "executable", plan.isExecutable());
	}

	private Set<String> stringSet(Object value) {
		if (!(value instanceof List<?> values)) {
			return Set.of();
		}
		return values.stream()
			.map(item -> Objects.toString(item, "").trim())
			.filter(StringUtils::hasText)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private List<EnumBindingHint> expectedEnumBindings(Object value, String caseId) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		List<EnumBindingHint> result = new ArrayList<>();
		for (Object item : values) {
			if (!(item instanceof Map<?, ?> binding)) {
				continue;
			}
			result.add(new EnumBindingHint(text(binding.get("rawText")), text(binding.get("modelCode")),
					text(binding.get("columnName")), text(binding.get("valueCode")), caseId, 1));
		}
		return List.copyOf(result);
	}

	private Map<String, Object> replayTimeRangeShape(SemanticBlueprint.TimeRangeSelection value) {
		if (value == null) {
			return Map.of();
		}
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("modelCode", Objects.toString(value.getModelCode(), ""));
		shape.put("timeColumn", Objects.toString(value.getTimeColumn(), ""));
		shape.put("relativeExpression", Objects.toString(value.getRelativeExpression(), ""));
		shape.put("granularity", Objects.toString(value.getGranularity(), ""));
		shape.put("timeZone", Objects.toString(value.getTimeZone(), ""));
		if (!StringUtils.hasText(value.getRelativeExpression())) {
			shape.put("startInclusive", Objects.toString(value.getStartInclusive(), ""));
			shape.put("endExclusive", Objects.toString(value.getEndExclusive(), ""));
		}
		return Map.copyOf(shape);
	}

	private Map<String, Object> expectedResultShape(SemanticBlueprint.ExpectedResultShape value) {
		if (value == null) {
			return Map.of();
		}
		return Map.of("columns", List.copyOf(value.getColumns() == null ? List.of() : value.getColumns()), "grain",
				Objects.toString(value.getGrain(), ""), "tabular", Objects.toString(value.getTabular(), ""), "chartable",
				Objects.toString(value.getChartable(), ""));
	}

	private void requireContained(List<String> errors, String type, List<String> required, Set<String> actual) {
		required.stream()
			.filter(value -> !actual.contains(value))
			.forEach(value -> errors.add("Missing " + type + ": " + value));
	}

	private QueryCaseHints.TimeBindingHint timeBinding(SemanticBlueprint plan, String caseId) {
		SemanticBlueprint.GroupSelection timeGroup = plan.getGroupBy()
			.stream()
			.filter(group -> StringUtils.hasText(group.getTimeBucketGranularity()))
			.findFirst()
			.orElse(null);
		SemanticBlueprint.TimeRangeSelection timeRange = plan.getTimeRange();
		if (timeRange == null && timeGroup == null) {
			return null;
		}
		String modelCode = timeRange == null ? timeGroup.getModelCode() : timeRange.getModelCode();
		String columnName = timeRange == null ? timeGroup.getColumnName() : timeRange.getTimeColumn();
		String granularity = timeGroup == null ? null : timeGroup.getTimeBucketGranularity();
		return new QueryCaseHints.TimeBindingHint("", modelCode, columnName, caseId, 1, granularity);
	}

	private String intent(SemanticBlueprint plan) {
		if (plan.getSourceSubPlans().size() > 1) {
			return "MULTI_SOURCE_ANALYTICS";
		}
		if (!plan.getMetrics().isEmpty() && !plan.getDimensions().isEmpty()) {
			return "GROUPED_AGGREGATION";
		}
		return plan.getMetrics().isEmpty() ? "ENTITY_LOOKUP" : "AGGREGATION";
	}

	private void checkExpected(List<Object> expected, Set<String> actual, String type, List<String> missing) {
		expected.stream()
			.map(String::valueOf)
			.filter(value -> !actual.contains(value))
			.forEach(value -> missing.add(type + ":" + value));
	}

	private Set<String> assetKeys(SemanticCatalogSnapshot catalog) {
		Set<String> values = new LinkedHashSet<>();
		catalog.getModels().forEach(item -> values.add("MODEL:" + item.getModelCode()));
		catalog.getMetrics().forEach(item -> values.add("METRIC:" + item.getMetricCode()));
		catalog.getDimensions().forEach(item -> values.add("DIMENSION:" + item.getDimensionCode()));
		catalog.getRelationships().forEach(item -> values.add("RELATIONSHIP:" + item.getRelationshipCode()));
		catalog.getRules().forEach(item -> values.add("RULE:" + item.getRuleCode()));
		catalog.getGrains().forEach(item -> values.add("GRAIN:" + item.getModelCode() + ":" + item.getGrainCode()));
		catalog.getEnumValues()
			.forEach(item -> values
				.add("ENUM_VALUE:" + item.getModelCode() + ":" + item.getColumnName() + ":" + item.getValueCode()));
		return Set.copyOf(values);
	}

	private String text(Object value) {
		return Objects.toString(value, "").trim();
	}

}
