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

import cn.lgs.semevosql.semantic.application.ScenarioResolutionService.BindingCandidate;
import cn.lgs.semevosql.semantic.application.ScenarioResolutionService.ResolvedBinding;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Pure candidate discovery/ranking for deterministic scenario binding. */
@Component
class ScenarioBindingMatcher {

	List<BindingCandidate> metricCandidates(String term, SemanticCatalogSnapshot catalog,
			List<ProjectEvidence> evidence) {
		List<BindingCandidate> values = new ArrayList<>(catalog.getMetrics()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.map(asset -> candidate("METRIC", asset.getMetricCode(), asset.getBusinessName(), asset.getModelCode(),
					asset.getDescription(), asset.getEvidence(), term, evidence))
			.filter(candidate -> candidate.score() >= 80)
			.toList());
		values.addAll(entityCountMetricCandidates(term, catalog));
		return bestCandidates(values);
	}

	List<BindingCandidate> dimensionCandidates(String term, SemanticCatalogSnapshot catalog,
			List<ProjectEvidence> evidence) {
		return bestCandidates(catalog.getDimensions()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.map(asset -> candidate("DIMENSION", asset.getDimensionCode(), asset.getBusinessName(), asset.getModelCode(),
					joinText(asset.getDescription(), asset.getHierarchy()), asset.getEvidence(), term, evidence))
			.filter(candidate -> candidate.score() >= 80)
			.toList());
	}

	List<BindingCandidate> filterCandidates(String term, SemanticCatalogSnapshot catalog,
			List<ProjectEvidence> evidence) {
		List<BindingCandidate> values = new ArrayList<>();
		catalog.getRules()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.map(asset -> candidate("RULE", asset.getRuleCode(), asset.getBusinessName(), asset.getModelCode(),
					asset.getDescription(), asset.getEvidence(), term, evidence))
			.filter(candidate -> candidate.score() >= 80)
			.forEach(values::add);
		catalog.getEnumValues()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.map(asset -> candidate("ENUM_VALUE", enumKey(asset), asset.getBusinessName(), asset.getModelCode(),
					joinText(asset.getAliases(), asset.getDescription()), asset.getEvidence(), term, evidence))
			.filter(candidate -> candidate.score() >= 80)
			.forEach(values::add);
		catalog.getDimensions()
			.stream()
			.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(asset -> catalog.getColumns()
				.stream()
				.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED
						&& asset.getModelCode().equals(column.getModelCode())
						&& asset.getColumnName().equals(column.getColumnName()) && Boolean.TRUE.equals(column.getAllowFilter())))
			.map(asset -> candidate("DIMENSION", asset.getDimensionCode(), asset.getBusinessName(), asset.getModelCode(),
					joinText(asset.getDescription(), asset.getHierarchy()), asset.getEvidence(), term, evidence))
			.filter(candidate -> candidate.score() >= 80)
			.forEach(values::add);
		return bestCandidates(values);
	}

	List<BindingCandidate> timeCandidates(SemanticCatalogSnapshot catalog, List<ResolvedBinding> resolved,
			Set<String> requiredModels, List<ProjectEvidence> evidence, String requirementText) {
		Set<String> metricTimeColumns = resolved.stream()
			.filter(binding -> "METRIC".equals(binding.assetType()))
			.map(binding -> catalog.getMetrics()
				.stream()
				.filter(metric -> binding.assetKey().equals(metric.getMetricCode()))
				.findFirst()
				.map(SemanticCatalogSnapshot.Metric::getTimeColumn)
				.orElse(null))
			.filter(ScenarioBindingMatcher::hasText)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (metricTimeColumns.size() == 1) {
			String columnName = metricTimeColumns.iterator().next();
			return catalog.getColumns()
				.stream()
				.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(column -> columnName.equals(column.getColumnName()))
				.filter(column -> requiredModels.isEmpty() || requiredModels.contains(column.getModelCode()))
				.map(column -> timeColumnCandidate(column, evidence, 100))
				.limit(1)
				.toList();
		}
		List<SemanticCatalogSnapshot.Column> timeColumns = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> column.getRole() == SemanticColumnRole.TIME)
			.filter(column -> requiredModels.isEmpty() || requiredModels.contains(column.getModelCode()))
			.toList();
		List<BindingCandidate> explicitMatches = timeColumns.stream()
			.map(column -> timeColumnCandidate(column, evidence,
					matchScore(requirementText, column.getBusinessName(), column.getColumnName(), column.getDescription())))
			.filter(candidate -> candidate.score() >= 80)
			.toList();
		if (!explicitMatches.isEmpty()) {
			return bestCandidates(explicitMatches);
		}
		return bestCandidates(timeColumns.stream().map(column -> timeColumnCandidate(column, evidence, 90)).toList());
	}

	List<BindingCandidate> bestCandidates(List<BindingCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		Map<String, BindingCandidate> unique = new LinkedHashMap<>();
		for (BindingCandidate candidate : candidates) {
			String key = candidate.assetType() + ":" + candidate.assetKey();
			BindingCandidate current = unique.get(key);
			if (current == null || candidate.score() > current.score()) {
				unique.put(key, candidate);
			}
		}
		int best = unique.values().stream().mapToInt(BindingCandidate::score).max().orElse(0);
		return unique.values()
			.stream()
			.filter(candidate -> candidate.score() == best)
			.sorted(Comparator.comparing(BindingCandidate::optionLabel).thenComparing(BindingCandidate::assetKey))
			.toList();
	}

	int matchScore(String term, String... values) {
		String normalizedTerm = normalize(term);
		if (!hasText(normalizedTerm)) {
			return 0;
		}
		int score = 0;
		for (String value : values) {
			for (String candidate : split(value)) {
				String normalized = normalize(candidate);
				if (!hasText(normalized)) {
					continue;
				}
				if (normalizedTerm.equals(normalized)) {
					score = Math.max(score, 100);
				}
				else if (normalized.length() >= 2
						&& (normalizedTerm.contains(normalized) || normalized.contains(normalizedTerm))) {
					score = Math.max(score, 80);
				}
			}
		}
		return score;
	}

	private List<BindingCandidate> entityCountMetricCandidates(String term, SemanticCatalogSnapshot catalog) {
		Set<String> matchedModels = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> entityTermMatches(term, model))
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (matchedModels.size() != 1) {
			return List.of();
		}
		String modelCode = matchedModels.iterator().next();
		Set<String> grainKeys = catalog.getGrains()
			.stream()
			.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(grain -> modelCode.equals(grain.getModelCode()))
			.map(SemanticCatalogSnapshot.Grain::getKeyColumns)
			.filter(ScenarioBindingMatcher::hasText)
			.flatMap(value -> Arrays.stream(value.split(",")))
			.map(String::trim)
			.filter(ScenarioBindingMatcher::hasText)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return catalog.getMetrics()
			.stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> modelCode.equals(metric.getModelCode()))
			.filter(metric -> Set.of("COUNT", "COUNT_DISTINCT")
				.contains(Objects.toString(metric.getAggregation(), "").toUpperCase(Locale.ROOT)))
			.filter(metric -> grainKeys.contains(metric.getExpression()))
			.map(metric -> new BindingCandidate("METRIC", metric.getMetricCode(),
					firstText(metric.getBusinessName(), metric.getMetricCode()), metric.getModelCode(),
					optionLabel(firstText(metric.getBusinessName(), metric.getMetricCode()), metric.getDescription(),
							metric.getModelCode()),
					95, "entity name deterministically maps to the governed primary-grain count metric"))
			.toList();
	}

	private boolean entityTermMatches(String term, SemanticCatalogSnapshot.Model model) {
		String normalizedTerm = singularEnglish(normalize(term));
		if (!hasText(normalizedTerm)) {
			return false;
		}
		return Stream.of(model.getBusinessName(), model.getModelCode(), model.getPhysicalTable())
			.map(ScenarioBindingMatcher::normalize)
			.map(ScenarioBindingMatcher::singularEnglish)
			.filter(ScenarioBindingMatcher::hasText)
			.anyMatch(value -> value.equals(normalizedTerm) || value.endsWith(normalizedTerm));
	}

	private BindingCandidate timeColumnCandidate(SemanticCatalogSnapshot.Column column, List<ProjectEvidence> evidence,
			int score) {
		String business = firstText(column.getBusinessName(), column.getColumnName());
		String key = column.getModelCode() + ":" + column.getColumnName();
		return new BindingCandidate("TIME_COLUMN", key, business, column.getModelCode(),
				optionLabel(business, column.getDescription(), column.getModelCode()), score,
				evidenceSummary("COLUMN", key, evidence));
	}

	private BindingCandidate candidate(String assetType, String assetKey, String businessName, String modelCode,
			String description, String directEvidence, String term, List<ProjectEvidence> evidence) {
		int score = matchScore(term, assetKey, businessName, description, directEvidence);
		String evidenceSummary = evidenceSummary(assetType, assetKey, evidence);
		if (score < 90 && hasText(evidenceSummary) && containsNormalized(evidenceSummary, term)) {
			score = Math.max(score, 85);
		}
		String business = firstText(businessName, assetKey);
		return new BindingCandidate(assetType, assetKey, business, modelCode,
				optionLabel(business, description, modelCode), score, evidenceSummary);
	}

	private String evidenceSummary(String assetType, String assetKey, List<ProjectEvidence> evidence) {
		return evidence.stream()
			.filter(value -> typeMatches(assetType, value.getEvidenceType().name()))
			.filter(value -> Objects.equals(assetKey, value.getSubjectKey()))
			.map(value -> Objects.toString(value.getSourceLocation(), "") + " "
					+ Objects.toString(value.getPayloadJson(), ""))
			.filter(ScenarioBindingMatcher::hasText)
			.limit(3)
			.collect(Collectors.joining(" | "));
	}

	private boolean typeMatches(String assetType, String evidenceType) {
		return "TIME_COLUMN".equals(assetType) ? "COLUMN".equals(evidenceType) : assetType.equals(evidenceType);
	}

	private static String enumKey(SemanticCatalogSnapshot.EnumValue asset) {
		return asset.getModelCode() + ":" + asset.getColumnName() + ":" + asset.getValueCode();
	}

	private static String optionLabel(String businessName, String description, String modelCode) {
		String qualifier = firstText(description, modelCode);
		if (!hasText(qualifier) || normalize(qualifier).equals(normalize(businessName))) {
			return businessName;
		}
		String compact = qualifier.length() > 40 ? qualifier.substring(0, 40) + "…" : qualifier;
		return businessName + "（" + compact + "）";
	}

	private static List<String> split(String value) {
		return hasText(value) ? Arrays.stream(value.split("[,，;；|\\n]")).map(String::trim).filter(ScenarioBindingMatcher::hasText).toList()
				: List.of();
	}

	private static boolean containsNormalized(String value, String term) {
		String left = normalize(value);
		String right = normalize(term);
		return hasText(left) && hasText(right) && left.contains(right);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private static String singularEnglish(String value) {
		if (value != null && value.matches("[a-z0-9_]*[a-z]s") && value.length() > 3 && !value.endsWith("ss")) {
			return value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static String joinText(String... values) {
		return Arrays.stream(values).filter(ScenarioBindingMatcher::hasText).collect(Collectors.joining(" | "));
	}

	private static String firstText(String... values) {
		return Arrays.stream(values).filter(ScenarioBindingMatcher::hasText).map(String::trim).findFirst().orElse("");
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
