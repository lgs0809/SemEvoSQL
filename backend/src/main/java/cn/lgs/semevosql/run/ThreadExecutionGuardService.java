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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Database-backed single-flight guard for a conversation thread. The guard is
 * intentionally independent from the JVM stream context so two application instances
 * cannot execute different runs for the same thread at the same time.
 */
@Service
public class ThreadExecutionGuardService {

	private final JdbcTemplate jdbc;

	public ThreadExecutionGuardService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public void claim(String threadId, String runId) {
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(runId)) {
			throw new IllegalArgumentException("threadId and runId are required");
		}
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		if (insertIfAbsent(threadId, runId, now) == 1) {
			return;
		}
		Guard current = lock(threadId);
		if (current == null) {
			Guard runOwner = lockByRunId(runId);
			if (runOwner != null) {
				throw new IllegalArgumentException(
						"Run " + runId + " is already bound to thread " + runOwner.threadId());
			}
			if (insertIfAbsent(threadId, runId, now) == 1) {
				return;
			}
			current = lock(threadId);
			if (current == null) {
				runOwner = lockByRunId(runId);
				if (runOwner != null) {
					throw new IllegalArgumentException(
							"Run " + runId + " is already bound to thread " + runOwner.threadId());
				}
				throw new IllegalStateException("Unable to claim execution guard for thread " + threadId);
			}
		}
		if (runId.equals(current.runId())) {
			jdbc.update("UPDATE qw_thread_execution_guard SET update_time = ? WHERE thread_id = ? AND run_id = ?",
					Timestamp.valueOf(LocalDateTime.now()), threadId, runId);
			return;
		}
		String status = findRunStatus(current.runId());
		if (isReleasedStatus(status)) {
			jdbc.update("""
					UPDATE qw_thread_execution_guard
					SET run_id = ?, update_time = ?
					WHERE thread_id = ? AND run_id = ?
					""", runId, Timestamp.valueOf(LocalDateTime.now()), threadId, current.runId());
			return;
		}
		throw new ThreadExecutionConflictException(threadId, current.runId(), status);
	}

	private int insertIfAbsent(String threadId, String runId, Timestamp now) {
		return jdbc.update("""
				INSERT INTO qw_thread_execution_guard(thread_id, run_id, create_time, update_time)
				VALUES (?, ?, ?, ?)
				ON CONFLICT DO NOTHING
				""", threadId, runId, now, now);
	}

	@Transactional
	public void release(String threadId, String runId) {
		if (StringUtils.hasText(threadId) && StringUtils.hasText(runId)) {
			jdbc.update("DELETE FROM qw_thread_execution_guard WHERE thread_id = ? AND run_id = ?", threadId, runId);
		}
	}

	private Guard lock(String threadId) {
		List<Guard> values = jdbc.query("""
				SELECT thread_id, run_id
				FROM qw_thread_execution_guard
				WHERE thread_id = ?
				FOR UPDATE
				""", (rs, rowNum) -> new Guard(rs.getString("thread_id"), rs.getString("run_id")), threadId);
		return values.isEmpty() ? null : values.get(0);
	}

	private Guard lockByRunId(String runId) {
		List<Guard> values = jdbc.query("""
				SELECT thread_id, run_id
				FROM qw_thread_execution_guard
				WHERE run_id = ?
				FOR UPDATE
				""", (rs, rowNum) -> new Guard(rs.getString("thread_id"), rs.getString("run_id")), runId);
		return values.isEmpty() ? null : values.get(0);
	}

	private String findRunStatus(String runId) {
		List<String> statuses = jdbc.query("SELECT status FROM qw_query_run WHERE run_id = ?",
				(rs, rowNum) -> rs.getString(1), runId);
		return statuses.isEmpty() ? null : statuses.get(0);
	}

	private static boolean isReleasedStatus(String status) {
		return status == null || "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
				|| "EXPIRED".equals(status);
	}

	private record Guard(String threadId, String runId) {
	}

	public static final class ThreadExecutionConflictException extends RuntimeException {

		private final String threadId;

		private final String activeRunId;

		private final String activeStatus;

		public ThreadExecutionConflictException(String threadId, String activeRunId, String activeStatus) {
			super("Thread " + threadId + " is already owned by run " + activeRunId + " (" + activeStatus + ")");
			this.threadId = threadId;
			this.activeRunId = activeRunId;
			this.activeStatus = activeStatus;
		}

		public String threadId() {
			return threadId;
		}

		public String activeRunId() {
			return activeRunId;
		}

		public String activeStatus() {
			return activeStatus;
		}

	}

}
