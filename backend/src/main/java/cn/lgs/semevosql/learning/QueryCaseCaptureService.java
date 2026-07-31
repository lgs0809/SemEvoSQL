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
import cn.lgs.semevosql.run.SemanticPlanSnapshotService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Transaction boundary for idempotent Query Case capture and asset evidence. */
@Service
public class QueryCaseCaptureService {

	private final JdbcTemplate jdbc;

	private final QueryCaseRepository repository;

	private final QueryCaseAssetReferenceRepository assetReferences;

	private final QueryCaseLineageService lineageService;

	private final QueryCaseRetrievalIndexService retrievalIndex;

	private final ConversationContextDependencyFingerprintService contextFingerprintService;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final VersionedJson versionedJson = new VersionedJson();

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticPlanSnapshotService semanticPlanSnapshots;

	private final RunExecutionFenceService executionFence;

	@Autowired
	public QueryCaseCaptureService(JdbcTemplate jdbc, QueryCaseRepository repository,
			QueryCaseAssetReferenceRepository assetReferences, QueryCaseLineageService lineageService,
			QueryCaseRetrievalIndexService retrievalIndex,
			ConversationContextDependencyFingerprintService contextFingerprintService,
			SemanticCatalogPatchAnalyzer patchAnalyzer, SemanticCatalogRepository catalogRepository,
			SemanticPlanSnapshotService semanticPlanSnapshots, RunExecutionFenceService executionFence) {
		this.jdbc = jdbc;
		this.repository = repository;
		this.assetReferences = assetReferences;
		this.lineageService = lineageService;
		this.retrievalIndex = retrievalIndex;
		this.contextFingerprintService = contextFingerprintService;
		this.patchAnalyzer = patchAnalyzer;
		this.catalogRepository = catalogRepository;
		this.semanticPlanSnapshots = semanticPlanSnapshots;
		this.executionFence = executionFence;
	}

	/** Lightweight constructor retained for focused capture tests. */
	public QueryCaseCaptureService(JdbcTemplate jdbc, QueryCaseRepository repository,
			QueryCaseAssetReferenceRepository assetReferences, QueryCaseLineageService lineageService,
			QueryCaseRetrievalIndexService retrievalIndex,
			ConversationContextDependencyFingerprintService contextFingerprintService,
			SemanticCatalogPatchAnalyzer patchAnalyzer, SemanticCatalogRepository catalogRepository,
			SemanticPlanSnapshotService semanticPlanSnapshots) {
		this(jdbc, repository, assetReferences, lineageService, retrievalIndex, contextFingerprintService, patchAnalyzer,
				catalogRepository, semanticPlanSnapshots, null);
	}

	@Transactional
	public Optional<QueryCaseSummary> captureEligibleCandidate(String episodeId) {
		if (!StringUtils.hasText(episodeId)) {
			return Optional.empty();
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT e.id AS episode_id, e.project_id, e.project_version_id, e.datasource_id,
				       e.catalog_hash, e.normalized_question, e.original_question,
				       a.id AS attempt_id, s.id AS sql_trace_id, s.sql_text, s.explain_summary, s.retry_count,
				       f.rating, f.adopted, r.run_id, r.execution_snapshot
				FROM qw_episode e
				JOIN qw_feedback f ON f.episode_id = e.id
				JOIN qw_attempt a ON a.episode_id = e.id AND a.status = 'SUCCEEDED'
				JOIN qw_sql_trace s ON s.attempt_id = a.id AND s.status = 'SUCCEEDED'
				LEFT JOIN qw_query_run r ON r.run_id = (
					SELECT r2.run_id FROM qw_query_run r2
					WHERE r2.status = 'SUCCEEDED' AND r2.attempt_id = a.id
					ORDER BY r2.finish_time DESC, r2.update_time DESC LIMIT 1
				)
				WHERE e.id = ? AND e.status = 'SUCCEEDED'
				  AND (COALESCE(f.adopted, FALSE) OR COALESCE(f.rating, 0) >= 4)
				ORDER BY a.attempt_no DESC, s.create_time DESC, s.id DESC
				LIMIT 1
				""", episodeId);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> source = rows.get(0);
		String sql = Objects.toString(source.get("sql_text"), "").trim();
		String question = firstText(source.get("normalized_question"), source.get("original_question"));
		if (!StringUtils.hasText(sql) || !StringUtils.hasText(question)) {
			return Optional.empty();
		}
		Long projectId = number(source.get("project_id"));
		Long projectVersionId = number(source.get("project_version_id"));
		String catalogHash = Objects.toString(source.get("catalog_hash"), "");
		Integer datasourceId = source.get("datasource_id") == null ? null
				: ((Number) source.get("datasource_id")).intValue();
		String runId = Objects.toString(source.get("run_id"), "");
		String attemptId = Objects.toString(source.get("attempt_id"), "");
		if (executionFence != null && StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
		}
		Optional<SemanticBlueprint> plan = semanticPlanSnapshots.latest(runId);
		String typedIrJson = plan.map(value -> versionedJson.write(JsonPayloadRegistry.SEMANTIC_QUERY_PLAN, value))
			.orElse(null);
		String intentType = plan.map(this::intentType).orElse(null);
		String timeRangeJson = plan.map(SemanticBlueprint::getTimeRange).map(this::json).orElse(null);
		String shapeHash = plan.map(this::shapeHash).orElse(null);
		List<Map<String, Object>> resolutions = contextFingerprintService.resolutions(runId);
		boolean conversationIndependent = contextFingerprintService.conversationIndependent(question, resolutions);
		String contextHash = contextFingerprintService.fingerprint(runId, question);
		Map<String, Object> proof = qualityProof(source, runId, plan.orElse(null));
		boolean autoApproved = autoApprovable(projectVersionId, plan.orElse(null), resolutions, source, runId);
		String sqlHash = sha256(normalizeSql(sql));
		String scopeSignature = plan.map(QueryCaseCaptureService::scopeSignature).orElse("PROJECT_SAFE");
		String fingerprint = sha256(projectId + "|" + projectVersionId + "|" + catalogHash + "|" + datasourceId + "|"
				+ normalizeText(question) + "|" + sqlHash + "|" + scopeSignature);
		String qualitySummary = json(Map.of("source", "EPISODE_FEEDBACK", "rating",
				source.get("rating") == null ? 0 : source.get("rating"), "adopted", truth(source.get("adopted")),
				"sqlTraceId", Objects.toString(source.get("sql_trace_id"), ""), "structuredPlan", plan.isPresent(),
				"clarificationCount", resolutions.size()));
		String id = UUID.randomUUID().toString();
		QueryCaseLineageService.Lineage lineage = lineageService.forCapture(id, runId, episodeId);
		int inserted = jdbc.update(
				"""
						INSERT INTO qw_query_example
						(id, project_id, project_version_id, catalog_hash, datasource_id, episode_id, attempt_id,
						 run_id, context_hash, original_question, normalized_question, intent_type, conversation_independent,
						 resolved_time_range_json, typed_ir_json, resolution_json, quality_proof_json,
						 result_schema_hash, canonical_shape_hash, sql_text, sql_hash, fingerprint, status,
						 rebind_status, derived_from_case_ids, root_evidence_ids, evidence_lineage_hash,
						 quality_summary, create_time, update_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE',
						 'VALID', ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						ON CONFLICT (fingerprint) DO NOTHING
						""",
				id, projectId, projectVersionId, catalogHash, datasourceId, episodeId,
				Objects.toString(source.get("attempt_id"), ""), runId, contextHash,
				Objects.toString(source.get("original_question"), ""), question, intentType, conversationIndependent,
				timeRangeJson, typedIrJson, json(resolutions),
				versionedJson.write(JsonPayloadRegistry.QUERY_CASE_QUALITY_PROOF, proof),
				Objects.toString(proof.get("resultSchemaHash"), null), shapeHash, sql, sqlHash, fingerprint,
				lineageService.json(lineage.derivedFromCaseIds()), lineageService.json(lineage.rootEvidenceIds()),
				lineage.lineageHash(), qualitySummary);
		if (inserted == 0) {
			Optional<Map<String, Object>> existing = repository.findByFingerprint(fingerprint);
			if (existing.isPresent() && plan.isPresent()) {
				String existingId = Objects.toString(existing.get().get("id"), "");
				jdbc.update("""
						UPDATE qw_query_example
						SET typed_ir_json = COALESCE(typed_ir_json, ?::jsonb),
						    intent_type = COALESCE(intent_type, ?),
						    resolved_time_range_json = COALESCE(resolved_time_range_json, ?::jsonb),
						    canonical_shape_hash = COALESCE(canonical_shape_hash, ?),
						    quality_proof_json = ?, quality_summary = ?, update_time = CURRENT_TIMESTAMP
						WHERE id = ?
						""", typedIrJson, intentType, timeRangeJson, shapeHash,
						versionedJson.write(JsonPayloadRegistry.QUERY_CASE_QUALITY_PROOF, proof), qualitySummary, existingId);
				persistAssetReferences(existingId, catalogHash, plan.get());
				persistBindingDependencies(existingId, plan.get());
			}
			return existing.map(row -> repository.get(number(row.get("project_id")), Objects.toString(row.get("id"))));
		}
		plan.ifPresent(value -> {
			persistAssetReferences(id, catalogHash, value);
			persistBindingDependencies(id, value);
		});
		lineageService.appendEvent(id, "QUERY_CASE_CAPTURED", null, "CANDIDATE", "semevosql-system", "SYSTEM",
				Map.of("derivedFromCaseIds", lineage.derivedFromCaseIds(), "rootEvidenceIds", lineage.rootEvidenceIds(),
						"evidenceLineageHash", lineage.lineageHash()));
		if (autoApproved) {
			LearningAssetTrustPolicy.assertAutomaticPromotionAllowed(
					LearningAssetTrustPolicy.AssetClass.VALIDATED_QUERY_CASE);
			jdbc.update("""
					UPDATE qw_query_example
					SET status = 'APPROVED', reviewed_by = 'semevosql-system',
					    review_comment = 'Automatically reusable validated Semantic Query Case',
					    reviewed_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status = 'CANDIDATE'
					""", id);
			retrievalIndex.indexApprovedCase(id, question);
			lineageService.appendEvent(id, "QUERY_CASE_AUTO_APPROVED", "CANDIDATE", "APPROVED", "semevosql-system",
					"SYSTEM", Map.of("reason", "validated-single-source-success-with-scope-aware-reuse"));
		}
		return Optional.of(repository.get(projectId, id));
	}

	private boolean autoApprovable(Long projectVersionId, SemanticBlueprint plan, List<Map<String, Object>> resolutions,
			Map<String, Object> source, String runId) {
		if (plan == null || !plan.isExecutable() || plan.getSourceSubPlans().size() != 1
				|| (resolutions != null && !resolutions.isEmpty()) || !postExecutionReviewPassed(runId)) {
			return false;
		}
		Map<String, Object> explain = jsonMap(Objects.toString(source.get("explain_summary"), ""));
		String compilerMode = Objects.toString(explain.get("compilerMode"), "");
		int retryCount = source.get("retry_count") instanceof Number number ? number.intValue() : 0;
		if (Math.max(0, retryCount - 1) > 0) {
			return false;
		}
		boolean deterministic = "DETERMINISTIC".equalsIgnoreCase(compilerMode)
				|| "PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode);
		boolean positiveFeedback = truth(source.get("adopted"))
				|| source.get("rating") instanceof Number rating && rating.intValue() >= 4;
		boolean stronglyValidatedAdvanced = "SEMANTIC_SQL".equalsIgnoreCase(compilerMode) && positiveFeedback;
		if (!deterministic && !stronglyValidatedAdvanced) {
			return false;
		}
		Boolean published = jdbc.queryForObject(
				"SELECT EXISTS(SELECT 1 FROM qw_project_version WHERE id = ? AND status = 'PUBLISHED')", Boolean.class,
				projectVersionId);
		return Boolean.TRUE.equals(published);
	}

	private boolean postExecutionReviewPassed(String runId) {
		if (!StringUtils.hasText(runId)) {
			return false;
		}
		List<String> payloads;
		try {
			payloads = jdbc.queryForList("""
					SELECT payload FROM qw_run_event
					WHERE run_id = ? AND event_type = 'POST_EXECUTION_REVIEW'
					ORDER BY sequence DESC LIMIT 1
					""", String.class, runId);
		}
		catch (DataAccessException ignored) {
			return false;
		}
		if (payloads.isEmpty()) {
			return false;
		}
		Map<String, Object> payload = jsonMap(payloads.get(0));
		Object review = payload.get("review");
		if (!(review instanceof Map<?, ?> values)) {
			return false;
		}
		return "PASS".equalsIgnoreCase(Objects.toString(values.get("decision"), ""));
	}

	private Map<String, Object> jsonMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			JsonNode node = mapper.readTree(value);
			return node.isObject() ? mapper.convertValue(node,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					}) : Map.of();
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private Map<String, Object> qualityProof(Map<String, Object> source, String runId, SemanticBlueprint plan) {
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("episodeSucceeded", true);
		proof.put("attemptSucceeded", true);
		proof.put("sqlSucceeded", true);
		proof.put("userAdopted", truth(source.get("adopted")));
		proof.put("userRating", source.get("rating") == null ? 0 : source.get("rating"));
		proof.put("semanticPlanExecutable", plan != null && plan.isExecutable());
		proof.put("postExecutionReviewPassed", postExecutionReviewPassed(runId));
		if (StringUtils.hasText(runId)) {
			List<Map<String, Object>> artifacts = jdbc.queryForList("""
					SELECT artifact_id, artifact_type, schema_json, row_count, content_hash, status
					FROM qw_result_artifact WHERE run_id = ? AND status = 'READY'
					ORDER BY CASE WHEN artifact_type = 'MERGED_RESULT' THEN 0 ELSE 1 END, create_time DESC
					LIMIT 1
					""", runId);
			proof.put("finalResultArtifact", artifacts.isEmpty() ? Map.of() : artifacts.get(0));
			String schemas = artifacts.stream()
				.map(item -> Objects.toString(item.get("schema_json"), ""))
				.collect(Collectors.joining("|"));
			if (StringUtils.hasText(schemas)) {
				proof.put("resultSchemaHash", sha256(schemas));
			}
		}
		return Map.copyOf(proof);
	}

	private void persistBindingDependencies(String queryCaseId, SemanticBlueprint plan) {
		if (!StringUtils.hasText(queryCaseId) || plan == null) {
			return;
		}
		jdbc.update("DELETE FROM qw_query_case_binding_dependency WHERE query_example_id = ?", queryCaseId);
		for (SemanticBlueprint.BindingDependency dependency : plan.getBindingDependencies()) {
			if (!StringUtils.hasText(dependency.getAssetType()) || !StringUtils.hasText(dependency.getAssetKey())
					|| !StringUtils.hasText(dependency.getScope()) || !StringUtils.hasText(dependency.getSource())) {
				continue;
			}
			jdbc.update("""
					INSERT INTO qw_query_case_binding_dependency
					(query_example_id, phrase, asset_type, asset_key, binding_scope, binding_source, principal_id, source_record_id)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT DO NOTHING
					""", queryCaseId, dependency.getPhrase(), dependency.getAssetType(), dependency.getAssetKey(),
					dependency.getScope(), dependency.getSource(), dependency.getPrincipalId(), dependency.getSourceRecordId());
		}
	}

	private void persistAssetReferences(String queryCaseId, String catalogHash, SemanticBlueprint plan) {
		List<QueryCaseAssetReferenceRepository.ReferenceValue> references = new ArrayList<>();
		plan.getModels().forEach(value -> references.add(reference("MODEL", value.getModelCode(), value)));
		plan.getMetrics().forEach(value -> references.add(reference("METRIC", value.getMetricCode(), value)));
		plan.getDimensions().forEach(value -> references.add(reference("DIMENSION", value.getDimensionCode(), value)));
		plan.getGrains()
			.forEach(value -> references
				.add(reference("GRAIN", value.getModelCode() + ":" + value.getGrainCode(), value)));
		plan.getRelationships()
			.forEach(value -> references.add(reference("RELATIONSHIP", value.getRelationshipCode(), value)));
		plan.getRules().forEach(value -> references.add(reference("RULE", value.getRuleCode(), value)));
		plan.getEnumResolutions()
			.forEach(value -> references.add(reference("ENUM_VALUE",
					value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode(), value)));
		if (catalogRepository != null && patchAnalyzer != null && plan.getProjectId() != null
				&& plan.getProjectVersionId() != null) {
			Map<String, String> fingerprints = catalogAssetFingerprints(
					catalogRepository.loadCatalog(plan.getProjectId(), plan.getProjectVersionId()));
			references
				.replaceAll(reference -> new QueryCaseAssetReferenceRepository.ReferenceValue(reference.assetType(),
						reference.assetKey(), fingerprints.getOrDefault(
								reference.assetType() + ":" + reference.assetKey(), reference.assetFingerprint())));
		}
		references.stream()
			.distinct()
			.forEach(reference -> assetReferences.insertIfAbsent(queryCaseId, catalogHash, reference));
	}

	private QueryCaseAssetReferenceRepository.ReferenceValue reference(String type, String key, Object value) {
		return new QueryCaseAssetReferenceRepository.ReferenceValue(type, key, sha256(json(value)));
	}

	private Map<String, String> catalogAssetFingerprints(SemanticCatalogSnapshot catalog) {
		Map<String, String> values = new LinkedHashMap<>();
		catalog.getModels()
			.forEach(value -> values.put("MODEL:" + value.getModelCode(),
					patchAnalyzer.fingerprintAsset(AssetType.MODEL, value)));
		catalog.getMetrics()
			.forEach(value -> values.put("METRIC:" + value.getMetricCode(),
					patchAnalyzer.fingerprintAsset(AssetType.METRIC, value)));
		catalog.getDimensions()
			.forEach(value -> values.put("DIMENSION:" + value.getDimensionCode(),
					patchAnalyzer.fingerprintAsset(AssetType.DIMENSION, value)));
		catalog.getGrains()
			.forEach(value -> values.put("GRAIN:" + value.getModelCode() + ":" + value.getGrainCode(),
					patchAnalyzer.fingerprintAsset(AssetType.GRAIN, value)));
		catalog.getRelationships()
			.forEach(value -> values.put("RELATIONSHIP:" + value.getRelationshipCode(),
					patchAnalyzer.fingerprintAsset(AssetType.RELATIONSHIP, value)));
		catalog.getRules()
			.forEach(value -> values.put("RULE:" + value.getRuleCode(),
					patchAnalyzer.fingerprintAsset(AssetType.RULE, value)));
		catalog.getEnumValues()
			.forEach(value -> values.put(
					"ENUM_VALUE:" + value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode(),
					patchAnalyzer.fingerprintAsset(AssetType.ENUM_VALUE, value)));
		return Map.copyOf(values);
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

	static String scopeSignature(SemanticBlueprint plan) {
		if (plan == null || plan.getBindingDependencies() == null || plan.getBindingDependencies().isEmpty()) {
			return "PROJECT_SAFE";
		}
		return plan.getBindingDependencies()
			.stream()
			.map(dependency -> String.join("|", Objects.toString(dependency.getScope(), ""),
					Objects.toString(dependency.getSource(), ""), Objects.toString(dependency.getPrincipalId(), ""),
					Objects.toString(dependency.getAssetType(), ""), Objects.toString(dependency.getAssetKey(), "")))
			.sorted()
			.collect(java.util.stream.Collectors.joining(";"));
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
		shape.put("computationCapabilities", plan.getComputationIntent() == null ? List.of()
				: plan.getComputationIntent().capabilities().stream().map(Enum::name).sorted().toList());
		shape.put("computationRequirements", plan.getComputationIntent() == null ? List.of()
				: plan.getComputationIntent().canonicalRequirements());
		shape.put("hasTime", plan.getTimeRange() != null);
		return canonicalJson.hash(shape);
	}

	private List<String> sorted(List<String> values) {
		return values.stream().filter(Objects::nonNull).sorted().toList();
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode Query Case data", ex);
		}
	}

	private static String normalizeText(String value) {
		return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private static String normalizeSql(String value) {
		return normalizeText(value).replaceAll("\\s*([(),=<>+*/-])\\s*", "$1");
	}

	private static String firstText(Object first, Object second) {
		String value = Objects.toString(first, "").trim();
		return value.isBlank() ? Objects.toString(second, "").trim() : value;
	}

	private static Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private static boolean truth(Object value) {
		return Boolean.TRUE.equals(value) || Objects.equals(value, 1) || Objects.equals(value, (byte) 1);
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

}
