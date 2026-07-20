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
package cn.lgs.semevosql.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QueryRunRetentionRepository {

	private static final String ELIGIBLE_RUN_PREDICATE = """
			r.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')
			AND r.finish_time IS NOT NULL
			AND r.finish_time < ?
			AND (r.lease_expire_time IS NULL OR r.lease_expire_time < CURRENT_TIMESTAMP)
			AND NOT EXISTS (SELECT 1 FROM qw_evaluation_job j WHERE j.run_id = r.run_id)
			AND NOT EXISTS (SELECT 1 FROM qw_evaluation_case_result c WHERE c.run_id = r.run_id)
			AND NOT EXISTS (SELECT 1 FROM qw_semantic_evolution_event e WHERE e.replay_run_id = r.run_id)
			AND NOT EXISTS (SELECT 1 FROM qw_manual_attestation a WHERE a.related_replay_run_id = r.run_id)
			AND NOT EXISTS (SELECT 1 FROM qw_release_decision d WHERE d.related_replay_run_id = r.run_id)
			""";

	private final JdbcTemplate jdbc;

	public boolean tryAcquireLease(String leaseName, String ownerInstance, String leaseToken, LocalDateTime now,
			LocalDateTime leaseExpireTime) {
		return jdbc.update("""
				UPDATE qw_maintenance_lease
				SET owner_instance = ?, lease_token = ?, lease_expire_time = ?, revision = revision + 1,
				    update_time = CURRENT_TIMESTAMP
				WHERE lease_name = ?
				  AND (lease_expire_time IS NULL OR lease_expire_time < ?)
				""", ownerInstance, leaseToken, leaseExpireTime, leaseName, now) == 1;
	}

	public boolean renewLease(String leaseName, String leaseToken, LocalDateTime leaseExpireTime) {
		return jdbc.update("""
				UPDATE qw_maintenance_lease
				SET lease_expire_time = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE lease_name = ? AND lease_token = ?
				""", leaseExpireTime, leaseName, leaseToken) == 1;
	}

	public void releaseLease(String leaseName, String leaseToken) {
		jdbc.update("""
				UPDATE qw_maintenance_lease
				SET owner_instance = NULL, lease_token = NULL, lease_expire_time = NULL,
				    revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE lease_name = ? AND lease_token = ?
				""", leaseName, leaseToken);
	}

	public boolean lockLease(String leaseName, String leaseToken, LocalDateTime now) {
		try {
			jdbc.queryForObject("""
					SELECT lease_name FROM qw_maintenance_lease
					WHERE lease_name = ? AND lease_token = ? AND lease_expire_time >= ?
					FOR UPDATE
					""", String.class, leaseName, leaseToken, now);
			return true;
		}
		catch (EmptyResultDataAccessException ex) {
			return false;
		}
	}

	public List<String> findEligibleRunIds(LocalDateTime cutoff, int limit) {
		return jdbc.queryForList("""
				SELECT r.run_id
				FROM qw_query_run r
				WHERE %s
				ORDER BY r.finish_time, r.run_id
				LIMIT ?
				""".formatted(ELIGIBLE_RUN_PREDICATE), String.class, cutoff, limit);
	}

	public boolean lockRun(String runId) {
		try {
			jdbc.queryForObject("SELECT run_id FROM qw_query_run WHERE run_id = ? FOR UPDATE", String.class, runId);
			return true;
		}
		catch (EmptyResultDataAccessException ex) {
			return false;
		}
	}

	public Optional<RunArchiveSource> findEligibleRun(String runId, LocalDateTime cutoff) {
		try {
			return Optional.ofNullable(jdbc.queryForObject(
					"""
							SELECT r.run_id, r.run_type, r.project_id, r.project_version_id, r.episode_id, r.attempt_id,
							       r.thread_id, r.status, r.request_id, r.idempotency_key, r.start_time, r.finish_time, r.error_code,
							       r.error_message, r.last_event_sequence, r.request_payload, r.recovery_payload,
							       r.execution_snapshot,
							       (SELECT COUNT(*) FROM qw_run_event e WHERE e.run_id = r.run_id) AS event_count,
							       (SELECT COUNT(*) FROM qw_run_node_effect n WHERE n.run_id = r.run_id) AS node_effect_count,
							       (SELECT COUNT(*) FROM qw_runtime_clarification c WHERE c.run_id = r.run_id) AS clarification_count,
							       (SELECT COUNT(*) FROM qw_source_sub_run s WHERE s.run_id = r.run_id) AS source_sub_run_count,
							       (SELECT COUNT(*) FROM qw_result_artifact a WHERE a.run_id = r.run_id) AS artifact_count
							FROM qw_query_run r
							WHERE r.run_id = ? AND %s
							"""
						.formatted(ELIGIBLE_RUN_PREDICATE),
					this::mapRunArchiveSource, runId, cutoff));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public void insertArchive(RunArchive archive) {
		jdbc.update("""
				INSERT INTO qw_run_archive(
				 run_id, run_type, project_id, project_version_id, thread_id, status, request_id,
				 idempotency_key, start_time, finish_time, error_code, error_message_hash,
				 last_event_sequence, event_count, node_effect_count, clarification_count,
				 source_sub_run_count, artifact_count, payload_hash, retention_version, archived_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", archive.runId(), archive.runType(), archive.projectId(), archive.projectVersionId(),
				archive.threadId(), archive.status(), archive.requestId(), archive.idempotencyKey(),
				archive.startTime(), archive.finishTime(), archive.errorCode(), archive.errorMessageHash(),
				archive.lastEventSequence(), archive.eventCount(), archive.nodeEffectCount(),
				archive.clarificationCount(), archive.sourceSubRunCount(), archive.artifactCount(),
				archive.payloadHash(), archive.retentionVersion(), archive.archivedTime());
	}

	public int purgeRunDetails(String runId, String episodeId, String attemptId) {
		int deleted = 0;
		jdbc.update("UPDATE qw_project_message SET run_id = NULL WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_query_case_usage WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_user_semantic_preference_usage WHERE run_id = ?", runId);
		jdbc.update("UPDATE qw_trajectory_path SET run_id = NULL WHERE run_id = ?", runId);
		if (episodeId != null && !episodeId.isBlank()) {
			Integer otherEpisodeRuns = jdbc.queryForObject("""
					SELECT COUNT(*) FROM qw_query_run
					WHERE episode_id = ? AND run_id <> ?
					""", Integer.class, episodeId, runId);
			if (otherEpisodeRuns == null || otherEpisodeRuns == 0) {
				deleted += jdbc.update("DELETE FROM qw_feedback WHERE episode_id = ?", episodeId);
				deleted += jdbc.update("""
						DELETE FROM qw_node_trace
						WHERE attempt_id IN (SELECT id FROM qw_attempt WHERE episode_id = ?)
						""", episodeId);
				deleted += jdbc.update("""
						DELETE FROM qw_sql_trace
						WHERE attempt_id IN (SELECT id FROM qw_attempt WHERE episode_id = ?)
						""", episodeId);
				deleted += jdbc.update("DELETE FROM qw_attempt WHERE episode_id = ?", episodeId);
				deleted += jdbc.update("DELETE FROM qw_episode WHERE id = ?", episodeId);
			}
		}
		else if (attemptId != null && !attemptId.isBlank()) {
			Integer otherAttemptRuns = jdbc.queryForObject("""
					SELECT COUNT(*) FROM qw_query_run
					WHERE attempt_id = ? AND run_id <> ?
					""", Integer.class, attemptId, runId);
			if (otherAttemptRuns == null || otherAttemptRuns == 0) {
				deleted += jdbc.update("DELETE FROM qw_node_trace WHERE attempt_id = ?", attemptId);
				deleted += jdbc.update("DELETE FROM qw_sql_trace WHERE attempt_id = ?", attemptId);
				deleted += jdbc.update("DELETE FROM qw_attempt WHERE id = ?", attemptId);
			}
		}
		deleted += jdbc.update("""
				DELETE FROM qw_runtime_clarification_answer
				WHERE clarification_id IN (
				 SELECT clarification_id FROM qw_runtime_clarification WHERE run_id = ?
				)
				""", runId);
		deleted += jdbc.update("DELETE FROM qw_runtime_clarification WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_run_subscription WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_run_node_effect WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_run_checkpoint WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_run_event WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_conversation_turn WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_thread_execution_guard WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_merge_execution WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_result_artifact WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_source_sub_run WHERE run_id = ?", runId);
		deleted += jdbc.update("DELETE FROM qw_query_run WHERE run_id = ?", runId);
		return deleted;
	}

	public void insertBatch(RetentionBatch batch) {
		jdbc.update("""
				INSERT INTO qw_retention_batch(
				 batch_id, idempotency_key, owner_instance, cutoff_time, dry_run, status,
				 candidate_count, archived_count, deleted_count, failure_count, error_summary,
				 start_time, finish_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", batch.batchId(), batch.idempotencyKey(), batch.ownerInstance(), batch.cutoffTime(), batch.dryRun(),
				batch.status(), batch.candidateCount(), batch.archivedCount(), batch.deletedCount(),
				batch.failureCount(), batch.errorSummary(), batch.startTime(), batch.finishTime());
	}

	public boolean finishBatch(String batchId, String status, int candidateCount, int archivedCount, int deletedCount,
			int failureCount, String errorSummary, LocalDateTime finishTime) {
		return jdbc.update("""
				UPDATE qw_retention_batch
				SET status = ?, candidate_count = ?, archived_count = ?, deleted_count = ?,
				    failure_count = ?, error_summary = ?, finish_time = ?
				WHERE batch_id = ? AND status = 'RUNNING'
				""", status, candidateCount, archivedCount, deletedCount, failureCount, errorSummary, finishTime,
				batchId) == 1;
	}

	public int abandonRunningBatches(LocalDateTime finishTime) {
		return jdbc.update("""
				UPDATE qw_retention_batch
				SET status = 'ABANDONED', failure_count = failure_count + 1,
				    error_summary = 'MaintenanceLeaseExpired', finish_time = ?
				WHERE status = 'RUNNING'
				""", finishTime);
	}

	public int deleteFinishedBatchesBefore(LocalDateTime cutoff) {
		return jdbc.update("""
				DELETE FROM qw_retention_batch
				WHERE finish_time IS NOT NULL AND finish_time < ? AND status <> 'RUNNING'
				""", cutoff);
	}

	public Optional<RetentionBatch> findBatchByIdempotencyKey(String idempotencyKey) {
		try {
			return Optional.ofNullable(jdbc.queryForObject("""
					SELECT * FROM qw_retention_batch WHERE idempotency_key = ?
					""", this::mapRetentionBatch, idempotencyKey));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<RetentionBatch> findBatch(String batchId) {
		try {
			return Optional.ofNullable(jdbc.queryForObject("""
					SELECT * FROM qw_retention_batch WHERE batch_id = ?
					""", this::mapRetentionBatch, batchId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<RunArchive> findArchive(String runId) {
		try {
			return Optional.ofNullable(jdbc.queryForObject("""
					SELECT * FROM qw_run_archive WHERE run_id = ?
					""", this::mapRunArchive, runId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private RunArchiveSource mapRunArchiveSource(ResultSet rs, int rowNum) throws SQLException {
		return new RunArchiveSource(rs.getString("run_id"), rs.getString("run_type"),
				rs.getObject("project_id", Long.class), rs.getObject("project_version_id", Long.class),
				rs.getString("episode_id"), rs.getString("attempt_id"), rs.getString("thread_id"),
				rs.getString("status"), rs.getString("request_id"), rs.getString("idempotency_key"),
				rs.getTimestamp("start_time") == null ? null : rs.getTimestamp("start_time").toLocalDateTime(),
				rs.getTimestamp("finish_time") == null ? null : rs.getTimestamp("finish_time").toLocalDateTime(),
				rs.getString("error_code"), rs.getString("error_message"), rs.getLong("last_event_sequence"),
				rs.getString("request_payload"), rs.getString("recovery_payload"), rs.getString("execution_snapshot"),
				rs.getLong("event_count"), rs.getLong("node_effect_count"), rs.getLong("clarification_count"),
				rs.getLong("source_sub_run_count"), rs.getLong("artifact_count"));
	}

	private RetentionBatch mapRetentionBatch(ResultSet rs, int rowNum) throws SQLException {
		return new RetentionBatch(rs.getString("batch_id"), rs.getString("idempotency_key"),
				rs.getString("owner_instance"), rs.getTimestamp("cutoff_time").toLocalDateTime(),
				rs.getBoolean("dry_run"), rs.getString("status"), rs.getInt("candidate_count"),
				rs.getInt("archived_count"), rs.getInt("deleted_count"), rs.getInt("failure_count"),
				rs.getString("error_summary"), rs.getTimestamp("start_time").toLocalDateTime(),
				rs.getTimestamp("finish_time") == null ? null : rs.getTimestamp("finish_time").toLocalDateTime());
	}

	private RunArchive mapRunArchive(ResultSet rs, int rowNum) throws SQLException {
		return new RunArchive(rs.getString("run_id"), rs.getString("run_type"), rs.getObject("project_id", Long.class),
				rs.getObject("project_version_id", Long.class), rs.getString("thread_id"), rs.getString("status"),
				rs.getString("request_id"), rs.getString("idempotency_key"),
				rs.getTimestamp("start_time") == null ? null : rs.getTimestamp("start_time").toLocalDateTime(),
				rs.getTimestamp("finish_time") == null ? null : rs.getTimestamp("finish_time").toLocalDateTime(),
				rs.getString("error_code"), rs.getString("error_message_hash"), rs.getLong("last_event_sequence"),
				rs.getLong("event_count"), rs.getLong("node_effect_count"), rs.getLong("clarification_count"),
				rs.getLong("source_sub_run_count"), rs.getLong("artifact_count"), rs.getString("payload_hash"),
				rs.getString("retention_version"), rs.getTimestamp("archived_time").toLocalDateTime());
	}

	public record RunArchiveSource(String runId, String runType, Long projectId, Long projectVersionId,
			String episodeId, String attemptId, String threadId, String status, String requestId, String idempotencyKey,
			LocalDateTime startTime, LocalDateTime finishTime, String errorCode, String errorMessage,
			long lastEventSequence, String requestPayload, String recoveryPayload, String executionSnapshot,
			long eventCount, long nodeEffectCount, long clarificationCount, long sourceSubRunCount,
			long artifactCount) {
	}

	public record RunArchive(String runId, String runType, Long projectId, Long projectVersionId, String threadId,
			String status, String requestId, String idempotencyKey, LocalDateTime startTime, LocalDateTime finishTime,
			String errorCode, String errorMessageHash, long lastEventSequence, long eventCount, long nodeEffectCount,
			long clarificationCount, long sourceSubRunCount, long artifactCount, String payloadHash,
			String retentionVersion, LocalDateTime archivedTime) {
	}

	public record RetentionBatch(String batchId, String idempotencyKey, String ownerInstance, LocalDateTime cutoffTime,
			boolean dryRun, String status, int candidateCount, int archivedCount, int deletedCount, int failureCount,
			String errorSummary, LocalDateTime startTime, LocalDateTime finishTime) {
	}

}
