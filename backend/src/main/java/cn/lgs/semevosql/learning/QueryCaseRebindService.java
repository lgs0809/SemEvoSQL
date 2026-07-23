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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticReplayExecutor;
import cn.lgs.semevosql.learning.QueryCaseAssetReferenceRepository.ReferenceValue;
import cn.lgs.semevosql.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Cross-version deterministic rebuild, replay and rebind transaction coordinator. */
@Service
public class QueryCaseRebindService {

	private final QueryCaseRepository repository;

	private final QueryCaseAssetReferenceRepository assetReferences;

	private final SemanticCatalogApplicationService catalogApplicationService;

	private final SemanticSqlCompiler sqlCompiler;

	private final DatabaseUtil databaseUtil;

	private final SemanticReplayExecutor replayExecutor;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final SemanticCatalogRepository catalogRepository;

	private final QueryCaseLineageService lineageService;

	private final JdbcTemplate jdbc;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final VersionedJson versionedJson = new VersionedJson();

	@Autowired
	public QueryCaseRebindService(QueryCaseRepository repository, QueryCaseAssetReferenceRepository assetReferences,
			SemanticCatalogApplicationService catalogApplicationService, SemanticSqlCompiler sqlCompiler,
			DatabaseUtil databaseUtil, SemanticReplayExecutor replayExecutor,
			SemanticCatalogPatchAnalyzer patchAnalyzer, SemanticCatalogRepository catalogRepository,
			QueryCaseLineageService lineageService) {
		this.repository = repository;
		this.assetReferences = assetReferences;
		this.catalogApplicationService = catalogApplicationService;
		this.sqlCompiler = sqlCompiler;
		this.databaseUtil = databaseUtil;
		this.replayExecutor = replayExecutor;
		this.patchAnalyzer = patchAnalyzer;
		this.catalogRepository = catalogRepository;
		this.lineageService = lineageService;
		this.jdbc = repository.jdbc();
	}

	public QueryCaseRebindService(QueryCaseRepository repository, QueryCaseAssetReferenceRepository assetReferences,
			SemanticCatalogApplicationService catalogApplicationService, SemanticSqlCompiler sqlCompiler,
			DatabaseUtil databaseUtil, SemanticReplayExecutor replayExecutor,
			SemanticCatalogPatchAnalyzer patchAnalyzer, SemanticCatalogRepository catalogRepository) {
		this(repository, assetReferences, catalogApplicationService, sqlCompiler, databaseUtil, replayExecutor,
				patchAnalyzer, catalogRepository, new QueryCaseLineageService(repository.jdbc(), repository));
	}

	public RebindReport rebindApprovedExamples(Long projectId, Long sourceVersionId, Long targetVersionId,
			String targetCatalogHash, SemanticCatalogSnapshot targetCatalog) {
		if (projectId == null || sourceVersionId == null || targetVersionId == null
				|| !StringUtils.hasText(targetCatalogHash) || targetCatalog == null) {
			throw new IllegalArgumentException("source/target version, target hash and Catalog are required");
		}
		List<Map<String, Object>> sourceCases = repository.approvedSourceCases(projectId, sourceVersionId);
		SemanticCatalogSnapshot sourceCatalog = catalogRepository.loadCatalog(projectId, sourceVersionId);
		Map<String, String> sourceFingerprints = catalogAssetFingerprints(sourceCatalog);
		Map<String, String> targetFingerprints = catalogAssetFingerprints(targetCatalog);
		int rebound = 0;
		int needsReview = 0;
		for (Map<String, Object> source : sourceCases) {
			String sourceId = Objects.toString(source.get("id"));
			Map<String, Object> existing = repository.existingRebind(sourceId, targetVersionId, targetCatalogHash)
				.orElse(null);
			if (existing != null && "REBOUND".equals(Objects.toString(existing.get("status")))) {
				rebound++;
				continue;
			}
			List<Map<String, Object>> refs = assetReferences.findRowsByCaseId(sourceId);
			List<String> missing = refs.stream()
				.map(ref -> assetIdentity(ref.get("asset_type"), ref.get("asset_key")))
				.filter(identity -> !targetFingerprints.containsKey(identity))
				.toList();
			List<String> changed = refs.stream()
				.map(ref -> assetIdentity(ref.get("asset_type"), ref.get("asset_key")))
				.filter(identity -> sourceFingerprints.containsKey(identity) && targetFingerprints.containsKey(identity)
						&& !Objects.equals(sourceFingerprints.get(identity), targetFingerprints.get(identity)))
				.toList();
			List<String> highRiskChanged = changed.stream().filter(this::highRiskAsset).toList();
			if (!missing.isEmpty() || !highRiskChanged.isEmpty()) {
				needsReview++;
				upsertRebind(sourceId, targetVersionId, targetCatalogHash, null, "NEEDS_REVIEW",
						json(Map.of("missingAssets", missing, "changedAssets", changed, "highRiskChangedAssets",
								highRiskChanged, "reason", "Target Catalog is not safely compatible")));
				continue;
			}
			try {
				SemanticBlueprint sourcePlan = readPlanJson(Objects.toString(source.get("typed_ir_json"), ""))
					.orElseThrow(() -> new IllegalStateException("Source Query Case has no Semantic Blueprint"));
				List<String> targetTables = targetCatalog.getModels()
					.stream()
					.filter(model -> sourcePlan.getModels()
						.stream()
						.anyMatch(selected -> Objects.equals(selected.getModelCode(), model.getModelCode())))
					.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
					.filter(StringUtils::hasText)
					.toList();
				SemanticBlueprint targetPlan = catalogApplicationService.buildBlueprint(projectId, targetVersionId,
						Objects.toString(source.get("normalized_question")), targetTables, hints(sourcePlan, sourceId));
				List<String> incompatibilities = compareReboundPlan(sourcePlan, targetPlan);
				if (!incompatibilities.isEmpty()) {
					throw new RebindReviewRequiredException(incompatibilities);
				}
				if (!"DETERMINISTIC".equalsIgnoreCase(targetPlan.getCompilerMode())) {
					throw new RebindReviewRequiredException(
							List.of("Target plan requires constrained generation and human review"));
				}
				CompiledSemanticQuery compiled = sqlCompiler.compile(targetPlan, targetCatalog, dialects(targetPlan),
						Clock.systemUTC(), ZoneId.of("UTC"));
				String newSql = compiled.sources().size() == 1 ? compiled.sources().get(0).sql()
						: json(compiled.sources()
							.stream()
							.map(item -> Map.of("datasourceId", item.datasourceId(), "sql", item.sql(), "parameters",
									item.parameters()))
							.toList());
				String targetExampleId = createPendingRebound(source, targetVersionId, targetCatalogHash, targetPlan,
						newSql);
				upsertRebind(sourceId, targetVersionId, targetCatalogHash, targetExampleId, "REBOUND_PENDING_REPLAY",
						json(Map.of("changedAssets", changed)));
				persistTargetAssetReferences(targetExampleId, targetCatalogHash, targetPlan, targetFingerprints);
				SemanticReplayExecutor.ReplayExecution execution = replayExecutor.executeDetailed(projectId,
						targetCatalog, targetPlan, compiled.sources(), "query-case-rebind:" + targetExampleId);
				String resultSchemaHash = sha256(json(
						execution.finalResult().getColumn() == null ? List.of() : execution.finalResult().getColumn()));
				Map<String, Object> quality = Map.of("source", "CROSS_VERSION_REBIND", "sourceExampleId", sourceId,
						"replayPassed", true, "sourceAndMergeProof", execution.proof(), "warnings",
						execution.warnings(), "latencyMs", execution.latencyMs(), "resultSchemaHash", resultSchemaHash);
				String sqlHash = sha256(normalizeSql(newSql));
				String fingerprint = sha256(projectId + "|" + targetVersionId + "|" + targetCatalogHash + "|"
						+ normalizeText(Objects.toString(source.get("normalized_question"))) + "|" + sqlHash);
				jdbc.update("""
						UPDATE qw_query_example
						SET typed_ir_json = ?, sql_text = ?, sql_hash = ?, fingerprint = ?,
						    canonical_shape_hash = ?, result_schema_hash = ?, quality_proof_json = ?,
						    status = 'APPROVED', rebind_status = 'REBOUND', reviewed_time = CURRENT_TIMESTAMP,
						    update_time = CURRENT_TIMESTAMP
						WHERE id = ? AND rebind_status = 'REBOUND_PENDING_REPLAY'
						""", versionedJson.write(JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, targetPlan), newSql, sqlHash,
						fingerprint, shapeHash(targetPlan), resultSchemaHash, json(quality), targetExampleId);
				upsertRebind(sourceId, targetVersionId, targetCatalogHash, targetExampleId, "REBOUND",
						json(Map.of("changedAssets", changed, "replayPassed", true)));
				rebound++;
			}
			catch (RebindReviewRequiredException ex) {
				needsReview++;
				upsertRebind(sourceId, targetVersionId, targetCatalogHash,
						existing == null ? null : Objects.toString(existing.get("target_example_id"), null),
						"NEEDS_REVIEW", json(Map.of("reasons", ex.reasons(), "changedAssets", changed)));
			}
			catch (RuntimeException ex) {
				needsReview++;
				String targetExampleId = existing == null ? null
						: Objects.toString(existing.get("target_example_id"), null);
				if (StringUtils.hasText(targetExampleId)) {
					jdbc.update("""
							UPDATE qw_query_example SET status = 'CANDIDATE', rebind_status = 'NEEDS_REVIEW',
							    review_comment = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?
							""", truncate("Rebind replay failed: " + ex.getMessage(), 4000), targetExampleId);
				}
				upsertRebind(sourceId, targetVersionId, targetCatalogHash, targetExampleId, "NEEDS_REVIEW",
						json(Map.of("reason", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName()),
								"changedAssets", changed)));
			}
		}
		return new RebindReport(sourceCases.size(), rebound, needsReview);
	}

	private Map<String, String> catalogAssetFingerprints(SemanticCatalogSnapshot catalog) {
		Map<String, String> values = new LinkedHashMap<>();
		catalog.getModels()
			.forEach(value -> values.put(assetIdentity("MODEL", value.getModelCode()),
					patchAnalyzer.fingerprintAsset(AssetType.MODEL, value)));
		catalog.getColumns()
			.forEach(value -> values.put(assetIdentity("COLUMN", value.getModelCode() + ":" + value.getColumnName()),
					patchAnalyzer.fingerprintAsset(AssetType.COLUMN, value)));
		catalog.getMetrics()
			.forEach(value -> values.put(assetIdentity("METRIC", value.getMetricCode()),
					patchAnalyzer.fingerprintAsset(AssetType.METRIC, value)));
		catalog.getDimensions()
			.forEach(value -> values.put(assetIdentity("DIMENSION", value.getDimensionCode()),
					patchAnalyzer.fingerprintAsset(AssetType.DIMENSION, value)));
		catalog.getGrains()
			.forEach(value -> values.put(assetIdentity("GRAIN", value.getModelCode() + ":" + value.getGrainCode()),
					patchAnalyzer.fingerprintAsset(AssetType.GRAIN, value)));
		catalog.getRelationships()
			.forEach(value -> values.put(assetIdentity("RELATIONSHIP", value.getRelationshipCode()),
					patchAnalyzer.fingerprintAsset(AssetType.RELATIONSHIP, value)));
		catalog.getRules()
			.forEach(value -> values.put(assetIdentity("RULE", value.getRuleCode()),
					patchAnalyzer.fingerprintAsset(AssetType.RULE, value)));
		catalog.getEnumValues()
			.forEach(value -> values.put(
					assetIdentity("ENUM_VALUE",
							value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode()),
					patchAnalyzer.fingerprintAsset(AssetType.ENUM_VALUE, value)));
		return Map.copyOf(values);
	}

	private QueryCaseHints hints(SemanticBlueprint plan, String caseId) {
		List<QueryCaseHints.FilterBindingHint> literalFilters = plan.getFilters()
			.stream()
			.filter(value -> "LITERAL".equalsIgnoreCase(value.getValueType()))
			.map(value -> new QueryCaseHints.FilterBindingHint("", value.getModelCode(), value.getColumnName(),
					value.getOperator(), value.getValue(), caseId, 1))
			.toList();
		QueryCaseHints.TimeBindingHint timeBinding = timeBinding(plan, caseId);
		return new QueryCaseHints(
				plan.getModels()
					.stream()
					.map(SemanticBlueprint.ModelSelection::getModelCode)
					.collect(Collectors.toSet()),
				plan.getMetrics()
					.stream()
					.map(SemanticBlueprint.MetricSelection::getMetricCode)
					.collect(Collectors.toSet()),
				plan.getDimensions()
					.stream()
					.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
					.collect(Collectors.toSet()),
				plan.getGrains()
					.stream()
					.map(SemanticBlueprint.GrainSelection::getGrainCode)
					.collect(Collectors.toSet()),
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
				literalFilters, List.of(), timeBinding, true, intentType(plan), List.of(caseId), 1,
				Map.of("rebind", 1d));
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

	private List<String> compareReboundPlan(SemanticBlueprint source, SemanticBlueprint target) {
		List<String> reasons = new ArrayList<>(target.getValidationErrors());
		compareCodes("metric",
				source.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(),
				target.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList(), reasons);
		compareCodes("dimension",
				source.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList(),
				target.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList(),
				reasons);
		compareCodes("relationship",
				source.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList(),
				target.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList(),
				reasons);
		compareCodes("grain", source.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).toList(),
				target.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).toList(), reasons);
		if (!target.isExecutable()) {
			reasons.add("Target Semantic Blueprint is not executable");
		}
		return List.copyOf(reasons);
	}

	private void compareCodes(String type, List<String> source, List<String> target, List<String> reasons) {
		if (!new LinkedHashSet<>(source).equals(new LinkedHashSet<>(target))) {
			reasons.add("Rebuilt " + type + " bindings differ: source=" + source + ", target=" + target);
		}
	}

	private Map<Integer, SqlDialect> dialects(SemanticBlueprint plan) {
		Map<Integer, SqlDialect> values = new LinkedHashMap<>();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			if (source.getDatasourceId() != null) {
				values.put(source.getDatasourceId(),
						SqlDialect.from(databaseUtil.getDatasourceDbConfig(source.getDatasourceId()).getDialectType()));
			}
		}
		return Map.copyOf(values);
	}

	private String createPendingRebound(Map<String, Object> source, Long targetVersionId, String targetCatalogHash,
			SemanticBlueprint targetPlan, String newSql) {
		String sourceId = Objects.toString(source.get("id"));
		List<Map<String, Object>> prior = jdbc.queryForList("""
				SELECT * FROM qw_query_example
				WHERE source_example_id = ? AND project_version_id = ? AND catalog_hash = ?
				ORDER BY create_time DESC LIMIT 1
				""", sourceId, targetVersionId, targetCatalogHash);
		if (!prior.isEmpty()) {
			String existingId = Objects.toString(prior.get(0).get("id"));
			QueryCaseLineageService.Lineage lineage = lineageService.forRebind(existingId, sourceId);
			jdbc.update("""
					UPDATE qw_query_example SET typed_ir_json = ?, sql_text = ?, historical_sql_text = ?,
					 derived_from_case_ids = ?, root_evidence_ids = ?, evidence_lineage_hash = ?,
					 status = 'CANDIDATE', rebind_status = 'REBOUND_PENDING_REPLAY',
					 review_comment = 'Pending deterministic rebind replay', update_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", versionedJson.write(JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, targetPlan), newSql,
					source.get("sql_text"), lineageService.json(lineage.derivedFromCaseIds()),
					lineageService.json(lineage.rootEvidenceIds()), lineage.lineageHash(), existingId);
			return existingId;
		}
		String id = UUID.randomUUID().toString();
		QueryCaseLineageService.Lineage lineage = lineageService.forRebind(id, sourceId);
		String pendingFingerprint = sha256(
				"rebind-pending|" + sourceId + "|" + targetVersionId + "|" + targetCatalogHash);
		String newSqlHash = sha256(normalizeSql(newSql));
		jdbc.update("""
				INSERT INTO qw_query_example
				(id, project_id, project_version_id, catalog_hash, datasource_id, episode_id, attempt_id,
				 run_id, context_hash, original_question, normalized_question, intent_type,
				 conversation_independent, resolved_time_range_json, typed_ir_json, resolution_json,
				 quality_proof_json, result_schema_hash, canonical_shape_hash, sql_text, sql_hash,
				 historical_sql_text, fingerprint, status, rebind_status, source_example_id,
				 derived_from_case_ids, root_evidence_ids, evidence_lineage_hash, quality_summary,
				 reviewed_by, review_comment, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?,
				 'CANDIDATE', 'REBOUND_PENDING_REPLAY', ?, ?, ?, ?, ?, ?, 'semevosql-system',
				 'Pending deterministic rebind replay', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, number(source.get("project_id")), targetVersionId, targetCatalogHash,
				source.get("datasource_id"), source.get("episode_id"), source.get("attempt_id"), source.get("run_id"),
				source.get("context_hash"), source.get("original_question"), source.get("normalized_question"),
				source.get("intent_type"), source.get("conversation_independent"),
				source.get("resolved_time_range_json"),
				versionedJson.write(JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, targetPlan), source.get("resolution_json"),
				json(Map.of("source", "CROSS_VERSION_REBIND", "status", "PENDING_REPLAY")), shapeHash(targetPlan),
				newSql, newSqlHash, source.get("sql_text"), pendingFingerprint, sourceId,
				lineageService.json(lineage.derivedFromCaseIds()), lineageService.json(lineage.rootEvidenceIds()),
				lineage.lineageHash(), source.get("quality_summary"));
		lineageService.appendEvent(id, "QUERY_CASE_REBOUND", null, "CANDIDATE", "semevosql-system", "SYSTEM",
				Map.of("sourceCaseId", sourceId, "rootEvidenceIds", lineage.rootEvidenceIds(), "evidenceLineageHash",
						lineage.lineageHash()));
		return id;
	}

	private void persistTargetAssetReferences(String queryCaseId, String catalogHash, SemanticBlueprint plan,
			Map<String, String> fingerprints) {
		List<String> identities = new ArrayList<>();
		plan.getModels().forEach(value -> identities.add(assetIdentity("MODEL", value.getModelCode())));
		plan.getMetrics().forEach(value -> identities.add(assetIdentity("METRIC", value.getMetricCode())));
		plan.getDimensions().forEach(value -> identities.add(assetIdentity("DIMENSION", value.getDimensionCode())));
		plan.getGrains()
			.forEach(
					value -> identities.add(assetIdentity("GRAIN", value.getModelCode() + ":" + value.getGrainCode())));
		plan.getRelationships()
			.forEach(value -> identities.add(assetIdentity("RELATIONSHIP", value.getRelationshipCode())));
		plan.getRules().forEach(value -> identities.add(assetIdentity("RULE", value.getRuleCode())));
		plan.getEnumResolutions()
			.forEach(value -> identities.add(assetIdentity("ENUM_VALUE",
					value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode())));
		List<ReferenceValue> references = new ArrayList<>();
		for (String identity : new LinkedHashSet<>(identities)) {
			int separator = identity.indexOf(':');
			String type = identity.substring(0, separator);
			String key = identity.substring(separator + 1);
			String fingerprint = fingerprints.get(identity);
			if (!StringUtils.hasText(fingerprint)) {
				throw new IllegalStateException("Target Catalog fingerprint missing for " + identity);
			}
			references.add(new ReferenceValue(type, key, fingerprint));
		}
		assetReferences.replace(queryCaseId, catalogHash, references);
	}

	private void upsertRebind(String sourceId, Long targetVersionId, String hash, String targetId, String status,
			String reasons) {
		int updated = jdbc.update("""
				UPDATE qw_query_example_rebind SET target_example_id = ?, status = ?, reasons_json = ?,
				 update_time = CURRENT_TIMESTAMP
				WHERE source_example_id = ? AND target_version_id = ? AND target_catalog_hash = ?
				""", targetId, status, reasons, sourceId, targetVersionId, hash);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_query_example_rebind
					(id, source_example_id, target_version_id, target_catalog_hash, target_example_id,
					 status, reasons_json, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", UUID.randomUUID().toString(), sourceId, targetVersionId, hash, targetId, status, reasons);
		}
	}

	private Optional<SemanticBlueprint> readPlanJson(String value) {
		if (!StringUtils.hasText(value)) {
			return Optional.empty();
		}
		try {
			return Optional
				.of(versionedJson.read(value, JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, SemanticBlueprint.class));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	private String intentType(SemanticBlueprint plan) {
		if (plan.getSourceSubPlans().size() > 1) {
			return "MULTI_SOURCE_ANALYTICS";
		}
		if (!plan.getMetrics().isEmpty() && !plan.getDimensions().isEmpty()) {
			return "GROUPED_AGGREGATION";
		}
		if (!plan.getMetrics().isEmpty()) {
			return "AGGREGATION";
		}
		return "ENTITY_LOOKUP";
	}

	private String shapeHash(SemanticBlueprint plan) {
		Map<String, Object> shape = new TreeMap<>();
		shape.put("models",
				sorted(plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).toList()));
		shape.put("metrics",
				sorted(plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList()));
		shape.put("dimensions", sorted(
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).toList()));
		shape.put("grains",
				sorted(plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).toList()));
		shape.put("relationships",
				sorted(plan.getRelationships()
					.stream()
					.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
					.toList()));
		shape.put("rules", sorted(plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).toList()));
		shape.put("intent", intentType(plan));
		shape.put("hasTime", plan.getTimeRange() != null);
		return canonicalJson.hash(shape);
	}

	private List<String> sorted(List<String> values) {
		return values.stream().filter(Objects::nonNull).sorted().toList();
	}

	private boolean highRiskAsset(String identity) {
		return identity.startsWith("METRIC:") || identity.startsWith("RELATIONSHIP:") || identity.startsWith("GRAIN:")
				|| identity.startsWith("RULE:");
	}

	private String assetIdentity(Object type, Object key) {
		return Objects.toString(type, "") + ":" + Objects.toString(key, "");
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode Query Case rebind data", ex);
		}
	}

	private static String normalizeText(String value) {
		return Objects.toString(value, "").trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
	}

	private static String normalizeSql(String value) {
		return normalizeText(value).replaceAll("\\s*([(),=<>+*/-])\\s*", "$1");
	}

	private static Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static final class RebindReviewRequiredException extends RuntimeException {

		private final List<String> reasons;

		private RebindReviewRequiredException(List<String> reasons) {
			super(String.join("; ", reasons));
			this.reasons = List.copyOf(reasons);
		}

		private List<String> reasons() {
			return reasons;
		}

	}

	public record RebindReport(int total, int rebound, int needsReview) {
	}

}
