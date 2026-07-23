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
package cn.lgs.semevosql.onboarding;

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic Catalog-derived suggestions used by Grill-Me onboarding questions. */
final class OnboardingCatalogAdvisor {

	private OnboardingCatalogAdvisor() {
	}

	static boolean hasBusinessName(SemanticCatalogSnapshot.Model model) {
		return text(model.getBusinessName()) && !model.getBusinessName().equalsIgnoreCase(model.getModelCode());
	}

	static boolean numericColumn(SemanticCatalogSnapshot.Column column) {
		String type = Objects.toString(column.getDataType(), "").toLowerCase(Locale.ROOT);
		return type.contains("int") || type.contains("decimal") || type.contains("number") || type.contains("double")
				|| type.contains("float");
	}

	static boolean enumCandidate(SemanticCatalogSnapshot.Column column) {
		String name = Objects.toString(column.getColumnName(), "").toLowerCase(Locale.ROOT);
		return name.contains("status") || name.contains("state") || name.contains("type") || name.contains("code");
	}

	static boolean logicalDeleteCandidate(SemanticCatalogSnapshot.Column column) {
		String name = Objects.toString(column.getColumnName(), "").toLowerCase(Locale.ROOT);
		return name.equals("deleted") || name.contains("is_deleted") || name.contains("delete_flag")
				|| name.contains("del_flag");
	}

	static boolean testDataCandidate(SemanticCatalogSnapshot.Column column) {
		String name = Objects.toString(column.getColumnName(), "").toLowerCase(Locale.ROOT);
		return name.contains("is_test") || name.contains("test_flag") || name.contains("environment");
	}

	static String modelRecommendations(SemanticCatalogSnapshot catalog) {
		return json(catalog.getModels()
			.stream()
			.map(model -> Map.of("modelCode", model.getModelCode(), "businessName",
					Objects.toString(model.getBusinessName(), model.getModelCode()), "physicalTable",
					model.getPhysicalTable()))
			.toList());
	}

	static String grainRecommendation(SemanticCatalogSnapshot catalog) {
		SemanticCatalogSnapshot.Model model = catalog.getModels().stream().findFirst().orElse(null);
		if (model == null) {
			return null;
		}
		List<String> keys = catalog.getColumns()
			.stream()
			.filter(column -> model.getModelCode().equals(column.getModelCode()))
			.filter(column -> {
				String name = column.getColumnName().toLowerCase(Locale.ROOT);
				return name.equals("id") || name.endsWith("_id");
			})
			.limit(3)
			.map(SemanticCatalogSnapshot.Column::getColumnName)
			.toList();
		return json(Map.of("modelCode", model.getModelCode(), "grainCode", model.getModelCode() + "_grain",
				"keyColumns", keys, "description", "一行代表一个" + Objects.toString(model.getBusinessName(), model.getModelCode())));
	}

	static String timeRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getColumns()
			.stream()
			.filter(column -> column.getRole() == SemanticColumnRole.TIME)
			.findFirst()
			.map(column -> json(Map.of("modelCode", column.getModelCode(), "timeColumn", column.getColumnName())))
			.orElse(null);
	}

	static String metricRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getMetrics().isEmpty() ? null
				: json(catalog.getMetrics()
					.stream()
					.limit(5)
					.map(metric -> Map.of("modelCode", metric.getModelCode(), "metricCode", metric.getMetricCode(),
							"businessName", Objects.toString(metric.getBusinessName(), metric.getMetricCode()), "expression",
							Objects.toString(metric.getExpression(), "")))
					.toList());
	}

	static String dimensionRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getDimensions().isEmpty() ? null
				: json(catalog.getDimensions()
					.stream()
					.limit(10)
					.map(dimension -> Map.of("modelCode", dimension.getModelCode(), "dimensionCode",
							dimension.getDimensionCode(), "businessName",
							Objects.toString(dimension.getBusinessName(), dimension.getDimensionCode()), "columnName",
							Objects.toString(dimension.getColumnName(), "")))
					.toList());
	}

	static String enumRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getEnumValues().isEmpty() ? null
				: json(catalog.getEnumValues()
					.stream()
					.limit(20)
					.map(value -> Map.of("modelCode", value.getModelCode(), "columnName", value.getColumnName(),
							"valueCode", value.getValueCode(), "businessName",
							Objects.toString(value.getBusinessName(), value.getValueCode())))
					.toList());
	}

	static String relationshipRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getRelationships().isEmpty() ? null
				: json(catalog.getRelationships()
					.stream()
					.limit(10)
					.map(value -> Map.of("relationshipCode", value.getRelationshipCode(), "sourceModelCode",
							value.getSourceModelCode(), "targetModelCode", value.getTargetModelCode(), "joinType",
							Objects.toString(value.getJoinType(), "LEFT"), "joinCondition",
							Objects.toString(value.getJoinCondition(), "")))
					.toList());
	}

	static String logicalDeleteRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getColumns()
			.stream()
			.filter(OnboardingCatalogAdvisor::logicalDeleteCandidate)
			.findFirst()
			.map(column -> json(Map.of("modelCode", column.getModelCode(), "ruleCode", "logical_delete", "expression",
					column.getColumnName() + " = 0")))
			.orElse(null);
	}

	static String testDataRecommendation(SemanticCatalogSnapshot catalog) {
		return catalog.getColumns()
			.stream()
			.filter(OnboardingCatalogAdvisor::testDataCandidate)
			.findFirst()
			.map(column -> json(Map.of("modelCode", column.getModelCode(), "ruleCode", "exclude_test_data",
					"expression", column.getColumnName() + " = 0")))
			.orElse(null);
	}

	static String grainEvidence(SemanticCatalogSnapshot catalog) {
		return catalog.getGrains()
			.stream()
			.map(grain -> grain.getModelCode() + ":" + grain.getKeyColumns())
			.collect(Collectors.joining(", "));
	}

	static String metricEvidence(SemanticCatalogSnapshot catalog) {
		return catalog.getMetrics()
			.stream()
			.map(metric -> metric.getMetricCode() + "=" + metric.getExpression())
			.collect(Collectors.joining(", "));
	}

	static String relationshipEvidence(SemanticCatalogSnapshot catalog) {
		return catalog.getRelationships()
			.stream()
			.map(value -> value.getRelationshipCode() + ":" + value.getJoinCondition() + ":" + value.getCardinality())
			.collect(Collectors.joining(", "));
	}

	static String rulesEvidence(SemanticCatalogSnapshot catalog) {
		return catalog.getRules()
			.stream()
			.map(value -> value.getRuleCode() + "=" + value.getExpression())
			.collect(Collectors.joining(", "));
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize onboarding recommendation", ex);
		}
	}

}
