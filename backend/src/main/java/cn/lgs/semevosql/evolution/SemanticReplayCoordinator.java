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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.connector.JdbcStatementCancellationRegistry;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.evolution.SemanticReplayService.ReplayCancelledException;
import cn.lgs.semevosql.evolution.SemanticReplayService.ReplayProgress;
import cn.lgs.semevosql.evolution.SemanticReplayService.ReplaySummary;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.run.RunLeaseUnavailableException;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable, asynchronous Semantic Replay coordinator built on the existing QueryRun and
 * EvaluationJob primitives. Browser/SSE lifetime never owns job lifetime.
 */
@Slf4j
@Service
public class SemanticReplayCoordinator {

	private static final String JOB_TYPE = "SEMANTIC_REPLAY";

	private final JdbcTemplate jdbc;

	private final QueryRunService runService;

	private final SemanticReplayService replayService;

	private final SemanticEvolutionAuditService auditService;

	private final Executor executor;

	private final SemanticEvolutionStateMachine stateMachine;

	private final VersionedJson versionedJson = new VersionedJson();

	private final java.util.Set<String> scheduled = ConcurrentHashMap.newKeySet();

	private ApplicationEventPublisher eventPublisher;

	@Autowired
	public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	@Autowired
	public SemanticReplayCoordinator(JdbcTemplate jdbc, QueryRunService runService, SemanticReplayService replayService,
			SemanticEvolutionAuditService auditService, @Qualifier("semEvoSQLEvaluationExecutor") Executor executor,
			SemanticEvolutionStateMachine stateMachine) {
		this.jdbc = jdbc;
		this.runService = runService;
		this.replayService = replayService;
		this.auditService = auditService;
		this.executor = executor;
		this.stateMachine = stateMachine;
	}

	@Transactional
	public ReplayRunView start(String candidateId, OperatorContext operator) {
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE",
				candidateId);
		Long projectId = number(candidate.get("project_id"));
		Long sourceVersionId = number(candidate.get("source_version_id"));
		boolean changeSetReplay = StringUtils.hasText(text(candidate.get("semantic_change_set_id")));
		Long targetVersionId = changeSetReplay ? sourceVersionId : number(candidate.get("target_draft_version_id"));
		List<Map<String, Object>> existing = jdbc.queryForList("""
				SELECT * FROM qw_evaluation_job
				WHERE project_id = ? AND idempotency_key = ? AND job_type = ?
				""", projectId, operator.idempotencyKey(), JOB_TYPE);
		if (!existing.isEmpty()) {
			Map<String, Object> job = existing.get(0);
			if (!Objects.equals(candidateId, text(job.get("candidate_id")))) {
				throw new IllegalArgumentException("Idempotency-Key is already bound to a different Semantic Replay");
			}
			scheduleAfterCommit(text(job.get("id")));
			return view(job);
		}
		List<Map<String, Object>> active = jdbc.queryForList("""
				SELECT * FROM qw_evaluation_job
				WHERE candidate_id = ? AND job_type = ? AND status IN ('PENDING','RUNNING')
				ORDER BY create_time DESC LIMIT 1
				""", candidateId, JOB_TYPE);
		if (!active.isEmpty()) {
			throw new IllegalStateException(
					"Semantic candidate already has an active Replay Run: " + text(active.get(0).get("id")));
		}
		String status = text(candidate.get("status"));
		if (!List.of("PATCH_APPLIED", "REPLAY_FAILED").contains(status)) {
			throw new IllegalStateException("Replay requires PATCH_APPLIED or REPLAY_FAILED; current=" + status);
		}
		String jobId = UUID
			.nameUUIDFromBytes(("semantic-replay-job:" + candidateId + ":" + operator.idempotencyKey())
				.getBytes(StandardCharsets.UTF_8))
			.toString();
		String requestJson = json(Map.of("candidateId", candidateId, "targetVersionId", targetVersionId));
		QueryRun run = runService
			.create(new CreateRunCommand(RunType.REPLAY, projectId, targetVersionId, "semantic-replay:" + candidateId,
					operator.requestId(), "semantic-replay:" + operator.idempotencyKey(), requestJson));
		jdbc.update("""
				INSERT INTO qw_evaluation_job
				(id, run_id, project_id, project_version_id, job_type, candidate_id, idempotency_key,
				 status, progress, cancel_requested, request_json, checkpoint_json, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, FALSE, ?, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", jobId, run.runId(), projectId, targetVersionId, JOB_TYPE, candidateId, operator.idempotencyKey(),
				requestJson);
		stateMachine.transition(candidateId, CandidateStatus.valueOf(status), number(candidate.get("revision")),
				CandidateStatus.REPLAY_RUNNING, Mutation.none());
		runService.appendEvent(run.runId(), "REPLAY_RUN_ESTABLISHED", "semantic-replay", requestJson,
				"Durable Semantic Replay established", "semantic-replay-established:" + jobId);
		auditService.append(candidateId, "REPLAY_STARTED", status, "REPLAY_RUNNING", operator,
				number(candidate.get("source_version_id")), targetVersionId, text(candidate.get("patch_hash")),
				run.runId(), Map.of("candidateId", candidateId), Map.of("replayRunId", jobId, "runId", run.runId()));
		scheduleAfterCommit(jobId);
		return view(one("SELECT * FROM qw_evaluation_job WHERE id = ?", jobId));
	}

	public ReplayRunView get(String replayRunId) {
		return view(one("SELECT * FROM qw_evaluation_job WHERE id = ? AND job_type = ?", replayRunId, JOB_TYPE));
	}

	public List<RunEvent> events(String replayRunId, long afterSequence, int limit) {
		Map<String, Object> job = one("SELECT * FROM qw_evaluation_job WHERE id = ? AND job_type = ?", replayRunId,
				JOB_TYPE);
		return runService.events(text(job.get("run_id")), afterSequence, Math.max(1, Math.min(limit, 1000)));
	}

	@Transactional
	public ReplayRunView cancel(String replayRunId, OperatorContext operator) {
		Map<String, Object> job = one("SELECT * FROM qw_evaluation_job WHERE id = ? AND job_type = ? FOR UPDATE",
				replayRunId, JOB_TYPE);
		String status = text(job.get("status"));
		if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
			return view(job);
		}
		String runId = text(job.get("run_id"));
		jdbc.update("""
				UPDATE qw_evaluation_job SET cancel_requested = TRUE, status = 'CANCELLED',
				 error_message = 'cancelled by operator', finished_time = CURRENT_TIMESTAMP,
				 update_time = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('PENDING','RUNNING')
				""", replayRunId);
		QueryRun run = runService.cancel(runId, operator.idempotencyKey());
		JdbcStatementCancellationRegistry.cancelPrefix("semantic-replay:" + runId);
		stateMachine.transition(text(job.get("candidate_id")), java.util.Set.of(CandidateStatus.REPLAY_RUNNING),
				CandidateStatus.PATCH_APPLIED, Mutation.none());
		if (run.status() == RunStatus.CANCEL_REQUESTED) {
			runService.acknowledgeCancelled(runId);
		}
		runService.appendEvent(runId, "RUN_CANCELLED", "semantic-replay", null, "Semantic Replay cancelled",
				"semantic-replay-cancelled:" + replayRunId);
		Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?",
				text(job.get("candidate_id")));
		auditService.append(text(job.get("candidate_id")), "REPLAY_CANCELLED", "REPLAY_RUNNING", "PATCH_APPLIED",
				operator, number(candidate.get("source_version_id")), number(candidate.get("target_draft_version_id")),
				text(candidate.get("patch_hash")), runId, Map.of("replayRunId", replayRunId),
				Map.of("replayRunId", replayRunId));
		releaseLease(runId);
		return get(replayRunId);
	}

	@Scheduled(fixedDelayString = "${semevosql.semantic-replay.recovery-scan-ms:10000}")
	public void recover() {
		jdbc.queryForList("""
				SELECT id FROM qw_evaluation_job
				WHERE job_type = ? AND status IN ('PENDING','RUNNING') ORDER BY update_time LIMIT 200
				""", JOB_TYPE).forEach(job -> schedule(text(job.get("id"))));
	}

	private void scheduleAfterCommit(String jobId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			schedule(jobId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				schedule(jobId);
			}
		});
	}

	private void schedule(String jobId) {
		if (!scheduled.add(jobId)) {
			return;
		}
		try {
			executor.execute(() -> {
				try {
					execute(jobId);
				}
				finally {
					scheduled.remove(jobId);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			scheduled.remove(jobId);
			Map<String, Object> job = one("SELECT * FROM qw_evaluation_job WHERE id = ?", jobId);
			runService.appendEvent(text(job.get("run_id")), "REPLAY_QUEUE_REJECTED", "semantic-replay", null,
					"Evaluation queue is full; durable recovery will retry", "replay-queue-rejected:" + jobId);
		}
	}

	private void execute(String jobId) {
		Map<String, Object> job = one("SELECT * FROM qw_evaluation_job WHERE id = ? AND job_type = ?", jobId, JOB_TYPE);
		if (!List.of("PENDING", "RUNNING").contains(text(job.get("status")))) {
			return;
		}
		String runId = text(job.get("run_id"));
		try {
			runService.acquireLease(runId);
		}
		catch (RunLeaseUnavailableException ex) {
			return;
		}
		try {
			QueryRun run = runService.get(runId);
			if (run.status() == RunStatus.QUEUED) {
				runService.transition(runId, RunStatus.RUNNING, "semantic-replay", null, null);
			}
			jdbc.update("""
					UPDATE qw_evaluation_job SET status = 'RUNNING', update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status = 'PENDING'
					""", jobId);
			runService.appendEvent(runId, "REPLAY_STARTED", "semantic-replay", null,
					"Semantic Replay execution started", "replay-started:" + jobId);
			DurableProgress progress = new DurableProgress(jobId, runId);
			ReplaySummary summary = replayService.resumeCandidate(text(job.get("candidate_id")), progress, jobId);
			String summaryJson = versionedJson.write(JsonPayloadRegistry.REPLAY_SUMMARY, summary);
			jdbc.update("""
					UPDATE qw_evaluation_job SET status = 'SUCCEEDED', progress = 100, current_case_id = NULL,
					 current_level = NULL, result_json = ?, finished_time = CURRENT_TIMESTAMP,
					 update_time = CURRENT_TIMESTAMP WHERE id = ? AND status = 'RUNNING'
					""", summaryJson, jobId);
			runService.transition(runId, RunStatus.SUCCEEDED, "semantic-replay", null, null);
			runService.appendEvent(runId, "RUN_SUCCEEDED", "semantic-replay", summaryJson, "Semantic Replay completed",
					"replay-succeeded:" + jobId);
			Map<String, Object> candidate = one("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?",
					text(job.get("candidate_id")));
			OperatorContext system = new OperatorContext("semevosql-system", "RECOVERY_WORKER",
					runId, "semantic-replay-complete:" + jobId);
			String candidateId = text(job.get("candidate_id"));
			auditService.append(candidateId, "REPLAY_COMPLETED", "REPLAY_RUNNING",
					summary.allPassed() ? "REPLAY_PASSED" : "REPLAY_FAILED", system,
					number(candidate.get("source_version_id")), number(candidate.get("target_draft_version_id")),
					text(candidate.get("patch_hash")), runId, Map.of("replayRunId", jobId), summary);
			if (summary.allPassed() && eventPublisher != null) {
				eventPublisher.publishEvent(new LowRiskSemanticEvolutionReplayPassedEvent(candidateId));
			}
		}
		catch (ReplayCancelledException ex) {
			acknowledgeWorkerCancellation(jobId, runId);
		}
		catch (Exception ex) {
			log.error("Semantic Replay {} failed", jobId, ex);
			jdbc.update("""
					UPDATE qw_evaluation_job SET status = 'FAILED', error_message = ?,
					 finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status IN ('PENDING','RUNNING')
					""", message(ex), jobId);
			String failureSummary = versionedJson.write(JsonPayloadRegistry.REPLAY_SUMMARY,
					Map.of("error", message(ex), "replayRunId", jobId, "allPassed", false));
			stateMachine.transition(text(job.get("candidate_id")), java.util.Set.of(CandidateStatus.REPLAY_RUNNING),
					CandidateStatus.REPLAY_FAILED, Mutation.replayCompleted(failureSummary));
			QueryRun current = runService.get(runId);
			if (current.status() == RunStatus.CANCEL_REQUESTED) {
				acknowledgeWorkerCancellation(jobId, runId);
			}
			else if (!current.terminal()) {
				runService.transition(runId, RunStatus.FAILED, "semantic-replay", "SEMANTIC_REPLAY_FAILED",
						message(ex));
				runService.appendEvent(runId, "RUN_FAILED", "semantic-replay", null, message(ex),
						"replay-failed:" + jobId);
			}
		}
		finally {
			releaseLease(runId);
		}
	}

	private void acknowledgeWorkerCancellation(String jobId, String runId) {
		jdbc.update("""
				UPDATE qw_evaluation_job SET status = 'CANCELLED', cancel_requested = TRUE,
				 finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status IN ('PENDING','RUNNING')
				""", jobId);
		QueryRun run = runService.get(runId);
		if (run.status() == RunStatus.CANCEL_REQUESTED) {
			runService.acknowledgeCancelled(runId);
		}
	}

	private void releaseLease(String runId) {
		try {
			runService.releaseLease(runId);
		}
		catch (RuntimeException ex) {
			log.debug("Unable to release Semantic Replay lease {}", runId, ex);
		}
	}

	private ReplayRunView view(Map<String, Object> value) {
		return new ReplayRunView(text(value.get("id")), text(value.get("run_id")), text(value.get("candidate_id")),
				text(value.get("status")),
				value.get("progress") == null ? 0 : ((Number) value.get("progress")).intValue(),
				textOrNull(value.get("current_case_id")), textOrNull(value.get("current_level")),
				textOrNull(value.get("checkpoint_json")), textOrNull(value.get("result_json")),
				textOrNull(value.get("error_message")), truth(value.get("cancel_requested")));
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Semantic Replay Run not found or not unique");
		}
		return rows.get(0);
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode Semantic Replay payload", ex);
		}
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private String textOrNull(Object value) {
		String result = text(value);
		return result.isBlank() ? null : result;
	}

	private boolean truth(Object value) {
		return value instanceof Boolean bool ? bool
				: value instanceof Number number ? number.intValue() != 0 : Boolean.parseBoolean(text(value));
	}

	private String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		String value = current.getMessage();
		return value == null || value.isBlank() ? current.getClass().getSimpleName() : value;
	}

	private final class DurableProgress implements ReplayProgress {

		private final String jobId;

		private final String runId;

		private int total;

		private int completed;

		private DurableProgress(String jobId, String runId) {
			this.jobId = jobId;
			this.runId = runId;
			Integer terminal = jdbc.queryForObject("""
					SELECT COUNT(DISTINCT case_id) FROM qw_semantic_replay_result
					WHERE candidate_id = (SELECT candidate_id FROM qw_evaluation_job WHERE id = ?)
					  AND status IN ('PASSED','FAILED','REVIEW_REQUIRED')
					""", Integer.class, jobId);
			this.completed = terminal == null ? 0 : terminal;
		}

		@Override
		public void total(int total) {
			this.total = Math.max(1, total);
			checkpoint(null, null);
		}

		@Override
		public void caseStarted(String caseId, String level) {
			renew();
			checkpoint(caseId, level);
		}

		@Override
		public void levelStarted(String caseId, String level) {
			renew();
			checkpoint(caseId, level);
		}

		@Override
		public void levelCompleted(String caseId, String level, String status) {
			runService.appendEvent(runId, "REPLAY_LEVEL_COMPLETED", "semantic-replay",
					json(Map.of("caseId", caseId, "level", level, "status", status)),
					"Replay level checkpoint persisted",
					"replay-level:" + jobId + ":" + caseId + ":" + level + ":" + status);
			checkpoint(caseId, level);
		}

		@Override
		public void caseCompleted(String caseId) {
			completed++;
			checkpoint(caseId, null);
		}

		@Override
		public boolean cancelled() {
			Map<String, Object> job = one("SELECT status, cancel_requested FROM qw_evaluation_job WHERE id = ?", jobId);
			return "CANCELLED".equals(text(job.get("status"))) || truth(job.get("cancel_requested"))
					|| runService.get(runId).status() == RunStatus.CANCEL_REQUESTED;
		}

		@Override
		public String cancellationKey() {
			return "semantic-replay:" + runId;
		}

		private void renew() {
			try {
				runService.renewLease(runId);
			}
			catch (RuntimeException ex) {
				if (cancelled()) {
					throw new ReplayCancelledException("Replay cancelled while renewing its lease");
				}
				throw ex;
			}
		}

		private void checkpoint(String caseId, String level) {
			int progress = Math.min(99, (int) Math.floor(completed * 100d / Math.max(1, total)));
			String checkpoint = versionedJson.write(JsonPayloadRegistry.EVALUATION_CHECKPOINT,
					Map.of("completedCases", completed, "totalCases", total));
			jdbc.update("""
					UPDATE qw_evaluation_job SET progress = ?, current_case_id = ?, current_level = ?,
					 checkpoint_json = ?, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status IN ('PENDING','RUNNING')
					""", progress, caseId, level, checkpoint, jobId);
			runService.saveCheckpoint(runId, "semantic-replay:" + jobId, level, checkpoint,
					caseId == null ? "" : caseId + ":" + Objects.toString(level, ""));
		}

	}

	public record ReplayRunView(String replayRunId, String runId, String candidateId, String status, int progress,
			String currentCaseId, String currentLevel, String checkpointJson, String resultJson, String errorMessage,
			boolean cancelRequested) {
	}

}
