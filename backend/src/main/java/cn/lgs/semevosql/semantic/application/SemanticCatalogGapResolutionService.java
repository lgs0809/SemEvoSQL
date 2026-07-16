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

import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot;
import cn.lgs.semevosql.project.domain.InitializationAnalysisStatus;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticGapResolutionHandler;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.application.HistoricalSqlSemanticMaterialParser.RelationshipCandidate;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SemanticCatalogGapResolutionService implements SemanticGapResolutionHandler {

	private static final String UNKNOWN_GRAIN = "UNKNOWN_GRAIN";

	private static final String MISSING_MODEL_BUSINESS_NAME = "MISSING_MODEL_BUSINESS_NAME";

	private static final String AMBIGUOUS_DEFAULT_TIME_COLUMN = "AMBIGUOUS_DEFAULT_TIME_COLUMN";

	private static final String HISTORICAL_SQL_RELATIONSHIP_CANDIDATE = "HISTORICAL_SQL_RELATIONSHIP_CANDIDATE";

	private static final String INVALID_GRAIN_KEY_COLUMNS = "INVALID_GRAIN_KEY_COLUMNS";

	private static final String SEMANTIC_ASSET_CONFLICT = "SEMANTIC_ASSET_CONFLICT";

	private static final String MISSING_LOGICAL_BINDING = "MISSING_LOGICAL_BINDING";

	private static final String MISSING_AUTHORITY_RULE = "MISSING_AUTHORITY_RULE";

	private static final String MISSING_FRESHNESS_POLICY = "MISSING_FRESHNESS_POLICY";

	private static final String MISSING_CROSS_SOURCE_RELATIONSHIP = "MISSING_CROSS_SOURCE_RELATIONSHIP";

	private static final String MISSING_MERGE_POLICY = "MISSING_MERGE_POLICY";

	private static final String CROSS_ASSET_METRIC_DEFINITION_CONFLICT = "CROSS_ASSET_METRIC_DEFINITION_CONFLICT";

	private static final String FANOUT_METRIC_RISK = "FANOUT_METRIC_RISK";

	private static final Set<String> ALLOWED_JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");

	/**
	 * Review-only gaps are deliberately closed by a human answer without mutating the
	 * catalog. Keep this list explicit so structural gaps cannot be waived accidentally.
	 */
	private static final Set<String> REVIEW_ONLY_GAP_TYPES = Set.of("BUSINESS_RULE_UNCONFIRMED",
			"HISTORICAL_SQL_REVIEW", "INVALID_LLM_EXTRACTED_ASSET", "LLM_EXTRACTION_REVIEW", "LLM_EXTRACTION_TRUNCATED",
			"LOW_CONFIDENCE_LLM_EXTRACTION", "SEMANTIC_EXTRACTION_CONFLICT", "UNSTRUCTURED_MATERIAL_REVIEW");

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticProjectRepository projectRepository;

	private final MultiSourcePolicyService multiSourcePolicyService;

	private final ScenarioResolutionService scenarioResolutionService;

	@Override
	public boolean supports(String gapType) {
		return scenarioResolutionService.supportsGapType(gapType) || isCatalogMutationGap(gapType)
				|| REVIEW_ONLY_GAP_TYPES.contains(gapType);
	}

	@Override
	public void applyResolution(SemanticGap gap, String answer) {
		if (gap == null || answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("Semantic gap and non-blank answer are required");
		}
		if (!supports(gap.getGapType())) {
			throw new IllegalArgumentException("Semantic gap type is not safely resolvable: " + gap.getGapType());
		}
		if (scenarioResolutionService.supportsGapType(gap.getGapType())) {
			scenarioResolutionService.applyGapResolution(gap, answer);
			return;
		}
		if (REVIEW_ONLY_GAP_TYPES.contains(gap.getGapType())) {
			return;
		}
		SemanticProjectVersion version = projectRepository.findVersion(gap.getProjectVersionId())
			.orElseThrow(() -> new IllegalArgumentException(
					"Semantic project version not found: " + gap.getProjectVersionId()));
		if (version.getStatus() != ProjectVersionStatus.DRAFT
				|| version.getAnalysisStatus() != InitializationAnalysisStatus.RUNNING) {
			throw new IllegalStateException("Catalog gaps can only be resolved while DRAFT analysis is RUNNING");
		}

		if (isMultiSourcePolicyGap(gap.getGapType())) {
			applyMultiSourcePolicyResolution(gap, answer);
			return;
		}

		SemanticCatalogSnapshot snapshot = catalogRepository.loadCatalog(gap.getProjectId(), gap.getProjectVersionId());
		switch (gap.getGapType()) {
			case UNKNOWN_GRAIN -> applyGrain(snapshot, gap, answer, requireImpactScope(gap));
			case INVALID_GRAIN_KEY_COLUMNS -> applyInvalidGrainKeys(snapshot, gap, answer);
			case MISSING_MODEL_BUSINESS_NAME -> applyModelBusinessName(snapshot, gap, answer);
			case AMBIGUOUS_DEFAULT_TIME_COLUMN -> applyDefaultTimeColumn(snapshot, gap, answer);
			case HISTORICAL_SQL_RELATIONSHIP_CANDIDATE -> applyHistoricalSqlRelationship(snapshot, gap, answer);
			case SEMANTIC_ASSET_CONFLICT -> applyConflictResolution(snapshot, gap, answer);
			case CROSS_ASSET_METRIC_DEFINITION_CONFLICT -> applyMetricDefinitionConflict(snapshot, gap, answer);
			case FANOUT_METRIC_RISK -> applyFanoutMetricGrain(snapshot, gap, answer);
			default -> throw new IllegalArgumentException("Unsupported semantic gap type: " + gap.getGapType());
		}
		catalogRepository.replaceCatalog(snapshot);
	}

	private void applyGrain(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer, String modelCode) {
		List<String> keyColumns = grainKeyColumns(answer);
		if (keyColumns.isEmpty()) {
			throw new IllegalArgumentException("Grain answer must contain at least one column name");
		}
		Set<String> availableColumns = snapshot.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> modelCode.equals(column.getModelCode()))
			.map(SemanticCatalogSnapshot.Column::getColumnName)
			.collect(Collectors.toSet());
		List<String> missingColumns = keyColumns.stream().filter(column -> !availableColumns.contains(column)).toList();
		if (!missingColumns.isEmpty()) {
			throw new IllegalArgumentException(
					"Grain references missing columns: " + String.join(", ", missingColumns));
		}

		LocalDateTime now = LocalDateTime.now();
		snapshot.getGrains().removeIf(grain -> modelCode.equals(grain.getModelCode()));
		snapshot.getGrains()
			.add(SemanticCatalogSnapshot.Grain.builder()
				.projectId(snapshot.getProjectId())
				.projectVersionId(snapshot.getProjectVersionId())
				.modelCode(modelCode)
				.grainCode(modelCode + "_confirmed_grain")
				.keyColumns(String.join(",", keyColumns))
				.uniquenessRule("Confirmed by semantic initialization gap " + gap.getId())
				.description("User-confirmed model grain")
				.evidence(answer)
				.status(SemanticAssetStatus.ENABLED)
				.createTime(now)
				.updateTime(now)
				.build());
	}

	private void applyInvalidGrainKeys(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		String impactScope = requireImpactScope(gap);
		if (!impactScope.startsWith("GRAIN:")) {
			throw new IllegalArgumentException("Invalid grain coverage gap impactScope: " + impactScope);
		}
		String grainCode = impactScope.substring("GRAIN:".length()).trim();
		SemanticCatalogSnapshot.Grain grain = snapshot.getGrains()
			.stream()
			.filter(candidate -> grainCode.equals(candidate.getGrainCode()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Semantic grain not found: " + grainCode));
		applyGrain(snapshot, gap, answer, grain.getModelCode());
	}

	private void applyMetricDefinitionConflict(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		JsonNode resolution;
		try {
			resolution = JsonUtil.getObjectMapper().readTree(answer);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Metric conflict answer must be valid JSON", ex);
		}
		String authoritativeMetricCode = requiredText(resolution, "authoritativeMetricCode");
		String impactScope = requireImpactScope(gap);
		if (!impactScope.startsWith("METRICS:")) {
			throw new IllegalArgumentException("Invalid metric conflict impactScope: " + impactScope);
		}
		Set<String> affectedCodes = splitColumns(impactScope.substring("METRICS:".length())).stream()
			.collect(Collectors.toSet());
		if (!affectedCodes.contains(authoritativeMetricCode)) {
			throw new IllegalArgumentException(
					"Authoritative metric is not part of the conflict: " + authoritativeMetricCode);
		}
		boolean authoritativeExists = snapshot.getMetrics()
			.stream()
			.anyMatch(metric -> authoritativeMetricCode.equals(metric.getMetricCode()));
		if (!authoritativeExists) {
			throw new IllegalArgumentException("Authoritative metric does not exist: " + authoritativeMetricCode);
		}
		LocalDateTime now = LocalDateTime.now();
		for (SemanticCatalogSnapshot.Metric metric : snapshot.getMetrics()) {
			if (!affectedCodes.contains(metric.getMetricCode())) {
				continue;
			}
			metric.setStatus(authoritativeMetricCode.equals(metric.getMetricCode()) ? SemanticAssetStatus.ENABLED
					: SemanticAssetStatus.DISABLED);
			metric.setEvidence(appendEvidence(metric.getEvidence(),
					"semantic-gap:" + gap.getId() + "; authoritativeMetricCode=" + authoritativeMetricCode));
			metric.setUpdateTime(now);
		}
	}

	private void applyFanoutMetricGrain(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		String impactScope = requireImpactScope(gap);
		if (!impactScope.startsWith("METRIC:")) {
			throw new IllegalArgumentException("Invalid fanout metric impactScope: " + impactScope);
		}
		String metricCode = impactScope.substring("METRIC:".length()).trim();
		SemanticCatalogSnapshot.Metric metric = snapshot.getMetrics()
			.stream()
			.filter(candidate -> metricCode.equals(candidate.getMetricCode()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Semantic metric not found: " + metricCode));
		applyGrain(snapshot, gap, answer, metric.getModelCode());
	}

	private String appendEvidence(String current, String addition) {
		return current == null || current.isBlank() ? addition : current + "\n" + addition;
	}

	private void applyConflictResolution(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		JsonNode resolution;
		JsonNode evidence;
		try {
			resolution = JsonUtil.getObjectMapper().readTree(answer);
			evidence = JsonUtil.getObjectMapper().readTree(gap.getEvidence());
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Semantic conflict answer and evidence must be valid JSON", ex);
		}
		String choice = requiredText(resolution, "choice").toUpperCase(Locale.ROOT);
		if ("CURRENT".equals(choice)) {
			return;
		}
		if (!"INCOMING".equals(choice)) {
			throw new IllegalArgumentException("Semantic conflict choice must be CURRENT or INCOMING");
		}
		String typeValue = requiredText(evidence, "assetType");
		String assetKey = requiredText(evidence, "assetKey");
		JsonNode incomingAsset = evidence.get("incomingAsset");
		if (incomingAsset == null || incomingAsset.isNull() || !incomingAsset.isObject()) {
			throw new IllegalArgumentException("Semantic conflict evidence is missing incomingAsset");
		}
		AssetType assetType;
		try {
			assetType = AssetType.valueOf(typeValue);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported semantic conflict asset type: " + typeValue, ex);
		}
		replaceIncomingAsset(snapshot, assetType, assetKey, incomingAsset);
	}

	private void replaceIncomingAsset(SemanticCatalogSnapshot snapshot, AssetType assetType, String assetKey,
			JsonNode incomingAsset) {
		try {
			switch (assetType) {
				case MODEL -> replaceModel(snapshot, assetKey, incomingAsset);
				case COLUMN -> replaceColumn(snapshot, assetKey, incomingAsset);
				case METRIC -> replaceMetric(snapshot, assetKey, incomingAsset);
				case DIMENSION -> replaceDimension(snapshot, assetKey, incomingAsset);
				case RELATIONSHIP -> replaceRelationship(snapshot, assetKey, incomingAsset);
				case GRAIN -> replaceGrain(snapshot, assetKey, incomingAsset);
				case ENUM_VALUE -> replaceEnumValue(snapshot, assetKey, incomingAsset);
				case RULE -> replaceRule(snapshot, assetKey, incomingAsset);
			}
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to apply incoming semantic conflict definition", ex);
		}
	}

	private void replaceModel(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		ObjectNode sanitized = incomingAsset.deepCopy();
		sanitized.remove("enabled");
		SemanticCatalogSnapshot.Model asset = JsonUtil.getObjectMapper()
			.treeToValue(sanitized, SemanticCatalogSnapshot.Model.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getModels().removeIf(current -> assetKey.equals(current.getModelCode()));
		snapshot.getModels().add(asset);
	}

	private void replaceColumn(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Column asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Column.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getColumns()
			.removeIf(current -> assetKey.equals(assetKey(current.getModelCode(), current.getColumnName())));
		snapshot.getColumns().add(asset);
	}

	private void replaceMetric(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Metric asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Metric.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getMetrics().removeIf(current -> assetKey.equals(current.getMetricCode()));
		snapshot.getMetrics().add(asset);
	}

	private void replaceDimension(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Dimension asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Dimension.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getDimensions().removeIf(current -> assetKey.equals(current.getDimensionCode()));
		snapshot.getDimensions().add(asset);
	}

	private void replaceRelationship(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Relationship asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Relationship.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getRelationships().removeIf(current -> assetKey.equals(current.getRelationshipCode()));
		snapshot.getRelationships().add(asset);
	}

	private void replaceGrain(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Grain asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Grain.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getGrains()
			.removeIf(current -> assetKey.equals(assetKey(current.getModelCode(), current.getGrainCode())));
		snapshot.getGrains().add(asset);
	}

	private void replaceEnumValue(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.EnumValue asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.EnumValue.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getEnumValues()
			.removeIf(current -> assetKey
				.equals(assetKey(current.getModelCode(), current.getColumnName(), current.getValueCode())));
		snapshot.getEnumValues().add(asset);
	}

	private void replaceRule(SemanticCatalogSnapshot snapshot, String assetKey, JsonNode incomingAsset)
			throws Exception {
		SemanticCatalogSnapshot.Rule asset = JsonUtil.getObjectMapper()
			.treeToValue(incomingAsset, SemanticCatalogSnapshot.Rule.class);
		normalizeAssetScope(snapshot, asset);
		snapshot.getRules().removeIf(current -> assetKey.equals(current.getRuleCode()));
		snapshot.getRules().add(asset);
	}

	private void normalizeAssetScope(SemanticCatalogSnapshot snapshot, Object asset) {
		LocalDateTime now = LocalDateTime.now();
		if (asset instanceof SemanticCatalogSnapshot.Model model) {
			model.setProjectId(snapshot.getProjectId());
			model.setProjectVersionId(snapshot.getProjectVersionId());
			model.setCreateTime(model.getCreateTime() == null ? now : model.getCreateTime());
			model.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Column column) {
			column.setProjectId(snapshot.getProjectId());
			column.setProjectVersionId(snapshot.getProjectVersionId());
			column.setCreateTime(column.getCreateTime() == null ? now : column.getCreateTime());
			column.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Metric metric) {
			metric.setProjectId(snapshot.getProjectId());
			metric.setProjectVersionId(snapshot.getProjectVersionId());
			metric.setCreateTime(metric.getCreateTime() == null ? now : metric.getCreateTime());
			metric.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Dimension dimension) {
			dimension.setProjectId(snapshot.getProjectId());
			dimension.setProjectVersionId(snapshot.getProjectVersionId());
			dimension.setCreateTime(dimension.getCreateTime() == null ? now : dimension.getCreateTime());
			dimension.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Relationship relationship) {
			relationship.setProjectId(snapshot.getProjectId());
			relationship.setProjectVersionId(snapshot.getProjectVersionId());
			relationship.setCreateTime(relationship.getCreateTime() == null ? now : relationship.getCreateTime());
			relationship.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Grain grain) {
			grain.setProjectId(snapshot.getProjectId());
			grain.setProjectVersionId(snapshot.getProjectVersionId());
			grain.setCreateTime(grain.getCreateTime() == null ? now : grain.getCreateTime());
			grain.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.EnumValue enumValue) {
			enumValue.setProjectId(snapshot.getProjectId());
			enumValue.setProjectVersionId(snapshot.getProjectVersionId());
			enumValue.setCreateTime(enumValue.getCreateTime() == null ? now : enumValue.getCreateTime());
			enumValue.setUpdateTime(now);
		}
		else if (asset instanceof SemanticCatalogSnapshot.Rule rule) {
			rule.setProjectId(snapshot.getProjectId());
			rule.setProjectVersionId(snapshot.getProjectVersionId());
			rule.setCreateTime(rule.getCreateTime() == null ? now : rule.getCreateTime());
			rule.setUpdateTime(now);
		}
	}

	private void applyMultiSourcePolicyResolution(SemanticGap gap, String answer) {
		MultiSourcePolicySnapshot snapshot = multiSourcePolicyService.get(gap.getProjectId(),
				gap.getProjectVersionId());
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(answer);
			switch (gap.getGapType()) {
				case MISSING_LOGICAL_BINDING -> {
					MultiSourcePolicySnapshot.LogicalColumnBinding binding = JsonUtil.getObjectMapper()
						.treeToValue(payload(root, "binding"), MultiSourcePolicySnapshot.LogicalColumnBinding.class);
					snapshot.getLogicalBindings()
						.removeIf(existing -> same(existing.getLogicalEntityCode(), binding.getLogicalEntityCode())
								&& same(existing.getLogicalAttributeCode(), binding.getLogicalAttributeCode())
								&& same(existing.getDatasourceId(), binding.getDatasourceId()));
					binding.setEvidence(answer);
					snapshot.getLogicalBindings().add(binding);
				}
				case MISSING_AUTHORITY_RULE -> {
					MultiSourcePolicySnapshot.AuthorityRule rule = JsonUtil.getObjectMapper()
						.treeToValue(payload(root, "authorityRule"), MultiSourcePolicySnapshot.AuthorityRule.class);
					snapshot.getAuthorityRules()
						.removeIf(existing -> existing.getLogicalAssetType() == rule.getLogicalAssetType()
								&& same(existing.getLogicalAssetCode(), rule.getLogicalAssetCode())
								&& same(existing.getDatasourceId(), rule.getDatasourceId()));
					rule.setEvidence(answer);
					snapshot.getAuthorityRules().add(rule);
				}
				case MISSING_FRESHNESS_POLICY -> {
					MultiSourcePolicySnapshot.FreshnessPolicy policy = JsonUtil.getObjectMapper()
						.treeToValue(payload(root, "freshnessPolicy"), MultiSourcePolicySnapshot.FreshnessPolicy.class);
					snapshot.getFreshnessPolicies()
						.removeIf(existing -> same(existing.getDatasourceId(), policy.getDatasourceId()));
					policy.setEvidence(answer);
					snapshot.getFreshnessPolicies().add(policy);
				}
				case MISSING_CROSS_SOURCE_RELATIONSHIP -> {
					MultiSourcePolicySnapshot.CrossSourceRelationship relationship = JsonUtil.getObjectMapper()
						.treeToValue(payload(root, "relationship"),
								MultiSourcePolicySnapshot.CrossSourceRelationship.class);
					snapshot.getCrossSourceRelationships()
						.removeIf(existing -> same(existing.getRelationshipCode(), relationship.getRelationshipCode()));
					relationship.setEvidence(answer);
					snapshot.getCrossSourceRelationships().add(relationship);
				}
				case MISSING_MERGE_POLICY -> {
					MultiSourcePolicySnapshot.MergePolicy policy = JsonUtil.getObjectMapper()
						.treeToValue(payload(root, "mergePolicy"), MultiSourcePolicySnapshot.MergePolicy.class);
					snapshot.getMergePolicies()
						.removeIf(existing -> same(existing.getPolicyCode(), policy.getPolicyCode()));
					policy.setEvidence(answer);
					snapshot.getMergePolicies().add(policy);
				}
				default ->
					throw new IllegalArgumentException("Unsupported multi-source semantic gap: " + gap.getGapType());
			}
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Multi-source semantic gap answer must be valid structured JSON", ex);
		}
		multiSourcePolicyService.replace(gap.getProjectId(), gap.getProjectVersionId(), snapshot);
	}

	private JsonNode payload(JsonNode root, String field) {
		JsonNode nested = root == null ? null : root.get(field);
		return nested != null && nested.isObject() ? nested : root;
	}

	private boolean same(Object left, Object right) {
		return java.util.Objects.equals(left, right);
	}

	private void applyModelBusinessName(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		String modelCode = requireImpactScope(gap);
		SemanticCatalogSnapshot.Model model = snapshot.getModels()
			.stream()
			.filter(candidate -> modelCode.equals(candidate.getModelCode()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Semantic model not found: " + modelCode));
		model.setBusinessName(answer.trim());
		model.setUpdateTime(LocalDateTime.now());
	}

	private void applyDefaultTimeColumn(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		String modelCode = requireImpactScope(gap);
		String timeColumn = answer.trim();
		boolean exists = snapshot.getColumns()
			.stream()
			.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED
					&& modelCode.equals(column.getModelCode()) && timeColumn.equals(column.getColumnName()));
		if (!exists) {
			throw new IllegalArgumentException("Default time column does not exist: " + modelCode + "." + timeColumn);
		}
		List<SemanticCatalogSnapshot.Grain> grains = snapshot.getGrains()
			.stream()
			.filter(grain -> modelCode.equals(grain.getModelCode()))
			.toList();
		if (grains.isEmpty()) {
			throw new IllegalStateException("Resolve the model grain before selecting the default time column");
		}
		for (SemanticCatalogSnapshot.Grain grain : grains) {
			grain.setTimeColumn(timeColumn);
			grain.setUpdateTime(LocalDateTime.now());
		}
	}

	private void applyHistoricalSqlRelationship(SemanticCatalogSnapshot snapshot, SemanticGap gap, String answer) {
		JsonNode resolution;
		RelationshipCandidate candidate;
		try {
			resolution = JsonUtil.getObjectMapper().readTree(answer);
			candidate = JsonUtil.getObjectMapper().readValue(requireImpactScope(gap), RelationshipCandidate.class);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Historical SQL relationship answer and candidate must be valid JSON",
					ex);
		}
		if (!resolution.has("accepted") || !resolution.get("accepted").isBoolean()) {
			throw new IllegalArgumentException("Historical SQL relationship answer requires boolean accepted");
		}
		if (!resolution.get("accepted").booleanValue()) {
			return;
		}

		SemanticCatalogSnapshot.Model sourceModel = requireModel(snapshot, candidate.sourceModelCode());
		SemanticCatalogSnapshot.Model targetModel = requireModel(snapshot, candidate.targetModelCode());
		requireColumn(snapshot, candidate.sourceModelCode(), candidate.sourceColumn());
		requireColumn(snapshot, candidate.targetModelCode(), candidate.targetColumn());

		String cardinalityValue = requiredText(resolution, "cardinality").toUpperCase(Locale.ROOT);
		RelationshipCardinality cardinality;
		try {
			cardinality = RelationshipCardinality.valueOf(cardinalityValue);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported relationship cardinality: " + cardinalityValue, ex);
		}
		String joinType = requiredText(resolution, "joinType").toUpperCase(Locale.ROOT);
		if (!ALLOWED_JOIN_TYPES.contains(joinType)) {
			throw new IllegalArgumentException("Unsupported relationship joinType: " + joinType);
		}

		String relationshipCode = relationshipCode(candidate);
		boolean alreadyExists = snapshot.getRelationships()
			.stream()
			.anyMatch(relationship -> relationshipCode.equals(relationship.getRelationshipCode()));
		if (alreadyExists) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		snapshot.getRelationships()
			.add(SemanticCatalogSnapshot.Relationship.builder()
				.projectId(snapshot.getProjectId())
				.projectVersionId(snapshot.getProjectVersionId())
				.relationshipCode(relationshipCode)
				.sourceModelCode(candidate.sourceModelCode())
				.targetModelCode(candidate.targetModelCode())
				.cardinality(cardinality)
				.joinType(joinType)
				.joinCondition(sourceModel.getPhysicalTable() + "." + candidate.sourceColumn() + " = "
						+ targetModel.getPhysicalTable() + "." + candidate.targetColumn())
				.description("User-confirmed relationship discovered from historical SQL")
				.evidence("semantic-gap:" + gap.getId())
				.status(SemanticAssetStatus.ENABLED)
				.createTime(now)
				.updateTime(now)
				.build());
	}

	private SemanticCatalogSnapshot.Model requireModel(SemanticCatalogSnapshot snapshot, String modelCode) {
		return snapshot.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> modelCode.equals(model.getModelCode()))
			.findFirst()
			.orElseThrow(
					() -> new IllegalArgumentException("Historical SQL relationship model not found: " + modelCode));
	}

	private void requireColumn(SemanticCatalogSnapshot snapshot, String modelCode, String columnName) {
		boolean exists = snapshot.getColumns()
			.stream()
			.anyMatch(column -> column.getStatus() == SemanticAssetStatus.ENABLED
					&& modelCode.equals(column.getModelCode()) && columnName.equals(column.getColumnName()));
		if (!exists) {
			throw new IllegalArgumentException(
					"Historical SQL relationship column not found: " + modelCode + "." + columnName);
		}
	}

	private String requiredText(JsonNode resolution, String fieldName) {
		JsonNode value = resolution.get(fieldName);
		if (value == null || !value.isTextual() || value.textValue().isBlank()) {
			throw new IllegalArgumentException("Historical SQL relationship answer requires " + fieldName);
		}
		return value.textValue().trim();
	}

	private String relationshipCode(RelationshipCandidate candidate) {
		String code = toCode(candidate.sourceModelCode()) + "_" + toCode(candidate.sourceColumn()) + "_to_"
				+ toCode(candidate.targetModelCode()) + "_" + toCode(candidate.targetColumn());
		if (code.length() <= 128) {
			return code;
		}
		return code.substring(0, 111) + "_" + stableHash(code).substring(0, 16);
	}

	private String stableHash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private String toCode(String value) {
		String code = value == null ? ""
				: value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
		return code.isBlank() ? "asset" : code;
	}

	private boolean isCatalogMutationGap(String gapType) {
		return UNKNOWN_GRAIN.equals(gapType) || INVALID_GRAIN_KEY_COLUMNS.equals(gapType)
				|| MISSING_MODEL_BUSINESS_NAME.equals(gapType) || AMBIGUOUS_DEFAULT_TIME_COLUMN.equals(gapType)
				|| HISTORICAL_SQL_RELATIONSHIP_CANDIDATE.equals(gapType) || SEMANTIC_ASSET_CONFLICT.equals(gapType)
				|| CROSS_ASSET_METRIC_DEFINITION_CONFLICT.equals(gapType) || FANOUT_METRIC_RISK.equals(gapType)
				|| isMultiSourcePolicyGap(gapType);
	}

	private boolean isMultiSourcePolicyGap(String gapType) {
		return MISSING_LOGICAL_BINDING.equals(gapType) || MISSING_AUTHORITY_RULE.equals(gapType)
				|| MISSING_FRESHNESS_POLICY.equals(gapType) || MISSING_CROSS_SOURCE_RELATIONSHIP.equals(gapType)
				|| MISSING_MERGE_POLICY.equals(gapType);
	}

	private String requireImpactScope(SemanticGap gap) {
		if (gap.getImpactScope() == null || gap.getImpactScope().isBlank()) {
			throw new IllegalArgumentException("Catalog mutation gap is missing impactScope: " + gap.getGapType());
		}
		return gap.getImpactScope().trim();
	}

	private List<String> grainKeyColumns(String answer) {
		String trimmed = answer.trim();
		if (!trimmed.startsWith("{")) {
			return splitColumns(trimmed);
		}
		try {
			JsonNode value = JsonUtil.getObjectMapper().readTree(trimmed).get("keyColumns");
			if (value == null || value.isNull()) {
				return List.of();
			}
			if (value.isArray()) {
				return java.util.stream.StreamSupport.stream(value.spliterator(), false)
					.filter(JsonNode::isTextual)
					.map(JsonNode::textValue)
					.map(String::trim)
					.filter(column -> !column.isBlank())
					.distinct()
					.toList();
			}
			if (value.isTextual()) {
				return splitColumns(value.textValue());
			}
			throw new IllegalArgumentException("Grain keyColumns must be a string or array");
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Grain answer must be valid JSON", ex);
		}
	}

	private String assetKey(String... parts) {
		return String.join(":", parts);
	}

	private List<String> splitColumns(String answer) {
		return Arrays.stream(answer.split("[,，]"))
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.distinct()
			.toList();
	}

}
