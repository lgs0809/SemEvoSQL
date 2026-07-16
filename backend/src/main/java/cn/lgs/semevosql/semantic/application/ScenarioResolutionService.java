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

import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticGapStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.semantic.domain.BusinessQueryRequirement;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import cn.lgs.semevosql.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.ScenarioResolution;
import cn.lgs.semevosql.semantic.domain.ScenarioResolution.Status;
import cn.lgs.semevosql.semantic.domain.ScenarioResolutionRepository;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

/**
 * Deterministically resolves DB-independent business scenarios against the current
 * catalog. It only auto-binds unique, strongly supported candidates. Ambiguous or missing
 * business requirements become scenario-scoped gaps instead of catalog-null-field gaps.
 */
@Service
@RequiredArgsConstructor
public class ScenarioResolutionService {

	public static final String GAP_PREFIX = "scenario-resolution:";

	private static final Set<String> SCENARIO_GAP_TYPES = Set.of("AMBIGUOUS_METRIC", "AMBIGUOUS_ENUM",
			"AMBIGUOUS_TIME_BINDING", "AMBIGUOUS_DIMENSION", "AMBIGUOUS_RELATIONSHIP", "AMBIGUOUS_AUTHORITY",
			"MISSING_REQUIRED_SEMANTIC");

	private static final int STRONG_AUTO_BIND_SCORE = 90;

	private static final Set<String> DERIVED_METRIC_AGGREGATIONS = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT",
			"COUNT_DISTINCT");

	private static final Set<String> RELATIONSHIP_JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");

	private static final Pattern DERIVED_METRIC_TOKEN = Pattern
		.compile("\\s*([A-Za-z_][A-Za-z0-9_$]*|\\d+(?:\\.\\d+)?|[()+\\-*/])\\s*");

	private final BusinessQueryScenarioRepository scenarioRepository;

	private final ScenarioResolutionRepository resolutionRepository;

	private final SemanticCatalogRepository catalogRepository;

	private final ProjectEvidenceRepository evidenceRepository;

	private final SemanticProjectRepository projectRepository;

	private final ScenarioBindingMatcher bindingMatcher;

	private final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

	public ResolutionCoverage refreshVersion(Long projectId, Long projectVersionId) {
		List<BusinessQueryScenario> active = scenarioRepository.findActiveByVersion(projectVersionId);
		Map<Long, ScenarioResolution> resolutions = new LinkedHashMap<>();
		for (BusinessQueryScenario scenario : active) {
			ScenarioResolution resolution = resolveScenario(scenario);
			resolutions.put(scenario.getId(), resolution);
		}
		reconcileScenarioGaps(projectId, projectVersionId, active, resolutions);
		return coverage(active, resolutions);
	}

	public ScenarioResolution refreshScenario(Long scenarioId) {
		BusinessQueryScenario scenario = scenarioRepository.findById(scenarioId)
			.orElseThrow(() -> new IllegalArgumentException("Business query scenario not found: " + scenarioId));
		return resolveScenario(scenario);
	}

	public ScenarioResolution get(Long scenarioId) {
		return resolutionRepository.findByScenario(scenarioId).orElseGet(() -> refreshScenario(scenarioId));
	}

	public boolean supportsGapType(String gapType) {
		return SCENARIO_GAP_TYPES.contains(gapType);
	}

	public void applyGapResolution(SemanticGap gap, String answer) {
		if (gap == null || !supportsGapType(gap.getGapType())) {
			throw new IllegalArgumentException("Scenario resolution gap is required");
		}
		JsonNode gapEvidence = readTree(gap.getEvidence());
		JsonNode response = readTree(answer);
		String choice = requiredText(response, "choice");
		String other = null;
		BindingCandidate selected = null;
		if ("其他".equals(choice)) {
			other = optionalText(response, "other");
			if (!hasText(other)) {
				throw new IllegalArgumentException("选择“其他”时必须说明实际业务含义");
			}
			if ("MISSING_REQUIRED_SEMANTIC".equals(gap.getGapType())) {
				applyMissingSemanticDefinition(gap, gapEvidence, response, other);
				ManualBinding createdBinding = createdDefinitionBinding(response.path("definition"), other);
				for (GapTargetRef target : gapTargets(gapEvidence)) {
					ScenarioResolution current = get(target.scenarioId());
					Map<String, ManualBinding> manual = readManualBindings(current.getManualBindingsJson());
					manual.put(target.requirementKey(), createdBinding);
					resolutionRepository.updateManualBindings(target.scenarioId(), json(manual));
					refreshScenario(target.scenarioId());
				}
				return;
			}
		}
		else {
			selected = candidates(gapEvidence).stream()
				.filter(candidate -> choice.equals(candidate.optionLabel()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Scenario candidate is no longer valid: " + choice));
		}
		for (GapTargetRef target : gapTargets(gapEvidence)) {
			ScenarioResolution current = get(target.scenarioId());
			Map<String, ManualBinding> manual = readManualBindings(current.getManualBindingsJson());
			if (selected == null) {
				manual.put(target.requirementKey(), new ManualBinding(null, null, other, true));
			}
			else {
				manual.put(target.requirementKey(),
						new ManualBinding(selected.assetType(), selected.assetKey(), selected.optionLabel(), false));
			}
			resolutionRepository.updateManualBindings(target.scenarioId(), json(manual));
			refreshScenario(target.scenarioId());
		}
	}

	public List<String> unresolvedCoreViolations(Long projectId, Long projectVersionId) {
		ResolutionCoverage result = refreshVersion(projectId, projectVersionId);
		List<String> violations = new ArrayList<>();
		if (result.coreAmbiguous() > 0) {
			violations.add("ACTIVE CORE scenarios still ambiguous: " + result.coreAmbiguous());
		}
		if (result.coreUnsupported() > 0) {
			violations.add("ACTIVE CORE scenarios unsupported: " + result.coreUnsupported());
		}
		return List.copyOf(violations);
	}

	private ScenarioResolution resolveScenario(BusinessQueryScenario scenario) {
		BusinessQueryRequirement requirement = readRequirement(scenario.getRequirementJson());
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(scenario.getProjectId(),
				scenario.getProjectVersionId());
		List<ProjectEvidence> evidence = evidenceRepository.findActiveEvidenceByVersion(scenario.getProjectVersionId());
		Map<String, ManualBinding> manual = resolutionRepository.findByScenario(scenario.getId())
			.map(ScenarioResolution::getManualBindingsJson)
			.map(this::readManualBindings)
			.orElseGet(LinkedHashMap::new);

		List<ResolvedBinding> resolved = new ArrayList<>();
		List<RequirementProbe> candidateBindings = new ArrayList<>();
		List<UnresolvedRequirement> unresolved = new ArrayList<>();
		Set<String> requiredModels = new LinkedHashSet<>();
		for (String capability : unsupportedCapabilities(requirement)) {
			unresolved.add(new UnresolvedRequirement(requirementKey("CAPABILITY", capability), "CAPABILITY", capability,
					"UNSUPPORTED_QUERY_CAPABILITY", "当前 SemEvoSQL 确定性分析链路尚不支持该业务能力"));
		}

		for (String measure : requirement.measures()) {
			resolveProbe(scenario, probe("MEASURE", measure, bindingMatcher.metricCandidates(measure, catalog, evidence)), manual, catalog,
					resolved, candidateBindings, unresolved, requiredModels);
		}
		for (String attribute : distinct(requirement.attributes(), requirement.groupings())) {
			resolveProbe(scenario, probe("DIMENSION", attribute, bindingMatcher.dimensionCandidates(attribute, catalog, evidence)),
					manual, catalog, resolved, candidateBindings, unresolved, requiredModels);
		}
		for (String filter : requirement.filters()) {
			resolveProbe(scenario, probe("FILTER", filter, bindingMatcher.filterCandidates(filter, catalog, evidence)), manual, catalog,
					resolved, candidateBindings, unresolved, requiredModels);
		}
		for (String sorting : requirement.sorting()) {
			List<BindingCandidate> candidates = new ArrayList<>();
			candidates.addAll(bindingMatcher.metricCandidates(sorting, catalog, evidence));
			candidates.addAll(bindingMatcher.dimensionCandidates(sorting, catalog, evidence));
			resolveProbe(scenario, probe("SORTING", sorting, bindingMatcher.bestCandidates(candidates)), manual, catalog, resolved,
					candidateBindings, unresolved, requiredModels);
		}

		if (!requirement.timeConstraints().isEmpty()) {
			String timeRequirement = String.join("；", requirement.timeConstraints());
			resolveProbe(scenario,
					probe("TIME", timeRequirement, bindingMatcher.timeCandidates(catalog, resolved, requiredModels, evidence, timeRequirement)),
					manual, catalog, resolved, candidateBindings, unresolved, requiredModels);
		}

		if (requiredModels.size() > 1) {
			RequirementProbe relationship = probe("RELATIONSHIP", String.join("、", requiredModels), List.of());
			ManualBinding selectedRelationship = manual.get(relationship.requirementKey());
			if (!connected(requiredModels, catalog.getRelationships())) {
				candidateBindings.add(relationship);
				unresolved.add(new UnresolvedRequirement(relationship.requirementKey(), relationship.requirementType(),
						relationship.requirementText(), "MISSING_REQUIRED_SEMANTIC", "所需业务资产跨模型但当前没有可验证的关系路径"));
			}
			else if (selectedRelationship != null && "RELATIONSHIP".equals(selectedRelationship.assetType())) {
				BindingCandidate selectedCandidate = manualCatalogCandidate(selectedRelationship, catalog);
				if (selectedCandidate == null) {
					candidateBindings.add(relationship);
					unresolved.add(new UnresolvedRequirement(relationship.requirementKey(), relationship.requirementType(),
							relationship.requirementText(), "MISSING_REQUIRED_SEMANTIC",
							"用户确认的关系已不存在或未启用：" + selectedRelationship.assetKey()));
				}
				else {
					addResolved(relationship, selectedCandidate, "MANUAL", resolved, requiredModels);
				}
			}
		}

		Status status = unresolved.stream().anyMatch(value -> "UNSUPPORTED_QUERY_CAPABILITY".equals(value.reason()))
				? Status.UNSUPPORTED : unresolved.stream().anyMatch(value -> value.reason().startsWith("AMBIGUOUS"))
						? Status.AMBIGUOUS : unresolved.isEmpty() ? Status.RESOLVED : Status.UNSUPPORTED;
		Map<String, Object> resolutionEvidence = new LinkedHashMap<>();
		resolutionEvidence.put("scenarioCode", scenario.getScenarioCode());
		resolutionEvidence.put("businessName", scenario.getBusinessName());
		resolutionEvidence.put("sourceMaterialId", scenario.getSourceMaterialId());
		resolutionEvidence.put("sourceAttemptId", scenario.getSourceAttemptId());
		resolutionEvidence.put("sourceLocation", Objects.toString(scenario.getSourceLocation(), ""));
		String hash = sha256(json(Map.of("resolved", resolved, "candidates", candidateBindings, "unresolved",
				unresolved, "manual", manual)));
		LocalDateTime now = LocalDateTime.now();
		ScenarioResolution result = ScenarioResolution.builder()
			.scenarioId(scenario.getId())
			.projectId(scenario.getProjectId())
			.projectVersionId(scenario.getProjectVersionId())
			.status(status)
			.resolvedBindingsJson(json(resolved))
			.candidateBindingsJson(json(candidateBindings))
			.unresolvedRequirementsJson(json(unresolved))
			.evidenceJson(json(resolutionEvidence))
			.manualBindingsJson(json(manual))
			.resolutionHash(hash)
			.revision(0L)
			.createTime(now)
			.updateTime(now)
			.build();
		resolutionRepository.save(result);
		return result;
	}

	private void resolveProbe(BusinessQueryScenario scenario, RequirementProbe probe, Map<String, ManualBinding> manual,
			SemanticCatalogSnapshot catalog, List<ResolvedBinding> resolved, List<RequirementProbe> candidates,
			List<UnresolvedRequirement> unresolved, Set<String> requiredModels) {
		candidates.add(probe);
		ManualBinding selected = manual.get(probe.requirementKey());
		if (selected != null && selected.other()) {
			unresolved.add(new UnresolvedRequirement(probe.requirementKey(), probe.requirementType(),
					probe.requirementText(), "MISSING_REQUIRED_SEMANTIC", "用户指定了其他业务含义：" + selected.optionLabel()));
			return;
		}
		if (selected != null) {
			BindingCandidate manualCandidate = probe.candidates()
				.stream()
				.filter(candidate -> Objects.equals(candidate.assetType(), selected.assetType())
						&& Objects.equals(candidate.assetKey(), selected.assetKey()))
				.findFirst()
				.orElseGet(() -> manualCatalogCandidate(selected, catalog));
			if (manualCandidate != null) {
				addResolved(probe, manualCandidate, "MANUAL", resolved, requiredModels);
				return;
			}
		}
		if (probe.candidates().size() == 1 && probe.candidates().get(0).score() >= STRONG_AUTO_BIND_SCORE) {
			addResolved(probe, probe.candidates().get(0), "DETERMINISTIC", resolved, requiredModels);
			return;
		}
		boolean weakSingleCandidate = probe.candidates().size() == 1;
		String reason = probe.candidates().isEmpty() || weakSingleCandidate ? missingReason(probe.requirementType())
				: ambiguousReason(probe.requirementType());
		String detail = probe.candidates().isEmpty() ? "没有找到足够明确的现有语义资产"
				: weakSingleCandidate ? "仅找到低置信度相似候选，不能把相似名称自动当成确定业务口径"
						: "存在多个同等强度的候选，不能安全自动选择";
		unresolved.add(new UnresolvedRequirement(probe.requirementKey(), probe.requirementType(), probe.requirementText(),
				reason, detail));
	}

	private BindingCandidate manualCatalogCandidate(ManualBinding selected, SemanticCatalogSnapshot catalog) {
		if (selected == null || catalog == null || !hasText(selected.assetType()) || !hasText(selected.assetKey())) {
			return null;
		}
		return switch (selected.assetType()) {
			case "METRIC" -> catalog.getMetrics()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> selected.assetKey().equals(asset.getMetricCode()))
				.findFirst()
				.map(asset -> new BindingCandidate("METRIC", asset.getMetricCode(),
						firstText(asset.getBusinessName(), asset.getMetricCode()), asset.getModelCode(),
						firstText(selected.optionLabel(), asset.getBusinessName(), asset.getMetricCode()), 100,
						"manual binding to enabled Catalog metric"))
				.orElse(null);
			case "DIMENSION" -> catalog.getDimensions()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> selected.assetKey().equals(asset.getDimensionCode()))
				.findFirst()
				.map(asset -> new BindingCandidate("DIMENSION", asset.getDimensionCode(),
						firstText(asset.getBusinessName(), asset.getDimensionCode()), asset.getModelCode(),
						firstText(selected.optionLabel(), asset.getBusinessName(), asset.getDimensionCode()), 100,
						"manual binding to enabled Catalog dimension"))
				.orElse(null);
			case "RULE" -> catalog.getRules()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> selected.assetKey().equals(asset.getRuleCode()))
				.findFirst()
				.map(asset -> new BindingCandidate("RULE", asset.getRuleCode(),
						firstText(asset.getBusinessName(), asset.getRuleCode()), asset.getModelCode(),
						firstText(selected.optionLabel(), asset.getBusinessName(), asset.getRuleCode()), 100,
						"manual binding to enabled Catalog rule"))
				.orElse(null);
			case "ENUM_VALUE" -> catalog.getEnumValues()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> selected.assetKey().equals(enumKey(asset)))
				.findFirst()
				.map(asset -> new BindingCandidate("ENUM_VALUE", enumKey(asset),
						firstText(asset.getBusinessName(), asset.getValueCode()), asset.getModelCode(),
						firstText(selected.optionLabel(), asset.getBusinessName(), asset.getValueCode()), 100,
						"manual binding to enabled Catalog enum value"))
				.orElse(null);
			case "TIME_COLUMN" -> catalog.getColumns()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> asset.getRole() == SemanticColumnRole.TIME)
				.filter(asset -> selected.assetKey().equals(asset.getModelCode() + ":" + asset.getColumnName()))
				.findFirst()
				.map(asset -> new BindingCandidate("TIME_COLUMN", selected.assetKey(),
						firstText(asset.getBusinessName(), asset.getColumnName()), asset.getModelCode(),
						firstText(selected.optionLabel(), asset.getBusinessName(), asset.getColumnName()), 100,
						"manual binding to enabled Catalog time column"))
				.orElse(null);
			case "RELATIONSHIP" -> catalog.getRelationships()
				.stream()
				.filter(asset -> asset.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(asset -> selected.assetKey().equals(asset.getRelationshipCode()))
				.findFirst()
				.map(asset -> new BindingCandidate("RELATIONSHIP", asset.getRelationshipCode(), asset.getRelationshipCode(),
						asset.getSourceModelCode(), firstText(selected.optionLabel(), asset.getRelationshipCode()), 100,
						"manual binding to enabled Catalog relationship"))
				.orElse(null);
			default -> null;
		};
	}

	private void addResolved(RequirementProbe probe, BindingCandidate candidate, String source,
			List<ResolvedBinding> resolved, Set<String> requiredModels) {
		resolved.add(new ResolvedBinding(probe.requirementKey(), probe.requirementType(), probe.requirementText(),
				candidate.assetType(), candidate.assetKey(), candidate.businessName(), candidate.modelCode(), source));
		if (hasText(candidate.modelCode())) {
			requiredModels.add(candidate.modelCode());
		}
	}

	private ManualBinding createdDefinitionBinding(JsonNode definition, String other) {
		String definitionType = requiredText(definition, "type");
		if ("EXISTING_ASSET".equals(definitionType)) {
			return new ManualBinding(requiredText(definition, "assetType"), requiredText(definition, "assetKey"), other,
					false);
		}
		if ("DERIVED_METRIC".equals(definitionType)) {
			String metricCode = requiredText(definition, "metricCode");
			String businessName = optionalText(definition, "businessName");
			return new ManualBinding("METRIC", metricCode, hasText(businessName) ? businessName : other, false);
		}
		if ("ENUM_SET_FILTER".equals(definitionType)) {
			String ruleCode = requiredText(definition, "ruleCode");
			String businessName = optionalText(definition, "businessName");
			return new ManualBinding("RULE", ruleCode, hasText(businessName) ? businessName : other, false);
		}
		if ("RELATIONSHIP".equals(definitionType)) {
			String relationshipCode = requiredText(definition, "relationshipCode");
			return new ManualBinding("RELATIONSHIP", relationshipCode, relationshipCode, false);
		}
		throw new IllegalArgumentException("Unsupported missing semantic definition type: " + definitionType);
	}

	private void validateExistingAssetDefinition(String requirementType, SemanticGap gap, JsonNode definition) {
		String assetType = requiredText(definition, "assetType");
		String assetKey = requiredText(definition, "assetKey");
		Set<String> allowedTypes = switch (requirementType) {
			case "MEASURE" -> Set.of("METRIC");
			case "DIMENSION" -> Set.of("DIMENSION");
			case "FILTER" -> Set.of("DIMENSION", "RULE", "ENUM_VALUE");
			case "TIME" -> Set.of("TIME_COLUMN");
			case "SORTING" -> Set.of("METRIC", "DIMENSION");
			case "RELATIONSHIP" -> Set.of("RELATIONSHIP");
			default -> Set.of();
		};
		if (!allowedTypes.contains(assetType)) {
			throw new IllegalArgumentException(
					"Existing Catalog asset type " + assetType + " is not valid for requirement type " + requirementType);
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(gap.getProjectId(), gap.getProjectVersionId());
		if (manualCatalogCandidate(new ManualBinding(assetType, assetKey, assetKey, false), catalog) == null) {
			throw new IllegalArgumentException("Existing Catalog asset is missing or disabled: " + assetType + ":" + assetKey);
		}
	}

	private void applyMissingSemanticDefinition(SemanticGap gap, JsonNode gapEvidence, JsonNode response,
			String other) {
		String requirementType = requiredText(gapEvidence, "requirementType");
		JsonNode definition = response.path("definition");
		if (definition.isMissingNode() || !definition.isObject()) {
			throw new IllegalArgumentException("缺失语义必须提供可验证的 definition，不能只提交自由文本");
		}
		String definitionType = requiredText(definition, "type");
		if ("EXISTING_ASSET".equals(definitionType)) {
			validateExistingAssetDefinition(requirementType, gap, definition);
			return;
		}
		if ("MEASURE".equals(requirementType) && "DERIVED_METRIC".equals(definitionType)) {
			applyDerivedMetricDefinition(gap, response, definition, other);
			return;
		}
		if ("RELATIONSHIP".equals(requirementType) && "RELATIONSHIP".equals(definitionType)) {
			applyRelationshipDefinition(gap, response, definition);
			return;
		}
		if (!"FILTER".equals(requirementType) || !"ENUM_SET_FILTER".equals(definitionType)) {
			throw new IllegalArgumentException(
					"Unsupported structured definition for missing semantic requirement type " + requirementType);
		}
		String modelCode = requiredText(definition, "modelCode");
		String columnName = requiredText(definition, "columnName");
		String ruleCode = requiredText(definition, "ruleCode");
		if (!ruleCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
			throw new IllegalArgumentException("ruleCode must be a stable identifier using letters, digits, and underscores");
		}
		List<String> valueCodes = new ArrayList<>();
		for (JsonNode item : definition.path("valueCodes")) {
			String value = item.asText("").trim();
			if (!value.isBlank() && !valueCodes.contains(value)) {
				valueCodes.add(value);
			}
		}
		if (valueCodes.isEmpty()) {
			throw new IllegalArgumentException("ENUM_SET_FILTER definition requires at least one valueCode");
		}

		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(gap.getProjectId(), gap.getProjectVersionId());
		boolean modelExists = catalog.getModels()
			.stream()
			.anyMatch(model -> model.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(model.getModelCode()));
		if (!modelExists) {
			throw new IllegalArgumentException("Semantic model not found for Grill-Me definition: " + modelCode);
		}
		boolean columnExists = catalog.getColumns()
			.stream()
			.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(column.getModelCode())
					&& columnName.equals(column.getColumnName()));
		if (!columnExists) {
			throw new IllegalArgumentException(
					"Semantic column not found for Grill-Me definition: " + modelCode + "." + columnName);
		}
		Set<String> availableValues = catalog.getEnumValues()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(value.getModelCode())
					&& columnName.equals(value.getColumnName()))
			.map(SemanticCatalogSnapshot.EnumValue::getValueCode)
			.collect(Collectors.toSet());
		List<String> unknownValues = valueCodes.stream().filter(value -> !availableValues.contains(value)).toList();
		if (!unknownValues.isEmpty()) {
			throw new IllegalArgumentException(
					"Grill-Me definition references unknown enum values: " + String.join(", ", unknownValues));
		}

		String businessName = optionalText(definition, "businessName");
		if (!hasText(businessName)) {
			businessName = other;
		}
		String expression = columnName + " IN (" + valueCodes.stream().map(this::sqlStringLiteral).collect(Collectors.joining(","))
				+ ")";
		LocalDateTime now = LocalDateTime.now();
		SemanticCatalogSnapshot.Rule rule = SemanticCatalogSnapshot.Rule.builder()
			.projectId(gap.getProjectId())
			.projectVersionId(gap.getProjectVersionId())
			.modelCode(modelCode)
			.ruleCode(ruleCode)
			.ruleType("BUSINESS_FILTER")
			.businessName(businessName)
			.expression(expression)
			.severity("INFO")
			.description("Grill-Me confirmed enum-set business filter")
			.evidence(answerEvidence(gap, response))
			.status(SemanticAssetStatus.ENABLED)
			.createTime(now)
			.updateTime(now)
			.build();
		catalog.getRules().removeIf(existing -> ruleCode.equals(existing.getRuleCode()));
		catalog.getRules().add(rule);
		catalogRepository.replaceCatalog(catalog);
	}

	private void applyRelationshipDefinition(SemanticGap gap, JsonNode response, JsonNode definition) {
		String relationshipCode = requiredText(definition, "relationshipCode");
		if (!relationshipCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
			throw new IllegalArgumentException(
					"relationshipCode must be a stable identifier using letters, digits, and underscores");
		}
		String sourceModelCode = requiredText(definition, "sourceModelCode");
		String sourceColumn = requiredText(definition, "sourceColumn");
		String targetModelCode = requiredText(definition, "targetModelCode");
		String targetColumn = requiredText(definition, "targetColumn");
		if (sourceModelCode.equals(targetModelCode)) {
			throw new IllegalArgumentException("Relationship must connect two different semantic models");
		}
		String joinType = requiredText(definition, "joinType").toUpperCase(Locale.ROOT);
		if (!RELATIONSHIP_JOIN_TYPES.contains(joinType)) {
			throw new IllegalArgumentException("Unsupported relationship joinType: " + joinType);
		}
		RelationshipCardinality cardinality;
		try {
			cardinality = RelationshipCardinality.valueOf(requiredText(definition, "cardinality").toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported relationship cardinality", ex);
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(gap.getProjectId(), gap.getProjectVersionId());
		requireEnabledModel(catalog, sourceModelCode);
		requireEnabledModel(catalog, targetModelCode);
		requireEnabledColumn(catalog, sourceModelCode, sourceColumn);
		requireEnabledColumn(catalog, targetModelCode, targetColumn);
		LocalDateTime now = LocalDateTime.now();
		SemanticCatalogSnapshot.Relationship relationship = SemanticCatalogSnapshot.Relationship.builder()
			.projectId(gap.getProjectId())
			.projectVersionId(gap.getProjectVersionId())
			.relationshipCode(relationshipCode)
			.sourceModelCode(sourceModelCode)
			.targetModelCode(targetModelCode)
			.cardinality(cardinality)
			.joinType(joinType)
			.joinCondition(sourceModelCode + "." + sourceColumn + " = " + targetModelCode + "." + targetColumn)
			.description(firstText(optionalText(definition, "description"), "Grill-Me confirmed semantic relationship"))
			.evidence(answerEvidence(gap, response))
			.status(SemanticAssetStatus.ENABLED)
			.createTime(now)
			.updateTime(now)
			.build();
		catalog.getRelationships().removeIf(existing -> relationshipCode.equals(existing.getRelationshipCode()));
		catalog.getRelationships().add(relationship);
		catalogRepository.replaceCatalog(catalog);
	}

	private void requireEnabledModel(SemanticCatalogSnapshot catalog, String modelCode) {
		boolean exists = catalog.getModels()
			.stream()
			.anyMatch(model -> model.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(model.getModelCode()));
		if (!exists) {
			throw new IllegalArgumentException("Semantic model not found for Grill-Me relationship: " + modelCode);
		}
	}

	private void requireEnabledColumn(SemanticCatalogSnapshot catalog, String modelCode, String columnName) {
		if (!columnName.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
			throw new IllegalArgumentException("Unsafe relationship column identifier: " + columnName);
		}
		boolean exists = catalog.getColumns()
			.stream()
			.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(column.getModelCode())
					&& columnName.equals(column.getColumnName()));
		if (!exists) {
			throw new IllegalArgumentException("Governed relationship column not found: " + modelCode + "." + columnName);
		}
	}

	private void applyDerivedMetricDefinition(SemanticGap gap, JsonNode response, JsonNode definition, String other) {
		String metricCode = requiredText(definition, "metricCode");
		if (!metricCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
			throw new IllegalArgumentException("metricCode must be a stable identifier using letters, digits, and underscores");
		}
		String modelCode = requiredText(definition, "modelCode");
		String expression = requiredText(definition, "expression");
		String aggregation = requiredText(definition, "aggregation").toUpperCase(Locale.ROOT);
		if (!DERIVED_METRIC_AGGREGATIONS.contains(aggregation)) {
			throw new IllegalArgumentException(
					"DERIVED_METRIC aggregation must be one of SUM, AVG, MIN, MAX, COUNT, COUNT_DISTINCT");
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(gap.getProjectId(), gap.getProjectVersionId());
		boolean modelExists = catalog.getModels()
			.stream()
			.anyMatch(model -> model.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(model.getModelCode()));
		if (!modelExists) {
			throw new IllegalArgumentException("Semantic model not found for Grill-Me metric definition: " + modelCode);
		}
		Set<String> expressionColumns = Set.of("COUNT", "COUNT_DISTINCT").contains(aggregation)
				? validateCountMetricExpression(expression, modelCode, catalog)
				: validateDerivedMetricExpression(expression, modelCode, catalog);
		if (expressionColumns.isEmpty()) {
			throw new IllegalArgumentException("DERIVED_METRIC expression must reference at least one governed column");
		}
		String timeColumn = optionalText(definition, "timeColumn");
		if (hasText(timeColumn)) {
			boolean timeColumnExists = catalog.getColumns()
				.stream()
				.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED
						&& modelCode.equals(column.getModelCode()) && timeColumn.equals(column.getColumnName())
						&& column.getRole() == SemanticColumnRole.TIME);
			if (!timeColumnExists) {
				throw new IllegalArgumentException(
						"DERIVED_METRIC timeColumn must be an enabled TIME column on the same model: " + timeColumn);
			}
		}
		String businessName = optionalText(definition, "businessName");
		if (!hasText(businessName)) {
			businessName = other;
		}
		String description = optionalText(definition, "description");
		if (!hasText(description)) {
			description = "Grill-Me confirmed derived business metric";
		}
		if (description.length() > 1000) {
			throw new IllegalArgumentException("DERIVED_METRIC description is too long");
		}
		LocalDateTime now = LocalDateTime.now();
		SemanticCatalogSnapshot.Metric metric = SemanticCatalogSnapshot.Metric.builder()
			.projectId(gap.getProjectId())
			.projectVersionId(gap.getProjectVersionId())
			.modelCode(modelCode)
			.metricCode(metricCode)
			.businessName(businessName)
			.expression(expression)
			.aggregation(aggregation)
			.unit(optionalText(definition, "unit"))
			.timeColumn(timeColumn)
			.description(description)
			.evidence(answerEvidence(gap, response))
			.status(SemanticAssetStatus.ENABLED)
			.createTime(now)
			.updateTime(now)
			.build();
		catalog.getMetrics().removeIf(existing -> metricCode.equals(existing.getMetricCode()));
		catalog.getMetrics().add(metric);
		catalogRepository.replaceCatalog(catalog);
	}

	private Set<String> validateCountMetricExpression(String expression, String modelCode,
			SemanticCatalogSnapshot catalog) {
		String columnName = expression == null ? "" : expression.trim();
		if (!columnName.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
			throw new IllegalArgumentException("COUNT metric expression must be exactly one governed column");
		}
		SemanticCatalogSnapshot.Column column = catalog.getColumns()
			.stream()
			.filter(candidate -> candidate.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(candidate -> modelCode.equals(candidate.getModelCode()) && columnName.equals(candidate.getColumnName()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
					"COUNT metric expression references an unknown governed column: " + modelCode + "." + columnName));
		if (!Boolean.TRUE.equals(column.getAllowAggregation())) {
			throw new IllegalArgumentException("COUNT metric column is not allowed for aggregation: " + modelCode + "."
					+ columnName);
		}
		return Set.of(columnName);
	}

	private Set<String> validateDerivedMetricExpression(String expression, String modelCode,
			SemanticCatalogSnapshot catalog) {
		if (!hasText(expression) || expression.length() > 1000) {
			throw new IllegalArgumentException("DERIVED_METRIC expression is empty or too long");
		}
		Matcher matcher = DERIVED_METRIC_TOKEN.matcher(expression);
		int position = 0;
		int parentheses = 0;
		boolean expectingOperand = true;
		Set<String> referencedColumns = new LinkedHashSet<>();
		while (matcher.find()) {
			if (matcher.start() != position) {
				throw new IllegalArgumentException("DERIVED_METRIC expression contains unsupported syntax");
			}
			String token = matcher.group(1);
			position = matcher.end();
			if (expectingOperand) {
				if ("(".equals(token)) {
					parentheses++;
					continue;
				}
				if ("-".equals(token)) {
					continue;
				}
				if (token.matches("\\d+(?:\\.\\d+)?")) {
					expectingOperand = false;
					continue;
				}
				if (!token.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
					throw new IllegalArgumentException("DERIVED_METRIC expression expected a governed column or number");
				}
				SemanticCatalogSnapshot.Column column = catalog.getColumns()
					.stream()
					.filter(candidate -> candidate.getStatus() == SemanticAssetStatus.ENABLED)
					.filter(candidate -> modelCode.equals(candidate.getModelCode()) && token.equals(candidate.getColumnName()))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
							"DERIVED_METRIC expression references an unknown governed column: " + modelCode + "." + token));
				if (column.getRole() != SemanticColumnRole.MEASURE || !Boolean.TRUE.equals(column.getAllowAggregation())) {
					throw new IllegalArgumentException(
							"DERIVED_METRIC expression may only use aggregatable MEASURE columns: " + modelCode + "." + token);
				}
				referencedColumns.add(token);
				expectingOperand = false;
				continue;
			}
			if (")".equals(token)) {
				if (parentheses-- <= 0) {
					throw new IllegalArgumentException("DERIVED_METRIC expression has unbalanced parentheses");
				}
				continue;
			}
			if (Set.of("+", "-", "*", "/").contains(token)) {
				expectingOperand = true;
				continue;
			}
			throw new IllegalArgumentException("DERIVED_METRIC expression expected an arithmetic operator");
		}
		if (position != expression.length() || expectingOperand || parentheses != 0) {
			throw new IllegalArgumentException("DERIVED_METRIC expression is not a complete safe arithmetic expression");
		}
		return Set.copyOf(referencedColumns);
	}

	private String answerEvidence(SemanticGap gap, JsonNode response) {
		return "scenario-gap:" + gap.getGapKey() + "; answer=" + json(response);
	}

	private String sqlStringLiteral(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	private RequirementProbe probe(String type, String text, List<BindingCandidate> candidates) {
		return new RequirementProbe(requirementKey(type, text), type, text,
				candidates == null ? List.of() : candidates);
	}

	private void reconcileScenarioGaps(Long projectId, Long projectVersionId, List<BusinessQueryScenario> scenarios,
			Map<Long, ScenarioResolution> resolutions) {
		Map<Long, BusinessQueryScenario> byId = scenarios.stream()
			.collect(Collectors.toMap(BusinessQueryScenario::getId, Function.identity()));
		Map<String, List<ScenarioGapTarget>> grouped = new LinkedHashMap<>();
		for (ScenarioResolution resolution : resolutions.values()) {
			BusinessQueryScenario scenario = byId.get(resolution.getScenarioId());
			if (scenario == null) {
				continue;
			}
			List<RequirementProbe> probes = readProbes(resolution.getCandidateBindingsJson());
			List<UnresolvedRequirement> unresolvedRequirements = readUnresolved(
					resolution.getUnresolvedRequirementsJson());
			if (unresolvedRequirements.stream()
				.anyMatch(value -> "UNSUPPORTED_QUERY_CAPABILITY".equals(value.reason()))) {
				continue;
			}
			for (UnresolvedRequirement unresolved : unresolvedRequirements) {
				if (!grillMeEligible(unresolved)) {
					continue;
				}
				RequirementProbe probe = probes.stream()
					.filter(value -> value.requirementKey().equals(unresolved.requirementKey()))
					.findFirst()
					.orElse(new RequirementProbe(unresolved.requirementKey(), unresolved.requirementType(),
							unresolved.requirementText(), List.of()));
				ScenarioGapTarget target = new ScenarioGapTarget(scenario, unresolved, probe);
				grouped.computeIfAbsent(gapRoot(target), ignored -> new ArrayList<>()).add(target);
			}
		}
		Map<String, SemanticGap> expected = new LinkedHashMap<>();
		for (List<ScenarioGapTarget> targets : grouped.values()) {
			SemanticGap gap = scenarioGap(targets);
			expected.put(gap.getGapKey(), gap);
		}
		for (SemanticGap open : projectRepository.findOpenGapsByKeyPrefix(projectVersionId, GAP_PREFIX)) {
			if (!expected.containsKey(open.getGapKey())) {
				open.resolve("当前 Scenario Resolution 已不再需要该确认。", "system");
				projectRepository.updateGap(open);
			}
		}
		for (SemanticGap gap : expected.values()) {
			SemanticGap existing = projectRepository.findGapByKey(projectVersionId, gap.getGapKey()).orElse(null);
			if (existing == null) {
				projectRepository.insertGap(gap);
			}
			else if (existing.getStatus() != SemanticGapStatus.OPEN) {
				existing.setGapType(gap.getGapType());
				existing.setQuestion(gap.getQuestion());
				existing.setRecommendation(gap.getRecommendation());
				existing.setEvidence(gap.getEvidence());
				existing.setImpactScope(gap.getImpactScope());
				existing.setPriority(gap.getPriority());
				existing.reopen();
				projectRepository.updateGap(existing);
				projectRepository.updateGapDefinition(existing);
			}
			else {
				existing.setGapType(gap.getGapType());
				existing.setQuestion(gap.getQuestion());
				existing.setRecommendation(gap.getRecommendation());
				existing.setEvidence(gap.getEvidence());
				existing.setImpactScope(gap.getImpactScope());
				existing.setPriority(gap.getPriority());
				projectRepository.updateGapDefinition(existing);
			}
		}
	}

	private boolean grillMeEligible(UnresolvedRequirement unresolved) {
		if (unresolved.reason().startsWith("AMBIGUOUS")) {
			return true;
		}
		return "MISSING_REQUIRED_SEMANTIC".equals(unresolved.reason())
				&& !"CAPABILITY".equals(unresolved.requirementType());
	}

	private String gapRoot(ScenarioGapTarget target) {
		String candidates = target.probe()
			.candidates()
			.stream()
			.map(candidate -> candidate.assetType() + ":" + candidate.assetKey())
			.sorted()
			.collect(Collectors.joining("|"));
		String root = target.unresolved().reason() + "|" + target.unresolved().requirementType() + "|"
				+ normalize(target.unresolved().requirementText()) + "|" + candidates;
		return sha256(root).substring(0, 24);
	}

	private SemanticGap scenarioGap(List<ScenarioGapTarget> targets) {
		ScenarioGapTarget primary = targets.get(0);
		BusinessQueryScenario scenario = primary.scenario();
		UnresolvedRequirement unresolved = primary.unresolved();
		RequirementProbe probe = primary.probe();
		String gapType = unresolved.reason();
		List<String> labels = probe.candidates().stream().map(BindingCandidate::optionLabel).distinct().toList();
		List<String> scenarioNames = targets.stream()
			.map(target -> target.scenario().getBusinessName())
			.filter(this::hasText)
			.distinct()
			.toList();
		String scenarioContext = scenarioNames.size() <= 1 ? "业务场景“" + scenario.getBusinessName() + "”"
				: "业务场景“" + scenarioNames.get(0) + "”等 " + scenarioNames.size() + " 个场景";
		String question;
		String recommendation;
		if (gapType.startsWith("AMBIGUOUS")) {
			question = scenarioContext + "中的“" + unresolved.requirementText() + "”有多个可用业务含义，请确认采用哪一个："
					+ String.join("；", labels) + "。";
			recommendation = "请选择与这些业务场景实际口径一致的含义；如果都不对请选择“其他”并说明。";
		}
		else {
			question = scenarioContext + "需要“" + unresolved.requirementText() + "”，但现有项目材料和语义中还无法可靠绑定。它实际指什么？";
			recommendation = "补充对应业务含义；如果现有候选都不正确，请选择“其他”并说明。";
		}
		List<Map<String, Object>> targetEvidence = targets.stream()
			.map(target -> Map.<String, Object>of("scenarioId", target.scenario().getId(), "scenarioCode",
					target.scenario().getScenarioCode(), "scenarioName", target.scenario().getBusinessName(),
					"requirementKey", target.unresolved().requirementKey()))
			.toList();
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("scenarioId", scenario.getId());
		evidence.put("scenarioCode", scenario.getScenarioCode());
		evidence.put("scenarioName", scenario.getBusinessName());
		evidence.put("requirementKey", unresolved.requirementKey());
		evidence.put("requirementType", unresolved.requirementType());
		evidence.put("requirementText", unresolved.requirementText());
		evidence.put("reason", unresolved.detail());
		evidence.put("candidates", probe.candidates());
		evidence.put("targets", targetEvidence);
		evidence.put("affectedScenarioCount", targets.size());
		String impactScope = "SCENARIOS:" + targets.stream()
			.map(target -> target.scenario().getId().toString())
			.sorted()
			.collect(Collectors.joining(","));
		int priority = targets.stream()
			.mapToInt(target -> priority(target.scenario().getImportance()))
			.min()
			.orElse(50);
		return SemanticGap.openWithKey(scenario.getProjectId(), scenario.getProjectVersionId(),
				GAP_PREFIX + "root:" + gapRoot(primary), gapType, question, recommendation, json(evidence), impactScope,
				priority);
	}

	private ResolutionCoverage coverage(List<BusinessQueryScenario> scenarios,
			Map<Long, ScenarioResolution> resolutions) {
		int resolved = 0;
		int ambiguous = 0;
		int unsupported = 0;
		int coreResolved = 0;
		int coreAmbiguous = 0;
		int coreUnsupported = 0;
		for (BusinessQueryScenario scenario : scenarios) {
			ScenarioResolution resolution = resolutions.get(scenario.getId());
			Status status = resolution == null ? Status.UNSUPPORTED : resolution.getStatus();
			boolean core = scenario.getImportance() == BusinessQueryScenario.Importance.CORE;
			switch (status) {
				case RESOLVED -> {
					resolved++;
					if (core) {
						coreResolved++;
					}
				}
				case AMBIGUOUS -> {
					ambiguous++;
					if (core) {
						coreAmbiguous++;
					}
				}
				case UNSUPPORTED -> {
					unsupported++;
					if (core) {
						coreUnsupported++;
					}
				}
			}
		}
		return new ResolutionCoverage(scenarios.size(), resolved, ambiguous, unsupported, coreResolved, coreAmbiguous,
				coreUnsupported);
	}

	private List<BindingCandidate> candidates(JsonNode evidence) {
		try {
			return objectMapper.convertValue(evidence.path("candidates"), new TypeReference<List<BindingCandidate>>() {
			});
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid scenario gap candidates", ex);
		}
	}

	private List<GapTargetRef> gapTargets(JsonNode evidence) {
		JsonNode targets = evidence.path("targets");
		if (targets.isArray() && !targets.isEmpty()) {
			List<GapTargetRef> values = new ArrayList<>();
			for (JsonNode target : targets) {
				values
					.add(new GapTargetRef(requiredLong(target, "scenarioId"), requiredText(target, "requirementKey")));
			}
			return List.copyOf(values);
		}
		return List
			.of(new GapTargetRef(requiredLong(evidence, "scenarioId"), requiredText(evidence, "requirementKey")));
	}

	private Map<String, ManualBinding> readManualBindings(String value) {
		if (!hasText(value)) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(objectMapper.readValue(value, new TypeReference<Map<String, ManualBinding>>() {
			}));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid manual scenario bindings", ex);
		}
	}

	private List<RequirementProbe> readProbes(String value) {
		try {
			return objectMapper.readValue(value, new TypeReference<List<RequirementProbe>>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid scenario candidate bindings", ex);
		}
	}

	private List<UnresolvedRequirement> readUnresolved(String value) {
		try {
			return objectMapper.readValue(value, new TypeReference<List<UnresolvedRequirement>>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid unresolved scenario requirements", ex);
		}
	}

	private BusinessQueryRequirement readRequirement(String value) {
		try {
			return objectMapper.readValue(value, BusinessQueryRequirement.class);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid scenario requirement JSON", ex);
		}
	}

	private List<String> unsupportedCapabilities(BusinessQueryRequirement requirement) {
		List<String> values = new ArrayList<>();
		values.addAll(requirement.measures());
		values.addAll(requirement.attributes());
		values.addAll(requirement.filters());
		values.addAll(requirement.timeConstraints());
		values.addAll(requirement.groupings());
		values.addAll(requirement.sorting());
		if (hasText(requirement.comparison())) {
			values.add(requirement.comparison());
		}
		Set<String> unsupportedTerms = Set.of("预测", "预估", "forecast", "forecasting", "predict", "prediction", "whatif");
		return values.stream()
			.filter(this::hasText)
			.filter(value -> unsupportedTerms.stream().anyMatch(term -> normalize(value).contains(normalize(term))))
			.distinct()
			.toList();
	}

	private boolean connected(Set<String> modelCodes, List<SemanticCatalogSnapshot.Relationship> relationships) {
		if (modelCodes.size() <= 1) {
			return true;
		}
		Map<String, Set<String>> adjacency = new HashMap<>();
		for (SemanticCatalogSnapshot.Relationship relationship : relationships) {
			if (relationship.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			String left = relationship.getSourceModelCode();
			String right = relationship.getTargetModelCode();
			if (!hasText(left) || !hasText(right)) {
				continue;
			}
			adjacency.computeIfAbsent(left, ignored -> new LinkedHashSet<>()).add(right);
			adjacency.computeIfAbsent(right, ignored -> new LinkedHashSet<>()).add(left);
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

	private String missingReason(String type) {
		return "UNSUPPORTED_CAPABILITY".equals(type) ? "UNSUPPORTED_QUERY_CAPABILITY" : "MISSING_REQUIRED_SEMANTIC";
	}

	private String ambiguousReason(String type) {
		return switch (type) {
			case "MEASURE" -> "AMBIGUOUS_METRIC";
			case "DIMENSION", "SORTING" -> "AMBIGUOUS_DIMENSION";
			case "FILTER" -> "AMBIGUOUS_ENUM";
			case "TIME" -> "AMBIGUOUS_TIME_BINDING";
			case "RELATIONSHIP" -> "AMBIGUOUS_RELATIONSHIP";
			default -> "MISSING_REQUIRED_SEMANTIC";
		};
	}

	private String requirementKey(String type, String text) {
		return type.toLowerCase(Locale.ROOT) + "-" + sha256(normalize(text)).substring(0, 16);
	}

	private String enumKey(SemanticCatalogSnapshot.EnumValue asset) {
		return asset.getModelCode() + ":" + asset.getColumnName() + ":" + asset.getValueCode();
	}

	private String optionLabel(String businessName, String description, String modelCode) {
		String qualifier = firstText(description, modelCode);
		if (!hasText(qualifier) || normalize(qualifier).equals(normalize(businessName))) {
			return businessName;
		}
		String compact = qualifier.length() > 40 ? qualifier.substring(0, 40) + "…" : qualifier;
		return businessName + "（" + compact + "）";
	}

	@SafeVarargs
	private final List<String> distinct(List<String>... values) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (List<String> items : values) {
			if (items != null) {
				items.stream().filter(this::hasText).map(String::trim).forEach(result::add);
			}
		}
		return List.copyOf(result);
	}

	private List<String> split(String value) {
		if (!hasText(value)) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split("[,，;；|\\n]")).map(String::trim).filter(this::hasText).toList();
	}

	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private String firstText(String... values) {
		return java.util.Arrays.stream(values).filter(this::hasText).map(String::trim).findFirst().orElse("");
	}

	private int priority(BusinessQueryScenario.Importance importance) {
		if (importance == null) {
			return 50;
		}
		return switch (importance) {
			case CORE -> 5;
			case IMPORTANT -> 20;
			case OPTIONAL -> 60;
			case DISCOVERED -> 80;

		};
	}

	private JsonNode readTree(String value) {
		try {
			return objectMapper.readTree(value == null ? "{}" : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid scenario resolution JSON", ex);
		}
	}

	private Long requiredLong(JsonNode node, String field) {
		if (!node.hasNonNull(field) || !node.get(field).canConvertToLong()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return node.get(field).longValue();
	}

	private String requiredText(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (!hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value;
	}

	private String optionalText(JsonNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText().trim() : null;
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode scenario resolution", ex);
		}
	}

	private String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record BindingCandidate(String assetType, String assetKey, String businessName, String modelCode,
			String optionLabel, int score, String evidence) {
	}

	public record RequirementProbe(String requirementKey, String requirementType, String requirementText,
			List<BindingCandidate> candidates) {
	}

	public record ResolvedBinding(String requirementKey, String requirementType, String requirementText,
			String assetType, String assetKey, String businessName, String modelCode, String source) {
	}

	public record UnresolvedRequirement(String requirementKey, String requirementType, String requirementText,
			String reason, String detail) {
	}

	public record ManualBinding(String assetType, String assetKey, String optionLabel, boolean other) {
	}

	private record ScenarioGapTarget(BusinessQueryScenario scenario, UnresolvedRequirement unresolved,
			RequirementProbe probe) {
	}

	private record GapTargetRef(Long scenarioId, String requirementKey) {
	}

	public record ResolutionCoverage(int total, int resolved, int ambiguous, int unsupported, int coreResolved,
			int coreAmbiguous, int coreUnsupported) {
	}

}
