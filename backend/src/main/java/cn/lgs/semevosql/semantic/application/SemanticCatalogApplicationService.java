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
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService.PlanningDecision;
import cn.lgs.semevosql.project.domain.InitializationAnalysisStatus;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogCloner;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType;
import cn.lgs.semevosql.util.JsonUtil;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SemanticCatalogApplicationService implements ProjectVersionCatalogReadiness, ProjectVersionCatalogCloner {

	private static final Set<String> QUERY_SELECTABLE_RULE_TYPES = Set.of("BUSINESS_RULE", "BUSINESS_FILTER");

	private static final Set<String> MANDATORY_GOVERNANCE_RULE_TYPES = Set.of("MANDATORY_FILTER", "ROW_FILTER",
			"SECURITY_FILTER", "DATA_SCOPE", "REQUIRED_PREDICATE");

	private static final Pattern GOVERNED_ENUM_SET_FILTER = Pattern.compile(
			"(?is)^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s+IN\\s*\\((.+)\\)\\s*$");

	private static final Pattern GOVERNED_ENUM_EQUALITY_FILTER = Pattern.compile(
			"(?is)^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*'((?:''|[^'])*)'\\s*$");

	private static final Pattern SQL_STRING_LITERAL = Pattern.compile("\\s*'((?:''|[^'])*)'\\s*(?:,|$)");

	private final SemanticCatalogRepository catalogRepository;

    private final SemanticCatalogReadService catalogReader;

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogPromptRenderer promptRenderer;

	private final MultiSourcePolicyService multiSourcePolicyService;

	private final ScenarioResolutionService scenarioResolutionService;

	private final SemanticHybridRetrievalService hybridRetrievalService;

	private final SemanticBlueprintEnricher queryIrEnricher;

    private final SourceSchemaExportService sourceMetadata;

    private final cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocumentBuildService retrievalDocuments;

	@Transactional
	public SemanticCatalogSnapshot replaceDraftCatalog(Long projectId, Long projectVersionId,
			SemanticCatalogSnapshot requestedSnapshot) {
        catalogRepository.lockVersion(projectId,projectVersionId);
		SemanticProjectVersion version = requireVersion(projectId, projectVersionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Semantic catalog can only be replaced in a DRAFT project version");
		}
		if (version.getAnalysisStatus() != InitializationAnalysisStatus.RUNNING
				&& version.getAnalysisStatus() != InitializationAnalysisStatus.COMPLETED) {
			throw new IllegalStateException(
					"Semantic catalog can only be written in a RUNNING or COMPLETED DRAFT analysis");
		}

		SemanticCatalogSnapshot normalized = normalize(projectId, projectVersionId, requestedSnapshot);
		List<String> violations = validateDraftWrite(normalized);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Invalid semantic catalog draft: " + String.join("; ", violations));
		}
		catalogRepository.replaceCatalog(normalized);
        return prepareRetrieval(projectId,projectVersionId);
	}

	@Transactional
	public SemanticCatalogSnapshot mergeDraftCatalog(Long projectId, Long projectVersionId,
			SemanticCatalogSnapshot requestedPatch) {
        catalogRepository.lockVersion(projectId,projectVersionId);
		SemanticProjectVersion version = requireVersion(projectId, projectVersionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT
				|| (version.getAnalysisStatus() != InitializationAnalysisStatus.RUNNING
						&& version.getAnalysisStatus() != InitializationAnalysisStatus.COMPLETED)) {
			throw new IllegalStateException("Semantic catalog patches require a RUNNING or COMPLETED DRAFT version");
		}
		SemanticCatalogSnapshot current = catalogRepository.loadCatalog(projectId, projectVersionId);
		SemanticCatalogSnapshot patch = normalize(projectId, projectVersionId, requestedPatch);
		List<SemanticCatalogSnapshot.Dimension> currentDimensions = withoutGeneratedDimensionDuplicates(
				current.getDimensions(), patch.getDimensions());
		List<SemanticCatalogSnapshot.Grain> currentGrains = withoutGeneratedGrainDuplicates(current.getGrains(),
				patch.getGrains());
		SemanticCatalogSnapshot merged = SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
            .sharedDefinitions(SharedCatalogProtocol.canonicalRows(mergeByKey(current.getSharedDefinitions(),patch.getSharedDefinitions(),d->d.path("code").asText()+"@"+d.path("revision").asInt()),false))
            .enumDictionaries(SharedCatalogProtocol.canonicalRows(mergeByKey(current.getEnumDictionaries(),patch.getEnumDictionaries(),d->d.path("code").asText()+"@"+d.path("revision").asInt()),false))
            .modelBindings(SharedCatalogProtocol.canonicalRows(mergeByKey(current.getModelBindings(),patch.getModelBindings(),b->b.path("model").asText()+"/"+b.path("code").asText()),true))
			.models(mergeByKey(current.getModels(), patch.getModels(), SemanticCatalogSnapshot.Model::getModelCode))
			.columns(mergeByKey(current.getColumns(), patch.getColumns(),
					column -> key(column.getModelCode(), column.getColumnName())))
			.metrics(
					mergeByKey(current.getMetrics(), patch.getMetrics(), SemanticCatalogSnapshot.Metric::getMetricCode))
			.dimensions(mergeByKey(currentDimensions, patch.getDimensions(),
					SemanticCatalogSnapshot.Dimension::getDimensionCode))
			.relationships(mergeByKey(current.getRelationships(), patch.getRelationships(),
					SemanticCatalogSnapshot.Relationship::getRelationshipCode))
			.grains(mergeByKey(currentGrains, patch.getGrains(),
					grain -> key(grain.getModelCode(), grain.getGrainCode())))
			.enumValues(mergeByKey(current.getEnumValues(), patch.getEnumValues(),
					value -> key(key(value.getModelCode(), value.getColumnName()), value.getValueCode())))
			.rules(mergeByKey(current.getRules(), patch.getRules(), SemanticCatalogSnapshot.Rule::getRuleCode))
			.build();
		List<String> violations = validateDraftWrite(merged);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Invalid semantic catalog patch: " + String.join("; ", violations));
		}
		catalogRepository.replaceCatalog(merged);
        return prepareRetrieval(projectId,projectVersionId);
	}

    private SemanticCatalogSnapshot prepareRetrieval(Long projectId,Long versionId) {
        SemanticCatalogSnapshot persisted=catalogRepository.loadCatalog(projectId,versionId);
        retrievalDocuments.prepare(projectId,versionId,SemanticCatalogFingerprint.fingerprint(persisted));
        return persisted;
    }

	public SemanticCatalogSnapshot getCatalog(Long projectId, Long projectVersionId) {
		requireVersion(projectId, projectVersionId);
		return catalogRepository.loadCatalog(projectId, projectVersionId);
	}

	@Override
	@Transactional
	public void cloneCatalog(Long projectId, Long sourceVersionId, Long targetVersionId) {
        catalogRepository.lockVersion(projectId,targetVersionId);
		SemanticProjectVersion sourceVersion = requireVersion(projectId, sourceVersionId);
		SemanticProjectVersion targetVersion = requireVersion(projectId, targetVersionId);
		if (sourceVersion.getStatus() != ProjectVersionStatus.PUBLISHED
				&& sourceVersion.getStatus() != ProjectVersionStatus.ARCHIVED) {
			throw new IllegalStateException("Catalog can only be cloned from a published or archived version");
		}
		if (targetVersion.getStatus() != ProjectVersionStatus.DRAFT
				|| targetVersion.getAnalysisStatus() != InitializationAnalysisStatus.PENDING) {
			throw new IllegalStateException("Catalog can only be cloned into a pending DRAFT version");
		}
		SemanticCatalogSnapshot source = catalogRepository.loadCatalog(projectId, sourceVersionId);
		SemanticCatalogSnapshot copied = JsonUtil.getObjectMapper().convertValue(source, SemanticCatalogSnapshot.class);
		catalogRepository.replaceCatalog(normalize(projectId, targetVersionId, copied));
		multiSourcePolicyService.clonePolicy(projectId, sourceVersionId, targetVersionId);
        prepareRetrieval(projectId,targetVersionId);
	}

	public Integer requireSingleDatasource(Long projectId, Long projectVersionId) {
		Set<Integer> datasourceIds = catalogRepository.enabledDatasourceIds(projectId, projectVersionId);
		if (datasourceIds.size() != 1) {
			throw new IllegalStateException(
					"A runtime semantic catalog must reference exactly one enabled datasource, found: "
							+ datasourceIds);
		}
		return datasourceIds.iterator().next();
	}

	public Set<String> enabledPhysicalTables(Long projectId, Long projectVersionId) {
		return catalogRepository.enabledPhysicalTables(projectId, projectVersionId);
	}

	public List<String> recallPhysicalTables(Long projectId, Long projectVersionId, String query, int limit) {
		return recallPlanning(projectId, projectVersionId, query, limit).physicalTables();
	}

	public PlanningRecall recallPlanning(Long projectId, Long projectVersionId, String query, int limit) {
		if (!hasText(query) || query.codePoints().noneMatch(Character::isLetterOrDigit) || limit <= 0) {
			return PlanningRecall.empty();
		}
		SemanticProjectVersion version = requireVersion(projectId, projectVersionId);
		if (!hasText(version.getCatalogHash())) {
			return PlanningRecall.empty();
		}
		List<RetrievalHit> hits = hybridRetrievalService.retrieve(projectId, projectVersionId, version.getCatalogHash(),
				query, limit);
		List<String> physicalTables = hits.stream().map(RetrievalHit::physicalTable).filter(this::hasText).distinct()
			.toList();
		if (physicalTables.isEmpty()) {
			PlanningRecall fallback = boundedCatalogFallback(SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(projectVersionId)
                .models(catalogRepository.findEnabledModelSummaries(projectId,projectVersionId,limit+1)).build(), limit);
			if (!fallback.physicalTables().isEmpty()) {
				return fallback;
			}
		}
		return new PlanningRecall(physicalTables, hits);
	}

	static PlanningRecall boundedCatalogFallback(SemanticCatalogSnapshot snapshot, int limit) {
		if (snapshot == null || limit <= 0 || snapshot.getModels() == null) {
			return PlanningRecall.empty();
		}
		List<SemanticCatalogSnapshot.Model> enabledModels = snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> model.getPhysicalTable() != null && !model.getPhysicalTable().isBlank())
			.sorted(java.util.Comparator.comparing(SemanticCatalogSnapshot.Model::getModelCode))
			.toList();
		// A transient vector-channel outage must not turn a small, fully governed namespace into a random hard failure.
		// If every enabled model fits inside the normal recall budget, hand that complete bounded namespace to the Planner.
		// Large catalogs remain fail-closed because an arbitrary partial fallback would be less safe than RETRIEVAL_MISS.
		if (enabledModels.isEmpty() || enabledModels.size() > limit) {
			return PlanningRecall.empty();
		}
		List<RetrievalHit> fallbackHits = new ArrayList<>();
		for (int index = 0; index < enabledModels.size(); index++) {
			SemanticCatalogSnapshot.Model model = enabledModels.get(index);
			fallbackHits.add(new RetrievalHit(DocumentType.MODEL, "MODEL", "model:" + model.getModelCode(),
					model.getModelCode(), model.getPhysicalTable(), 0d, Map.of("BOUNDED_CATALOG_FALLBACK", index + 1),
					Map.of("BOUNDED_CATALOG_FALLBACK", 0d)));
		}
		return new PlanningRecall(enabledModels.stream().map(SemanticCatalogSnapshot.Model::getPhysicalTable).distinct()
			.toList(), fallbackHits);
	}

	public SemanticBlueprint buildBlueprint(Long projectId, Long projectVersionId, String canonicalQuery,
			Collection<String> selectedPhysicalTables) {
		throw new IllegalArgumentException(
				"Explicit governed semantic bindings are required; natural-language planning must use SemanticBlueprintGenerationService");
	}

	public SemanticBlueprint buildBlueprint(Long projectId, Long projectVersionId, String canonicalQuery,
			Collection<String> selectedPhysicalTables, QueryCaseHints caseHints) {
        Objects.requireNonNull(caseHints, "Governed semantic bindings are required");
        return catalogReader.readCurrent(projectId, projectVersionId, scope -> {
            Set<String> models = caseHints.modelCodes().isEmpty()
                    ? scope.resolvePhysicalModels(selectedPhysicalTables) : caseHints.modelCodes();
            return buildBlueprint(projectId, projectVersionId, scope.models(models), canonicalQuery,
                    selectedPhysicalTables, caseHints);
        });
	}

	public SemanticBlueprint buildBlueprint(Long projectId, Long policyVersionId, SemanticCatalogSnapshot snapshot,
			String canonicalQuery, Collection<String> selectedPhysicalTables, QueryCaseHints caseHints) {
		QueryCaseHints hints = Objects.requireNonNull(caseHints, "Governed semantic bindings are required");
		Objects.requireNonNull(snapshot, "Semantic Catalog snapshot is required");
		Set<String> selectedTables = new LinkedHashSet<>(
				selectedPhysicalTables == null ? List.of() : selectedPhysicalTables);
		List<SemanticCatalogSnapshot.Model> selectedModels = snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> selectedTables.contains(model.getPhysicalTable()))
            .filter(model -> hints.modelCodes().isEmpty() || hints.modelCodes().contains(model.getModelCode()))
			.toList();
		Set<String> selectedModelCodes = selectedModels.stream()
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		List<SemanticBlueprint.ModelSelection> models = selectedModels.stream()
			.map(model -> SemanticBlueprint.ModelSelection.builder()
				.modelCode(model.getModelCode())
				.physicalTable(model.getPhysicalTable())
				.businessName(model.getBusinessName())
				.datasourceId(model.getDatasourceId())
				.build())
			.toList();
		List<SemanticCatalogSnapshot.Metric> selectedMetricAssets = selectMetricAssets(snapshot.getMetrics(),
				selectedModelCodes, hints);
		List<SemanticBlueprint.MetricSelection> metrics = queryIrEnricher.metricsForIntent(canonicalQuery,
				selectedMetricAssets.stream()
					.map(metric -> SemanticBlueprint.MetricSelection.builder()
						.metricCode(metric.getMetricCode())
						.modelCode(metric.getModelCode())
						.businessName(metric.getBusinessName())
						.expression(metric.getExpression())
						.aggregation(metric.getAggregation())
						.unit(metric.getUnit())
                        .numericRange(metric.numericRange())
                        .definitionBinding(cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding.resolve(snapshot,"METRIC",metric.getModelCode(),metric.getMetricCode(),metric.getDefinitionBinding()))
						.timeColumn(metric.getTimeColumn())
						.filterExpression(metric.getFilterExpression())
						.additiveType(metric.getAdditiveType())
						.build())
					.toList());
		List<SemanticBlueprint.DimensionSelection> dimensions = snapshot.getDimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> selectedModelCodes.contains(dimension.getModelCode()))
			.filter(dimension -> hints.dimensionCodes().contains(dimension.getDimensionCode()))
			.map(dimension -> SemanticBlueprint.DimensionSelection.builder()
				.dimensionCode(dimension.getDimensionCode())
                .definitionBinding(cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding.resolve(snapshot,"DIMENSION",dimension.getModelCode(),dimension.getDimensionCode(),dimension.getDefinitionBinding()))
				.modelCode(dimension.getModelCode())
				.businessName(dimension.getBusinessName())
				.columnName(dimension.getColumnName())
				.expression(dimension.getExpression())
				.dimensionType(dimension.getDimensionType())
				.hierarchy(dimension.getHierarchy())
				.build())
			.toList();
		Set<String> enumFilterModelCodes = hints.enumBindings()
			.stream()
			.map(QueryCaseHints.EnumBindingHint::modelCode)
			.filter(selectedModelCodes::contains)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> effectiveModelCodesBuilder = new LinkedHashSet<>(strictEffectiveModelCodes(selectedModelCodes, metrics,
				dimensions, enumFilterModelCodes, hints, snapshot.getRelationships()));
		multiSourcePolicyService.get(projectId, policyVersionId)
			.getCrossSourceRelationships()
			.stream()
			.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(relationship -> hints.relationshipCodes().contains(relationship.getRelationshipCode()))
			.filter(relationship -> selectedModelCodes.contains(relationship.getLeftModelCode())
					&& selectedModelCodes.contains(relationship.getRightModelCode()))
			.forEach(relationship -> {
				effectiveModelCodesBuilder.add(relationship.getLeftModelCode());
				effectiveModelCodesBuilder.add(relationship.getRightModelCode());
			});
		Set<String> effectiveModelCodes = Set.copyOf(effectiveModelCodesBuilder);
		QueryCaseHints semanticHints = hints;
		List<SemanticCatalogSnapshot.Model> effectiveSelectedModels = snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> effectiveModelCodes.contains(model.getModelCode()))
			.toList();
		List<SemanticBlueprint.ModelSelection> effectiveModels = effectiveSelectedModels.stream()
			.map(model -> SemanticBlueprint.ModelSelection.builder()
				.modelCode(model.getModelCode())
				.physicalTable(model.getPhysicalTable())
				.businessName(model.getBusinessName())
				.datasourceId(model.getDatasourceId())
				.build())
			.toList();
		List<String> prunedRecallOnlyModels = selectedModelCodes.stream()
			.filter(modelCode -> !effectiveModelCodes.contains(modelCode))
			.sorted()
			.toList();
		List<SemanticBlueprint.GrainSelection> grains = snapshot.getGrains()
			.stream()
			.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(grain -> effectiveModelCodes.contains(grain.getModelCode()))
			.filter(grain -> hints.grainCodes().contains(grain.getGrainCode()))
			.map(grain -> SemanticBlueprint.GrainSelection.builder()
				.grainCode(grain.getGrainCode())
				.modelCode(grain.getModelCode())
				.keyColumns(grain.getKeyColumns())
				.timeColumn(grain.getTimeColumn())
				.uniquenessRule(grain.getUniquenessRule())
				.build())
			.toList();
		List<SemanticCatalogSnapshot.Relationship> selectedRelationships = snapshot.getRelationships()
			.stream()
			.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(relationship -> effectiveModelCodes.contains(relationship.getSourceModelCode()))
			.filter(relationship -> effectiveModelCodes.contains(relationship.getTargetModelCode()))
			.filter(relationship -> hints.relationshipCodes().contains(relationship.getRelationshipCode()))
			.toList();
		List<SemanticCatalogSnapshot.Relationship> relationshipPath = resolveRelationshipPath(effectiveModelCodes,
				selectedRelationships);
		List<SemanticBlueprint.RelationshipSelection> catalogRelationships = relationshipPath.stream()
			.map(relationship -> SemanticBlueprint.RelationshipSelection.builder()
				.relationshipCode(relationship.getRelationshipCode())
				.sourceModelCode(relationship.getSourceModelCode())
				.targetModelCode(relationship.getTargetModelCode())
				.cardinality(relationship.getCardinality())
				.joinType(relationship.getJoinType())
				.joinCondition(relationship.getJoinCondition())
				.build())
			.toList();
		List<SemanticBlueprint.RuleSelection> rules = snapshot.getRules()
			.stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(rule -> !hasText(rule.getModelCode()) || effectiveModelCodes.contains(rule.getModelCode()))
			.filter(rule -> ruleAppliesToPlan(rule, semanticHints))
			.map(rule -> SemanticBlueprint.RuleSelection.builder()
				.ruleCode(rule.getRuleCode())
				.modelCode(rule.getModelCode())
				.ruleType(rule.getRuleType())
				.businessName(rule.getBusinessName())
				.expression(rule.getExpression())
				.severity(rule.getSeverity())
				.build())
			.toList();

		SemanticBlueprintEnricher.IrDetails ir = queryIrEnricher.enrich(snapshot, canonicalQuery, effectiveModels,
				metrics, dimensions, grains, semanticHints);
		Set<String> metricModelCodes = metrics.stream()
			.map(SemanticBlueprint.MetricSelection::getModelCode)
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		boolean scalarCompositionDeclared = hints.resultComposition() != null
				&& "SCALAR".equalsIgnoreCase(hints.resultComposition().type());
		boolean relationshipFreeScalar = scalarCompositionDeclared && ir.expectedResult() != null
				&& "SCALAR".equalsIgnoreCase(ir.expectedResult().getGrain()) && ir.groupBy().isEmpty()
				&& hints.relationshipCodes().isEmpty() && !metricModelCodes.isEmpty()
				&& metricModelCodes.equals(effectiveModelCodes);
		if (scalarCompositionDeclared && !relationshipFreeScalar) {
			String expectedGrain = ir.expectedResult() == null ? "<none>" : Objects.toString(ir.expectedResult().getGrain(), "<none>");
			throw new IllegalArgumentException("Invalid SCALAR resultComposition shape: metricModels=" + metricModelCodes
					+ ", effectiveModels=" + effectiveModelCodes + ", expectedGrain=" + expectedGrain + ", groupCount="
					+ ir.groupBy().size() + ", relationshipCount=" + hints.relationshipCodes().size());
		}
		String scalarCalculationExpression = relationshipFreeScalar ? hints.resultComposition().calculationExpression() : null;
		PlanningDecision multiSourceDecision = multiSourcePolicyService.plan(projectId, policyVersionId,
				effectiveModelCodes, hints.relationshipCodes(), relationshipFreeScalar, scalarCalculationExpression);
		List<SemanticBlueprint.RelationshipSelection> relationshipSelections = new ArrayList<>(catalogRelationships);
		multiSourceDecision.relationships()
			.stream()
			.filter(relationship -> hints.relationshipCodes().contains(relationship.getRelationshipCode()))
			.filter(relationship -> effectiveModelCodes.contains(relationship.getLeftModelCode())
					&& effectiveModelCodes.contains(relationship.getRightModelCode()))
			.map(relationship -> SemanticBlueprint.RelationshipSelection.builder()
				.relationshipCode(relationship.getRelationshipCode())
				.sourceModelCode(relationship.getLeftModelCode())
				.targetModelCode(relationship.getRightModelCode())
				.cardinality(relationship.getCardinality())
				.joinType("CROSS_SOURCE_MERGE")
				.joinCondition(relationship.getLeftModelCode() + "." + relationship.getLeftKey() + " = "
						+ relationship.getRightModelCode() + "." + relationship.getRightKey())
				.build())
			.forEach(relationshipSelections::add);
		List<SemanticBlueprint.RelationshipSelection> relationships = List.copyOf(relationshipSelections);
		Map<String, List<String>> physicalTableByModelCode = effectiveSelectedModels.stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode,
					SemanticCatalogSnapshot.Model::physicalTables));
		List<SemanticBlueprint.SourceSubPlan> sourceSubPlans = multiSourceDecision.sources()
			.stream()
			.map(source -> SemanticBlueprint.SourceSubPlan.builder()
				.datasourceId(source.datasourceId())
				.domainCode(source.domainCode())
				.responsibility(source.responsibility())
				.priority(source.priority())
				.authorityRank(source.authorityRank())
				.modelCodes(source.modelCodes())
				.physicalTables(source.modelCodes()
					.stream()
					.map(physicalTableByModelCode::get)
					.filter(Objects::nonNull)
                    .flatMap(Collection::stream).distinct()
					.toList())
				.build())
			.toList();
		List<SemanticBlueprint.FreshnessNotice> freshnessNotices = multiSourceDecision.sources()
			.stream()
			.filter(source -> source.freshnessPolicy() != null)
			.map(source -> SemanticBlueprint.FreshnessNotice.builder()
				.datasourceId(source.datasourceId())
				.businessDateField(source.freshnessPolicy().getBusinessDateField())
				.timeZone(source.freshnessPolicy().getTimeZone())
				.freshnessType(source.freshnessPolicy().getFreshnessType())
				.latencyMinutes(source.freshnessPolicy().getLatencyMinutes())
				.availableUntilRule(source.freshnessPolicy().getAvailableUntilRule())
				.build())
			.toList();
		SemanticBlueprint.MergePlan mergePlan = multiSourceDecision.mergePolicy() == null ? null
				: SemanticBlueprint.MergePlan.builder()
					.policyCode(multiSourceDecision.mergePolicy().getPolicyCode())
					.mergeType(multiSourceDecision.mergePolicy().getMergeType())
					.relationshipCode(multiSourceDecision.mergePolicy().getRelationshipCode())
					.leftInputKey(multiSourceDecision.mergePolicy().getLeftInputKey())
					.rightInputKey(multiSourceDecision.mergePolicy().getRightInputKey())
					.outputKey(multiSourceDecision.mergePolicy().getOutputKey())
					.inputGrain(multiSourceDecision.mergePolicy().getInputGrain())
					.nullPolicy(multiSourceDecision.mergePolicy().getNullPolicy())
					.duplicatePolicy(multiSourceDecision.mergePolicy().getDuplicatePolicy())
					.maxRows(multiSourceDecision.mergePolicy().getMaxRows())
					.partialFailurePolicy(multiSourceDecision.mergePolicy().getPartialFailurePolicy())
					.calculationExpression(multiSourceDecision.mergePolicy().getCalculationExpression())
					.build();
		BusinessRuleFilterResult businessRuleFilters = businessRuleFilters(snapshot, rules);

		List<String> errors = new ArrayList<>(multiSourceDecision.errors());
		errors.addAll(ir.errors());
		errors.addAll(businessRuleFilters.errors());
		List<String> warnings = new ArrayList<>(multiSourceDecision.warnings());
		warnings.addAll(ir.warnings());
		if (!prunedRecallOnlyModels.isEmpty()) {
			warnings.add("Pruned recall-only semantic models that were not required by selected assets: "
					+ String.join(", ", prunedRecallOnlyModels));
		}
		if (!hints.emptyHints()) {
			warnings
				.add("Governed historical case hints were rebound to the current Catalog and revalidated; sourceCases="
						+ String.join(",", hints.sourceExampleIds()));
		}
		if (effectiveSelectedModels.isEmpty()) {
			errors.add("No enabled semantic model matches the selected physical tables");
		}
		if (effectiveModelCodes.size() > 1 && !relationshipFreeScalar
				&& !isConnected(effectiveModelCodes, selectedRelationships, multiSourceDecision, hints.relationshipCodes())) {
			errors.add("Selected semantic models are not connected by published semantic/cross-source relationships: "
					+ String.join(", ", effectiveModelCodes));
		}

		Map<String, List<SemanticCatalogSnapshot.Relationship>> oneToManyBySource = relationshipPath.stream()
			.filter(relationship -> relationship.getCardinality() == RelationshipCardinality.ONE_TO_MANY)
			.collect(Collectors.groupingBy(SemanticCatalogSnapshot.Relationship::getSourceModelCode));
		List<String> preAggregationModelCodes = oneToManyBySource.values()
			.stream()
			.filter(related -> related.size() > 1)
			.flatMap(related -> related.stream().map(SemanticCatalogSnapshot.Relationship::getTargetModelCode))
			.distinct()
			.toList();
		if (!preAggregationModelCodes.isEmpty()) {
			warnings.add("Potential fan-out detected; aggregate these models before joining: "
					+ String.join(", ", preAggregationModelCodes));
		}
		if (metrics.isEmpty() && snapshot.getMetrics()
			.stream()
			.anyMatch(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED
					&& effectiveModelCodes.contains(metric.getModelCode()))) {
			warnings.add("No published metric was explicitly matched; SQL generation must not invent a metric formula");
		}

		return SemanticBlueprint.builder()
			.projectId(projectId)
			.projectVersionId(policyVersionId)
			.canonicalQuery(canonicalQuery)
			.compilerMode(ir.compilerMode())
			.projections(ir.projections())
			.filters(mergeFilters(ir.filters(), businessRuleFilters.filters()))
			.enumResolutions(ir.enumResolutions())
			.timeRange(ir.timeRange())
			.groupBy(ir.groupBy())
			.orderBy(ir.orderBy())
			.limit(ir.limit())
			.expectedResult(withScalarCalculation(ir.expectedResult(), scalarCalculationExpression))
			.scalarCalculation(hasText(scalarCalculationExpression)
                ? cn.lgs.semevosql.semantic.domain.ScalarCalculation.parse(scalarCalculationExpression,
                    metrics.stream().map(SemanticBlueprint.MetricSelection::getMetricCode).collect(Collectors.toSet()))
                : null)
			.models(effectiveModels)
			.metrics(metrics)
			.dimensions(ir.dimensions())
			.grains(grains)
			.relationships(relationships)
			.rules(rules)
			.preAggregationModelCodes(preAggregationModelCodes)
			.sourceSubPlans(sourceSubPlans)
			.mergePlan(mergePlan)
			.freshnessNotices(freshnessNotices)
			.validationWarnings(warnings)
			.validationErrors(errors)
			.executable(errors.isEmpty())
			.build();
	}

    public SemanticCatalogSnapshot getCatalogForPhysicalTables(Long projectId,Long versionId,Collection<String> tables) {
        requireVersion(projectId,versionId);
        return catalogReader.getForPhysicalTables(projectId, versionId, tables);
    }

    public String renderRuntimePrompt(SemanticCatalogSnapshot snapshot){return promptRenderer.render(snapshot);}

    public String renderRuntimePromptForModels(Long projectId, Long projectVersionId, Set<String> modelCodes) {
        return promptRenderer.render(catalogReader.getForModels(projectId, projectVersionId, modelCodes));
    }

    public SemanticCatalogSnapshot getCatalogForModels(Long projectId, Long projectVersionId, Set<String> modelCodes) {
        return catalogReader.getForModels(projectId, projectVersionId, modelCodes);
    }

    public List<String> relationshipExpressionsForModels(Long projectId, Long projectVersionId, Set<String> modelCodes) {
        var catalog=catalogReader.getForModels(projectId,projectVersionId,modelCodes);
        var visibility=new cn.lgs.semevosql.semantic.domain.SemanticCatalogVisibility(catalog);
        return catalog.getRelationships().stream().filter(visibility::maySendRelationship)
            .filter(r->modelCodes.contains(r.getSourceModelCode())&&modelCodes.contains(r.getTargetModelCode()))
            .map(SemanticCatalogSnapshot.Relationship::getJoinCondition).distinct().toList();
    }

	public String renderRuntimePrompt(Long projectId, Long projectVersionId, Collection<String> physicalTables) {
		return promptRenderer.render(catalogReader.getForPhysicalTables(projectId, projectVersionId, physicalTables));
	}

	public List<String> relationshipExpressions(Long projectId, Long projectVersionId,
			Collection<String> physicalTables) {
		SemanticCatalogSnapshot snapshot = catalogReader.getForPhysicalTables(projectId, projectVersionId, physicalTables);
        Set<String> selectedTables = new HashSet<>(physicalTables);
		Map<String, String> tableByModel = snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode,
					SemanticCatalogSnapshot.Model::getPhysicalTable));
		return snapshot.getRelationships()
			.stream()
			.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(relationship -> selectedTables.contains(tableByModel.get(relationship.getSourceModelCode())))
			.filter(relationship -> selectedTables.contains(tableByModel.get(relationship.getTargetModelCode())))
			.map(SemanticCatalogSnapshot.Relationship::getJoinCondition)
			.distinct()
			.toList();
	}

	@Override
	public CatalogReadiness assess(Long projectId, Long projectVersionId) {
		SemanticCatalogSnapshot snapshot = catalogRepository.loadCatalog(projectId, projectVersionId);
		List<String> violations = new ArrayList<>(validate(snapshot));
        if(snapshot.getModels().stream().anyMatch(m->m.getSourceJson()!=null)) {
            try {sourceMetadata.verifyStructuredSources(snapshot);}
            catch(RuntimeException invalid){violations.add("Current structured source metadata could not be verified: "+invalid.getMessage());}
        }
		violations.addAll(multiSourcePolicyService.validateForRelease(projectId, projectVersionId, snapshot));
		violations.addAll(scenarioResolutionService.unresolvedCoreViolations(projectId, projectVersionId));
		List<String> distinctViolations = List.copyOf(new LinkedHashSet<>(violations));
		return distinctViolations.isEmpty() ? CatalogReadiness.accepted()
				: CatalogReadiness.rejected(distinctViolations);
	}

	List<String> validateDraftWrite(SemanticCatalogSnapshot snapshot) {
        if(snapshot.getModels().stream().anyMatch(m->m.getSourceJson()!=null))sourceMetadata.verifyStructuredSources(snapshot);
		return validate(snapshot);
	}

	List<String> validate(SemanticCatalogSnapshot snapshot) {
		List<String> violations = new ArrayList<>();
		List<SemanticCatalogSnapshot.Model> enabledModels = snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.toList();
		if (enabledModels.isEmpty()) {
			violations.add("at least one enabled model is required");
			return violations;
		}

		validateRequiredModelFields(enabledModels, violations);
		validateUnique(enabledModels, SemanticCatalogSnapshot.Model::getModelCode, "duplicate modelCode", violations);

		Set<String> modelCodes = enabledModels.stream()
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.collect(Collectors.toSet());
		Set<String> columnKeys = snapshot.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.map(column -> key(column.getModelCode(), column.getColumnName()))
			.collect(Collectors.toSet());

		validateUnique(snapshot.getColumns(), column -> key(column.getModelCode(), column.getColumnName()),
				"duplicate model column", violations);
		validateUnique(snapshot.getMetrics(), SemanticCatalogSnapshot.Metric::getMetricCode, "duplicate metricCode",
				violations);
		validateUnique(snapshot.getDimensions(), SemanticCatalogSnapshot.Dimension::getDimensionCode,
				"duplicate dimensionCode", violations);
		validateUnique(snapshot.getRelationships(), SemanticCatalogSnapshot.Relationship::getRelationshipCode,
				"duplicate relationshipCode", violations);
		validateUnique(snapshot.getRules(), SemanticCatalogSnapshot.Rule::getRuleCode, "duplicate ruleCode",
				violations);

		for (SemanticCatalogSnapshot.Model model : enabledModels) {
			boolean hasColumn = snapshot.getColumns()
				.stream()
				.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED
						&& model.getModelCode().equals(column.getModelCode()));
			if (!hasColumn) {
				violations.add("enabled model has no enabled columns: " + model.getModelCode());
			}
		}

		validateModelReferences(snapshot, modelCodes, violations);
		validateColumnReferences(snapshot, columnKeys, violations);
		validateRelationships(snapshot, modelCodes, violations);
		validateExpressions(snapshot, violations);

        for(var metric:snapshot.getMetrics()) {
            try { metric.numericRange(); }
            catch(IllegalArgumentException invalid) { violations.add("Invalid metric numeric range: "+metric.getMetricCode()+": "+invalid.getMessage()); }
        }
		return List.copyOf(new LinkedHashSet<>(violations));
	}

	private SemanticCatalogSnapshot normalize(Long projectId, Long projectVersionId,
			SemanticCatalogSnapshot requestedSnapshot) {
		if (requestedSnapshot == null) {
			throw new IllegalArgumentException("Semantic catalog payload is required");
		}
		LocalDateTime now = LocalDateTime.now();
		SemanticCatalogSnapshot snapshot = SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.models(safe(requestedSnapshot.getModels()))
			.columns(safe(requestedSnapshot.getColumns()))
			.metrics(safe(requestedSnapshot.getMetrics()))
			.dimensions(safe(requestedSnapshot.getDimensions()))
			.relationships(safe(requestedSnapshot.getRelationships()))
			.grains(safe(requestedSnapshot.getGrains()))
			.enumValues(safe(requestedSnapshot.getEnumValues()))
			.rules(safe(requestedSnapshot.getRules()))
            .sharedDefinitions(safe(requestedSnapshot.getSharedDefinitions()))
            .modelBindings(safe(requestedSnapshot.getModelBindings()))
            .enumDictionaries(safe(requestedSnapshot.getEnumDictionaries()))
			.build();
		snapshot.getModels().forEach(model -> normalizeAsset(model, projectId, projectVersionId, now));
		snapshot.getColumns().forEach(column -> normalizeAsset(column, projectId, projectVersionId, now));
		snapshot.getMetrics().forEach(metric -> normalizeAsset(metric, projectId, projectVersionId, now));
		snapshot.getDimensions().forEach(dimension -> normalizeAsset(dimension, projectId, projectVersionId, now));
		snapshot.getRelationships()
			.forEach(relationship -> normalizeAsset(relationship, projectId, projectVersionId, now));
		snapshot.getGrains().forEach(grain -> normalizeAsset(grain, projectId, projectVersionId, now));
		snapshot.getEnumValues().forEach(value -> normalizeAsset(value, projectId, projectVersionId, now));
		snapshot.getRules().forEach(rule -> normalizeAsset(rule, projectId, projectVersionId, now));
		return snapshot;
	}

	private void normalizeAsset(Object asset, Long projectId, Long projectVersionId, LocalDateTime now) {
		if (asset instanceof SemanticCatalogSnapshot.Model model) {
			model.setProjectId(projectId);
			model.setProjectVersionId(projectVersionId);
			model.setStatus(defaultStatus(model.getStatus()));
			model.setCreateTime(now);
			model.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Column column) {
			column.setProjectId(projectId);
			column.setProjectVersionId(projectVersionId);
			column.setStatus(defaultStatus(column.getStatus()));
			column.setCreateTime(now);
			column.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Metric metric) {
			metric.setProjectId(projectId);
			metric.setProjectVersionId(projectVersionId);
			metric.setStatus(defaultStatus(metric.getStatus()));
			metric.setCreateTime(now);
			metric.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Dimension dimension) {
			dimension.setProjectId(projectId);
			dimension.setProjectVersionId(projectVersionId);
			dimension.setStatus(defaultStatus(dimension.getStatus()));
			dimension.setCreateTime(now);
			dimension.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Relationship relationship) {
			relationship.setProjectId(projectId);
			relationship.setProjectVersionId(projectVersionId);
			relationship.setStatus(defaultStatus(relationship.getStatus()));
			relationship.setCreateTime(now);
			relationship.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Grain grain) {
			grain.setProjectId(projectId);
			grain.setProjectVersionId(projectVersionId);
			grain.setStatus(defaultStatus(grain.getStatus()));
			grain.setCreateTime(now);
			grain.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.EnumValue value) {
			value.setProjectId(projectId);
			value.setProjectVersionId(projectVersionId);
			value.setStatus(defaultStatus(value.getStatus()));
			value.setCreateTime(now);
			value.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Rule rule) {
			rule.setProjectId(projectId);
			rule.setProjectVersionId(projectVersionId);
			rule.setStatus(defaultStatus(rule.getStatus()));
			rule.setCreateTime(now);
			rule.setUpdateTime(now);
		}
	}

	private void validateRequiredModelFields(List<SemanticCatalogSnapshot.Model> models, List<String> violations) {
		for (SemanticCatalogSnapshot.Model model : models) {
            if(model.getSourceJson()!=null) {
                try {
                    var source=cn.lgs.semevosql.semantic.domain.GovernedModelSource.parse(model.getSourceJson());
                    var base=source.tables().stream().filter(t->t.alias().equals(source.base())).findFirst().orElseThrow();
                    if(!model.getPhysicalTable().equals(base.schema()+"."+base.table()))violations.add("Source base differs from physicalTable");
                } catch(IllegalArgumentException invalid){violations.add("Invalid governed source: "+model.getModelCode());}
            }
			if (!hasText(model.getModelCode())) {
				violations.add("enabled model is missing modelCode");
			}
			if (!hasText(model.getPhysicalTable())) {
				violations.add("enabled model is missing physicalTable: " + model.getModelCode());
			}
			if (model.getDatasourceId() == null) {
				violations.add("enabled model is missing datasourceId: " + model.getModelCode());
			}
		}
	}

	private void validateModelReferences(SemanticCatalogSnapshot snapshot, Set<String> modelCodes,
			List<String> violations) {
		Map<String, Collection<String>> references = new HashMap<>();
		references.put("column",
				snapshot.getColumns().stream().map(SemanticCatalogSnapshot.Column::getModelCode).toList());
		references.put("metric",
				snapshot.getMetrics().stream().map(SemanticCatalogSnapshot.Metric::getModelCode).toList());
		references.put("dimension",
				snapshot.getDimensions().stream().map(SemanticCatalogSnapshot.Dimension::getModelCode).toList());
		references.put("grain",
				snapshot.getGrains().stream().map(SemanticCatalogSnapshot.Grain::getModelCode).toList());
		references.put("enumValue",
				snapshot.getEnumValues().stream().map(SemanticCatalogSnapshot.EnumValue::getModelCode).toList());
		for (Map.Entry<String, Collection<String>> entry : references.entrySet()) {
			for (String modelCode : entry.getValue()) {
				if (!modelCodes.contains(modelCode)) {
					violations.add(entry.getKey() + " references missing or disabled model: " + modelCode);
				}
			}
		}
		for (SemanticCatalogSnapshot.Rule rule : snapshot.getRules()) {
			if (hasText(rule.getModelCode()) && !modelCodes.contains(rule.getModelCode())) {
				violations.add("rule references missing or disabled model: " + rule.getModelCode());
			}
		}
	}

	private void validateColumnReferences(SemanticCatalogSnapshot snapshot, Set<String> columnKeys,
			List<String> violations) {
		for (SemanticCatalogSnapshot.Dimension dimension : snapshot.getDimensions()) {
			if (hasText(dimension.getColumnName())
					&& !columnKeys.contains(key(dimension.getModelCode(), dimension.getColumnName()))) {
				violations.add("dimension references missing column: " + dimension.getDimensionCode());
			}
			if (!hasText(dimension.getColumnName()) && !hasText(dimension.getExpression())) {
				violations.add("dimension requires columnName or expression: " + dimension.getDimensionCode());
			}
		}
		for (SemanticCatalogSnapshot.Metric metric : snapshot.getMetrics()) {
			if (hasText(metric.getTimeColumn())
					&& !columnKeys.contains(key(metric.getModelCode(), metric.getTimeColumn()))) {
				violations.add("metric references missing time column: " + metric.getMetricCode());
			}
		}
		for (SemanticCatalogSnapshot.EnumValue value : snapshot.getEnumValues()) {
			if (!columnKeys.contains(key(value.getModelCode(), value.getColumnName()))) {
				violations
					.add("enum value references missing column: " + value.getModelCode() + "." + value.getColumnName());
			}
		}
		for (SemanticCatalogSnapshot.Grain grain : snapshot.getGrains()) {
			for (String columnName : splitColumns(grain.getKeyColumns())) {
				if (!columnKeys.contains(key(grain.getModelCode(), columnName))) {
					violations.add("grain references missing key column: " + grain.getGrainCode() + "." + columnName);
				}
			}
		}
	}

	private void validateRelationships(SemanticCatalogSnapshot snapshot, Set<String> modelCodes,
			List<String> violations) {
		for (SemanticCatalogSnapshot.Relationship relationship : snapshot.getRelationships()) {
			if (!modelCodes.contains(relationship.getSourceModelCode())
					|| !modelCodes.contains(relationship.getTargetModelCode())) {
				violations
					.add("relationship references missing or disabled model: " + relationship.getRelationshipCode());
			}
			if (relationship.getCardinality() == null) {
				violations.add("relationship cardinality is required: " + relationship.getRelationshipCode());
			}
			if (!hasText(relationship.getJoinCondition())) {
				violations.add("relationship joinCondition is required: " + relationship.getRelationshipCode());
			}
		}
	}

	private void validateExpressions(SemanticCatalogSnapshot snapshot, List<String> violations) {
		for (SemanticCatalogSnapshot.Metric metric : snapshot.getMetrics()) {
			if (!hasText(metric.getMetricCode()) || !hasText(metric.getExpression())) {
				violations.add("metricCode and expression are required");
			}
		}
		for (SemanticCatalogSnapshot.Rule rule : snapshot.getRules()) {
			if (!hasText(rule.getRuleCode()) || !hasText(rule.getRuleType()) || !hasText(rule.getExpression())) {
				violations.add("ruleCode, ruleType and expression are required");
			}
		}
	}

	private boolean ruleAppliesToPlan(SemanticCatalogSnapshot.Rule rule, QueryCaseHints hints) {
		String ruleType = normalizeRuleType(rule.getRuleType());
		if (MANDATORY_GOVERNANCE_RULE_TYPES.contains(ruleType)) {
			return true;
		}
		return QUERY_SELECTABLE_RULE_TYPES.contains(ruleType) && hints.ruleCodes().contains(rule.getRuleCode());
	}

	private BusinessRuleFilterResult businessRuleFilters(SemanticCatalogSnapshot snapshot,
			List<SemanticBlueprint.RuleSelection> rules) {
		List<SemanticBlueprint.FilterSelection> filters = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		for (SemanticBlueprint.RuleSelection rule : safe(rules)) {
			String ruleType = normalizeRuleType(rule.getRuleType());
			if (!QUERY_SELECTABLE_RULE_TYPES.contains(ruleType) && !MANDATORY_GOVERNANCE_RULE_TYPES.contains(ruleType)) {
				continue;
			}
			String expression = Objects.toString(rule.getExpression(), "");
			Matcher setMatcher = GOVERNED_ENUM_SET_FILTER.matcher(expression);
			Matcher equalityMatcher = GOVERNED_ENUM_EQUALITY_FILTER.matcher(expression);
			String columnName;
			String operator;
			List<String> values;
			if (setMatcher.matches()) {
				columnName = setMatcher.group(1);
				operator = "IN";
				values = parseSqlStringLiterals(setMatcher.group(2));
			}
			else if (equalityMatcher.matches()) {
				columnName = equalityMatcher.group(1);
				operator = "EQ";
				values = List.of(equalityMatcher.group(2).replace("''", "'"));
			}
			else {
				if (requiresGovernedPredicateExpansion(ruleType)) {
					errors.add("Selected governed rule is not a supported typed enum predicate: " + rule.getRuleCode());
				}
				continue;
			}
			String modelCode = rule.getModelCode();
			if (!hasText(modelCode) || values.isEmpty()) {
				errors.add("Selected governed rule is missing a governed model or enum values: " + rule.getRuleCode());
				continue;
			}
			SemanticCatalogSnapshot.Column column = safe(snapshot.getColumns())
				.stream()
				.filter(candidate -> candidate.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(candidate -> modelCode.equals(candidate.getModelCode()) && columnName.equals(candidate.getColumnName()))
				.findFirst()
				.orElse(null);
			if (column == null || !Boolean.TRUE.equals(column.getAllowFilter())) {
				errors.add("Selected governed rule references a missing or non-filterable column: " + rule.getRuleCode());
				continue;
			}
			Set<String> allowedValues = safe(snapshot.getEnumValues())
				.stream()
				.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(value -> modelCode.equals(value.getModelCode()) && columnName.equals(value.getColumnName()))
				.map(SemanticCatalogSnapshot.EnumValue::getValueCode)
				.collect(Collectors.toSet());
			if (!allowedValues.containsAll(values)) {
				errors.add("Selected governed rule references enum values outside the published Catalog: " + rule.getRuleCode());
				continue;
			}
			filters.add(SemanticBlueprint.FilterSelection.builder()
				.modelCode(modelCode)
				.columnName(columnName)
				.expression(columnName)
				.operator(operator)
				.value("EQ".equals(operator) ? values.get(0) : List.copyOf(values))
				.valueType("ENUM")
				.required(true)
				.build());
		}
		return new BusinessRuleFilterResult(List.copyOf(filters), List.copyOf(errors));
	}

	private boolean requiresGovernedPredicateExpansion(String ruleType) {
		return MANDATORY_GOVERNANCE_RULE_TYPES.contains(ruleType) || QUERY_SELECTABLE_RULE_TYPES.contains(ruleType);
	}

	private List<String> parseSqlStringLiterals(String body) {
		List<String> values = new ArrayList<>();
		Matcher matcher = SQL_STRING_LITERAL.matcher(body);
		int position = 0;
		while (matcher.find()) {
			if (matcher.start() != position) {
				return List.of();
			}
			values.add(matcher.group(1).replace("''", "'"));
			position = matcher.end();
		}
		return position == body.length() ? List.copyOf(values) : List.of();
	}

	private List<SemanticBlueprint.FilterSelection> mergeFilters(List<SemanticBlueprint.FilterSelection> left,
			List<SemanticBlueprint.FilterSelection> right) {
		Map<String, SemanticBlueprint.FilterSelection> merged = new LinkedHashMap<>();
		for (SemanticBlueprint.FilterSelection filter : safe(left)) {
			merged.put(filterKey(filter), filter);
		}
		for (SemanticBlueprint.FilterSelection filter : safe(right)) {
			merged.putIfAbsent(filterKey(filter), filter);
		}
		return List.copyOf(merged.values());
	}

	private String filterKey(SemanticBlueprint.FilterSelection filter) {
		String base = key(filter.getModelCode(), filter.getColumnName());
		String operator = Objects.toString(filter.getOperator(), "").toUpperCase(Locale.ROOT);
		if ("EQ".equals(operator)) {
			return base + "|ENUM_SET|" + Objects.toString(filter.getValue(), "");
		}
		if ("IN".equals(operator) && filter.getValue() instanceof Collection<?> values) {
			return base + "|ENUM_SET|" + canonicalFilterValues(values);
		}
		return base + "|" + operator + "|" + Objects.toString(filter.getValue(), "");
	}

	private String canonicalFilterValues(Collection<?> values) {
		return values.stream().map(value -> Objects.toString(value, "")).sorted().collect(Collectors.joining("\u001f"));
	}

	private record BusinessRuleFilterResult(List<SemanticBlueprint.FilterSelection> filters, List<String> errors) {
	}

	private List<SemanticCatalogSnapshot.Dimension> withoutGeneratedDimensionDuplicates(
			List<SemanticCatalogSnapshot.Dimension> current, List<SemanticCatalogSnapshot.Dimension> patch) {
		Set<String> explicitBindings = safe(patch)
			.stream()
			.filter(dimension -> hasText(dimension.getColumnName()))
			.filter(dimension -> !isDatabaseScanEvidence(dimension.getEvidence()))
			.map(dimension -> key(dimension.getModelCode(), dimension.getColumnName()))
			.collect(Collectors.toSet());
		if (explicitBindings.isEmpty()) {
			return safe(current);
		}
		return safe(current)
			.stream()
			.filter(dimension -> !(isDatabaseScanEvidence(dimension.getEvidence())
					&& explicitBindings.contains(key(dimension.getModelCode(), dimension.getColumnName()))))
			.toList();
	}

	private List<SemanticCatalogSnapshot.Grain> withoutGeneratedGrainDuplicates(
			List<SemanticCatalogSnapshot.Grain> current, List<SemanticCatalogSnapshot.Grain> patch) {
		Set<String> explicitModels = safe(patch)
			.stream()
			.filter(grain -> !isDatabaseScanEvidence(grain.getEvidence()))
			.map(SemanticCatalogSnapshot.Grain::getModelCode)
			.filter(this::hasText)
			.collect(Collectors.toSet());
		if (explicitModels.isEmpty()) {
			return safe(current);
		}
		return safe(current)
			.stream()
			.filter(grain -> !(isDatabaseScanEvidence(grain.getEvidence())
					&& explicitModels.contains(grain.getModelCode())))
			.toList();
	}

	private boolean isDatabaseScanEvidence(String evidence) {
		return hasText(evidence) && evidence.startsWith("database-schema-scan:");
	}

	private <T> List<T> mergeByKey(List<T> current, List<T> patch, Function<T, String> keyExtractor) {
		Map<String, T> merged = new LinkedHashMap<>();
		for (T item : safe(current)) {
			merged.put(keyExtractor.apply(item), item);
		}
		for (T item : safe(patch)) {
			merged.put(keyExtractor.apply(item), item);
		}
		return new ArrayList<>(merged.values());
	}

	private <T> void validateUnique(List<T> items, Function<T, String> keyExtractor, String message,
			List<String> violations) {
		Set<String> seen = new HashSet<>();
		for (T item : items) {
			String itemKey = keyExtractor.apply(item);
			if (hasText(itemKey) && !seen.add(itemKey)) {
				violations.add(message + ": " + itemKey);
			}
		}
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long projectVersionId) {
		SemanticProjectVersion version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + projectVersionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	private SemanticAssetStatus defaultStatus(SemanticAssetStatus status) {
		return status == null ? SemanticAssetStatus.ENABLED : status;
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}

	private List<String> splitColumns(String value) {
		if (!hasText(value)) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(this::hasText).toList();
	}

	private String key(String first, String second) {
		return first + "::" + second;
	}

	private String normalizeRuleType(String ruleType) {
		return Objects.toString(ruleType, "").trim().toUpperCase(Locale.ROOT);
	}

	private Set<String> strictEffectiveModelCodes(Set<String> recalledModelCodes,
			List<SemanticBlueprint.MetricSelection> metrics, List<SemanticBlueprint.DimensionSelection> dimensions,
			Set<String> enumFilterModelCodes, QueryCaseHints hints,
			List<SemanticCatalogSnapshot.Relationship> relationships) {
		Set<String> required = new LinkedHashSet<>();
		metrics.stream()
			.map(SemanticBlueprint.MetricSelection::getModelCode)
			.filter(this::hasText)
			.forEach(required::add);
		dimensions.stream()
			.map(SemanticBlueprint.DimensionSelection::getModelCode)
			.filter(this::hasText)
			.forEach(required::add);
		required.addAll(enumFilterModelCodes == null ? Set.of() : enumFilterModelCodes);
		required.addAll(hints.modelCodes());
		for (SemanticCatalogSnapshot.Relationship relationship : safe(relationships)) {
			if (relationship.getStatus() == SemanticAssetStatus.ENABLED
					&& hints.relationshipCodes().contains(relationship.getRelationshipCode())) {
				required.add(relationship.getSourceModelCode());
				required.add(relationship.getTargetModelCode());
			}
		}
		return required.isEmpty() ? Set.copyOf(recalledModelCodes) : Set.copyOf(required);
	}

	private List<SemanticCatalogSnapshot.Relationship> resolveRelationshipPath(Set<String> modelCodes,
			List<SemanticCatalogSnapshot.Relationship> relationships) {
		if (modelCodes.size() <= 1) {
			return List.of();
		}
		Map<String, List<SemanticCatalogSnapshot.Relationship>> adjacency = new HashMap<>();
		for (String modelCode : modelCodes) {
			adjacency.put(modelCode, new ArrayList<>());
		}
		for (SemanticCatalogSnapshot.Relationship relationship : relationships) {
			adjacency.computeIfAbsent(relationship.getSourceModelCode(), ignored -> new ArrayList<>())
				.add(relationship);
			adjacency.computeIfAbsent(relationship.getTargetModelCode(), ignored -> new ArrayList<>())
				.add(relationship);
		}
		adjacency.values()
			.forEach(edges -> edges
				.sort((left, right) -> left.getRelationshipCode().compareTo(right.getRelationshipCode())));

		String start = modelCodes.stream().sorted().findFirst().orElseThrow();
		Set<String> visited = new LinkedHashSet<>();
		Deque<String> pending = new ArrayDeque<>();
		List<SemanticCatalogSnapshot.Relationship> path = new ArrayList<>();
		visited.add(start);
		pending.add(start);
		while (!pending.isEmpty()) {
			String current = pending.removeFirst();
			for (SemanticCatalogSnapshot.Relationship relationship : adjacency.getOrDefault(current, List.of())) {
				String neighbor = current.equals(relationship.getSourceModelCode()) ? relationship.getTargetModelCode()
						: relationship.getSourceModelCode();
				if (modelCodes.contains(neighbor) && visited.add(neighbor)) {
					path.add(relationship);
					pending.addLast(neighbor);
				}
			}
		}
		return List.copyOf(path);
	}

	private SemanticBlueprint.ExpectedResultShape withScalarCalculation(SemanticBlueprint.ExpectedResultShape expected,
			String calculationExpression) {
		if (expected == null || !hasText(calculationExpression) || !calculationExpression.contains("=")) {
			return expected;
		}
		String output = calculationExpression.substring(0, calculationExpression.indexOf('=')).trim();
		if (!hasText(output)) {
			return expected;
		}
		Set<String> columns = new LinkedHashSet<>(expected.getColumns() == null ? List.of() : expected.getColumns());
		columns.add(output);
		return SemanticBlueprint.ExpectedResultShape.builder()
			.columns(List.copyOf(columns))
			.grain(expected.getGrain())
			.maxRows(expected.getMaxRows())
			.tabular(expected.getTabular())
			.chartable(expected.getChartable())
			.build();
	}

	private boolean isConnected(Set<String> modelCodes, List<SemanticCatalogSnapshot.Relationship> relationships,
			PlanningDecision multiSourceDecision, Set<String> selectedRelationshipCodes) {
		if (modelCodes.size() <= 1) {
			return true;
		}
		Map<String, Set<String>> adjacency = new HashMap<>();
		for (String modelCode : modelCodes) {
			adjacency.put(modelCode, new HashSet<>());
		}
		for (SemanticCatalogSnapshot.Relationship relationship : relationships) {
			adjacency.computeIfAbsent(relationship.getSourceModelCode(), ignored -> new HashSet<>())
				.add(relationship.getTargetModelCode());
			adjacency.computeIfAbsent(relationship.getTargetModelCode(), ignored -> new HashSet<>())
				.add(relationship.getSourceModelCode());
		}
		if (multiSourceDecision != null) {
			for (var relationship : multiSourceDecision.relationships()) {
				if (selectedRelationshipCodes == null
						|| !selectedRelationshipCodes.contains(relationship.getRelationshipCode())) {
					continue;
				}
				adjacency.computeIfAbsent(relationship.getLeftModelCode(), ignored -> new HashSet<>())
					.add(relationship.getRightModelCode());
				adjacency.computeIfAbsent(relationship.getRightModelCode(), ignored -> new HashSet<>())
					.add(relationship.getLeftModelCode());
			}
		}
		String start = modelCodes.iterator().next();
		Set<String> visited = new HashSet<>();
		Deque<String> pending = new ArrayDeque<>();
		pending.add(start);
		while (!pending.isEmpty()) {
			String current = pending.removeFirst();
			if (visited.add(current)) {
				pending.addAll(adjacency.getOrDefault(current, Set.of()));
			}
		}
		return visited.containsAll(modelCodes);
	}

	private List<SemanticCatalogSnapshot.Metric> selectMetricAssets(List<SemanticCatalogSnapshot.Metric> available,
			Set<String> selectedModelCodes, QueryCaseHints hints) {
		return available.stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> selectedModelCodes.contains(metric.getModelCode()))
			.filter(metric -> hints.metricCodes().contains(metric.getMetricCode()))
			.toList();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record PlanningRecall(List<String> physicalTables, List<RetrievalHit> hits) {

		public PlanningRecall {
			physicalTables = List.copyOf(physicalTables == null ? List.of() : physicalTables);
			hits = List.copyOf(hits == null ? List.of() : hits);
		}

		public static PlanningRecall empty() {
			return new PlanningRecall(List.of(), List.of());
		}
	}

}
