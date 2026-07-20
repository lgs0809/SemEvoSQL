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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationOption;
import cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationStatus;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RuntimeClarificationRepository {

	private final JdbcTemplate jdbc;

    public int questionCount(String runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM qw_runtime_clarification WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

	public Optional<RuntimeClarification> findPendingByRun(String runId) {
		return first(jdbc.query("""
				SELECT * FROM qw_runtime_clarification
				WHERE run_id = ? AND status = 'PENDING' ORDER BY create_time DESC LIMIT 1
				""", this::map, runId));
	}

	public Optional<RuntimeClarification> findLatestByRun(String runId) {
		return first(jdbc.query("""
				SELECT * FROM qw_runtime_clarification WHERE run_id = ? ORDER BY create_time DESC LIMIT 1
				""", this::map, runId));
	}

	public List<RuntimeClarification> answeredByRun(String runId) {
		return jdbc.query("""
				SELECT * FROM qw_runtime_clarification
				WHERE run_id = ? AND status = 'ANSWERED' ORDER BY create_time, clarification_id
				""", this::map, runId);
	}

	public boolean hasAnsweredQuestion(String runId, String question) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_runtime_clarification
				WHERE run_id = ? AND status = 'ANSWERED' AND question = ?
				""", Integer.class, runId, question);
		return count != null && count > 0;
	}

	public Optional<RuntimeClarification> find(String clarificationId) {
		return first(jdbc.query("SELECT * FROM qw_runtime_clarification WHERE clarification_id = ?", this::map,
				clarificationId));
	}

	public RuntimeClarification lock(String clarificationId) {
		return first(jdbc.query("SELECT * FROM qw_runtime_clarification WHERE clarification_id = ? FOR UPDATE",
				this::map, clarificationId))
			.orElseThrow(() -> new IllegalArgumentException("Runtime clarification not found: " + clarificationId));
	}

	public void insert(RuntimeClarification clarification) {
		jdbc.update("""
				INSERT INTO qw_runtime_clarification
				(clarification_id, run_id, question, options_json, recommended_option, reason, evidence, status,
				 issue_type, asset_type, asset_key, raw_expression, resolved_value, resolution_source,
				 selected_option, custom_answer, selected_scope, answered_by, revision, create_time, update_time)
				VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", clarification.clarificationId(), clarification.runId(), clarification.question(),
				json(clarification.options()), clarification.recommendedOption(), clarification.reason(),
				clarification.evidence(), clarification.status().name(),
				clarification.issueType() == null ? null : clarification.issueType().name(), clarification.assetType(),
				clarification.assetKey(), clarification.rawExpression(), clarification.resolvedValue(),
				clarification.resolutionSource(), clarification.selectedOption(), clarification.customAnswer(),
				clarification.selectedScope() == null ? null : clarification.selectedScope().name(),
				clarification.answeredBy(), clarification.revision());
	}

    public int supersede(String question,long revision,String replacement) {
        return jdbc.update("""
            UPDATE qw_runtime_clarification SET status='SUPERSEDED',resolution_source='REPLACED',resolved_value=?,
              revision=revision+1,update_time=CURRENT_TIMESTAMP
            WHERE clarification_id=? AND revision=? AND status='PENDING'
            """,replacement,question,revision);
    }

	public Optional<ClarificationAnswer> findAnswerByIdempotency(String clarificationId, String idempotencyKey) {
		return first(jdbc.query("""
				SELECT clarification_id, idempotency_key, selected_option, custom_answer, selected_scope, answered_by,
				       clarification_revision, create_time
				FROM qw_runtime_clarification_answer
				WHERE clarification_id = ? AND idempotency_key = ?
				""", (rs, rowNum) -> new ClarificationAnswer(rs.getString("clarification_id"),
				rs.getString("idempotency_key"), rs.getString("selected_option"), rs.getString("custom_answer"),
				enumValue(SemanticBindingScope.class, rs.getString("selected_scope")), rs.getString("answered_by"),
				rs.getLong("clarification_revision"), time(rs.getTimestamp("create_time"))), clarificationId,
				idempotencyKey));
	}

	public void insertAnswer(String clarificationId, String idempotencyKey, String selectedOption, String customAnswer,
			SemanticBindingScope selectedScope, String answeredBy, long revision) {
		jdbc.update("""
				INSERT INTO qw_runtime_clarification_answer
				(clarification_id, idempotency_key, selected_option, custom_answer, selected_scope, answered_by,
				 clarification_revision, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", clarificationId, idempotencyKey, selectedOption, customAnswer,
				selectedScope == null ? null : selectedScope.name(), answeredBy, revision);
	}

	public int answer(String clarificationId, long expectedRevision, String selectedOption, String customAnswer,
			SemanticBindingScope selectedScope, String answeredBy, String resolvedValue, String resolutionSource) {
		return jdbc.update("""
				UPDATE qw_runtime_clarification SET status = 'ANSWERED', selected_option = ?, custom_answer = ?,
				selected_scope = ?, answered_by = ?, resolved_value = ?, resolution_source = ?, revision = revision + 1,
				update_time = CURRENT_TIMESTAMP
				WHERE clarification_id = ? AND revision = ? AND status = 'PENDING'
				""", selectedOption, customAnswer, selectedScope == null ? null : selectedScope.name(), answeredBy,
				resolvedValue, resolutionSource, clarificationId, expectedRevision);
	}

	private RuntimeClarification map(ResultSet rs, int rowNum) throws SQLException {
		return RuntimeClarification.builder()
			.clarificationId(rs.getString("clarification_id"))
			.runId(rs.getString("run_id"))
			.question(rs.getString("question"))
			.options(readOptions(rs.getString("options_json")))
			.recommendedOption(rs.getString("recommended_option"))
			.reason(rs.getString("reason"))
			.evidence(rs.getString("evidence"))
			.issueType(enumValue(SemanticIssueType.class, rs.getString("issue_type")))
			.assetType(rs.getString("asset_type"))
			.assetKey(rs.getString("asset_key"))
			.rawExpression(rs.getString("raw_expression"))
			.resolvedValue(rs.getString("resolved_value"))
			.resolutionSource(rs.getString("resolution_source"))
			.status(ClarificationStatus.valueOf(rs.getString("status")))
			.selectedOption(rs.getString("selected_option"))
			.customAnswer(rs.getString("custom_answer"))
			.selectedScope(enumValue(SemanticBindingScope.class, rs.getString("selected_scope")))
			.answeredBy(rs.getString("answered_by"))
			.revision(rs.getLong("revision"))
			.createTime(time(rs.getTimestamp("create_time")))
			.updateTime(time(rs.getTimestamp("update_time")))
			.build();
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid clarification options", ex);
		}
	}

	private List<ClarificationOption> readOptions(String value) {
		try {
			return JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid persisted clarification options", ex);
		}
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	private static <T> Optional<T> first(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
		return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
	}

	public record ClarificationAnswer(String clarificationId, String idempotencyKey, String selectedOption,
			String customAnswer, SemanticBindingScope selectedScope, String answeredBy, long clarificationRevision,
			LocalDateTime createTime) {
	}

}
