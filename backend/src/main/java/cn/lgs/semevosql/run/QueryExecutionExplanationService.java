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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Builds an explanation exclusively from persisted execution facts. */
@Service
public class QueryExecutionExplanationService {

	private final JdbcTemplate jdbc;

	private final SemanticPlanSnapshotService semanticPlanSnapshots;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public QueryExecutionExplanationService(JdbcTemplate jdbc, SemanticPlanSnapshotService semanticPlanSnapshots) {
		this.jdbc = jdbc;
		this.semanticPlanSnapshots = semanticPlanSnapshots;
	}

	public QueryExecutionExplanation explain(QueryRun run) {
		SemanticBlueprint plan = persistedSemanticPlan(run);
		MessageEvidence message = messageEvidence(run.runId());
		if (plan == null) {
			return new QueryExecutionExplanation(message.question(), message.bindings(), List.of(), List.of(), Map.of(),
					List.of(), List.of(), null, List.of(), List.of(), List.of(), sqlExecutions(run), reusedSteps(run),
					execution(run));
		}
		List<Map<String, Object>> definitions = new ArrayList<>();
		plan.getMetrics()
			.forEach(metric -> definitions
				.add(compact(Map.of("type", "METRIC", "code", Objects.toString(metric.getMetricCode(), ""), "name",
						Objects.toString(metric.getBusinessName(), ""), "expression",
						Objects.toString(metric.getExpression(), ""), "filter",
						Objects.toString(metric.getFilterExpression(), ""), "unit",
						Objects.toString(metric.getUnit(), "")))));
		plan.getRules()
			.forEach(rule -> definitions.add(compact(Map.of("type", "RULE", "code",
					Objects.toString(rule.getRuleCode(), ""), "name", Objects.toString(rule.getBusinessName(), ""),
					"expression", Objects.toString(rule.getExpression(), "")))));
		List<Map<String, Object>> filters = plan.getFilters()
			.stream()
			.map(value -> compact(Map.of("model", Objects.toString(value.getModelCode(), ""), "field",
					Objects.toString(value.getColumnName(), ""), "expression",
					Objects.toString(value.getExpression(), ""), "operator", Objects.toString(value.getOperator(), ""),
					"value", Objects.toString(value.getValue(), ""))))
			.toList();
		Map<String, Object> time = plan.getTimeRange() == null ? Map.of()
				: compact(Map.of("model", Objects.toString(plan.getTimeRange().getModelCode(), ""), "field",
						Objects.toString(plan.getTimeRange().getTimeColumn(), ""), "relativeExpression",
						Objects.toString(plan.getTimeRange().getRelativeExpression(), ""), "startInclusive",
						Objects.toString(plan.getTimeRange().getStartInclusive(), ""), "endExclusive",
						Objects.toString(plan.getTimeRange().getEndExclusive(), ""), "timeZone",
						Objects.toString(plan.getTimeRange().getTimeZone(), ""), "granularity",
						Objects.toString(plan.getTimeRange().getGranularity(), "")));
		List<Map<String, Object>> groups = plan.getGroupBy()
			.stream()
			.map(value -> compact(Map.of("model", Objects.toString(value.getModelCode(), ""), "field",
					Objects.toString(value.getColumnName(), ""), "expression",
					Objects.toString(value.getExpression(), ""), "alias", Objects.toString(value.getAlias(), ""))))
			.toList();
		List<Map<String, Object>> ordering = plan.getOrderBy()
			.stream()
			.map(value -> compact(Map.of("expression", Objects.toString(value.getExpression(), ""), "direction",
					Objects.toString(value.getDirection(), ""), "nulls", Objects.toString(value.getNulls(), ""))))
			.toList();
		List<Map<String, Object>> models = plan.getModels()
			.stream()
			.map(value -> compact(Map.of("code", Objects.toString(value.getModelCode(), ""), "name",
					Objects.toString(value.getBusinessName(), ""), "table",
					Objects.toString(value.getPhysicalTable(), ""), "datasourceId",
					value.getDatasourceId() == null ? "" : value.getDatasourceId())))
			.toList();
		List<Map<String, Object>> relationships = plan.getRelationships()
			.stream()
			.map(value -> compact(Map.of("code", Objects.toString(value.getRelationshipCode(), ""), "from",
					Objects.toString(value.getSourceModelCode(), ""), "to",
					Objects.toString(value.getTargetModelCode(), ""), "cardinality",
					Objects.toString(value.getCardinality(), ""), "joinType", Objects.toString(value.getJoinType(), ""),
					"condition", Objects.toString(value.getJoinCondition(), ""))))
			.toList();
		List<Map<String, Object>> datasources = plan.getSourceSubPlans()
			.stream()
			.map(value -> compact(Map.of("datasourceId", value.getDatasourceId() == null ? "" : value.getDatasourceId(),
					"domain", Objects.toString(value.getDomainCode(), ""), "responsibility",
					Objects.toString(value.getResponsibility(), ""), "tables",
					value.getPhysicalTables() == null ? List.of() : value.getPhysicalTables())))
			.toList();
		Integer limit = plan.getLimit();
		List<Map<String, Object>> semanticBindings = message.bindings().isEmpty() ? semanticBindings(plan)
				: message.bindings();
		return new QueryExecutionExplanation(message.question(), semanticBindings, definitions, filters, time, groups,
				ordering, limit, models, relationships, datasources, sqlExecutions(run), reusedSteps(run),
				execution(run));
	}

	private List<Map<String, Object>> semanticBindings(SemanticBlueprint plan) {
		List<Map<String, Object>> result = new ArrayList<>();
		plan.getMetrics().forEach(metric -> result.add(compact(Map.of(
				"assetType", "METRIC",
				"assetKey", Objects.toString(metric.getMetricCode(), ""),
				"displayName", Objects.toString(metric.getBusinessName(), ""),
				"modelCode", Objects.toString(metric.getModelCode(), ""),
				"source", "TYPED_PLAN"))));
		plan.getDimensions().forEach(dimension -> result.add(compact(Map.of(
				"assetType", "DIMENSION",
				"assetKey", Objects.toString(dimension.getDimensionCode(), ""),
				"displayName", Objects.toString(dimension.getBusinessName(), ""),
				"modelCode", Objects.toString(dimension.getModelCode(), ""),
				"source", "TYPED_PLAN"))));
		if (plan.getTimeRange() != null && StringUtils.hasText(plan.getTimeRange().getTimeColumn())) {
			result.add(compact(Map.of(
					"assetType", "TIME_COLUMN",
					"assetKey", Objects.toString(plan.getTimeRange().getModelCode(), "") + ":"
							+ plan.getTimeRange().getTimeColumn(),
					"displayName", plan.getTimeRange().getTimeColumn(),
					"modelCode", Objects.toString(plan.getTimeRange().getModelCode(), ""),
					"source", "TYPED_PLAN")));
		}
		return List.copyOf(result);
	}

	private SemanticBlueprint persistedSemanticPlan(QueryRun run) {
		return semanticPlanSnapshots.latest(run.runId()).orElse(null);
	}

	private MessageEvidence messageEvidence(String runId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT content, metadata_json FROM qw_project_message
				WHERE run_id = ? AND role = 'USER' ORDER BY sequence_no DESC LIMIT 1
				""", runId);
		if (rows.isEmpty()) {
			return new MessageEvidence("", List.of());
		}
		Map<String, Object> row = rows.get(0);
		String metadata = Objects.toString(row.get("metadata_json"), "");
		if (!StringUtils.hasText(metadata)) {
			return new MessageEvidence(Objects.toString(row.get("content"), ""), List.of());
		}
		try {
			JsonNode node = mapper.readTree(metadata).path("semanticBindings");
			List<Map<String, Object>> bindings = node.isArray()
					? mapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {
					}) : List.of();
			return new MessageEvidence(Objects.toString(row.get("content"), ""), bindings);
		}
		catch (Exception ignored) {
			return new MessageEvidence(Objects.toString(row.get("content"), ""), List.of());
		}
	}

	private List<Map<String, Object>> sqlExecutions(QueryRun run) {
		List<Map<String, Object>> sourceExecutions = jdbc.queryForList("""
				SELECT s.execution_key AS "executionKey", s.datasource_id AS "datasourceId", s.sql_text AS sql,
				       s.status, s.row_count AS "rowCount", s.result_artifact_id AS "resultArtifactId",
				       a.content_hash AS "resultContentHash", a.status AS "artifactStatus",
				       s.freshness_as_of AS "freshnessAsOf", s.error_message AS error
				FROM qw_source_sub_run s
				LEFT JOIN qw_result_artifact a ON a.artifact_id = s.result_artifact_id
				WHERE s.run_id = ?
				ORDER BY s.execution_key, s.create_time, s.datasource_id
				""", run.runId());
		if (!sourceExecutions.isEmpty()) {
			return sourceExecutions;
		}
		if (!StringUtils.hasText(run.attemptId())) {
			return List.of();
		}
		return jdbc.queryForList("""
				SELECT sql_text AS sql, guard_summary AS "guardSummary", cost_summary AS "costSummary",
				       result_summary AS "resultSummary", status, duration_ms AS "durationMs", error_type AS "errorType"
				FROM qw_sql_trace WHERE attempt_id = ? ORDER BY create_time
				""", run.attemptId());
	}

	private List<String> reusedSteps(QueryRun run) {
		if (!StringUtils.hasText(run.attemptId())) {
			return List.of();
		}
		LinkedHashMap<String, Boolean> reused = new LinkedHashMap<>();
		jdbc.queryForList("""
				SELECT node_name FROM qw_node_trace WHERE attempt_id = ? AND reused = TRUE ORDER BY create_time
				""", String.class, run.attemptId()).forEach(value -> reused.put(value, Boolean.TRUE));
		for (Map<String, Object> row : jdbc.queryForList(
				"""
						SELECT result_summary FROM qw_sql_trace WHERE attempt_id = ? AND status = 'SUCCEEDED' ORDER BY create_time
						""",
				run.attemptId())) {
			String summary = Objects.toString(row.get("result_summary"), "");
			if (!StringUtils.hasText(summary)) {
				continue;
			}
			try {
				JsonNode node = mapper.readTree(summary);
				String templateId = node.path("patternTemplateId").asText("");
				if (StringUtils.hasText(templateId)) {
					String patternId = node.path("patternId").asText("");
					String label = StringUtils.hasText(patternId)
							? "Query Pattern " + patternId + " / template " + templateId
							: "Query Pattern template " + templateId;
					reused.put(label, Boolean.TRUE);
				}
			}
			catch (Exception ignored) {
				// Explanation is best-effort over persisted facts; malformed legacy trace
				// metadata must not hide the actual SQL/execution facts.
			}
		}
		return List.copyOf(reused.keySet());
	}

	private Map<String, Object> execution(QueryRun run) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		result.put("runId", run.runId());
		result.put("status", run.status().name());
		result.put("errorCode", Objects.toString(run.errorCode(), ""));
		result.put("errorMessage", Objects.toString(run.errorMessage(), ""));
		if (run.startTime() != null && run.finishTime() != null) {
			result.put("durationMs", Duration.between(run.startTime(), run.finishTime()).toMillis());
		}
		List<Map<String, Object>> artifacts = jdbc.queryForList("""
				SELECT artifact_type AS "artifactType", row_count AS "rowCount", status
				FROM qw_result_artifact WHERE run_id = ? ORDER BY create_time DESC LIMIT 1
				""", run.runId());
		if (!artifacts.isEmpty()) {
			result.put("result", artifacts.get(0));
		}
		return Map.copyOf(result);
	}

	private Map<String, Object> compact(Map<String, Object> source) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, value) -> {
			if (value instanceof String text && text.isBlank()) {
				return;
			}
			if (value instanceof List<?> list && list.isEmpty()) {
				return;
			}
			result.put(key, value);
		});
		return Map.copyOf(result);
	}

	private record MessageEvidence(String question, List<Map<String, Object>> bindings) {
	}

}
