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
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.compiler.LoweringCapabilityProbe;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Governed structural memory for successful model-generated Semantic SQL.
 *
 * <p>Patterns are never executed directly. Only literal-free SQL shapes are recalled as
 * constrained-generation evidence, and the newly generated SQL still passes the normal
 * preflight, safety, cost and post-execution-review gates.</p>
 */
@Service
public class ValidatedSemanticSqlPatternService {

	private static final int QUARANTINE_CONSECUTIVE_FAILURES = 2;

	private static final int QUARANTINE_MIN_USES = 4;

	private static final double QUARANTINE_FAILURE_RATE = 0.40d;

	private final JdbcTemplate jdbc;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final RunExecutionFenceService executionFence;

	@Autowired
	public ValidatedSemanticSqlPatternService(JdbcTemplate jdbc, RunExecutionFenceService executionFence) {
		this.jdbc = jdbc;
		this.executionFence = executionFence;
	}

	/** Lightweight constructor retained for pure shape-validation tests. */
	public ValidatedSemanticSqlPatternService(JdbcTemplate jdbc) {
		this(jdbc, null);
	}

	@Transactional
	public boolean captureSuccessful(Long projectId, Long projectVersionId, String catalogHash, String runId,
			String attemptId, SemanticBlueprint plan, List<Map<String, Object>> sqlTraces, boolean corrected,
			boolean postExecutionReviewPassed) {
		if (executionFence != null && StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
		}
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash) || plan == null
				|| !plan.isExecutable() || corrected || !postExecutionReviewPassed || plan.getSourceSubPlans().size() != 1) {
			return false;
		}
		List<Map<String, Object>> succeeded = sqlTraces == null ? List.of()
				: sqlTraces.stream().filter(trace -> "SUCCEEDED".equalsIgnoreCase(text(trace.get("status")))).toList();
		if (succeeded.size() != 1) {
			return false;
		}
		Map<String, Object> trace = succeeded.get(0);
		Map<String, Object> explain = readJson(text(trace.get("explain_summary")));
		String compilerMode = text(explain.get("compilerMode"));
		if (!StringUtils.hasText(compilerMode) || "DETERMINISTIC".equalsIgnoreCase(compilerMode)
				|| "PATTERN_TEMPLATE".equalsIgnoreCase(compilerMode) || logicalRetryCount(trace) > 0) {
			return false;
		}
		String sqlShape = parameterizedSqlShape(text(trace.get("sql_text")));
		if (!StringUtils.hasText(sqlShape)) {
			return false;
		}
		Integer datasourceId = plan.getSourceSubPlans().get(0).getDatasourceId();
		String semanticShape = semanticShapeHash(plan);
		String computationShape = computationShapeHash(plan);
		String id = UUID.randomUUID().toString();
		String proof = json(Map.of("compilerMode", compilerMode, "postExecutionReview", "PASS", "retryCount",
				logicalRetryCount(trace), "source", "SUCCESSFUL_SEMANTIC_SQL"));
		int inserted = jdbc.update("""
				INSERT INTO qw_semantic_sql_pattern
				(id, project_id, project_version_id, catalog_hash, datasource_id, semantic_shape_hash,
				 computation_shape_hash, question_shape, sql_shape, quality_proof_json, binding_dependencies_json,
				 status, success_count, source_run_id, source_attempt_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'ACTIVE', 1, ?, ?)
				ON CONFLICT DO NOTHING
				""", id, projectId, projectVersionId, catalogHash, datasourceId, semanticShape, computationShape,
				Objects.toString(plan.getCanonicalQuery(), ""), sqlShape, proof, json(plan.getBindingDependencies()), runId,
				attemptId);
		if (inserted == 0) {
			jdbc.update("""
					UPDATE qw_semantic_sql_pattern
					SET success_count = success_count + 1, update_time = CURRENT_TIMESTAMP
					WHERE project_version_id = ? AND catalog_hash = ?
					  AND datasource_id IS NOT DISTINCT FROM ? AND semantic_shape_hash = ?
					  AND computation_shape_hash = ? AND sql_shape = ?
					""", projectVersionId, catalogHash, datasourceId, semanticShape, computationShape, sqlShape);
		}
		return true;
	}

	@Transactional
	public String renderReusablePatternHints(Long projectId, Long projectVersionId, String catalogHash,
			Integer datasourceId, String principalId, String runId, SemanticBlueprint plan, int limit) {
		return renderReusablePatternHints(projectId, projectVersionId, catalogHash, datasourceId, principalId, runId,
				null, plan, limit);
	}

	@Transactional
	public String renderReusablePatternHints(Long projectId, Long projectVersionId, String catalogHash,
			Integer datasourceId, String principalId, String runId, String attemptId, SemanticBlueprint plan, int limit) {
		if (projectId == null || projectVersionId == null || plan == null || !StringUtils.hasText(catalogHash)) {
			return "";
		}
		if (executionFence != null && StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			executionFence.assertActiveAndLock(runId, attemptId);
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT * FROM qw_semantic_sql_pattern
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				  AND datasource_id IS NOT DISTINCT FROM ? AND semantic_shape_hash = ?
				  AND computation_shape_hash = ? AND status = 'ACTIVE'
				ORDER BY success_count DESC, failure_count ASC, update_time DESC
				LIMIT ?
				""", projectId, projectVersionId, catalogHash, datasourceId, semanticShapeHash(plan),
				computationShapeHash(plan), Math.max(1, Math.min(limit, 5)));
		List<Map<String, Object>> compatible = rows.stream().filter(row -> scopeCompatible(row, principalId)).toList();
		if (compatible.isEmpty()) {
			return "";
		}
		StringBuilder result = new StringBuilder(
				"\n[已验证复杂 Semantic SQL Pattern，仅作为当前 Catalog 下的结构参考]\n");
		int index = 0;
		for (Map<String, Object> row : compatible) {
			index++;
			String patternId = text(row.get("id"));
			result.append(index).append(". Computation shape: ").append(computationShapeHash(plan))
				.append("\nSQL Shape: ").append(text(row.get("sql_shape"))).append("\n");
			recordApplied(patternId, runId);
		}
		result.append("必须针对本次 Semantic Blueprint 重新生成并重新绑定；不得复制历史 literal、字段或关系，且仍需通过全部运行时门禁。\n");
		return result.toString();
	}

	@Transactional
	public void recordRunOutcome(String runId, boolean succeeded) {
		recordRunOutcome(runId, null, succeeded);
	}

	@Transactional
	public void recordRunOutcome(String runId, String attemptId, boolean succeeded) {
		if (!StringUtils.hasText(runId)) {
			return;
		}
		if (executionFence != null && StringUtils.hasText(attemptId)) {
			executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
		}
		List<Map<String, Object>> usages = jdbc.queryForList("""
				SELECT id, pattern_id FROM qw_semantic_sql_pattern_usage
				WHERE run_id = ? AND event_type = 'APPLIED' AND valid = TRUE
				""", runId);
		for (Map<String, Object> usage : usages) {
			Long usageId = ((Number) usage.get("id")).longValue();
			String patternId = text(usage.get("pattern_id"));
			jdbc.update("""
					UPDATE qw_semantic_sql_pattern_usage
					SET event_type = ?, update_time = CURRENT_TIMESTAMP WHERE id = ? AND event_type = 'APPLIED'
					""", succeeded ? "SUCCEEDED" : "FAILED", usageId);
			if (succeeded) {
				jdbc.update("""
						UPDATE qw_semantic_sql_pattern
						SET success_count = success_count + 1, consecutive_failure_count = 0,
						    last_used_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
						WHERE id = ?
						""", patternId);
			}
			else {
				jdbc.update("""
						UPDATE qw_semantic_sql_pattern
						SET failure_count = failure_count + 1, consecutive_failure_count = consecutive_failure_count + 1,
						    last_used_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
						WHERE id = ?
						""", patternId);
				quarantineIfNeeded(patternId);
			}
		}
	}

	private void recordApplied(String patternId, String runId) {
		if (!StringUtils.hasText(patternId) || !StringUtils.hasText(runId)) {
			return;
		}
		String key = "semantic-sql-pattern-applied:" + patternId + ":" + runId;
		int inserted = jdbc.update("""
				INSERT INTO qw_semantic_sql_pattern_usage(pattern_id, run_id, event_type, valid, idempotency_key)
				VALUES (?, ?, 'APPLIED', TRUE, ?) ON CONFLICT (idempotency_key) DO NOTHING
				""", patternId, runId, key);
		if (inserted > 0) {
			jdbc.update("""
					UPDATE qw_semantic_sql_pattern
					SET usage_count = usage_count + 1, last_used_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status = 'ACTIVE'
					""", patternId);
		}
	}

	private void quarantineIfNeeded(String patternId) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qw_semantic_sql_pattern WHERE id = ?", patternId);
		if (rows.isEmpty()) {
			return;
		}
		Map<String, Object> row = rows.get(0);
		long successes = number(row.get("success_count"));
		long failures = number(row.get("failure_count"));
		long consecutive = number(row.get("consecutive_failure_count"));
		if (shouldQuarantine(successes, failures, consecutive)) {
			jdbc.update("""
					UPDATE qw_semantic_sql_pattern
					SET status = 'QUARANTINED', quarantine_reason = ?, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status = 'ACTIVE'
					""", consecutive >= QUARANTINE_CONSECUTIVE_FAILURES ? "consecutive failed recalls"
						: "failed recall rate exceeded governance threshold", patternId);
		}
	}

	private boolean scopeCompatible(Map<String, Object> row, String principalId) {
		String payload = text(row.get("binding_dependencies_json"));
		if (!StringUtils.hasText(payload)) {
			return true;
		}
		try {
			List<Map<String, Object>> dependencies = mapper.readValue(payload, new TypeReference<>() {
			});
			for (Map<String, Object> dependency : dependencies) {
				String scope = text(dependency.get("scope")).toUpperCase(Locale.ROOT);
				if ("QUERY".equals(scope) || "PROJECT_PENDING".equals(scope)) {
					return false;
				}
				if ("USER".equals(scope) && (!StringUtils.hasText(principalId)
						|| !Objects.equals(principalId, text(dependency.get("principalId"))))) {
					return false;
				}
			}
			return true;
		}
		catch (Exception invalid) {
			return false;
		}
	}

	private String semanticShapeHash(SemanticBlueprint plan) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("models", plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).sorted().toList());
		shape.put("metrics", plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).sorted().toList());
		shape.put("dimensions",
				plan.getDimensions().stream().map(SemanticBlueprint.DimensionSelection::getDimensionCode).sorted().toList());
		shape.put("grains", plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).sorted().toList());
		shape.put("relationships", plan.getRelationships()
			.stream()
			.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
			.sorted()
			.toList());
		shape.put("sourceCount", plan.getSourceSubPlans().size());
		return canonicalJson.hash(shape);
	}

	String computationShapeHash(SemanticBlueprint plan) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("capabilities", LoweringCapabilityProbe.effectiveCapabilities(plan).stream().map(Enum::name).sorted().toList());
		shape.put("requirements", plan.getComputationIntent() == null ? List.of()
				: plan.getComputationIntent().canonicalRequirements());
		shape.put("groups", plan.getGroupBy().stream().map(group -> Map.of("model",
				Objects.toString(group.getModelCode(), ""), "column", Objects.toString(group.getColumnName(), ""), "bucket",
				Objects.toString(group.getTimeBucketGranularity(), ""))).toList());
		shape.put("orderCount", plan.getOrderBy().size());
		shape.put("hasLimit", plan.getLimit() != null);
		shape.put("hasTimeRange", plan.getTimeRange() != null);
		return canonicalJson.hash(shape);
	}

	static String parameterizedSqlShape(String sql) {
		if (!StringUtils.hasText(sql)) {
			return "";
		}
		return sql.replaceAll("'(?:''|[^'])*'", "?")
			.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}:\\d{2})?\\b", "?")
			.replaceAll("(?<![A-Za-z0-9_$])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_$])", "?");
	}

	static boolean shouldQuarantine(long successes, long failures, long consecutiveFailures) {
		long evaluated = successes + failures;
		boolean badRate = evaluated >= QUARANTINE_MIN_USES
				&& failures / (double) evaluated >= QUARANTINE_FAILURE_RATE;
		return consecutiveFailures >= QUARANTINE_CONSECUTIVE_FAILURES || badRate;
	}

	private int logicalRetryCount(Map<String, Object> trace) {
		Object value = trace.get("retry_count");
		return value instanceof Number number ? Math.max(0, number.intValue() - 1) : 0;
	}

	private Map<String, Object> readJson(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return mapper.readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (Exception failure) {
			throw new IllegalArgumentException("Unable to encode Semantic SQL Pattern", failure);
		}
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	private static String text(Object value) {
		return Objects.toString(value, "").trim();
	}
}
