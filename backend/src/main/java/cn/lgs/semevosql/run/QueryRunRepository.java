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

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QueryRunRepository {

	private final JdbcTemplate jdbc;

	private final RowMapper<QueryRun> runMapper = this::mapRun;

	private final RowMapper<RunEvent> eventMapper = this::mapEvent;

	public Optional<QueryRun> findById(String runId) {
		return first(jdbc.query("SELECT * FROM qw_query_run WHERE run_id = ?", runMapper, runId));
	}

	public Optional<QueryRun> findByIdempotencyKey(String idempotencyKey) {
		return first(jdbc.query("SELECT * FROM qw_query_run WHERE idempotency_key = ?", runMapper, idempotencyKey));
	}

	public Optional<QueryRun> findLatestByThreadId(String threadId) {
		return first(jdbc.query("""
				SELECT * FROM qw_query_run WHERE thread_id = ? ORDER BY create_time DESC LIMIT 1
				""", runMapper, threadId));
	}

	public QueryRun lock(String runId) {
		return first(jdbc.query("SELECT * FROM qw_query_run WHERE run_id = ? FOR UPDATE", runMapper, runId))
			.orElseThrow(() -> new IllegalArgumentException("Query run not found: " + runId));
	}

	public int insertIfAbsent(QueryRun run) {
		return jdbc.update(
				"""
						INSERT INTO qw_query_run
						(run_id, run_type, project_id, project_version_id, episode_id, attempt_id, thread_id, status,
						 current_node, last_event_sequence, request_id, idempotency_key, owner_instance, lease_expire_time,
						 start_time, finish_time, error_code, error_message, revision, request_payload, recovery_payload,
						 execution_snapshot, deadline_epoch_millis, create_time, update_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						ON CONFLICT (idempotency_key) DO NOTHING
						""",
				run.runId(), run.runType().name(), run.projectId(), run.projectVersionId(), run.episodeId(),
				run.attemptId(), run.threadId(), run.status().name(), run.currentNode(), run.lastEventSequence(),
				run.requestId(), run.idempotencyKey(), run.ownerInstance(), run.leaseExpireTime(), run.startTime(),
				run.finishTime(), run.errorCode(), run.errorMessage(), run.revision(), run.requestPayload(),
				run.recoveryPayload(), run.executionSnapshot(), run.deadlineEpochMillis());
	}

	public int updateRecoveryPayload(String runId, long expectedRevision, String recoveryPayload) {
		return jdbc.update("""
				UPDATE qw_query_run
				SET recovery_payload = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ?
				""", recoveryPayload, runId, expectedRevision);
	}

	public int prepareQueuedExecution(String runId, long expectedRevision, RunType runType, String requestPayload,
			String executionSnapshot) {
		return jdbc.update("""
				UPDATE qw_query_run
				SET run_type = ?, request_payload = ?, recovery_payload = ?, execution_snapshot = ?,
				    current_node = NULL, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ? AND status = 'QUEUED'
				""", runType.name(), requestPayload, requestPayload, executionSnapshot, runId, expectedRevision);
	}

	public int requeueForReplan(String runId, long expectedRevision, String currentNode) {
		return jdbc.update("""
				UPDATE qw_query_run
				SET status = 'QUEUED', current_node = ?, error_code = NULL, error_message = NULL,
				    finish_time = NULL, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ? AND status IN ('RUNNING','WAITING_HUMAN','FAILED')
				""", currentNode, runId, expectedRevision);
	}

	public int updateStatus(String runId, long expectedRevision, RunStatus status, String currentNode, String errorCode,
			String errorMessage, LocalDateTime finishTime) {
		return jdbc.update("""
				UPDATE qw_query_run
				SET status = ?, current_node = ?, error_code = ?, error_message = ?, finish_time = ?,
				    revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ?
				""", status.name(), currentNode, errorCode, errorMessage, finishTime, runId, expectedRevision);
	}

	public int bindExecution(String runId, long expectedRevision, String episodeId, String attemptId, String threadId) {
		return jdbc.update(
				"""
						UPDATE qw_query_run SET episode_id = ?, attempt_id = ?, thread_id = ?, status = 'RUNNING',
						start_time = COALESCE(start_time, CURRENT_TIMESTAMP), revision = revision + 1, update_time = CURRENT_TIMESTAMP
						WHERE run_id = ? AND revision = ? AND status = 'QUEUED'
						""",
				episodeId, attemptId, threadId, runId, expectedRevision);
	}

	public Optional<RunEvent> findEventByIdempotency(String runId, String idempotencyKey) {
		return first(jdbc.query("SELECT * FROM qw_run_event WHERE run_id = ? AND idempotency_key = ?", eventMapper,
				runId, idempotencyKey));
	}

	public void insertEvent(RunEvent event) {
		jdbc.update("""
				INSERT INTO qw_run_event
				(run_id, sequence, event_type, node_name, payload, payload_summary, idempotency_key, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", event.runId(), event.sequence(), event.eventType(), event.nodeName(), event.payload(),
				event.payloadSummary(), event.idempotencyKey());
	}

	public int advanceSequenceLocked(String runId, long sequence, String nodeName) {
		return jdbc.update("""
				UPDATE qw_query_run SET last_event_sequence = ?, current_node = COALESCE(?, current_node),
				revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND last_event_sequence < ?
				""", sequence, nodeName, runId, sequence);
	}

	public List<RunEvent> events(String runId, long afterSequence, int limit) {
		return jdbc.query("""
				SELECT * FROM qw_run_event WHERE run_id = ? AND sequence > ? ORDER BY sequence ASC LIMIT ?
				""", eventMapper, runId, afterSequence, Math.max(1, Math.min(limit, 1000)));
	}

	public Optional<RunEvent> latestEvent(String runId, String eventType) {
		return first(jdbc.query("""
				SELECT * FROM qw_run_event WHERE run_id = ? AND event_type = ?
				ORDER BY sequence DESC LIMIT 1
				""", eventMapper, runId, eventType));
	}

	public List<String> outputNodeSequence(String runId) {
		return outputNodeSequence(runId, 0);
	}

	public List<String> outputNodeSequence(String runId, long afterSequence) {
		return jdbc.queryForList("""
				WITH ordered_outputs AS (
				    SELECT sequence, node_name,
				           LAG(node_name) OVER (ORDER BY sequence) AS previous_node
				    FROM qw_run_event
				    WHERE run_id = ? AND event_type = 'NODE_OUTPUT' AND node_name IS NOT NULL AND sequence > ?
				)
				SELECT node_name
				FROM ordered_outputs
				WHERE previous_node IS DISTINCT FROM node_name
				ORDER BY sequence
				""", String.class, runId, Math.max(0, afterSequence));
	}

	public int acquireLease(String runId, long expectedRevision, String ownerInstance, LocalDateTime expiresAt,
			LocalDateTime now) {
		return jdbc.update("""
				UPDATE qw_query_run SET owner_instance = ?, lease_expire_time = ?, revision = revision + 1,
				update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ?
				AND (owner_instance IS NULL OR owner_instance = ? OR lease_expire_time IS NULL OR lease_expire_time < ?)
				AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
				""", ownerInstance, expiresAt, runId, expectedRevision, ownerInstance, now);
	}

	public int renewLease(String runId, long expectedRevision, String ownerInstance, LocalDateTime expiresAt) {
		return jdbc.update("""
				UPDATE qw_query_run SET lease_expire_time = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND revision = ? AND owner_instance = ?
				AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
				""", expiresAt, runId, expectedRevision, ownerInstance);
	}

	public int clearLease(String runId, long expectedRevision, String ownerInstance) {
		return jdbc.update("""
				UPDATE qw_query_run SET owner_instance = NULL, lease_expire_time = NULL, revision = revision + 1,
				update_time = CURRENT_TIMESTAMP WHERE run_id = ? AND revision = ? AND owner_instance = ?
				""", runId, expectedRevision, ownerInstance);
	}

	public Optional<RunCheckpoint> checkpoint(String runId) {
		List<RunCheckpoint> values = jdbc.query("SELECT * FROM qw_run_checkpoint WHERE run_id = ?",
				(rs, rowNum) -> RunCheckpoint.builder()
					.runId(rs.getString("run_id"))
					.threadId(rs.getString("thread_id"))
					.currentNode(rs.getString("current_node"))
					.stateJson(rs.getString("state_json"))
					.completedNodeKeys(rs.getString("completed_node_keys"))
					.revision(rs.getLong("revision"))
					.updateTime(localDateTime(rs.getTimestamp("update_time")))
					.build(),
				runId);
		return first(values);
	}

	public int updateCheckpoint(RunCheckpoint checkpoint, long expectedRevision) {
		return jdbc.update("""
				UPDATE qw_run_checkpoint SET thread_id = ?, current_node = ?, state_json = ?, completed_node_keys = ?,
				revision = revision + 1, update_time = CURRENT_TIMESTAMP WHERE run_id = ? AND revision = ?
				""", checkpoint.threadId(), checkpoint.currentNode(), checkpoint.stateJson(),
				checkpoint.completedNodeKeys(), checkpoint.runId(), expectedRevision);
	}

	public void insertCheckpoint(RunCheckpoint checkpoint) {
		jdbc.update("""
				INSERT INTO qw_run_checkpoint
				(run_id, thread_id, current_node, state_json, completed_node_keys, revision, update_time)
				VALUES (?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
				""", checkpoint.runId(), checkpoint.threadId(), checkpoint.currentNode(), checkpoint.stateJson(),
				checkpoint.completedNodeKeys());
	}

	public List<QueryRun> recoverable(LocalDateTime now, int limit) {
		return jdbc.query("""
				SELECT * FROM qw_query_run
				WHERE status IN ('QUEUED','RUNNING','WAITING_HUMAN')
				AND (owner_instance IS NULL OR lease_expire_time IS NULL OR lease_expire_time < ?)
				ORDER BY update_time ASC LIMIT ?
				""", runMapper, now, Math.max(1, Math.min(limit, 1000)));
	}

	private QueryRun mapRun(ResultSet rs, int rowNum) throws SQLException {
		return QueryRun.builder()
			.runId(rs.getString("run_id"))
			.runType(RunType.valueOf(rs.getString("run_type")))
			.projectId(nullableLong(rs, "project_id"))
			.projectVersionId(nullableLong(rs, "project_version_id"))
			.episodeId(rs.getString("episode_id"))
			.attemptId(rs.getString("attempt_id"))
			.threadId(rs.getString("thread_id"))
			.status(RunStatus.valueOf(rs.getString("status")))
			.currentNode(rs.getString("current_node"))
			.lastEventSequence(rs.getLong("last_event_sequence"))
			.requestId(rs.getString("request_id"))
			.idempotencyKey(rs.getString("idempotency_key"))
			.ownerInstance(rs.getString("owner_instance"))
			.leaseExpireTime(localDateTime(rs.getTimestamp("lease_expire_time")))
			.startTime(localDateTime(rs.getTimestamp("start_time")))
			.finishTime(localDateTime(rs.getTimestamp("finish_time")))
			.errorCode(rs.getString("error_code"))
			.errorMessage(rs.getString("error_message"))
			.revision(rs.getLong("revision"))
			.requestPayload(rs.getString("request_payload"))
			.recoveryPayload(rs.getString("recovery_payload"))
			.executionSnapshot(rs.getString("execution_snapshot"))
			.deadlineEpochMillis(nullableLong(rs, "deadline_epoch_millis"))
			.build();
	}

	private RunEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
		return RunEvent.builder()
			.runId(rs.getString("run_id"))
			.sequence(rs.getLong("sequence"))
			.eventType(rs.getString("event_type"))
			.nodeName(rs.getString("node_name"))
			.payload(rs.getString("payload"))
			.payloadSummary(rs.getString("payload_summary"))
			.idempotencyKey(rs.getString("idempotency_key"))
			.createTime(localDateTime(rs.getTimestamp("create_time")))
			.build();
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static LocalDateTime localDateTime(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	private static <T> Optional<T> first(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

}
