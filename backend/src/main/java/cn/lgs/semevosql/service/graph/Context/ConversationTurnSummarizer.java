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
package cn.lgs.semevosql.service.graph.Context;

import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.AssetFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.ClarificationFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.FilterFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.ResultFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.TimeRangeFact;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates a deterministic context summary after a run succeeds. */
@Slf4j
@Component
public class ConversationTurnSummarizer {

	private static final Pattern SENSITIVE_COLUMN = Pattern.compile(
			"phone|mobile|email|idcard|identity|password|credential|msisdn|openid|unionid|imei|imsi|"
					+ "user_name|customer_name|real_name|recipient_name|address|身份证|手机号|邮箱|密码|姓名|地址",
			Pattern.CASE_INSENSITIVE);

	private final QueryRunService runService;

	private final ExecutionSnapshotService snapshotService;

	private final JdbcTemplate jdbc;

	private final ConversationContextProperties properties;

	private final ApproximateTokenCounter tokenCounter;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public ConversationTurnSummarizer(QueryRunService runService, ExecutionSnapshotService snapshotService,
			JdbcTemplate jdbc, ConversationContextProperties properties, ApproximateTokenCounter tokenCounter) {
		this.runService = runService;
		this.snapshotService = snapshotService;
		this.jdbc = jdbc;
		this.properties = properties;
		this.tokenCounter = tokenCounter;
	}

	public CompletionContext summarize(String runId, String userQuestion, String plannerOutput) {
		try {
			QueryRun run = runService.get(runId);
			SemanticBlueprint plan = snapshotService.readTyped(run.executionSnapshot())
				.map(snapshot -> snapshot.semanticPlan())
				.orElse(null);
			ResultFact result = result(runId).orElse(null);
			ConversationTurnSummary summary = plan == null ? fallbackSummary(userQuestion, plannerOutput, result)
					: fromPlan(plan, clarifications(runId), result);
			String json = mapper.writeValueAsString(summary);
			String resultJson = result == null ? null : mapper.writeValueAsString(result);
			return new CompletionContext(summary.canonicalQuery(), json, resultJson,
					result == null ? null : result.artifactId(),
					tokenCounter.estimate(renderForEstimate(userQuestion, summary)));
		}
		catch (Exception ex) {
			log.warn("Unable to create structured conversation memory for run {}; using fallback turn summary", runId, ex);
			ConversationTurnSummary fallback = fallbackSummary(userQuestion, plannerOutput, null);
			try {
				String json = mapper.writeValueAsString(fallback);
				return new CompletionContext(userQuestion, json, null, null,
						tokenCounter.estimate(renderForEstimate(userQuestion, fallback)));
			}
			catch (Exception serializationFailure) {
				return new CompletionContext(userQuestion, null, null, null, tokenCounter.estimate(userQuestion));
			}
		}
	}

	private ConversationTurnSummary fromPlan(SemanticBlueprint plan, List<ClarificationFact> clarifications,
			ResultFact result) {
		return new ConversationTurnSummary(ConversationTurnSummary.CURRENT_SCHEMA_VERSION, plan.getCanonicalQuery(),
				plan.getModels()
					.stream()
					.map(value -> new AssetFact(value.getModelCode(), value.getBusinessName()))
					.toList(),
				plan.getMetrics()
					.stream()
					.map(value -> new AssetFact(value.getMetricCode(), value.getBusinessName()))
					.toList(),
				plan.getDimensions()
					.stream()
					.map(value -> new AssetFact(value.getDimensionCode(), value.getBusinessName()))
					.toList(),
				plan.getFilters()
					.stream()
					.map(value -> new FilterFact(value.getModelCode(), value.getColumnName(), value.getOperator(),
							value.getValue()))
					.toList(),
				timeRange(plan.getTimeRange()),
				plan.getGroupBy()
					.stream()
					.map(value -> firstText(value.getAlias(), value.getExpression(), value.getColumnName()))
					.filter(Objects::nonNull)
					.toList(),
				clarifications, result, null);
	}

	private ConversationTurnSummary fallbackSummary(String userQuestion, String plannerOutput, ResultFact result) {
		String retainedPlan = tokenCounter.estimate(plannerOutput) <= properties.getFallbackPlannerMaxTokens()
				? plannerOutput : null;
		return new ConversationTurnSummary(ConversationTurnSummary.CURRENT_SCHEMA_VERSION, userQuestion, List.of(),
				List.of(), List.of(), List.of(), null, List.of(), List.of(), result, retainedPlan);
	}

	private TimeRangeFact timeRange(SemanticBlueprint.TimeRangeSelection value) {
		return value == null ? null
				: new TimeRangeFact(value.getModelCode(), value.getTimeColumn(), value.getStartInclusive(),
						value.getEndExclusive(), value.getRelativeExpression(), value.getTimeZone(),
						value.getGranularity());
	}

	private List<ClarificationFact> clarifications(String runId) {
		return jdbc.query("""
				SELECT issue_type, asset_type, asset_key, raw_expression, resolved_value
				FROM qw_runtime_clarification
				WHERE run_id = ? AND status = 'ANSWERED'
				ORDER BY create_time
				""",
				(rs, rowNum) -> new ClarificationFact(rs.getString("issue_type"), rs.getString("asset_type"),
						rs.getString("asset_key"), rs.getString("raw_expression"), rs.getString("resolved_value")),
				runId);
	}

	private Optional<ResultFact> result(String runId) {
        List<ArtifactRow> rows = jdbc.query(cn.lgs.semevosql.multisource.ResultArtifactSelection.FINAL_RESULT_SQL,
            (rs, rowNum) -> new ArtifactRow(rs.getString("artifact_id"), rs.getString("artifact_type"),
                rs.getString("schema_json"), rs.getString("data_json"), rs.getLong("row_count")), runId, runId);
		if (rows.isEmpty()) {
			return cn.lgs.semevosql.multisource.ResultArtifactSelection.hasReviewReceipt(jdbc, runId)
                ? Optional.empty() : resultFromEvents(runId);
		}
		ArtifactRow row = rows.get(0);
		List<String> columns = columns(row.schemaJson());
		Map<String, Object> scalarValues = row.rowCount() <= properties.getMaxResultPreviewRows()
				? safeScalarValues(row.dataJson()) : Map.of();
		ResultFact candidate = new ResultFact(row.artifactId(), row.artifactType(), row.rowCount(), columns,
				scalarValues);
		try {
			if (tokenCounter.estimate(mapper.writeValueAsString(candidate)) > properties.getMaxResultSummaryTokens()) {
				candidate = new ResultFact(row.artifactId(), row.artifactType(), row.rowCount(), columns, Map.of());
			}
		}
		catch (Exception ignored) {
			candidate = new ResultFact(row.artifactId(), row.artifactType(), row.rowCount(), columns, Map.of());
		}
		return Optional.of(candidate);
	}

	private Optional<ResultFact> resultFromEvents(String runId) {
		List<EventRow> events = jdbc.query("""
				SELECT sequence, payload
				FROM qw_run_event
				WHERE run_id = ? AND event_type = 'NODE_OUTPUT' AND node_name = 'SqlExecuteNode'
				ORDER BY sequence DESC
				""", (rs, rowNum) -> new EventRow(rs.getLong("sequence"), rs.getString("payload")), runId);
		for (EventRow event : events) {
			try {
				JsonNode payload = mapper.readTree(event.payload());
				if (!"RESULT_SET".equals(payload.path("textType").asText())) {
					continue;
				}
				JsonNode text = mapper.readTree(payload.path("text").asText("{}"));
				JsonNode data = text.path("resultSet").path("data");
				if (!data.isArray()) {
					continue;
				}
				List<String> columns = new ArrayList<>();
				if (!data.isEmpty() && data.get(0).isObject()) {
					data.get(0).fieldNames().forEachRemaining(column -> {
						if (!sensitive(column)) {
							columns.add(column);
						}
					});
				}
				Map<String, Object> scalars = data.size() <= properties.getMaxResultPreviewRows()
						? safeScalarValues(data.toString()) : Map.of();
				ResultFact candidate = new ResultFact("event:" + runId + ":" + event.sequence(), "SINGLE_SOURCE_RESULT",
						data.size(), columns, scalars);
				if (tokenCounter.estimate(mapper.writeValueAsString(candidate)) > properties
					.getMaxResultSummaryTokens()) {
					candidate = new ResultFact(candidate.artifactId(), candidate.artifactType(), candidate.rowCount(),
							candidate.columns(), Map.of());
				}
				return Optional.of(candidate);
			}
			catch (Exception ignored) {
				// Continue with an earlier completed result event.
			}
		}
		return Optional.empty();
	}

	private List<String> columns(String schemaJson) {
		try {
			JsonNode root = mapper.readTree(schemaJson);
			List<String> result = new ArrayList<>();
			collectColumnNames(root, result);
			return result.stream().filter(column -> !sensitive(column)).distinct().limit(50).toList();
		}
		catch (Exception ignored) {
			return List.of();
		}
	}

	private void collectColumnNames(JsonNode node, List<String> target) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isObject()) {
			JsonNode name = node.get("name");
			if (name != null && name.isTextual()) {
				target.add(name.asText());
			}
			node.fields().forEachRemaining(entry -> collectColumnNames(entry.getValue(), target));
		}
		else if (node.isArray()) {
			node.forEach(value -> collectColumnNames(value, target));
		}
	}

	private Map<String, Object> safeScalarValues(String dataJson) {
		try {
			JsonNode row = firstRow(mapper.readTree(dataJson));
			if (row == null || !row.isObject()) {
				return Map.of();
			}
			Map<String, Object> values = new LinkedHashMap<>();
			row.fields().forEachRemaining(entry -> {
				if (values.size() >= 8 || sensitive(entry.getKey())) {
					return;
				}
				Object value = safeScalarValue(entry.getValue());
				if (value != null) {
					values.put(entry.getKey(), value);
				}
			});
			return Map.copyOf(values);
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private JsonNode firstRow(JsonNode root) {
		if (root == null || root.isNull()) {
			return null;
		}
		if (root.isArray()) {
			return root.isEmpty() ? null : root.get(0);
		}
		if (root.isObject()) {
			for (String field : List.of("rows", "data", "result")) {
				JsonNode nested = root.get(field);
				if (nested != null && nested.isArray() && !nested.isEmpty()) {
					return nested.get(0);
				}
			}
			return root;
		}
		return null;
	}

	private boolean sensitive(String column) {
		return SENSITIVE_COLUMN.matcher(column.toLowerCase(Locale.ROOT)).find();
	}

	private Object safeScalarValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isNumber() || value.isBoolean()) {
			return mapper.convertValue(value, Object.class);
		}
		if (!value.isTextual()) {
			return null;
		}
		String text = value.asText().trim();
		if (text.length() > 64 || !text.matches("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) {
			return null;
		}
		try {
			return new BigDecimal(text);
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String renderForEstimate(String question, ConversationTurnSummary summary) {
		return question + " " + summary.searchableText(question);
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	public record CompletionContext(String canonicalQuery, String contextSummaryJson, String resultSummary,
			String resultArtifactId, int promptTokenEstimate) {
	}

	private record ArtifactRow(String artifactId, String artifactType, String schemaJson, String dataJson,
			long rowCount) {
	}

	private record EventRow(long sequence, String payload) {
	}

}
