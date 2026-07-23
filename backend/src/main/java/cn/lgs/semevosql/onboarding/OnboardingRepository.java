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

import cn.lgs.semevosql.onboarding.OnboardingConflict.ConflictStatus;
import cn.lgs.semevosql.onboarding.OnboardingCoverageItem.CoverageRequirement;
import cn.lgs.semevosql.onboarding.OnboardingCoverageItem.CoverageStatus;
import cn.lgs.semevosql.onboarding.OnboardingQuestion.QuestionStatus;
import cn.lgs.semevosql.onboarding.ProjectOnboardingSession.SessionStatus;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OnboardingRepository {

	private final JdbcTemplate jdbc;

	public Optional<ProjectOnboardingSession> findSession(String sessionId) {
		return first(
				jdbc.query("SELECT * FROM qw_onboarding_session WHERE session_id = ?", this::mapSession, sessionId));
	}

	public Optional<ProjectOnboardingSession> findSession(Long projectId, Long versionId) {
		return first(jdbc.query("""
				SELECT * FROM qw_onboarding_session WHERE project_id = ? AND project_version_id = ?
				ORDER BY create_time DESC LIMIT 1
				""", this::mapSession, projectId, versionId));
	}

	public Optional<ProjectOnboardingSession> findSessionByIdempotency(String idempotencyKey) {
		return first(jdbc.query("SELECT * FROM qw_onboarding_session WHERE idempotency_key = ?", this::mapSession,
				idempotencyKey));
	}

	public ProjectOnboardingSession lockSession(String sessionId) {
		return first(jdbc.query("SELECT * FROM qw_onboarding_session WHERE session_id = ? FOR UPDATE", this::mapSession,
				sessionId))
			.orElseThrow(() -> new IllegalArgumentException("Onboarding session not found: " + sessionId));
	}

	public void lockProjectVersion(Long projectVersionId) {
		List<Long> versions = jdbc.query("SELECT id FROM qw_project_version WHERE id = ? FOR UPDATE",
				(rs, rowNum) -> rs.getLong(1), projectVersionId);
		if (versions.isEmpty()) {
			throw new IllegalArgumentException("Semantic project version not found: " + projectVersionId);
		}
	}

	public void insertSession(ProjectOnboardingSession session) {
		jdbc.update("""
				INSERT INTO qw_onboarding_session
				(session_id, project_id, project_version_id, status, summary_confirmed, confirmed_by,
				 idempotency_key, confirmation_idempotency_key, confirmation_revision, revision,
				 create_time, update_time, complete_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
				""", session.sessionId(), session.projectId(), session.projectVersionId(), session.status().name(),
				session.summaryConfirmed(), session.confirmedBy(), session.idempotencyKey(),
				session.confirmationIdempotencyKey(), session.confirmationRevision(), session.revision(),
				session.completeTime());
	}

	public int updateSession(String sessionId, long expectedRevision, SessionStatus status, boolean confirmed,
			String confirmedBy, String confirmationIdempotencyKey, Long confirmationRevision,
			LocalDateTime completeTime) {
		return jdbc.update("""
				UPDATE qw_onboarding_session SET status = ?, summary_confirmed = ?, confirmed_by = ?,
				confirmation_idempotency_key = ?, confirmation_revision = ?, complete_time = ?,
				revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE session_id = ? AND revision = ?
				""", status.name(), confirmed, confirmedBy, confirmationIdempotencyKey, confirmationRevision,
				completeTime, sessionId, expectedRevision);
	}

	public void insertCoverage(OnboardingCoverageItem item) {
		jdbc.update(
				"""
						INSERT INTO qw_onboarding_coverage
						(id, session_id, category, requirement, status, satisfied_by, evidence, revision, create_time, update_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						""",
				item.id(), item.sessionId(), item.category().name(), item.requirement().name(), item.status().name(),
				item.satisfiedBy(), item.evidence(), item.revision());
	}

	public List<OnboardingCoverageItem> coverage(String sessionId) {
		return jdbc.query("SELECT * FROM qw_onboarding_coverage WHERE session_id = ? ORDER BY category",
				this::mapCoverage, sessionId);
	}

	public int updateCoverage(String sessionId, OnboardingCategory category, CoverageStatus status, String satisfiedBy,
			String evidence) {
		return jdbc.update("""
				UPDATE qw_onboarding_coverage SET status = ?, satisfied_by = ?, evidence = ?,
				revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE session_id = ? AND category = ?
				""", status.name(), satisfiedBy, evidence, sessionId, category.name());
	}

	public void insertQuestion(OnboardingQuestion question) {
		jdbc.update("""
				INSERT INTO qw_onboarding_question
				(id, session_id, project_id, project_version_id, category, question, recommended_answer,
				 recommendation_reason, evidence, answer_schema, blocking, priority, depends_on, status,
				 revision, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", question.id(), question.sessionId(), question.projectId(), question.projectVersionId(),
				question.category().name(), question.question(), question.recommendedAnswer(),
				question.recommendationReason(), question.evidence(), question.answerSchema(), question.blocking(),
				question.priority(), json(question.dependsOn()), question.status().name(), question.revision());
	}

	public Optional<OnboardingQuestion> findQuestion(String questionId) {
		return first(jdbc.query("SELECT * FROM qw_onboarding_question WHERE id = ?", this::mapQuestion, questionId));
	}

	public OnboardingQuestion lockQuestion(String questionId) {
		return first(jdbc.query("SELECT * FROM qw_onboarding_question WHERE id = ? FOR UPDATE", this::mapQuestion,
				questionId))
			.orElseThrow(() -> new IllegalArgumentException("Onboarding question not found: " + questionId));
	}

	public List<OnboardingQuestion> questions(String sessionId) {
		return jdbc.query("""
				SELECT * FROM qw_onboarding_question WHERE session_id = ? ORDER BY priority, create_time, id
				""", this::mapQuestion, sessionId);
	}

	public Optional<OnboardingQuestion> latestQuestion(String sessionId, OnboardingCategory category) {
		return first(jdbc.query("""
				SELECT * FROM qw_onboarding_question WHERE session_id = ? AND category = ?
				ORDER BY create_time DESC, id DESC LIMIT 1
				""", this::mapQuestion, sessionId, category.name()));
	}

	public int updateQuestionStatus(String questionId, long expectedRevision, QuestionStatus status) {
		return jdbc.update("""
				UPDATE qw_onboarding_question SET status = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND revision = ?
				""", status.name(), questionId, expectedRevision);
	}

	public int updateQuestionDefinition(OnboardingQuestion question, long expectedRevision) {
		return jdbc.update("""
				UPDATE qw_onboarding_question
				SET question = ?, recommended_answer = ?, recommendation_reason = ?, evidence = ?, answer_schema = ?,
				    blocking = ?, priority = ?, depends_on = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND revision = ?
				""", question.question(), question.recommendedAnswer(), question.recommendationReason(), question.evidence(),
				question.answerSchema(), question.blocking(), question.priority(), json(question.dependsOn()), question.id(),
				expectedRevision);
	}

	public int markQuestionsStale(String sessionId, String categoryJsonFragment) {
		return jdbc.update(
				"""
						UPDATE qw_onboarding_question SET status = 'STALE', revision = revision + 1, update_time = CURRENT_TIMESTAMP
						WHERE session_id = ? AND status IN ('PENDING','ANSWERED','SKIPPED') AND CAST(depends_on AS TEXT) LIKE ?
						""",
				sessionId, "%" + categoryJsonFragment + "%");
	}

	public Optional<OnboardingAnswer> findAnswerByIdempotency(String questionId, String idempotencyKey) {
		return first(jdbc.query("""
				SELECT * FROM qw_onboarding_answer WHERE question_id = ? AND idempotency_key = ?
				""", this::mapAnswer, questionId, idempotencyKey));
	}

	public void insertAnswer(OnboardingAnswer answer) {
		jdbc.update("""
				INSERT INTO qw_onboarding_answer
				(id, session_id, question_id, category, answer, answer_type, idempotency_key, answered_by,
				 question_revision, active, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)
				""", answer.id(), answer.sessionId(), answer.questionId(), answer.category().name(), answer.answer(),
				answer.answerType(), answer.idempotencyKey(), answer.answeredBy(), answer.questionRevision());
	}

	public void deactivateAnswers(String questionId) {
		jdbc.update("UPDATE qw_onboarding_answer SET active = FALSE WHERE question_id = ? AND active = TRUE",
				questionId);
	}

	public void deactivateAnswersByCategory(String sessionId, OnboardingCategory category) {
		jdbc.update("""
				UPDATE qw_onboarding_answer SET active = FALSE
				WHERE session_id = ? AND category = ? AND active = TRUE
				""", sessionId, category.name());
	}

	public boolean hasOpenConflictByQuestion(String sessionId, String questionId) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_onboarding_conflict
				WHERE session_id = ? AND resolution_question_id = ? AND status = 'OPEN'
				""", Integer.class, sessionId, questionId);
		return count != null && count > 0;
	}

	public boolean hasOpenConflictByCategory(String sessionId, OnboardingCategory category) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_onboarding_conflict c
				JOIN qw_onboarding_question q ON q.id = c.resolution_question_id
				WHERE c.session_id = ? AND c.status = 'OPEN' AND q.category = ?
				""", Integer.class, sessionId, category.name());
		return count != null && count > 0;
	}

	public int supersedeOpenConflictQuestionsByCategory(String sessionId, OnboardingCategory category) {
		return jdbc.update("""
				UPDATE qw_onboarding_question
				SET status = 'SUPERSEDED', revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE session_id = ? AND category = ? AND status = 'PENDING'
				AND id IN (
					SELECT resolution_question_id FROM qw_onboarding_conflict
					WHERE session_id = ? AND status = 'OPEN'
				)
				""", sessionId, category.name(), sessionId);
	}

	public int resolveOpenConflictsByCategory(String sessionId, OnboardingCategory category) {
		return jdbc.update("""
				UPDATE qw_onboarding_conflict
				SET status = 'RESOLVED', revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE session_id = ? AND status = 'OPEN'
				AND resolution_question_id IN (
					SELECT id FROM qw_onboarding_question WHERE session_id = ? AND category = ?
				)
				""", sessionId, sessionId, category.name());
	}

	public int resolveConflictByQuestion(String sessionId, String questionId) {
		return jdbc.update("""
				UPDATE qw_onboarding_conflict SET status = 'RESOLVED', revision = revision + 1,
				update_time = CURRENT_TIMESTAMP
				WHERE session_id = ? AND resolution_question_id = ? AND status = 'OPEN'
				""", sessionId, questionId);
	}

	public Map<OnboardingCategory, String> activeAnswers(String sessionId) {
		List<OnboardingAnswer> answers = jdbc.query("""
				SELECT * FROM qw_onboarding_answer WHERE session_id = ? AND active = TRUE ORDER BY create_time
				""", this::mapAnswer, sessionId);
		Map<OnboardingCategory, String> result = new LinkedHashMap<>();
		answers.forEach(answer -> result.put(answer.category(), answer.answer()));
		return result;
	}

	public void insertConflict(OnboardingConflict conflict) {
		jdbc.update("""
				INSERT INTO qw_onboarding_conflict
				(id, session_id, conflict_type, message, evidence, blocking, status, resolution_question_id,
				 revision, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", conflict.id(), conflict.sessionId(), conflict.conflictType(), conflict.message(),
				conflict.evidence(), conflict.blocking(), conflict.status().name(), conflict.resolutionQuestionId(),
				conflict.revision());
	}

	public List<OnboardingConflict> conflicts(String sessionId) {
		return jdbc.query("SELECT * FROM qw_onboarding_conflict WHERE session_id = ? ORDER BY create_time",
				this::mapConflict, sessionId);
	}

	public int resolveConflicts(String sessionId, String conflictType) {
		return jdbc.update("""
				UPDATE qw_onboarding_conflict SET status = 'RESOLVED', revision = revision + 1,
				update_time = CURRENT_TIMESTAMP WHERE session_id = ? AND conflict_type = ? AND status = 'OPEN'
				""", sessionId, conflictType);
	}

	private ProjectOnboardingSession mapSession(ResultSet rs, int rowNum) throws SQLException {
		return ProjectOnboardingSession.builder()
			.sessionId(rs.getString("session_id"))
			.projectId(rs.getLong("project_id"))
			.projectVersionId(rs.getLong("project_version_id"))
			.status(SessionStatus.valueOf(rs.getString("status")))
			.summaryConfirmed(rs.getBoolean("summary_confirmed"))
			.confirmedBy(rs.getString("confirmed_by"))
			.idempotencyKey(rs.getString("idempotency_key"))
			.confirmationIdempotencyKey(rs.getString("confirmation_idempotency_key"))
			.confirmationRevision((Long) rs.getObject("confirmation_revision"))
			.revision(rs.getLong("revision"))
			.createTime(time(rs.getTimestamp("create_time")))
			.updateTime(time(rs.getTimestamp("update_time")))
			.completeTime(time(rs.getTimestamp("complete_time")))
			.build();
	}

	private OnboardingCoverageItem mapCoverage(ResultSet rs, int rowNum) throws SQLException {
		return OnboardingCoverageItem.builder()
			.id(rs.getString("id"))
			.sessionId(rs.getString("session_id"))
			.category(OnboardingCategory.valueOf(rs.getString("category")))
			.requirement(CoverageRequirement.valueOf(rs.getString("requirement")))
			.status(CoverageStatus.valueOf(rs.getString("status")))
			.satisfiedBy(rs.getString("satisfied_by"))
			.evidence(rs.getString("evidence"))
			.revision(rs.getLong("revision"))
			.build();
	}

	private OnboardingQuestion mapQuestion(ResultSet rs, int rowNum) throws SQLException {
		return OnboardingQuestion.builder()
			.id(rs.getString("id"))
			.sessionId(rs.getString("session_id"))
			.projectId(rs.getLong("project_id"))
			.projectVersionId(rs.getLong("project_version_id"))
			.category(OnboardingCategory.valueOf(rs.getString("category")))
			.question(rs.getString("question"))
			.recommendedAnswer(rs.getString("recommended_answer"))
			.recommendationReason(rs.getString("recommendation_reason"))
			.evidence(rs.getString("evidence"))
			.answerSchema(rs.getString("answer_schema"))
			.blocking(rs.getBoolean("blocking"))
			.priority(rs.getInt("priority"))
			.dependsOn(readCategories(rs.getString("depends_on")))
			.status(QuestionStatus.valueOf(rs.getString("status")))
			.revision(rs.getLong("revision"))
			.createTime(time(rs.getTimestamp("create_time")))
			.updateTime(time(rs.getTimestamp("update_time")))
			.build();
	}

	private OnboardingAnswer mapAnswer(ResultSet rs, int rowNum) throws SQLException {
		return new OnboardingAnswer(rs.getString("id"), rs.getString("session_id"), rs.getString("question_id"),
				OnboardingCategory.valueOf(rs.getString("category")), rs.getString("answer"),
				rs.getString("answer_type"), rs.getString("idempotency_key"), rs.getString("answered_by"),
				rs.getLong("question_revision"), rs.getBoolean("active"), time(rs.getTimestamp("create_time")));
	}

	private OnboardingConflict mapConflict(ResultSet rs, int rowNum) throws SQLException {
		return OnboardingConflict.builder()
			.id(rs.getString("id"))
			.sessionId(rs.getString("session_id"))
			.conflictType(rs.getString("conflict_type"))
			.message(rs.getString("message"))
			.evidence(rs.getString("evidence"))
			.blocking(rs.getBoolean("blocking"))
			.status(ConflictStatus.valueOf(rs.getString("status")))
			.resolutionQuestionId(rs.getString("resolution_question_id"))
			.revision(rs.getLong("revision"))
			.createTime(time(rs.getTimestamp("create_time")))
			.updateTime(time(rs.getTimestamp("update_time")))
			.build();
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid onboarding JSON", ex);
		}
	}

	private List<OnboardingCategory> readCategories(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		try {
			return JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid persisted onboarding dependencies", ex);
		}
	}

	private static <T> Optional<T> first(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public record OnboardingAnswer(String id, String sessionId, String questionId, OnboardingCategory category,
			String answer, String answerType, String idempotencyKey, String answeredBy, long questionRevision,
			boolean active, LocalDateTime createTime) {
	}

}
