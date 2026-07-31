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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.clarification.ProjectSemanticAliasService.ProjectSemanticAlias;
import cn.lgs.semevosql.clarification.UserSemanticPreferenceService.UserSemanticPreference;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.AssetBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves PROJECT aliases and USER exact preferences into governed planner hints. */
@Service
public class RuntimeSemanticBindingService {

	private final UserSemanticPreferenceService preferenceService;

	private final ProjectSemanticAliasService projectAliasService;

	private final SemanticCatalogCache catalogCache;

	private final RunExecutionFenceService executionFence;

	public RuntimeSemanticBindingService(UserSemanticPreferenceService preferenceService,
			ProjectSemanticAliasService projectAliasService, SemanticCatalogCache catalogCache,
			RunExecutionFenceService executionFence) {
		this.preferenceService = preferenceService;
		this.projectAliasService = projectAliasService;
		this.catalogCache = catalogCache;
		this.executionFence = executionFence;
	}

	public BindingContext resolve(Long projectId, Long projectVersionId, String userId, String query) {
		SemanticCatalogSnapshot catalog = catalogCache.get(projectId, projectVersionId);
		Map<String, ResolvedRuntimeBinding> byPhrase = new LinkedHashMap<>();
		for (ProjectSemanticAlias alias : projectAliasService.applicable(projectId, projectVersionId, query)) {
			ResolvedRuntimeBinding binding = validate(catalog, alias.normalizedPhrase(), alias.displayPhrase(),
					alias.assetType(), alias.assetKey(), alias.businessLabel(), "PROJECT", alias.id(), null);
			if (binding != null && !shadowedByMoreSpecificExplicitAsset(catalog, query, binding)) {
				byPhrase.put(alias.normalizedPhrase(), binding);
			}
		}
		if (hasText(userId) && !RuntimePrincipalResolver.ANONYMOUS.equals(userId)) {
			for (UserSemanticPreference preference : preferenceService.applicable(projectId, userId, query)) {
				ResolvedRuntimeBinding binding = validate(catalog, preference.normalizedPhrase(),
						preference.displayPhrase(), preference.assetType(), preference.assetKey(),
						preference.businessLabel(), "USER", preference.id(), userId);
				if (binding != null && !shadowedByMoreSpecificExplicitAsset(catalog, query, binding)) {
					// Personal language intentionally overrides the project alias for
					// this user's same phrase, unless the current query explicitly uses a
					// longer published business term that contains that phrase.
					byPhrase.put(preference.normalizedPhrase(), binding);
				}
			}
		}
		List<ResolvedRuntimeBinding> bindings = List.copyOf(byPhrase.values());
		return new BindingContext(bindings, hints(bindings, false), physicalTables(catalog, bindings));
	}

	public BindingContext explicit(Long projectId, Long projectVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel) {
		return explicit(projectId, projectVersionId, rawPhrase, assetType, assetKey, businessLabel, "QUERY", null, null);
	}

	public BindingContext explicit(Long projectId, Long projectVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel, String source, Long sourceRecordId, String principalId) {
		SemanticCatalogSnapshot catalog = catalogCache.get(projectId, projectVersionId);
		String normalizedPhrase = UserSemanticPreferenceService.normalizePhrase(rawPhrase);
		ResolvedRuntimeBinding binding = validate(catalog, normalizedPhrase, rawPhrase, assetType, assetKey,
				businessLabel, source, sourceRecordId, principalId);
		if (binding == null) {
			throw new IllegalArgumentException(
					"Correction target is not an enabled semantic asset: " + assetType + ":" + assetKey);
		}
		List<ResolvedRuntimeBinding> bindings = List.of(binding);
		return new BindingContext(bindings, hints(bindings, true), physicalTables(catalog, bindings));
	}

	public BindingContext merge(List<BindingContext> contexts) {
		List<BindingContext> nonEmpty = contexts == null ? List.of()
				: contexts.stream().filter(Objects::nonNull).filter(context -> !context.empty()).toList();
		if (nonEmpty.isEmpty()) {
			return new BindingContext(List.of(), QueryCaseHints.empty(), List.of());
		}
		List<ResolvedRuntimeBinding> bindings = nonEmpty.stream()
			.flatMap(context -> context.bindings().stream())
			.toList();
		Set<String> models = new LinkedHashSet<>();
		Set<String> metrics = new LinkedHashSet<>();
		Set<String> dimensions = new LinkedHashSet<>();
		Set<String> grains = new LinkedHashSet<>();
		Set<String> relationships = new LinkedHashSet<>();
		Set<String> rules = new LinkedHashSet<>();
		List<EnumBindingHint> enums = new ArrayList<>();
		List<AssetBindingHint> assets = new ArrayList<>();
		TimeBindingHint time = null;
		boolean strict = false;
		Set<String> sourceIds = new LinkedHashSet<>();
		Map<String, Double> scores = new LinkedHashMap<>();
		Set<String> tables = new LinkedHashSet<>();
		for (BindingContext context : nonEmpty) {
			QueryCaseHints hint = context.hints();
			models.addAll(hint.modelCodes());
			metrics.addAll(hint.metricCodes());
			dimensions.addAll(hint.dimensionCodes());
			grains.addAll(hint.grainCodes());
			relationships.addAll(hint.relationshipCodes());
			rules.addAll(hint.ruleCodes());
			enums.addAll(hint.enumBindings());
			assets.addAll(hint.assetBindings());
			if (hint.timeBinding() != null) {
				time = hint.timeBinding();
			}
			strict = strict || hint.strictAssetBinding();
			sourceIds.addAll(hint.sourceExampleIds());
			scores.putAll(hint.componentScores());
			tables.addAll(context.additionalPhysicalTables());
		}
		QueryCaseHints merged = new QueryCaseHints(models, metrics, dimensions, grains, relationships, rules, enums,
				assets, time, strict, "RUNTIME_SEMANTIC_BINDING", List.copyOf(sourceIds), 1, scores);
		return new BindingContext(bindings, merged, List.copyOf(tables));
	}

	public void recordAppliedBindings(BindingContext context, String runId) {
		recordAppliedBindings(context, runId, null);
	}

	@Transactional
	public void recordAppliedBindings(BindingContext context, String runId, String attemptId) {
		if (context == null || !hasText(runId)) {
			return;
		}
		if (hasText(attemptId)) {
			executionFence.assertActiveAndLock(runId, attemptId);
		}
		for (ResolvedRuntimeBinding binding : context.bindings()) {
			if ("USER".equals(binding.source()) && binding.sourceRecordId() != null) {
				preferenceService.recordApplied(binding.sourceRecordId(), runId);
			}
		}
	}

	private QueryCaseHints hints(List<ResolvedRuntimeBinding> bindings, boolean strictAssetBinding) {
		Set<String> models = new LinkedHashSet<>();
		Set<String> metrics = new LinkedHashSet<>();
		Set<String> dimensions = new LinkedHashSet<>();
		List<AssetBindingHint> assetBindings = new ArrayList<>();
		List<EnumBindingHint> enums = new ArrayList<>();
		TimeBindingHint timeBinding = null;
		for (ResolvedRuntimeBinding binding : bindings) {
			if (hasText(binding.modelCode())) {
				models.add(binding.modelCode());
			}
			switch (binding.assetType()) {
				case "METRIC" -> {
					metrics.add(binding.assetKey());
					assetBindings.add(new AssetBindingHint(binding.displayPhrase(), "METRIC", binding.assetKey(),
							binding.modelCode(), binding.source(), 1));
				}
				case "DIMENSION" -> {
					dimensions.add(binding.assetKey());
					assetBindings.add(new AssetBindingHint(binding.displayPhrase(), "DIMENSION", binding.assetKey(),
							binding.modelCode(), binding.source(), 1));
				}
				case "ENUM_VALUE" -> {
					String[] parts = binding.assetKey().split(":", 3);
					if (parts.length == 3) {
						enums.add(new EnumBindingHint(binding.displayPhrase(), parts[0], parts[1], parts[2],
								binding.source(), 1));
					}
				}
				case "TIME_COLUMN" -> {
					String[] parts = binding.assetKey().split(":", 2);
					if (parts.length == 2) {
						timeBinding = new TimeBindingHint(binding.displayPhrase(), parts[0], parts[1], binding.source(),
								1);
					}
				}
				default -> {
				}
			}
		}
		return new QueryCaseHints(Set.copyOf(models), Set.copyOf(metrics), Set.copyOf(dimensions), Set.of(), Set.of(),
				Set.of(), List.copyOf(enums), List.copyOf(assetBindings), timeBinding, strictAssetBinding,
				"RUNTIME_SEMANTIC_BINDING", List.of(), 1, Map.of());
	}

	private List<String> physicalTables(SemanticCatalogSnapshot catalog, List<ResolvedRuntimeBinding> bindings) {
		Set<String> modelCodes = bindings.stream()
			.map(ResolvedRuntimeBinding::modelCode)
			.filter(RuntimeSemanticBindingService::hasText)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> modelCodes.contains(model.getModelCode()))
			.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
			.filter(RuntimeSemanticBindingService::hasText)
			.distinct()
			.toList();
	}

	private ResolvedRuntimeBinding validate(SemanticCatalogSnapshot catalog, String normalizedPhrase,
			String displayPhrase, String assetType, String assetKey, String businessLabel, String source,
			Long sourceRecordId, String principalId) {
		if (!hasText(assetType) || !hasText(assetKey)) {
			return null;
		}
		return switch (assetType) {
			case "METRIC" -> catalog.getMetrics()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> Objects.equals(value.getMetricCode(), assetKey))
				.findFirst()
				.map(value -> new ResolvedRuntimeBinding(normalizedPhrase, displayPhrase, assetType, assetKey,
						businessLabel, value.getModelCode(), source, sourceRecordId, principalId))
				.orElse(null);
			case "DIMENSION" -> catalog.getDimensions()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> Objects.equals(value.getDimensionCode(), assetKey))
				.findFirst()
				.map(value -> new ResolvedRuntimeBinding(normalizedPhrase, displayPhrase, assetType, assetKey,
						businessLabel, value.getModelCode(), source, sourceRecordId, principalId))
				.orElse(null);
			case "ENUM_VALUE" -> enumBinding(catalog, normalizedPhrase, displayPhrase, assetKey, businessLabel, source,
					sourceRecordId, principalId);
			case "TIME_COLUMN" -> timeBinding(catalog, normalizedPhrase, displayPhrase, assetKey, businessLabel, source,
					sourceRecordId, principalId);
			default -> null;
		};
	}

	private ResolvedRuntimeBinding timeBinding(SemanticCatalogSnapshot catalog, String normalizedPhrase,
			String displayPhrase, String assetKey, String businessLabel, String source, Long sourceRecordId,
			String principalId) {
		String[] parts = assetKey.split(":", 2);
		if (parts.length != 2) {
			return null;
		}
		return catalog.getColumns()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> Objects.equals(value.getModelCode(), parts[0])
					&& Objects.equals(value.getColumnName(), parts[1]))
			.filter(value -> value
				.getRole() == cn.lgs.semevosql.semantic.domain.SemanticColumnRole.TIME)
			.findFirst()
			.map(value -> new ResolvedRuntimeBinding(normalizedPhrase, displayPhrase, "TIME_COLUMN", assetKey,
					businessLabel, parts[0], source, sourceRecordId, principalId))
			.orElse(null);
	}

	private ResolvedRuntimeBinding enumBinding(SemanticCatalogSnapshot catalog, String normalizedPhrase,
			String displayPhrase, String assetKey, String businessLabel, String source, Long sourceRecordId,
			String principalId) {
		String[] parts = assetKey.split(":", 3);
		if (parts.length != 3) {
			return null;
		}
		return catalog.getEnumValues()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> Objects.equals(value.getModelCode(), parts[0])
					&& Objects.equals(value.getColumnName(), parts[1])
					&& Objects.equals(value.getValueCode(), parts[2]))
			.findFirst()
			.map(value -> new ResolvedRuntimeBinding(normalizedPhrase, displayPhrase, "ENUM_VALUE", assetKey,
					businessLabel, parts[0], source, sourceRecordId, principalId))
			.orElse(null);
	}

	private boolean shadowedByMoreSpecificExplicitAsset(SemanticCatalogSnapshot catalog, String query,
			ResolvedRuntimeBinding binding) {
		String phrase = UserSemanticPreferenceService.normalizePhrase(binding.displayPhrase());
		String normalizedQuery = UserSemanticPreferenceService.normalizePhrase(query);
		if (!hasText(phrase) || !hasText(normalizedQuery)) {
			return false;
		}
		return switch (binding.assetType()) {
			case "METRIC" -> catalog.getMetrics()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> !Objects.equals(value.getMetricCode(), binding.assetKey()))
				.anyMatch(value -> explicitLongerTerm(normalizedQuery, phrase, value.getBusinessName(),
						value.getMetricCode()));
			case "DIMENSION" -> catalog.getDimensions()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> !Objects.equals(value.getDimensionCode(), binding.assetKey()))
				.anyMatch(value -> explicitLongerTerm(normalizedQuery, phrase, value.getBusinessName(),
						value.getDimensionCode()));
			case "ENUM_VALUE" -> catalog.getEnumValues()
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> !Objects.equals(
						value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode(),
						binding.assetKey()))
				.anyMatch(value -> explicitLongerTerm(normalizedQuery, phrase, value.getBusinessName(),
						value.getValueCode()));
			default -> false;
		};
	}

	private boolean explicitLongerTerm(String normalizedQuery, String phrase, String... values) {
		for (String value : values) {
			String term = UserSemanticPreferenceService.normalizePhrase(value);
			if (hasText(term) && term.length() > phrase.length() && term.contains(phrase)
					&& normalizedQuery.contains(term)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record BindingContext(List<ResolvedRuntimeBinding> bindings, QueryCaseHints hints,
			List<String> additionalPhysicalTables) {

		public boolean empty() {
			return bindings == null || bindings.isEmpty();
		}
	}

	public record ResolvedRuntimeBinding(String normalizedPhrase, String displayPhrase, String assetType,
			String assetKey, String businessLabel, String modelCode, String source, Long sourceRecordId,
			String principalId) {
	}

}
