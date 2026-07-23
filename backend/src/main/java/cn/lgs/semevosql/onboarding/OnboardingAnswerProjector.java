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
package cn.lgs.semevosql.onboarding;

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.GoldenReplayMode;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Projects confirmed onboarding answers into governed semantic catalog tables. */
@Component
@RequiredArgsConstructor
class OnboardingAnswerProjector {

	private final JdbcTemplate jdbc;

	private final VersionedJson versionedJson = new VersionedJson();

	void project(Long projectId, Long versionId, OnboardingCategory category, String answer, String evidence) {
		Map<String, Object> value = answerObject(answer);
		switch (category) {
			case MODEL_BUSINESS_NAME -> jdbc.update("""
					UPDATE qw_semantic_model SET business_name = ?, evidence = ?, update_time = CURRENT_TIMESTAMP
					WHERE project_id = ? AND project_version_id = ? AND model_code = ?
					""", string(value, "businessName"), mergeEvidence(evidence, answer), projectId, versionId,
					string(value, "modelCode"));
			case MODEL_TYPE -> jdbc.update("""
					UPDATE qw_semantic_model SET model_type = ?, evidence = ?, update_time = CURRENT_TIMESTAMP
					WHERE project_id = ? AND project_version_id = ? AND model_code = ?
					""", string(value, "modelType"), mergeEvidence(evidence, answer), projectId, versionId,
					string(value, "modelCode"));
			case MODEL_GRAIN, MODEL_UNIQUENESS, DEFAULT_TIME_COLUMN -> projectGrain(projectId, versionId, value, evidence);
			case METRIC_DEFINITION, METRIC_AGGREGATION, METRIC_FILTER, METRIC_DISTINCT_RULE, METRIC_ADDITIVITY ->
				projectMetric(projectId, versionId, value, evidence);
			case DIMENSION_DEFINITION -> projectDimension(projectId, versionId, value, evidence);
			case ENUM_MEANING -> projectEnum(projectId, versionId, value, evidence);
			case RELATIONSHIP_JOIN, RELATIONSHIP_CARDINALITY -> projectRelationship(projectId, versionId, value, evidence);
			case SUPPORTED_QUERY_SCOPE, UNSUPPORTED_QUERY_SCOPE, BUSINESS_FILTER_RULE, LOGICAL_DELETE_RULE,
					TEST_DATA_FILTER_RULE, QUERY_AMBIGUITY_POLICY, RUNTIME_CLARIFICATION_POLICY ->
				projectRule(projectId, versionId, category, value, evidence, answer);
			case GOLDEN_QUESTION -> projectGoldenCase(projectId, value);
			default -> {
				// Scope, goals and acceptance answers remain first-class onboarding answers only.
			}
		}
	}

	private void projectGrain(Long projectId, Long versionId, Map<String, Object> value, String evidence) {
		String modelCode = string(value, "modelCode");
		if (!text(modelCode)) {
			return;
		}
		String grainCode = defaultString(value, "grainCode", modelCode + "_grain");
		String keyColumns = value.containsKey("keyColumns") ? jsonValue(value.get("keyColumns")) : null;
		String timeColumn = string(value, "timeColumn");
		String uniqueness = string(value, "uniquenessRule");
		int updated = jdbc.update("""
				UPDATE qw_semantic_grain SET key_columns = COALESCE(?, key_columns),
				time_column = COALESCE(?, time_column), uniqueness_rule = COALESCE(?, uniqueness_rule),
				description = COALESCE(?, description), evidence = ?, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND model_code = ? AND grain_code = ?
				""", emptyToNull(keyColumns), emptyToNull(timeColumn), emptyToNull(uniqueness),
				emptyToNull(string(value, "description")), mergeEvidence(evidence, jsonValue(value)), projectId, versionId,
				modelCode, grainCode);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_semantic_grain
					(project_id, project_version_id, model_code, grain_code, key_columns, time_column, uniqueness_rule,
					 description, evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, modelCode, grainCode, keyColumns, timeColumn, uniqueness,
					string(value, "description"), mergeEvidence(evidence, jsonValue(value)));
		}
	}

	private void projectMetric(Long projectId, Long versionId, Map<String, Object> value, String evidence) {
		String metricCode = string(value, "metricCode");
		if (!text(metricCode)) {
			return;
		}
		String modelCode = string(value, "modelCode");
		int updated = jdbc.update("""
				UPDATE qw_semantic_metric SET model_code = COALESCE(?, model_code),
				business_name = COALESCE(?, business_name), expression = COALESCE(?, expression),
				aggregation = COALESCE(?, aggregation), time_column = COALESCE(?, time_column),
				filter_expression = COALESCE(?, filter_expression), additive_type = COALESCE(?, additive_type),
				evidence = ?, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND metric_code = ?
				""", emptyToNull(modelCode), emptyToNull(string(value, "businessName")),
				emptyToNull(string(value, "expression")), emptyToNull(string(value, "aggregation")),
				emptyToNull(string(value, "timeColumn")), emptyToNull(string(value, "filterExpression")),
				emptyToNull(string(value, "additiveType")), mergeEvidence(evidence, jsonValue(value)), projectId, versionId,
				metricCode);
		if (updated == 0 && text(modelCode) && text(string(value, "expression"))) {
			jdbc.update("""
					INSERT INTO qw_semantic_metric
					(project_id, project_version_id, model_code, metric_code, business_name, expression, aggregation,
					 time_column, filter_expression, additive_type, evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, modelCode, metricCode, defaultString(value, "businessName", metricCode),
					string(value, "expression"), string(value, "aggregation"), string(value, "timeColumn"),
					string(value, "filterExpression"), string(value, "additiveType"),
					mergeEvidence(evidence, jsonValue(value)));
		}
	}

	private void projectDimension(Long projectId, Long versionId, Map<String, Object> value, String evidence) {
		String code = string(value, "dimensionCode");
		String modelCode = string(value, "modelCode");
		if (!text(code) || !text(modelCode)) {
			return;
		}
		int updated = jdbc.update("""
				UPDATE qw_semantic_dimension SET business_name = ?, column_name = ?, expression = ?, dimension_type = ?,
				evidence = ?, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND dimension_code = ?
				""", defaultString(value, "businessName", code), string(value, "columnName"),
				string(value, "expression"), string(value, "dimensionType"), mergeEvidence(evidence, jsonValue(value)),
				projectId, versionId, code);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_semantic_dimension
					(project_id, project_version_id, model_code, dimension_code, business_name, column_name,
					 expression, dimension_type, evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, modelCode, code, defaultString(value, "businessName", code),
					string(value, "columnName"), string(value, "expression"), string(value, "dimensionType"),
					mergeEvidence(evidence, jsonValue(value)));
		}
	}

	private void projectEnum(Long projectId, Long versionId, Map<String, Object> value, String evidence) {
		String modelCode = string(value, "modelCode");
		String columnName = string(value, "columnName");
		String valueCode = string(value, "valueCode");
		if (!text(modelCode) || !text(columnName) || !text(valueCode)) {
			return;
		}
		int updated = jdbc.update("""
				UPDATE qw_semantic_enum_value SET business_name = ?, description = ?, evidence = ?,
				status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND model_code = ? AND column_name = ? AND value_code = ?
				""", defaultString(value, "businessName", valueCode), string(value, "description"),
				mergeEvidence(evidence, jsonValue(value)), projectId, versionId, modelCode, columnName, valueCode);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_semantic_enum_value
					(project_id, project_version_id, model_code, column_name, value_code, business_name, description,
					 evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, modelCode, columnName, valueCode,
					defaultString(value, "businessName", valueCode), string(value, "description"),
					mergeEvidence(evidence, jsonValue(value)));
		}
	}

	private void projectRelationship(Long projectId, Long versionId, Map<String, Object> value, String evidence) {
		String code = string(value, "relationshipCode");
		if (!text(code)) {
			return;
		}
		int updated = jdbc.update("""
				UPDATE qw_semantic_relationship SET source_model_code = COALESCE(?, source_model_code),
				target_model_code = COALESCE(?, target_model_code), cardinality = COALESCE(?, cardinality),
				join_type = COALESCE(?, join_type), join_condition = COALESCE(?, join_condition),
				evidence = ?, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND relationship_code = ?
				""", emptyToNull(string(value, "sourceModelCode")), emptyToNull(string(value, "targetModelCode")),
				emptyToNull(string(value, "cardinality")), emptyToNull(string(value, "joinType")),
				emptyToNull(string(value, "joinCondition")), mergeEvidence(evidence, jsonValue(value)), projectId,
				versionId, code);
		if (updated == 0 && text(string(value, "sourceModelCode")) && text(string(value, "targetModelCode"))
				&& text(string(value, "joinCondition"))) {
			jdbc.update("""
					INSERT INTO qw_semantic_relationship
					(project_id, project_version_id, relationship_code, source_model_code, target_model_code,
					 cardinality, join_type, join_condition, evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, code, string(value, "sourceModelCode"), string(value, "targetModelCode"),
					defaultString(value, "cardinality", "MANY_TO_ONE"), defaultString(value, "joinType", "LEFT"),
					string(value, "joinCondition"), mergeEvidence(evidence, jsonValue(value)));
		}
	}

	private void projectRule(Long projectId, Long versionId, OnboardingCategory category, Map<String, Object> value,
			String evidence, String rawAnswer) {
		String code = defaultString(value, "ruleCode", category.name().toLowerCase(Locale.ROOT));
		String expression = defaultString(value, "expression", rawAnswer);
		String modelCode = string(value, "modelCode");
		String type = switch (category) {
			case SUPPORTED_QUERY_SCOPE -> "SUPPORTED_QUERY_SCOPE";
			case UNSUPPORTED_QUERY_SCOPE -> "UNSUPPORTED_QUERY_SCOPE";
			case LOGICAL_DELETE_RULE -> "LOGICAL_DELETE";
			case TEST_DATA_FILTER_RULE -> "TEST_DATA_FILTER";
			case QUERY_AMBIGUITY_POLICY -> "QUERY_AMBIGUITY_POLICY";
			case RUNTIME_CLARIFICATION_POLICY -> "RUNTIME_CLARIFICATION_POLICY";
			default -> "BUSINESS_FILTER";
		};
		int updated = jdbc.update("""
				UPDATE qw_semantic_rule SET model_code = ?, rule_type = ?, business_name = ?, expression = ?,
				evidence = ?, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND rule_code = ?
				""", modelCode, type, defaultString(value, "businessName", code), expression,
				mergeEvidence(evidence, rawAnswer), projectId, versionId, code);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_semantic_rule
					(project_id, project_version_id, model_code, rule_code, rule_type, business_name, expression,
					 evidence, status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", projectId, versionId, modelCode, code, type, defaultString(value, "businessName", code),
					expression, mergeEvidence(evidence, rawAnswer));
		}
	}

	private void projectGoldenCase(Long projectId, Map<String, Object> value) {
		String caseCode = string(value, "caseCode");
		String question = string(value, "question");
		if (!text(caseCode) || !text(question)) {
			return;
		}
		GoldenReplayMode replayMode = GoldenReplayMode.from(value.get("replayMode"));
		String datasetVersion = Objects.toString(value.get("datasetVersion"), null);
		if (replayMode == GoldenReplayMode.FIXTURE && !text(datasetVersion)) {
			throw new IllegalArgumentException("FIXTURE Golden Case requires datasetVersion");
		}
		String expected = versionedJson.write(JsonPayloadRegistry.GOLDEN_CASE_EXPECTED,
				value.getOrDefault("expected", Map.of()));
		int updated = jdbc.update("""
				UPDATE qw_golden_case SET question = ?, replay_mode = ?, dataset_version = ?,
				 expected_json = ?, enabled = TRUE, update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND case_code = ?
				""", question, replayMode.name(), datasetVersion, expected, projectId, caseCode);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO qw_golden_case
					(id, project_id, case_code, question, replay_mode, dataset_version, expected_json,
					 enabled, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", UUID.randomUUID().toString(), projectId, caseCode, question, replayMode.name(), datasetVersion,
					expected);
		}
	}

	private Map<String, Object> answerObject(String answer) {
		try {
			if (answer.trim().startsWith("{")) {
				return JsonUtil.getObjectMapper().readValue(answer, new TypeReference<>() {
				});
			}
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("answer must match the question answerSchema", ex);
		}
		return new LinkedHashMap<>(Map.of("value", answer));
	}

	private static String string(Map<String, Object> value, String key) {
		Object item = value.get(key);
		return item == null ? null : Objects.toString(item, null);
	}

	private static String defaultString(Map<String, Object> value, String key, String defaultValue) {
		String result = string(value, key);
		return text(result) ? result : defaultValue;
	}

	private static String jsonValue(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize onboarding data", ex);
		}
	}

	private static String mergeEvidence(String evidence, String answer) {
		return Objects.toString(evidence, "") + " | onboarding=" + answer;
	}

	private static String emptyToNull(String value) {
		return text(value) ? value : null;
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

}
