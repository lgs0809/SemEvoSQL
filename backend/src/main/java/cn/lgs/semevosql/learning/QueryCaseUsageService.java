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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.run.RunExecutionFenceService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Transaction boundary for Query Case recall and outcome usage facts. */
@Service
public class QueryCaseUsageService {

	private final JdbcTemplate jdbc;

	private final QueryCaseQuarantineService quarantineService;

	private final RunExecutionFenceService executionFence;

	@Autowired
	public QueryCaseUsageService(JdbcTemplate jdbc, QueryCaseQuarantineService quarantineService,
			RunExecutionFenceService executionFence) {
		this.jdbc = jdbc;
		this.quarantineService = quarantineService;
		this.executionFence = executionFence;
	}

	/** Lightweight constructor retained for focused tests and compatibility callers. */
	public QueryCaseUsageService(JdbcTemplate jdbc, QueryCaseQuarantineService quarantineService) {
		this(jdbc, quarantineService, null);
	}

	@Transactional
	public void recordHintUsage(String runId, QueryCaseHints hints) {
		recordHintUsage(runId, null, hints);
	}

	@Transactional
	public void recordHintUsage(String runId, String attemptId, QueryCaseHints hints) {
		if (!StringUtils.hasText(runId) || hints == null || hints.sourceExampleIds().isEmpty()) {
			return;
		}
		if (StringUtils.hasText(attemptId)) {
			if (executionFence == null) {
				throw new IllegalStateException("Run execution fence is required for attempt-scoped query case usage");
			}
			executionFence.assertActiveAndLock(runId, attemptId);
		}
		for (String caseId : new LinkedHashSet<>(hints.sourceExampleIds())) {
			String id = UUID
				.nameUUIDFromBytes(("query-case-usage:" + runId + ":" + caseId).getBytes(StandardCharsets.UTF_8))
				.toString();
			jdbc.update("""
					INSERT INTO qw_query_case_usage
					(id, run_id, query_example_id, recalled, adopted, failed_after_recall,
					 create_time, update_time)
					VALUES (?, ?, ?, TRUE, FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					ON CONFLICT (run_id, query_example_id) DO NOTHING
					""", id, runId, caseId);
		}
	}

	@Transactional
	public void recordEpisodeOutcome(String episodeId, String outcome) {
		if (!StringUtils.hasText(episodeId) || !StringUtils.hasText(outcome)) {
			return;
		}
		boolean failed = "FAILED".equalsIgnoreCase(outcome);
		for (Map<String, Object> usage : jdbc.queryForList("""
				SELECT u.*, r.error_code AS run_error_code, r.attempt_id AS run_attempt_id
				FROM qw_query_case_usage u
				JOIN qw_query_run r ON r.run_id = u.run_id
				WHERE r.episode_id = ?
				""", episodeId)) {
			String usageId = Objects.toString(usage.get("id"));
			String queryCaseId = Objects.toString(usage.get("query_example_id"));
			String runId = Objects.toString(usage.get("run_id"));
			String attemptId = Objects.toString(usage.get("run_attempt_id"), "");
			if (executionFence != null && StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
				executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
			}
			String errorCode = Objects.toString(usage.get("run_error_code"), "");
			boolean attributableFailure = failed && failureAttributableToQueryCase(runId, errorCode);
			boolean issue = failed ? attributableFailure : hasClarificationOrRepair(runId);
			if (attributableFailure) {
				int changed = jdbc.update("""
						UPDATE qw_query_case_usage
						SET failed_after_recall = TRUE, outcome = 'FAILED', update_time = CURRENT_TIMESTAMP
						WHERE id = ? AND failed_after_recall = FALSE
						""", usageId);
				if (changed == 1) {
					jdbc.update("""
							UPDATE qw_query_example
							SET failed_after_recall_count = failed_after_recall_count + 1,
							    update_time = CURRENT_TIMESTAMP WHERE id = ?
							""", queryCaseId);
				}
			}
			else {
				String recordedOutcome = failed ? "FAILED_NON_ATTRIBUTABLE" : outcome.toUpperCase(Locale.ROOT);
				jdbc.update("""
						UPDATE qw_query_case_usage SET outcome = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?
						""", recordedOutcome, usageId);
			}
			jdbc.update(issue ? """
					UPDATE qw_query_example
					SET consecutive_recall_issue_count = consecutive_recall_issue_count + 1,
					    update_time = CURRENT_TIMESTAMP WHERE id = ?
					""" : """
					UPDATE qw_query_example SET consecutive_recall_issue_count = 0,
					    update_time = CURRENT_TIMESTAMP WHERE id = ?
					""", queryCaseId);
			quarantineService.evaluate(queryCaseId);
		}
	}

	@Transactional
	public void recordEpisodeAdoption(String episodeId, boolean adopted) {
		if (!adopted || !StringUtils.hasText(episodeId)) {
			return;
		}
		for (Map<String, Object> usage : jdbc.queryForList("""
				SELECT u.*, r.run_id, r.attempt_id AS run_attempt_id FROM qw_query_case_usage u
				JOIN qw_query_run r ON r.run_id = u.run_id
				WHERE r.episode_id = ?
				""", episodeId)) {
			String runId = Objects.toString(usage.get("run_id"), "");
			String attemptId = Objects.toString(usage.get("run_attempt_id"), "");
			if (executionFence != null && StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
				executionFence.assertFinalizerOwnsRunAndLock(runId, attemptId);
			}
			int changed = jdbc.update("""
					UPDATE qw_query_case_usage SET adopted = TRUE, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND adopted = FALSE
					""", usage.get("id"));
			if (changed == 1) {
				jdbc.update("""
						UPDATE qw_query_example SET adopted_count = adopted_count + 1,
						 update_time = CURRENT_TIMESTAMP WHERE id = ?
						""", usage.get("query_example_id"));
			}
		}
	}

	@Transactional
	public void recordRecall(String queryCaseId) {
		jdbc.update("""
				UPDATE qw_query_example SET recall_count = recall_count + 1,
				 last_recalled_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = ?
				""", queryCaseId);
	}

	private boolean failureAttributableToQueryCase(String runId, String errorCode) {
		if (nonAttributableRuntimeFailure(errorCode)) {
			return false;
		}
		return hasClarificationOrRepair(runId);
	}

	static boolean nonAttributableRuntimeFailure(String errorCode) {
		if (!StringUtils.hasText(errorCode)) {
			return false;
		}
		String normalized = errorCode.trim().toUpperCase(Locale.ROOT);
		return normalized.contains("EXECUTION_SNAPSHOT_MISMATCH") || normalized.contains("WEBCLIENT")
				|| normalized.contains("MODELUNAVAILABLE") || normalized.contains("RUNLEASE")
				|| normalized.contains("RUNINPROGRESS") || normalized.contains("CANCELLATION");
	}

	private boolean hasClarificationOrRepair(String runId) {
		if (!StringUtils.hasText(runId)) {
			return false;
		}
		Integer clarifications = jdbc.queryForObject("SELECT COUNT(*) FROM qw_runtime_clarification WHERE run_id = ?",
				Integer.class, runId);
		if (clarifications != null && clarifications > 0) {
			return true;
		}
		Integer repairs = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_node_trace n
				JOIN qw_query_run r ON r.attempt_id = n.attempt_id
				WHERE r.run_id = ? AND n.correction_type IS NOT NULL
				""", Integer.class, runId);
		return repairs != null && repairs > 0;
	}

}
