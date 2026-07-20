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
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService.AssetRef;
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

	private final SemanticCatalogLookupService catalogLookup;

	private final RunExecutionFenceService executionFence;

    private PersonalPublicDefinitionService publicDefinitions;
    @org.springframework.beans.factory.annotation.Autowired
    public void publicDefinitions(PersonalPublicDefinitionService service){this.publicDefinitions=service;}

	public RuntimeSemanticBindingService(UserSemanticPreferenceService preferenceService,
			ProjectSemanticAliasService projectAliasService, SemanticCatalogLookupService catalogLookup,
			RunExecutionFenceService executionFence) {
		this.preferenceService = preferenceService;
		this.projectAliasService = projectAliasService;
		this.catalogLookup = catalogLookup;
		this.executionFence = executionFence;
	}

	public BindingContext resolve(Long projectId, Long projectVersionId, String userId, String query) {
        return resolve(projectId,projectVersionId,userId,query,Set.of());
    }
    public BindingContext resolve(Long projectId,Long projectVersionId,String userId,String query,Set<Long> excluded) {
        return resolve(projectId, projectVersionId, userId, query, excluded, false);
    }
    /** Preview private defaults for model selection; they are not confirmed constraints for this request. */
    public BindingContext planningCandidates(Long projectId, Long versionId, String userId, String query, Set<Long> excluded) {
        return resolve(projectId, versionId, userId, query, excluded, true);
    }
    private BindingContext resolve(Long projectId,Long projectVersionId,String userId,String query,Set<Long> excluded,
            boolean selectionPending) {
        var aliases=projectAliasService.applicable(projectId,projectVersionId,query);
        var preferences=hasText(userId) && !RuntimePrincipalResolver.ANONYMOUS.equals(userId)
                ? preferenceService.applicable(projectId,userId,query).stream().filter(p->!excluded.contains(p.id())).toList() : List.<UserSemanticPreference>of();
        var references=new LinkedHashSet<SemanticCatalogLookupService.BindingRef>();
        aliases.forEach(alias->references.add(new SemanticCatalogLookupService.BindingRef(new AssetRef(alias.assetType(),alias.assetKey()),alias.displayPhrase())));
        preferences.stream().filter(p->!"TEXT_DEFINITION".equals(p.assetType())).forEach(preference->references.add(new SemanticCatalogLookupService.BindingRef(new AssetRef(preference.assetType(),preference.assetKey()),preference.displayPhrase())));
        if(references.isEmpty() && preferences.isEmpty()) return new BindingContext(List.of(),QueryCaseHints.empty(),List.of());
        var textPhrases=preferences.stream().filter(p->"TEXT_DEFINITION".equals(p.assetType()))
            .map(p->new SemanticCatalogLookupService.BindingPhrase("METRIC",p.displayPhrase())).toList();
        SemanticCatalogSnapshot catalog=catalogLookup.loadRuntimeBindings(projectId,projectVersionId,references,textPhrases,query);
		Map<String, ResolvedRuntimeBinding> byPhrase = new LinkedHashMap<>();
		for (ProjectSemanticAlias alias : aliases) {
			ResolvedRuntimeBinding binding = validate(catalog, alias.normalizedPhrase(), alias.displayPhrase(),
					alias.assetType(), alias.assetKey(), alias.businessLabel(), "PROJECT", alias.id(), null);
			if (binding != null && !shadowedByMoreSpecificExplicitAsset(catalog, query, binding)) {
				byPhrase.put(alias.normalizedPhrase(), binding);
			}
		}
		if (hasText(userId) && !RuntimePrincipalResolver.ANONYMOUS.equals(userId)) {
			for (UserSemanticPreference preference : preferences) {
                var definition=preferenceService.definition(preference.id(),preference.currentRevision());
                if("TEXT_DEFINITION".equals(preference.assetType())) {
                    if(RuntimeBindingApplicability.shadowed(catalog,query,preference.assetType(),preference.assetKey(),preference.displayPhrase())) continue;
                    byPhrase.put(preference.normalizedPhrase(),new ResolvedRuntimeBinding(preference.normalizedPhrase(),preference.displayPhrase(),
                        preference.assetType(),preference.assetKey(),preference.businessLabel(),null,"USER",preference.id(),userId,
                        definition.revision(),definition.contentHash(),definition.dependencyFingerprint(),definition.text()));
                    continue;
                }

				ResolvedRuntimeBinding binding = validate(catalog, preference.normalizedPhrase(),
						preference.displayPhrase(), preference.assetType(), preference.assetKey(),
						preference.businessLabel(), "USER", preference.id(), userId);
				if (binding != null && !shadowedByMoreSpecificExplicitAsset(catalog, query, binding)) {
                    if(definition.dependencyFingerprint()!=null && !definition.dependencyFingerprint().equals(
                            PersonalDefinitionSnapshot.capture(catalog,preference.assetType(),preference.assetKey()).dependencyFingerprint())) {
                        String current=PersonalDefinitionSnapshot.capture(catalog,preference.assetType(),preference.assetKey()).dependencyFingerprint();
                        if(!"METRIC".equals(preference.assetType())||(!selectionPending && (publicDefinitions==null
                                ||!publicDefinitions.acknowledged(definition,preference.assetType(),preference.assetKey(),current))))
                            throw new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED");
                        cn.lgs.semevosql.semantic.application.FrozenPersonalMetric.project(definition,catalog);
                    }
                    binding=withDefinition(binding,definition);
                    // Personal language intentionally overrides the project alias for
					// this user's same phrase, unless the current query explicitly uses a
					// longer published business term that contains that phrase.
					byPhrase.put(preference.normalizedPhrase(), binding);
				}
			}
		}
		List<ResolvedRuntimeBinding> bindings = List.copyOf(byPhrase.values());
		return new BindingContext(bindings, hints(selectionPending
                ? bindings.stream().filter(b -> !"USER".equals(b.source())).toList() : bindings, false,catalog),
                physicalTables(catalog, bindings));
	}

	public BindingContext explicit(Long projectId, Long projectVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel) {
		return explicit(projectId, projectVersionId, rawPhrase, assetType, assetKey, businessLabel, "QUERY", null, null);
	}

	public BindingContext explicit(Long projectId, Long projectVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel, String source, Long sourceRecordId, String principalId) {
		SemanticCatalogSnapshot catalog = catalogLookup.loadCurrentAssets(projectId, projectVersionId, List.of(new AssetRef(assetType,assetKey)));
		String normalizedPhrase = UserSemanticPreferenceService.normalizePhrase(rawPhrase);
		ResolvedRuntimeBinding binding = validate(catalog, normalizedPhrase, rawPhrase, assetType, assetKey,
				businessLabel, source, sourceRecordId, principalId);
		if (binding == null) {
			throw new IllegalArgumentException(
					"Correction target is not an enabled semantic asset: " + assetType + ":" + assetKey);
		}
		if("USER".equals(source) && sourceRecordId!=null)binding=withDefinition(binding,
            preferenceService.definition(sourceRecordId,preferenceService.findById(sourceRecordId).orElseThrow().currentRevision()));
        List<ResolvedRuntimeBinding> bindings = List.of(binding);
        return new BindingContext(bindings, hints(bindings, true,catalog), physicalTables(catalog, bindings));
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
				if(binding.sourceRevision()!=null)preferenceService.recordApplied(binding.sourceRecordId(),binding.sourceRevision(),runId);
			}
		}
	}

	private QueryCaseHints hints(List<ResolvedRuntimeBinding> bindings, boolean strictAssetBinding,SemanticCatalogSnapshot catalog) {
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
                    String code=binding.assetKey();
                    if("USER".equals(binding.source())&&binding.sourceRevision()!=null&&binding.dependencyFingerprint()!=null) {
                        var definition=preferenceService.definition(binding.sourceRecordId(),binding.sourceRevision());
                        if(cn.lgs.semevosql.semantic.application.FrozenPersonalMetric.differs(definition,catalog))
                            code=cn.lgs.semevosql.semantic.application.FrozenPersonalMetric.project(definition,catalog).getMetricCode();
                    }
					metrics.add(code);
					assetBindings.add(new AssetBindingHint(binding.displayPhrase(), "METRIC", code,
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
        return RuntimeBindingApplicability.shadowed(catalog,query,binding.assetType(),binding.assetKey(),binding.displayPhrase());
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

    private ResolvedRuntimeBinding withDefinition(ResolvedRuntimeBinding binding,PersonalSemanticDefinitionStore.Definition d) {
        return new ResolvedRuntimeBinding(binding.normalizedPhrase(),binding.displayPhrase(),binding.assetType(),binding.assetKey(),binding.businessLabel(),
            binding.modelCode(),binding.source(),binding.sourceRecordId(),binding.principalId(),d.revision(),d.contentHash(),d.dependencyFingerprint(),d.text());
    }
    public record ResolvedRuntimeBinding(String normalizedPhrase,String displayPhrase,String assetType,String assetKey,String businessLabel,
        String modelCode,String source,Long sourceRecordId,String principalId,Integer sourceRevision,String sourceContentHash,
        String dependencyFingerprint,String definitionText) {
        public ResolvedRuntimeBinding(String normalizedPhrase,String displayPhrase,String assetType,String assetKey,String businessLabel,
                String modelCode,String source,Long sourceRecordId,String principalId) {
            this(normalizedPhrase,displayPhrase,assetType,assetKey,businessLabel,modelCode,source,sourceRecordId,principalId,null,null,null,null);
        }
    }
}
