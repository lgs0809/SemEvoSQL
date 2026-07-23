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
package cn.lgs.semevosql.task;

import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.QueryTask.TaskStatus;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable storage for optional in-run QueryTask/Todo state and accepted request context facts. */
@Repository
@RequiredArgsConstructor
public class QueryTaskRepository {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public void initialize(String runId, List<QueryTask> tasks) {
		if (tasks == null || tasks.size() < 2) {
			throw new IllegalArgumentException("Todo initialization requires at least two independent tasks");
		}
		for (QueryTask task : tasks) {
			jdbcTemplate.update("""
					INSERT INTO qw_query_task
					(run_id, task_id, ordinal_no, question, dependencies_json, status)
					VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?)
					ON CONFLICT (run_id, task_id) DO NOTHING
					""", runId, task.taskId(), task.ordinal(), task.question(), json(task.dependencies()), TaskStatus.PENDING.name());
		}
	}

	public List<QueryTask> list(String runId) {
		return jdbcTemplate.query("""
				SELECT task_id, ordinal_no, question, dependencies_json::text AS dependencies_json, status
				FROM qw_query_task WHERE run_id = ? ORDER BY ordinal_no
				""", this::mapTask, runId);
	}

	public boolean enabled(String runId) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM qw_query_task WHERE run_id = ?", Integer.class, runId);
		return count != null && count > 0;
	}

	public Optional<QueryTask> active(String runId) {
		return jdbcTemplate.query("""
				SELECT task_id, ordinal_no, question, dependencies_json::text AS dependencies_json, status
				FROM qw_query_task WHERE run_id = ? AND status = 'ACTIVE' ORDER BY ordinal_no LIMIT 1
				""", this::mapTask, runId).stream().findFirst();
	}

	@Transactional
	public QueryTask activateFirst(String runId) {
		Optional<QueryTask> existing = active(runId);
		if (existing.isPresent()) {
			return existing.orElseThrow();
		}
		QueryTask first = nextRunnable(runId)
			.orElseThrow(() -> new IllegalStateException("No pending Todo can be activated for run " + runId));
		activate(runId, first.taskId());
		return active(runId).orElseThrow();
	}

	@Transactional
	public void activate(String runId, String taskId) {
		Integer activeCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM qw_query_task WHERE run_id = ? AND status = 'ACTIVE'", Integer.class, runId);
		if (activeCount != null && activeCount > 0) {
			throw new IllegalStateException("Another Todo is already ACTIVE for run " + runId);
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_query_task SET status = 'ACTIVE', revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND task_id = ? AND status = 'PENDING'
				""", runId, taskId);
		if (updated != 1) {
			throw new IllegalStateException("Unable to activate pending Todo: " + runId + "/" + taskId);
		}
	}

	public Optional<QueryTask> nextRunnable(String runId) {
		List<QueryTask> tasks = list(runId);
		var done = tasks.stream().filter(task -> task.status() == TaskStatus.DONE).map(QueryTask::taskId)
			.collect(java.util.stream.Collectors.toSet());
		return tasks.stream().filter(task -> task.status() == TaskStatus.PENDING)
			.filter(task -> done.containsAll(task.dependencies())).findFirst();
	}

	public boolean allDone(String runId) {
		List<QueryTask> tasks = list(runId);
		return !tasks.isEmpty() && tasks.stream().allMatch(task -> task.status() == TaskStatus.DONE);
	}

	public SemanticBlueprint plan(String runId, String taskId) {
		String value = jdbcTemplate.queryForObject(
				"SELECT semantic_plan_json::text FROM qw_query_task WHERE run_id = ? AND task_id = ?", String.class, runId,
				taskId);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return JsonUtil.getObjectMapper().readValue(value, SemanticBlueprint.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to read persisted QueryTask semantic plan", ex);
		}
	}

	@Transactional
	public void savePlan(String runId, String taskId, SemanticBlueprint plan) {
		int updated = jdbcTemplate.update("""
				UPDATE qw_query_task SET semantic_plan_json = CAST(? AS JSONB), revision = revision + 1,
				update_time = CURRENT_TIMESTAMP WHERE run_id = ? AND task_id = ? AND status = 'ACTIVE'
				""", json(plan), runId, taskId);
		if (updated != 1) {
			throw new IllegalStateException("Semantic plan can only be saved for the ACTIVE Todo: " + runId + "/" + taskId);
		}
	}

	@Transactional
	public void saveAcceptedResult(String runId, String taskId, Object resultSummary, PostExecutionReview review) {
		if (review == null || review.decision() != PostExecutionReview.Decision.PASS) {
			throw new IllegalArgumentException("Only Post Review PASS may be persisted as an accepted task result");
		}
		int updated = jdbcTemplate.update("""
				UPDATE qw_query_task SET result_summary_json = CAST(? AS JSONB), review_json = CAST(? AS JSONB),
				status = 'DONE', revision = revision + 1, update_time = CURRENT_TIMESTAMP, finish_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND task_id = ? AND status = 'ACTIVE'
				""", json(resultSummary == null ? java.util.Map.of() : resultSummary), json(review), runId, taskId);
		if (updated != 1) {
			throw new IllegalStateException("Accepted result can only complete the ACTIVE Todo: " + runId + "/" + taskId);
		}
	}

	@Transactional
	public void markFailed(String runId, String taskId) {
		int updated = jdbcTemplate.update("""
				UPDATE qw_query_task SET status = 'FAILED', revision = revision + 1,
				update_time = CURRENT_TIMESTAMP, finish_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND task_id = ? AND status = 'ACTIVE'
				""", runId, taskId);
		if (updated != 1) {
			throw new IllegalStateException("Only the ACTIVE Todo may fail: " + runId + "/" + taskId);
		}
	}

	public String resultSummaryJson(String runId, String taskId) {
		return jdbcTemplate.queryForObject("SELECT result_summary_json::text FROM qw_query_task WHERE run_id = ? AND task_id = ?",
				String.class, runId, taskId);
	}

	@Transactional
	public void addAcceptedFact(String runId, String taskId, String factType, String factKey, Object fact,
			AcceptedFactSource source) {
		if (source == null || (source != AcceptedFactSource.REVIEW_PASS && source != AcceptedFactSource.USER_CONFIRMED)) {
			throw new IllegalArgumentException("Accepted context facts require REVIEW_PASS or USER_CONFIRMED source");
		}
		jdbcTemplate.update("""
				INSERT INTO qw_request_context_fact
				(fact_id, run_id, task_id, fact_type, fact_key, fact_json, source_type)
				VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
				ON CONFLICT (run_id, fact_type, fact_key, source_type)
				DO UPDATE SET fact_json = EXCLUDED.fact_json, task_id = EXCLUDED.task_id, create_time = CURRENT_TIMESTAMP
				""", UUID.randomUUID().toString(), runId, taskId, factType, factKey, json(fact), source.name());
	}

	public List<AcceptedContextFact> acceptedFacts(String runId) {
		return jdbcTemplate.query("""
				SELECT fact_id, task_id, fact_type, fact_key, fact_json::text AS fact_json, source_type
				FROM qw_request_context_fact WHERE run_id = ? ORDER BY create_time, fact_id
				""", (rs, rowNum) -> new AcceptedContextFact(rs.getString("fact_id"), rs.getString("task_id"),
					rs.getString("fact_type"), rs.getString("fact_key"), rs.getString("fact_json"),
					AcceptedFactSource.valueOf(rs.getString("source_type"))), runId);
	}

	private QueryTask mapTask(ResultSet rs, int rowNum) throws SQLException {
		try {
			List<String> dependencies = JsonUtil.getObjectMapper().readValue(rs.getString("dependencies_json"), STRING_LIST);
			return new QueryTask(rs.getString("task_id"), rs.getInt("ordinal_no"), rs.getString("question"), dependencies,
					TaskStatus.valueOf(rs.getString("status")));
		}
		catch (Exception ex) {
			throw new SQLException("Unable to read persisted QueryTask", ex);
		}
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize QueryTask state", ex);
		}
	}

	public enum AcceptedFactSource {
		REVIEW_PASS,
		USER_CONFIRMED
	}

	public record AcceptedContextFact(String factId, String taskId, String factType, String factKey, String factJson,
			AcceptedFactSource source) {
	}
}
