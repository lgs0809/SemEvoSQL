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
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Stores reusable execution templates underneath the existing Query Pattern identity. A
 * template is executable only when it originated from the deterministic compiler and a
 * successful guarded execution. Similar-but-not-isomorphic shapes remain plan-only.
 */
@Service
public class QueryPatternTemplateService {

	private final JdbcTemplate jdbc;

	private final CanonicalJson canonicalJson;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final RunExecutionFenceService executionFence;

	@Autowired
	public QueryPatternTemplateService(JdbcTemplate jdbc, CanonicalJson canonicalJson,
			RunExecutionFenceService executionFence) {
		this.jdbc = jdbc;
		this.canonicalJson = canonicalJson;
		this.executionFence = executionFence;
	}

	/** Lightweight constructor retained for focused shape tests. */
	public QueryPatternTemplateService(JdbcTemplate jdbc, CanonicalJson canonicalJson) {
		this(jdbc, canonicalJson, null);
	}

	@Transactional
	public CaptureMode captureSuccessful(String patternId, Long projectId, Long projectVersionId, String catalogHash,
			String runId, String attemptId, SemanticBlueprint plan, List<Map<String, Object>> sqlTraces,
			boolean hadClarification, boolean corrected) {
		return captureSuccessful(patternId, projectId, projectVersionId, catalogHash, runId, attemptId, plan, sqlTraces,
				hadClarification, corrected, true);
	}

	@Transactional
	public CaptureMode captureSuccessful(String patternId, Long projectId, Long projectVersionId, String catalogHash,
			String runId, String attemptId, SemanticBlueprint plan, List<Map<String, Object>> sqlTraces,
			boolean hadClarification, boolean corrected, boolean postExecutionReviewPassed) {
		assertFinalizer(runId, attemptId);
		if (plan == null || !plan.isExecutable() || corrected || projectId == null || projectVersionId == null
				|| !StringUtils.hasText(patternId)) {
			return CaptureMode.NONE;
		}
		if (plan.getSourceSubPlans().size() != 1 || !postExecutionReviewPassed) {
			upsertPlanOnly(patternId, projectId, projectVersionId, catalogHash, runId, attemptId, plan, null);
			return CaptureMode.PLAN_ONLY;
		}
		Integer datasourceId = plan.getSourceSubPlans().get(0).getDatasourceId();
		List<Map<String, Object>> succeeded = sqlTraces == null ? List.of()
				: sqlTraces.stream().filter(trace -> "SUCCEEDED".equalsIgnoreCase(text(trace.get("status")))).toList();
		if (succeeded.size() != 1 || hadClarification) {
			upsertPlanOnly(patternId, projectId, projectVersionId, catalogHash, runId, attemptId, plan, datasourceId);
			return CaptureMode.PLAN_ONLY;
		}
		Map<String, Object> trace = succeeded.get(0);
		Map<String, Object> preflight = readJson(text(trace.get("explain_summary")));
		String compilerMode = text(preflight.get("compilerMode"));
		String sql = text(trace.get("sql_text"));
		if (!"DETERMINISTIC".equalsIgnoreCase(compilerMode) || !StringUtils.hasText(sql)
				|| logicalRetryCount(trace) > 0) {
			upsertPlanOnly(patternId, projectId, projectVersionId, catalogHash, runId, attemptId, plan, datasourceId);
			return CaptureMode.PLAN_ONLY;
		}
		int parameterCount = integer(preflight.get("parameterCount"));
		upsert(patternId, projectId, projectVersionId, catalogHash, executionShapeHash(plan, datasourceId),
				datasourceId, "EXECUTABLE", plan, sql, parameterCount, runId, attemptId, null);
		return CaptureMode.EXECUTABLE;
	}

	public Optional<ReusableTemplate> findExecutable(Long projectId, Long projectVersionId, String catalogHash,
			SemanticBlueprint plan, CompiledSourceQuery compiled) {
		if (plan == null || compiled == null || projectId == null || projectVersionId == null) {
			return Optional.empty();
		}
		String shapeHash = executionShapeHash(plan, compiled.datasourceId());
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT * FROM qw_query_pattern_template
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ? AND execution_shape_hash = ?
				  AND datasource_id = ? AND reuse_mode = 'EXECUTABLE' AND status = 'ACTIVE'
				LIMIT 1
				""", projectId, projectVersionId, catalogHash, shapeHash, compiled.datasourceId());
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> row = rows.get(0);
		String sql = text(row.get("sql_template"));
		if (!StringUtils.hasText(sql) || integer(row.get("parameter_count")) != compiled.parameters().size()) {
			return Optional.empty();
		}
		// The current deterministic compiler is the authority for applicability. A stored
		// template is reused only when its SQL skeleton is identical; runtime parameters
		// come
		// from the newly compiled plan and SQL guards/preflight still execute normally.
		if (!sqlSkeleton(sql).equals(sqlSkeleton(compiled.sql()))) {
			return Optional.empty();
		}
		return Optional.of(new ReusableTemplate(text(row.get("id")), text(row.get("pattern_id")), sql,
				compiled.parameters(), shapeHash));
	}

	@Transactional
	public void markUsed(String templateId) {
		markUsed(templateId, null, null);
	}

	@Transactional
	public void markUsed(String templateId, String runId, String attemptId) {
		if (!StringUtils.hasText(templateId)) {
			return;
		}
		if (StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			if (executionFence == null) {
				throw new IllegalStateException("Run execution fence is required for attempt-scoped pattern usage");
			}
			executionFence.assertActiveAndLock(runId, attemptId);
		}
		jdbc.update("""
				UPDATE qw_query_pattern_template
				SET usage_count = usage_count + 1, last_used_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'ACTIVE'
				""", templateId);
	}

	@Transactional
	public void invalidateByRun(String runId, String reason) {
		if (!StringUtils.hasText(runId)) {
			return;
		}
		jdbc.update("""
				UPDATE qw_query_pattern_template
				SET status = 'INVALIDATED', invalidation_reason = ?, update_time = CURRENT_TIMESTAMP
				WHERE source_run_id = ? AND status = 'ACTIVE'
				""", trim(reason, 1000), runId);
	}

	private void assertFinalizer(String runId, String attemptId) {
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			return;
		}
		if (executionFence == null) {
			throw new IllegalStateException("Run execution fence is required for attempt-scoped pattern capture");
		}
		executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
	}

	public String executionShapeHash(SemanticBlueprint plan, Integer datasourceId) {
		return canonicalJson.hash(executionShape(plan, datasourceId));
	}

	private void upsertPlanOnly(String patternId, Long projectId, Long projectVersionId, String catalogHash,
			String runId, String attemptId, SemanticBlueprint plan, Integer datasourceId) {
		Integer sourceId = datasourceId == null && plan.getSourceSubPlans().size() == 1
				? plan.getSourceSubPlans().get(0).getDatasourceId() : datasourceId;
		if (sourceId == null) {
			return;
		}
		upsert(patternId, projectId, projectVersionId, catalogHash, executionShapeHash(plan, sourceId), sourceId,
				"PLAN_ONLY", plan, null, 0, runId, attemptId, null);
	}

	private void upsert(String patternId, Long projectId, Long projectVersionId, String catalogHash, String shapeHash,
			Integer datasourceId, String reuseMode, SemanticBlueprint plan, String sql, int parameterCount,
			String runId, String attemptId, String invalidationReason) {
		jdbc.update(
				"""
						INSERT INTO qw_query_pattern_template
						(id, pattern_id, project_id, project_version_id, catalog_hash, execution_shape_hash, datasource_id,
						 reuse_mode, plan_template_json, sql_template, parameter_count, source_run_id, source_attempt_id,
						 status, invalidation_reason, create_time, update_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						ON CONFLICT (project_version_id, execution_shape_hash, datasource_id)
						DO UPDATE SET pattern_id = EXCLUDED.pattern_id, catalog_hash = EXCLUDED.catalog_hash,
						 reuse_mode = CASE WHEN qw_query_pattern_template.reuse_mode = 'EXECUTABLE'
						                    AND EXCLUDED.reuse_mode = 'PLAN_ONLY' THEN qw_query_pattern_template.reuse_mode
						                   ELSE EXCLUDED.reuse_mode END,
						 plan_template_json = EXCLUDED.plan_template_json,
						 sql_template = CASE WHEN EXCLUDED.reuse_mode = 'EXECUTABLE' THEN EXCLUDED.sql_template
						                     ELSE qw_query_pattern_template.sql_template END,
						 parameter_count = CASE WHEN EXCLUDED.reuse_mode = 'EXECUTABLE' THEN EXCLUDED.parameter_count
						                        ELSE qw_query_pattern_template.parameter_count END,
						 source_run_id = CASE WHEN EXCLUDED.reuse_mode = 'EXECUTABLE' THEN EXCLUDED.source_run_id
						                      ELSE qw_query_pattern_template.source_run_id END,
						 source_attempt_id = CASE WHEN EXCLUDED.reuse_mode = 'EXECUTABLE' THEN EXCLUDED.source_attempt_id
						                          ELSE qw_query_pattern_template.source_attempt_id END,
						 status = 'ACTIVE', invalidation_reason = EXCLUDED.invalidation_reason, update_time = CURRENT_TIMESTAMP
						""",
				UUID.randomUUID().toString(), patternId, projectId, projectVersionId, catalogHash, shapeHash,
				datasourceId, reuseMode, json(plan), sql, parameterCount, runId, attemptId, invalidationReason);
	}

	private Map<String, Object> executionShape(SemanticBlueprint plan, Integer datasourceId) {
		LinkedHashMap<String, Object> shape = new LinkedHashMap<>();
		shape.put("datasourceId", datasourceId);
		shape.put("models", plan.getModels().stream().map(value -> value.getModelCode()).sorted().toList());
		shape.put("metrics", plan.getMetrics()
			.stream()
			.map(value -> Map.of("code", nullSafe(value.getMetricCode()), "model", nullSafe(value.getModelCode()),
					"expression", nullSafe(value.getExpression()), "filter", nullSafe(value.getFilterExpression())))
			.toList());
		shape.put("dimensions", plan.getDimensions()
			.stream()
			.map(value -> Map.of("code", nullSafe(value.getDimensionCode()), "model", nullSafe(value.getModelCode()),
					"column", nullSafe(value.getColumnName()), "expression", nullSafe(value.getExpression())))
			.toList());
		shape.put("projections",
				plan.getProjections()
					.stream()
					.map(value -> Map.of("model", nullSafe(value.getModelCode()), "column",
							nullSafe(value.getColumnName()), "expression", nullSafe(value.getExpression()), "alias",
							nullSafe(value.getAlias()), "type", nullSafe(value.getProjectionType())))
					.toList());
		shape.put("filters", plan.getFilters()
			.stream()
			.map(value -> Map.of("model", nullSafe(value.getModelCode()), "column", nullSafe(value.getColumnName()),
					"expression", nullSafe(value.getExpression()), "operator", nullSafe(value.getOperator()),
					"valueType", nullSafe(value.getValueType()), "valueShape", valueShape(value.getValue())))
			.toList());
		SemanticBlueprint.TimeRangeSelection time = plan.getTimeRange();
		shape.put("time",
				time == null ? Map.of() : Map.of("model", nullSafe(time.getModelCode()), "column",
						nullSafe(time.getTimeColumn()), "hasStart", StringUtils.hasText(time.getStartInclusive()),
						"hasEnd", StringUtils.hasText(time.getEndExclusive()), "relativeKind",
						relativeKind(time.getRelativeExpression()), "granularity", nullSafe(time.getGranularity())));
		shape.put("groupBy", plan.getGroupBy()
			.stream()
			.map(value -> Map.of("model", nullSafe(value.getModelCode()), "column", nullSafe(value.getColumnName()),
					"expression", nullSafe(value.getExpression()), "alias", nullSafe(value.getAlias())))
			.toList());
		shape.put("orderBy",
				plan.getOrderBy()
					.stream()
					.map(value -> Map.of("expression", nullSafe(value.getExpression()), "direction",
							nullSafe(value.getDirection()), "nulls", nullSafe(value.getNulls())))
					.toList());
		shape.put("limitParameterized", plan.getLimit() != null);
		shape.put("relationships",
				plan.getRelationships()
					.stream()
					.map(value -> Map.of("code", nullSafe(value.getRelationshipCode()), "joinType",
							nullSafe(value.getJoinType()), "condition", nullSafe(value.getJoinCondition())))
					.toList());
		shape.put("rules", plan.getRules().stream().map(value -> value.getRuleCode()).sorted().toList());
		return Map.copyOf(shape);
	}

	private Object valueShape(Object value) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof List<?> list) {
			return Map.of("kind", "LIST", "size", list.size(), "elementType",
					list.isEmpty() || list.get(0) == null ? "UNKNOWN" : list.get(0).getClass().getSimpleName());
		}
		return value.getClass().getSimpleName();
	}

	private String relativeKind(String expression) {
		if (!StringUtils.hasText(expression)) {
			return "NONE";
		}
		String normalized = expression.toLowerCase(java.util.Locale.ROOT);
		if (normalized.contains("month") || normalized.contains("月")) {
			return "MONTH";
		}
		if (normalized.contains("week") || normalized.contains("周")) {
			return "WEEK";
		}
		if (normalized.contains("year") || normalized.contains("年")) {
			return "YEAR";
		}
		if (normalized.contains("day") || normalized.contains("日") || normalized.contains("天")) {
			return "DAY";
		}
		return "RELATIVE";
	}

	private int logicalRetryCount(Map<String, Object> trace) {
		return Math.max(0, integer(trace.get("retry_count")) - 1);
	}

	private String sqlSkeleton(String sql) {
		return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
	}

	private Map<String, Object> readJson(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			JsonNode node = mapper.readTree(json);
			if (!node.isObject()) {
				return Map.of();
			}
			return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize Query Pattern template", ex);
		}
	}

	private static int integer(Object value) {
		return value instanceof Number number ? number.intValue()
				: StringUtils.hasText(Objects.toString(value, "")) ? Integer.parseInt(value.toString()) : 0;
	}

	private static String text(Object value) {
		return Objects.toString(value, "");
	}

	private static String nullSafe(Object value) {
		return Objects.toString(value, "");
	}

	private static String trim(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String text = value.trim();
		return text.length() <= max ? text : text.substring(0, max);
	}

	public enum CaptureMode {

		NONE, PLAN_ONLY, EXECUTABLE

	}

	public record ReusableTemplate(String templateId, String patternId, String sql, List<Object> parameters,
			String executionShapeHash) {
	}

}
