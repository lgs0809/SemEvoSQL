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

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Produces the same context-dependency fingerprint during Query Case capture and recall.
 * It excludes per-run clarification identifiers and timestamps, retaining only semantic
 * resolution facts.
 */
@Component
public class ConversationContextDependencyFingerprintService {

	private static final List<String> CONTEXT_REFERENCES = List.of("这个", "那个", "再", "继续", "上述", "它", "刚才", "同样", "之前",
			"上次", "前面");

	private final JdbcTemplate jdbc;

	private final CanonicalJson canonicalJson;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public ConversationContextDependencyFingerprintService(JdbcTemplate jdbc, CanonicalJson canonicalJson) {
		this.jdbc = jdbc;
		this.canonicalJson = canonicalJson;
	}

	public String fingerprint(String runId, String normalizedQuestion) {
		List<Map<String, Object>> resolutions = resolutions(runId);
		if (conversationIndependent(normalizedQuestion, resolutions)) {
			return null;
		}
		Map<String, Object> dependency = new LinkedHashMap<>();
		dependency.put("conversationDependent", true);
		dependency.put("resolutions", resolutions.stream().map(this::semanticResolution).toList());
		dependency.put("previousTurn", previousTurnDependency(runId));
		return canonicalJson.hash(dependency);
	}

	public List<Map<String, Object>> resolutions(String runId) {
		if (!StringUtils.hasText(runId)) {
			return List.of();
		}
		return jdbc.queryForList("""
				SELECT issue_type, asset_type, asset_key, raw_expression, resolved_value,
				       resolution_source, selected_option, custom_answer
				FROM qw_runtime_clarification
				WHERE run_id = ? AND status = 'ANSWERED' ORDER BY create_time
				""", runId);
	}

	public boolean conversationIndependent(String question, List<Map<String, Object>> resolutions) {
		String normalized = Objects.toString(question, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		boolean reference = CONTEXT_REFERENCES.stream().anyMatch(normalized::contains);
		return !reference && resolutions.stream()
			.noneMatch(item -> "CONVERSATION_CONTEXT".equals(Objects.toString(item.get("resolution_source"), "")));
	}

	private Map<String, Object> previousTurnDependency(String runId) {
		if (!StringUtils.hasText(runId)) {
			return Map.of();
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT previous.context_summary_json, previous.canonical_query, previous.user_question
				FROM qw_conversation_turn current_turn
				JOIN qw_conversation_turn previous
				  ON previous.thread_id = current_turn.thread_id
				 AND previous.turn_sequence < current_turn.turn_sequence
				 AND previous.status = 'COMPLETED'
				WHERE current_turn.run_id = ?
				ORDER BY previous.turn_sequence DESC
				LIMIT 1
				""", runId);
		if (rows.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> row = rows.get(0);
		String summaryJson = Objects.toString(row.get("context_summary_json"), "");
		if (StringUtils.hasText(summaryJson)) {
			try {
				return semanticTurn(mapper.readValue(summaryJson, ConversationTurnSummary.class));
			}
			catch (Exception ignored) {
				// Partially migrated rows fall back to the durable question fields.
			}
		}
		String query = Objects.toString(row.get("canonical_query"), "");
		if (!StringUtils.hasText(query)) {
			query = Objects.toString(row.get("user_question"), "");
		}
		return StringUtils.hasText(query) ? Map.of("canonicalQuery", query) : Map.of();
	}

	private Map<String, Object> semanticTurn(ConversationTurnSummary summary) {
		Map<String, Object> result = new LinkedHashMap<>();
		put(result, "canonicalQuery", summary.canonicalQuery());
		result.put("models", summary.models());
		result.put("metrics", summary.metrics());
		result.put("dimensions", summary.dimensions());
		result.put("filters", summary.filters());
		put(result, "timeRange", summary.timeRange());
		result.put("groupBy", summary.groupBy());
		result.put("clarifications", summary.clarifications());
		if (summary.result() != null) {
			result.put("result",
					Map.of("artifactType", summary.result().artifactType(), "rowCount", summary.result().rowCount(),
							"columns", summary.result().columns(), "scalarValues", summary.result().scalarValues()));
		}
		return Map.copyOf(result);
	}

	private void put(Map<String, Object> target, String key, Object value) {
		if (value != null && (!(value instanceof String text) || !text.isBlank())) {
			target.put(key, value);
		}
	}

	private Map<String, Object> semanticResolution(Map<String, Object> source) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String field : List.of("issue_type", "asset_type", "asset_key", "raw_expression", "resolved_value",
				"resolution_source", "selected_option", "custom_answer")) {
			Object value = source.get(field);
			if (value != null && !Objects.toString(value, "").isBlank()) {
				result.put(field, value);
			}
		}
		return Map.copyOf(result);
	}

}
