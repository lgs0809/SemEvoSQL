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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable storage for multi-turn conversation context. */
@Repository
public class ConversationTurnRepository {

	private static final String PENDING = "PENDING";

	private static final String COMPLETED = "COMPLETED";

	private static final String CANCELLED = "CANCELLED";

	private final JdbcTemplate jdbc;

	public ConversationTurnRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public ConversationTurn begin(String runId, String threadId, String userQuestion) {
		ConversationTurn existing = findByRun(runId).orElse(null);
		if (existing != null) {
			assertSameTurn(existing, threadId, userQuestion);
			return existing;
		}
		lockRun(runId);
		existing = findByRun(runId).orElse(null);
		if (existing != null) {
			assertSameTurn(existing, threadId, userQuestion);
			return existing;
		}
		Long sequence = jdbc.queryForObject(
				"SELECT COALESCE(MAX(turn_sequence), 0) + 1 FROM qw_conversation_turn WHERE thread_id = ?", Long.class,
				threadId);
		LocalDateTime now = LocalDateTime.now();
		ConversationTurn value = new ConversationTurn(UUID.randomUUID().toString(), runId, threadId,
				sequence == null ? 1 : sequence, userQuestion, "", null, null, null, null, 0, 0, PENDING, 0, now, now);
		try {
			jdbc.update("""
					INSERT INTO qw_conversation_turn(id, run_id, thread_id, turn_sequence, user_question,
					 planner_output, status, revision, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", value.id(), value.runId(), value.threadId(), value.turnSequence(), value.userQuestion(),
					value.plannerOutput(), value.status(), value.revision(), Timestamp.valueOf(now),
					Timestamp.valueOf(now));
			return value;
		}
		catch (DuplicateKeyException ex) {
			ConversationTurn concurrent = findByRun(runId).orElseThrow(() -> ex);
			assertSameTurn(concurrent, threadId, userQuestion);
			return concurrent;
		}
	}

	@Transactional
	public void savePending(String runId, String plannerOutput) {
		jdbc.update("""
				UPDATE qw_conversation_turn
				SET planner_output = ?, revision = revision + 1, update_time = ?
				WHERE run_id = ? AND status = ?
				""", Objects.toString(plannerOutput, ""), Timestamp.valueOf(LocalDateTime.now()), runId, PENDING);
	}

	@Transactional
	public void resetPending(String runId) {
		resetPending(runId, null);
	}

	@Transactional
	public void resetPending(String runId, String replacementQuestion) {
		if (replacementQuestion == null) {
			jdbc.update("""
					UPDATE qw_conversation_turn
					SET planner_output = '', revision = revision + 1, update_time = ?
					WHERE run_id = ? AND status = ?
					""", Timestamp.valueOf(LocalDateTime.now()), runId, PENDING);
			return;
		}
		jdbc.update("""
				UPDATE qw_conversation_turn
				SET user_question = ?, planner_output = '', revision = revision + 1, update_time = ?
				WHERE run_id = ? AND status = ?
				""", replacementQuestion, Timestamp.valueOf(LocalDateTime.now()), runId, PENDING);
	}

	@Transactional
	public boolean complete(String runId, String plannerOutput, ConversationTurnSummarizer.CompletionContext context) {
		return jdbc.update("""
				UPDATE qw_conversation_turn
				SET planner_output = ?, canonical_query = ?, context_summary_json = ?, result_summary = ?,
				    result_artifact_id = ?, prompt_token_estimate = ?, summary_version = ?, status = ?,
				    revision = revision + 1, update_time = ?
				WHERE run_id = ? AND status = ?
				  AND EXISTS (SELECT 1 FROM qw_query_run r
				              WHERE r.run_id = qw_conversation_turn.run_id AND r.status = 'SUCCEEDED')
				""", Objects.toString(plannerOutput, ""), context.canonicalQuery(), context.contextSummaryJson(),
				context.resultSummary(), context.resultArtifactId(), context.promptTokenEstimate(),
				ConversationTurnSummary.CURRENT_SCHEMA_VERSION, COMPLETED, Timestamp.valueOf(LocalDateTime.now()),
				runId, PENDING) == 1;
	}

	@Transactional
	public void cancel(String runId) {
		jdbc.update("""
				UPDATE qw_conversation_turn
				SET status = ?, revision = revision + 1, update_time = ?
				WHERE run_id = ? AND status = ?
				""", CANCELLED, Timestamp.valueOf(LocalDateTime.now()), runId, PENDING);
	}

    /** Keep the original turn for audit, while excluding it and its derived summary from future context. */
    @Transactional
    public void revokeConfirmedContext(String runId, long expectedRevision) {
        ConversationTurn turn=findByRun(runId).orElseThrow();
        // Shares the summary publisher's lock, including the first-insert race.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "semevosql-compaction:"+turn.threadId());
        if (jdbc.update("""
            UPDATE qw_conversation_turn SET status='CORRECTED',revision=revision+1,update_time=CURRENT_TIMESTAMP
            WHERE run_id=? AND revision=? AND status='COMPLETED'
            """,runId,expectedRevision)!=1)
            throw new IllegalStateException("Correction target context changed; confirmation is stale");
        jdbc.update("""
            UPDATE qw_conversation_context_compaction SET valid=FALSE,revision=revision+1,update_time=CURRENT_TIMESTAMP
            WHERE thread_id=? AND covered_through_sequence>=? AND valid=TRUE
            """,turn.threadId(),turn.turnSequence());
    }

	public Optional<ConversationTurn> findByRun(String runId) {
		return first(jdbc.query("""
				SELECT id, run_id, thread_id, turn_sequence, user_question, planner_output, canonical_query,
				       context_summary_json, result_summary, result_artifact_id, prompt_token_estimate,
				       summary_version, status, revision, create_time, update_time
				FROM qw_conversation_turn WHERE run_id = ?
				""", (rs, rowNum) -> map(rs), runId));
	}

	public Optional<ConversationTurn> findPendingByThread(String threadId) {
		return first(jdbc.query("""
				SELECT id, run_id, thread_id, turn_sequence, user_question, planner_output, canonical_query,
				       context_summary_json, result_summary, result_artifact_id, prompt_token_estimate,
				       summary_version, status, revision, create_time, update_time
				FROM qw_conversation_turn
				WHERE thread_id = ? AND status = ?
				ORDER BY turn_sequence DESC LIMIT 1
				""", (rs, rowNum) -> map(rs), threadId, PENDING));
	}

	public List<ConversationTurn> completedHistory(String threadId, int limit) {
		List<ConversationTurn> newestFirst = jdbc.query("""
				SELECT id, run_id, thread_id, turn_sequence, user_question, planner_output, canonical_query,
				       context_summary_json, result_summary, result_artifact_id, prompt_token_estimate,
				       summary_version, status, revision, create_time, update_time
				FROM qw_conversation_turn
				WHERE thread_id = ? AND status = ?
				ORDER BY turn_sequence DESC LIMIT ?
				""", (rs, rowNum) -> map(rs), threadId, COMPLETED, Math.max(1, limit));
		List<ConversationTurn> chronological = new ArrayList<>(newestFirst);
		Collections.reverse(chronological);
		return chronological;
	}

	public List<ConversationTurn> completedAfter(String threadId, long exclusiveSequence, int limit) {
		return jdbc.query("""
				SELECT id, run_id, thread_id, turn_sequence, user_question, planner_output, canonical_query,
				       context_summary_json, result_summary, result_artifact_id, prompt_token_estimate,
				       summary_version, status, revision, create_time, update_time
				FROM qw_conversation_turn
				WHERE thread_id = ? AND status = ? AND turn_sequence > ?
				ORDER BY turn_sequence ASC LIMIT ?
				""", (rs, rowNum) -> map(rs), threadId, COMPLETED, exclusiveSequence, Math.max(1, limit));
	}

	/** Read only a bounded old prefix; the newest retained turns never enter the model batch. */
	public List<ConversationTurn> compactionBatch(String threadId, long covered, int retainRecent, int limit) {
		return jdbc.query("""
				SELECT id, run_id, thread_id, turn_sequence, user_question, planner_output, canonical_query,
				       context_summary_json, result_summary, result_artifact_id, prompt_token_estimate,
				       summary_version, status, revision, create_time, update_time
				FROM qw_conversation_turn
				WHERE thread_id=? AND status='COMPLETED' AND turn_sequence>?
				  AND turn_sequence < (SELECT turn_sequence FROM qw_conversation_turn
				    WHERE thread_id=? AND status='COMPLETED' ORDER BY turn_sequence DESC LIMIT 1 OFFSET ?)
				ORDER BY turn_sequence ASC LIMIT ?
				""", (rs, rowNum) -> map(rs), threadId, covered, threadId,
				Math.max(1, retainRecent)-1, Math.min(200, Math.max(1, limit)));
	}

	private void lockRun(String runId) {
		List<String> values = jdbc.query("SELECT run_id FROM qw_query_run WHERE run_id = ? FOR UPDATE",
				(rs, rowNum) -> rs.getString(1), runId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Query run not found: " + runId);
		}
	}

	private static ConversationTurn map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ConversationTurn(rs.getString("id"), rs.getString("run_id"), rs.getString("thread_id"),
				rs.getLong("turn_sequence"), rs.getString("user_question"), rs.getString("planner_output"),
				rs.getString("canonical_query"), rs.getString("context_summary_json"), rs.getString("result_summary"),
				rs.getString("result_artifact_id"), rs.getInt("prompt_token_estimate"), rs.getInt("summary_version"),
				rs.getString("status"), rs.getLong("revision"), time(rs.getTimestamp("create_time")),
				time(rs.getTimestamp("update_time")));
	}

	private static void assertSameTurn(ConversationTurn existing, String threadId, String userQuestion) {
		if (!Objects.equals(existing.threadId(), threadId) || !Objects.equals(existing.userQuestion(), userQuestion)) {
			throw new IllegalArgumentException("runId is already bound to a different conversation turn");
		}
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	private static <T> Optional<T> first(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public record ConversationTurn(String id, String runId, String threadId, long turnSequence, String userQuestion,
			String plannerOutput, String canonicalQuery, String contextSummaryJson, String resultSummary,
			String resultArtifactId, int promptTokenEstimate, int summaryVersion, String status, long revision,
			LocalDateTime createTime, LocalDateTime updateTime) {
	}

}
