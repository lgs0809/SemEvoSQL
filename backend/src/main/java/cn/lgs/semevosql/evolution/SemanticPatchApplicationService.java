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

import cn.lgs.semevosql.clarification.ProjectSemanticAliasService;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Applies reviewed Semantic Patch DSL operations atomically to a cloned Draft. */
@Service
@RequiredArgsConstructor
public class SemanticPatchApplicationService {

	private static final String TRUE_AMBIGUITY_MESSAGE = "TRUE_AMBIGUITY requires explicit semantic resolution and cannot be auto-approved or applied";

	private final JdbcTemplate jdbc;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final VersionedJson versionedJson = new VersionedJson();

	private final SemanticPatchValidator patchValidator;

	private final ProjectSemanticAliasService projectSemanticAliasService;

	private final MultiSourcePolicyPatchService policyPatchService;

	private final SemanticEvolutionStateMachine stateMachine;

	@Transactional
	public PatchApplicationResult applyCandidate(String candidateId) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE",
				candidateId);
		if ("TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")).toUpperCase(Locale.ROOT))) {
			throw new IllegalStateException(TRUE_AMBIGUITY_MESSAGE);
		}
		if (policyCandidate(candidate)) {
			MultiSourcePolicyPatchService.PolicyPatchApplicationResult result = policyPatchService
				.applyCandidate(candidateId);
			return new PatchApplicationResult(result.candidateId(), result.targetDraftVersionId(),
					result.operationCount(), result.patchHash(), result.alreadyApplied());
		}
		String status = text(candidate.get("status"));
		if ("PATCH_APPLIED".equals(status) || "REPLAY_PASSED".equals(status) || "READY_FOR_PUBLISH".equals(status)
				|| "PUBLISHED".equals(status)) {
			return new PatchApplicationResult(candidateId, number(candidate.get("target_draft_version_id")), 0,
					text(candidate.get("patch_hash")), true);
		}
		if (!"DRAFT_CREATED".equals(status)) {
			throw new IllegalStateException("Semantic patch requires DRAFT_CREATED candidate; current=" + status);
		}
		Long projectId = number(candidate.get("project_id"));
		Long sourceVersionId = number(candidate.get("source_version_id"));
		Long targetVersionId = number(candidate.get("target_draft_version_id"));
		String sourceHash = required(text(candidate.get("source_catalog_hash")), "source_catalog_hash");
		Map<String, Object> sourceVersion = one("SELECT * FROM qw_project_version WHERE id = ?", sourceVersionId);
		if (!"PUBLISHED".equals(text(sourceVersion.get("status")))
				|| !Objects.equals(sourceHash, text(sourceVersion.get("catalog_hash")))) {
			throw new IllegalStateException("Semantic patch source version is no longer the pinned published Catalog");
		}
		Map<String, Object> targetVersion = one("SELECT * FROM qw_project_version WHERE id = ? FOR UPDATE",
				targetVersionId);
		if (!Objects.equals(projectId, number(targetVersion.get("project_id")))
				|| !"DRAFT".equals(text(targetVersion.get("status")))) {
			throw new IllegalStateException("Semantic patch target must be a Draft in the same project");
		}
		SemanticPatch patch = parsePatch(text(candidate.get("patch_json")));
		if (!Objects.equals(sourceVersionId, patch.sourceVersionId())
				|| !Objects.equals(sourceHash, patch.sourceCatalogHash())) {
			throw new IllegalStateException("Semantic patch source pin does not match its evolution candidate");
		}
		patchValidator.requireValid(candidateId, patch);
		SemanticCatalogSnapshot current = catalogRepository.loadCatalog(projectId, targetVersionId);
		SemanticCatalogSnapshot updated = mapper.convertValue(current, SemanticCatalogSnapshot.class);
		for (Operation operation : patch.operations()) {
			apply(updated, operation, projectId, targetVersionId, true);
		}
		updated.setProjectId(projectId);
		updated.setProjectVersionId(targetVersionId);
		catalogRepository.replaceCatalog(updated);
		String patchHash = canonicalJson.hash(patch);
		stateMachine.transition(candidateId, CandidateStatus.DRAFT_CREATED, number(candidate.get("revision")),
				CandidateStatus.PATCH_APPLIED, Mutation.patchApplied(patchHash));
		return new PatchApplicationResult(candidateId, targetVersionId, patch.operations().size(), patchHash, false);
	}

	@Transactional
	public PatchMaterializationResult materializePatch(Long projectId, Long targetVersionId, SemanticPatch patch) {
		if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
			throw new IllegalArgumentException("Semantic patch operations are required");
		}
		SemanticCatalogSnapshot current = catalogRepository.loadCatalog(projectId, targetVersionId);
		SemanticCatalogSnapshot updated = mapper.convertValue(current, SemanticCatalogSnapshot.class);
		for (Operation operation : patch.operations()) {
			apply(updated, operation, projectId, targetVersionId, true);
		}
		updated.setProjectId(projectId);
		updated.setProjectVersionId(targetVersionId);
		catalogRepository.replaceCatalog(updated);
		return new PatchMaterializationResult(patch.operations().size(), canonicalJson.hash(patch));
	}

	public String patchHash(SemanticPatch patch) {
		if (patch == null) {
			throw new IllegalArgumentException("Semantic patch is required");
		}
		return canonicalJson.hash(patch);
	}

	public SemanticCatalogSnapshot previewPatch(Long projectId, Long sourceVersionId, SemanticPatch patch) {
		if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
			throw new IllegalArgumentException("Semantic patch operations are required");
		}
		SemanticCatalogSnapshot current = catalogRepository.loadCatalog(projectId, sourceVersionId);
		SemanticCatalogSnapshot updated = mapper.convertValue(current, SemanticCatalogSnapshot.class);
		for (Operation operation : patch.operations()) {
			apply(updated, operation, projectId, sourceVersionId, false);
		}
		updated.setProjectId(projectId);
		updated.setProjectVersionId(sourceVersionId);
		return updated;
	}

	private void apply(SemanticCatalogSnapshot catalog, Operation operation, Long projectId, Long versionId,
			boolean persistProjectAlias) {
		switch (operation.operation()) {
			case ADD_COLUMN_SYNONYM -> addColumnSynonym(catalog, operation);
			case UPDATE_MODEL -> updateModel(catalog, operation);
			case UPDATE_COLUMN -> updateColumn(catalog, operation);
			case ADD_ENUM_ALIAS -> addEnumAlias(catalog, operation);
			case ADD_PROJECT_ALIAS -> {
				if (persistProjectAlias) {
					addProjectAlias(operation, projectId, versionId);
				}
				else {
					validateProjectAlias(operation);
				}
			}
			case ADD_ENUM_VALUE -> addAsset(catalog.getEnumValues(), operation, SemanticCatalogSnapshot.EnumValue.class,
					AssetType.ENUM_VALUE, this::enumKey, projectId, versionId);
			case UPDATE_ENUM_VALUE -> updateEnumValue(catalog, operation);
			case ADD_METRIC -> addAsset(catalog.getMetrics(), operation, SemanticCatalogSnapshot.Metric.class,
					AssetType.METRIC, SemanticCatalogSnapshot.Metric::getMetricCode, projectId, versionId);
			case UPDATE_METRIC -> updateMetric(catalog, operation);
			case ADD_DIMENSION -> addAsset(catalog.getDimensions(), operation, SemanticCatalogSnapshot.Dimension.class,
					AssetType.DIMENSION, SemanticCatalogSnapshot.Dimension::getDimensionCode, projectId, versionId);
			case UPDATE_DIMENSION -> updateDimension(catalog, operation);
			case ADD_RELATIONSHIP -> addAsset(catalog.getRelationships(), operation,
					SemanticCatalogSnapshot.Relationship.class, AssetType.RELATIONSHIP,
					SemanticCatalogSnapshot.Relationship::getRelationshipCode, projectId, versionId);
			case UPDATE_RELATIONSHIP -> updateRelationship(catalog, operation);
			case ADD_GRAIN -> addAsset(catalog.getGrains(), operation, SemanticCatalogSnapshot.Grain.class,
					AssetType.GRAIN, this::grainKey, projectId, versionId);
			case UPDATE_GRAIN -> updateGrain(catalog, operation);
			case ADD_RULE -> addAsset(catalog.getRules(), operation, SemanticCatalogSnapshot.Rule.class, AssetType.RULE,
					SemanticCatalogSnapshot.Rule::getRuleCode, projectId, versionId);
			case UPDATE_RULE -> updateRule(catalog, operation);
		}
	}

	private void updateModel(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Model model = catalog.getModels()
			.stream()
			.filter(value -> Objects.equals(value.getModelCode(), operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Model asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.MODEL, model, operation);
		setIfPresent(operation, "businessName", model::setBusinessName);
		setIfPresent(operation, "modelType", model::setModelType);
		setIfPresent(operation, "description", model::setDescription);
	}

	private void updateColumn(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Column column = catalog.getColumns()
			.stream()
			.filter(value -> columnKey(value).equals(operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Column asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.COLUMN, column, operation);
		setIfPresent(operation, "businessName", column::setBusinessName);
		setIfPresent(operation, "expression", column::setExpression);
		setIfPresent(operation, "synonyms", column::setSynonyms);
		setIfPresent(operation, "description", column::setDescription);
		if (operation.values().containsKey("role")) {
			column.setRole(mapper.convertValue(operation.values().get("role"), cn.lgs.semevosql.semantic.domain.SemanticColumnRole.class));
		}
	}

	private void addColumnSynonym(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Column column = catalog.getColumns()
			.stream()
			.filter(value -> columnKey(value).equals(operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Column asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.COLUMN, column, operation);
		String synonym = required(value(operation, "synonym"), "synonym");
		column.setSynonyms(mergeTokens(column.getSynonyms(), synonym));
	}

	private void updateEnumValue(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.EnumValue enumValue = catalog.getEnumValues()
			.stream()
			.filter(value -> enumKey(value).equals(operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Enum asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.ENUM_VALUE, enumValue, operation);
		setIfPresent(operation, "businessName", enumValue::setBusinessName);
		setIfPresent(operation, "aliases", enumValue::setAliases);
		setIfPresent(operation, "description", enumValue::setDescription);
		if (operation.values().containsKey("sortOrder")) {
			enumValue.setSortOrder(mapper.convertValue(operation.values().get("sortOrder"), Integer.class));
		}
	}

	private void addEnumAlias(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.EnumValue value = catalog.getEnumValues()
			.stream()
			.filter(item -> enumKey(item).equals(operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Enum asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.ENUM_VALUE, value, operation);
		String alias = required(value(operation, "alias"), "alias");
		value.setAliases(mergeTokens(value.getAliases(), alias));
	}

	private void addProjectAlias(Operation operation, Long projectId, Long versionId) {
		projectSemanticAliasService.save(projectId, versionId, required(value(operation, "phrase"), "phrase"),
				required(value(operation, "targetAssetType"), "targetAssetType"),
				required(value(operation, "targetAssetKey"), "targetAssetKey"),
				required(value(operation, "businessLabel"), "businessLabel"), "Governed Semantic Patch");
	}

	private void validateProjectAlias(Operation operation) {
		required(value(operation, "phrase"), "phrase");
		required(value(operation, "targetAssetType"), "targetAssetType");
		required(value(operation, "targetAssetKey"), "targetAssetKey");
		required(value(operation, "businessLabel"), "businessLabel");
	}

	private void updateMetric(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Metric metric = catalog.getMetrics()
			.stream()
			.filter(value -> Objects.equals(value.getMetricCode(), operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Metric asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.METRIC, metric, operation);
		setIfPresent(operation, "businessName", metric::setBusinessName);
		setIfPresent(operation, "expression", metric::setExpression);
		setIfPresent(operation, "aggregation", metric::setAggregation);
		setIfPresent(operation, "unit", metric::setUnit);
		setIfPresent(operation, "timeColumn", metric::setTimeColumn);
		setIfPresent(operation, "filterExpression", metric::setFilterExpression);
		setIfPresent(operation, "additiveType", metric::setAdditiveType);
		setIfPresent(operation, "description", metric::setDescription);
	}

	private void updateDimension(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Dimension dimension = catalog.getDimensions()
			.stream()
			.filter(value -> Objects.equals(value.getDimensionCode(), operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Dimension asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.DIMENSION, dimension, operation);
		setIfPresent(operation, "businessName", dimension::setBusinessName);
		setIfPresent(operation, "columnName", dimension::setColumnName);
		setIfPresent(operation, "expression", dimension::setExpression);
		setIfPresent(operation, "dimensionType", dimension::setDimensionType);
		setIfPresent(operation, "hierarchy", dimension::setHierarchy);
		setIfPresent(operation, "description", dimension::setDescription);
	}

	private void updateRelationship(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Relationship relationship = catalog.getRelationships()
			.stream()
			.filter(value -> Objects.equals(value.getRelationshipCode(), operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Relationship asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.RELATIONSHIP, relationship, operation);
		setIfPresent(operation, "sourceModelCode", relationship::setSourceModelCode);
		setIfPresent(operation, "targetModelCode", relationship::setTargetModelCode);
		setIfPresent(operation, "joinType", relationship::setJoinType);
		setIfPresent(operation, "joinCondition", relationship::setJoinCondition);
		setIfPresent(operation, "description", relationship::setDescription);
		if (operation.values().containsKey("cardinality")) {
			relationship.setCardinality(
					RelationshipCardinality.valueOf(value(operation, "cardinality").toUpperCase(Locale.ROOT)));
		}
	}

	private void updateGrain(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Grain grain = catalog.getGrains()
			.stream()
			.filter(value -> grainKey(value).equals(operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Grain asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.GRAIN, grain, operation);
		setIfPresent(operation, "keyColumns", grain::setKeyColumns);
		setIfPresent(operation, "timeColumn", grain::setTimeColumn);
		setIfPresent(operation, "uniquenessRule", grain::setUniquenessRule);
		setIfPresent(operation, "description", grain::setDescription);
	}

	private void updateRule(SemanticCatalogSnapshot catalog, Operation operation) {
		SemanticCatalogSnapshot.Rule rule = catalog.getRules()
			.stream()
			.filter(value -> Objects.equals(value.getRuleCode(), operation.assetKey()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Rule asset not found: " + operation.assetKey()));
		assertFingerprint(AssetType.RULE, rule, operation);
		setIfPresent(operation, "modelCode", rule::setModelCode);
		setIfPresent(operation, "ruleType", rule::setRuleType);
		setIfPresent(operation, "businessName", rule::setBusinessName);
		setIfPresent(operation, "expression", rule::setExpression);
		setIfPresent(operation, "severity", rule::setSeverity);
		setIfPresent(operation, "description", rule::setDescription);
	}

	private <T> void addAsset(List<T> target, Operation operation, Class<T> type, AssetType assetType,
			java.util.function.Function<T, String> key, Long projectId, Long versionId) {
		T value = mapper.convertValue(operation.values(), type);
		setOwnership(value, projectId, versionId);
		String actualKey = key.apply(value);
		if (!Objects.equals(operation.assetKey(), actualKey)) {
			throw new IllegalArgumentException("Patch assetKey does not match added " + assetType + ": expected="
					+ operation.assetKey() + ", actual=" + actualKey);
		}
		if (target.stream().map(key).anyMatch(actualKey::equals)) {
			throw new IllegalStateException("Semantic asset already exists: " + assetType + ":" + actualKey);
		}
		target.add(value);
	}

	private void setOwnership(Object asset, Long projectId, Long versionId) {
		try {
			asset.getClass().getMethod("setProjectId", Long.class).invoke(asset, projectId);
			asset.getClass().getMethod("setProjectVersionId", Long.class).invoke(asset, versionId);
			asset.getClass()
				.getMethod("setStatus", SemanticAssetStatus.class)
				.invoke(asset, SemanticAssetStatus.ENABLED);
			try {
				asset.getClass().getMethod("setCreateTime", LocalDateTime.class).invoke(asset, LocalDateTime.now());
				asset.getClass().getMethod("setUpdateTime", LocalDateTime.class).invoke(asset, LocalDateTime.now());
			}
			catch (ReflectiveOperationException ignored) {
				// Time fields are optional on imported value objects.
			}
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalArgumentException("Unsupported semantic asset type: " + asset.getClass().getName(), ex);
		}
	}

	private void assertFingerprint(AssetType type, Object current, Operation operation) {
		String expected = required(operation.expectedCurrentFingerprint(), "expectedCurrentFingerprint");
		String actual = patchAnalyzer.fingerprintAsset(type, current);
		if (!Objects.equals(expected, actual)) {
			throw new IllegalStateException(
					"Semantic asset changed since patch proposal: " + type + ":" + operation.assetKey());
		}
	}

	private void setIfPresent(Operation operation, String key, java.util.function.Consumer<String> consumer) {
		if (operation.values().containsKey(key)) {
			consumer.accept(Objects.toString(operation.values().get(key), null));
		}
	}

	private String value(Operation operation, String key) {
		return Objects.toString(operation.values().get(key), "").trim();
	}

	private String mergeTokens(String current, String added) {
		Set<String> values = new LinkedHashSet<>();
		if (StringUtils.hasText(current)) {
			values.addAll(
					Arrays.stream(current.split("[,，;；\\n]")).map(String::trim).filter(StringUtils::hasText).toList());
		}
		values.add(added.trim());
		return String.join(",", values);
	}

	private String columnKey(SemanticCatalogSnapshot.Column value) {
		return value.getModelCode() + ":" + value.getColumnName();
	}

	private String enumKey(SemanticCatalogSnapshot.EnumValue value) {
		return value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode();
	}

	private String grainKey(SemanticCatalogSnapshot.Grain value) {
		return value.getModelCode() + ":" + value.getGrainCode();
	}

	private SemanticPatch parsePatch(String value) {
		return versionedJson.read(required(value, "patch_json"), JsonPayloadRegistry.SEMANTIC_PATCH,
				SemanticPatch.class);
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> values = jdbc.queryForList(sql, args);
		if (values.size() != 1) {
			throw new IllegalArgumentException("Expected one row for semantic patch operation");
		}
		return values.get(0);
	}

	private String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private boolean policyCandidate(Map<String, Object> candidate) {
		return Set.of("DATASOURCE_AUTHORITY_INCORRECT", "MULTI_SOURCE_POLICY_INCORRECT")
			.contains(text(candidate.get("candidate_type")).toUpperCase(Locale.ROOT))
				|| text(candidate.get("asset_type")).toUpperCase(Locale.ROOT).startsWith("POLICY_");
	}

	public record PatchApplicationResult(String candidateId, Long targetDraftVersionId, int operationCount,
			String patchHash, boolean alreadyApplied) {
	}

	public record PatchMaterializationResult(int operationCount, String patchHash) {
	}

}
