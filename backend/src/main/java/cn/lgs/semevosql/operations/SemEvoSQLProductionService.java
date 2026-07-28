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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.concurrency.SemEvoSQLConcurrencyProperties;
import cn.lgs.semevosql.concurrency.TaskDeadlineExceededException;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService;
import cn.lgs.semevosql.episode.application.EpisodeApplicationService.StartCommand;
import cn.lgs.semevosql.episode.domain.EpisodeRelationType;
import cn.lgs.semevosql.episode.domain.EpisodeTurnType;
import cn.lgs.semevosql.evolution.GoldenReplayMode;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunLeaseUnavailableException;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.trajectory.TrajectoryAnalysisService;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persistent production control plane for audit, jobs, evaluation and controlled
 * releases. Payload fields deliberately store summaries, never credentials or result
 * sets.
 */
@Slf4j
@Service
public class SemEvoSQLProductionService {

	private static final Set<String> JOB_TYPES = Set.of("INITIALIZATION", "MATERIAL_PARSE", "REPLAY", "SCHEMA_DRIFT",
			"RELEASE_VALIDATION");

	private static final ScheduledExecutorService DEADLINE_SCHEDULER = Executors.newScheduledThreadPool(2, task -> {
		Thread thread = new Thread(task, "semevosql-job-deadline");
		thread.setDaemon(true);
		return thread;
	});

	private final JdbcTemplate jdbc;

	private final Executor initializationExecutor;

	private final Executor evaluationExecutor;

	private final SemanticCatalogCache catalogCache;

	private final ProjectVersionCatalogReadiness readiness;

	private final QueryRunService runService;

	private final EpisodeApplicationService episodeApplicationService;

	private final ValidatedQueryExampleService queryExampleService;

	private final VersionedJson versionedJson = new VersionedJson();

	private final TrajectoryAnalysisService trajectoryAnalysisService;

	private final QueryPatternTemplateService patternTemplateService;

	private final ControlledReleaseService controlledReleaseService;

	private final ProductionGoldenReplayRunner goldenReplayRunner;

	private volatile long initializationTaskTimeoutMs = 600000L;

	private volatile long evaluationTaskTimeoutMs = 600000L;

	private final Set<String> activeJobRuns = ConcurrentHashMap.newKeySet();

	private final Set<String> scheduledJobIds = ConcurrentHashMap.newKeySet();

	private final Set<String> deadlineExceededJobIds = ConcurrentHashMap.newKeySet();

	@Autowired
	public SemEvoSQLProductionService(JdbcTemplate jdbc,
			@Qualifier("semEvoSQLInitializationExecutor") Executor initializationExecutor,
			@Qualifier("semEvoSQLEvaluationExecutor") Executor evaluationExecutor, SemanticCatalogCache catalogCache,
			ProjectVersionCatalogReadiness readiness, QueryRunService runService,
			EpisodeApplicationService episodeApplicationService, ValidatedQueryExampleService queryExampleService,
			TrajectoryAnalysisService trajectoryAnalysisService,
			QueryPatternTemplateService patternTemplateService, ControlledReleaseService controlledReleaseService,
			ProductionGoldenReplayRunner goldenReplayRunner) {
		this.jdbc = jdbc;
		this.initializationExecutor = initializationExecutor;
		this.evaluationExecutor = evaluationExecutor;
		this.catalogCache = catalogCache;
		this.readiness = readiness;
		this.runService = runService;
		this.episodeApplicationService = episodeApplicationService;
		this.queryExampleService = queryExampleService;
		this.trajectoryAnalysisService = trajectoryAnalysisService;
		this.patternTemplateService = patternTemplateService;
		this.controlledReleaseService = controlledReleaseService;
		this.goldenReplayRunner = goldenReplayRunner;
	}

	@Autowired
	void setConcurrencyProperties(SemEvoSQLConcurrencyProperties properties) {
		this.initializationTaskTimeoutMs = Math.max(1L, properties.getInitialization().getTaskTimeoutMs());
		this.evaluationTaskTimeoutMs = Math.max(1L, properties.getEvaluation().getTaskTimeoutMs());
	}

	@Transactional
	public Map<String, Object> createEpisode(EpisodeRequest request) {
		var episode = episodeApplicationService.start(new StartCommand(request.requestId(), request.requestId(), null,
				request.agentId(), request.projectId(), request.projectVersionId(), request.datasourceId(),
				request.conversationId(), request.parentEpisodeId(), request.relationType(), request.originalQuestion(),
				request.normalizedQuestion(), request.modelName(), request.promptVersion()));
		return episode(episode.episodeId());
	}

	@Transactional
	public Map<String, Object> completeAttempt(String episodeId, String attemptId, String status, String errorType) {
		int updated = jdbc.update("""
				UPDATE qw_attempt
				SET status = ?, error_type = ?, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND episode_id = ? AND status = 'RUNNING'
				""", status, errorType, attemptId, episodeId);
		if (updated == 0) {
			List<Map<String, Object>> existing = jdbc
				.queryForList("SELECT * FROM qw_attempt WHERE id = ? AND episode_id = ?", attemptId, episodeId);
			if (existing.size() == 1 && Objects.equals(existing.get(0).get("status"), status)
					&& Objects.equals(existing.get(0).get("error_type"), errorType)) {
				return existing.get(0);
			}
			throw new IllegalStateException("Attempt is not RUNNING or does not belong to episode: " + attemptId);
		}
		return jdbc.queryForMap("SELECT * FROM qw_attempt WHERE id = ?", attemptId);
	}

	@Transactional
	public Map<String, Object> createNextAttempt(String episodeId) {
		return createNextAttempt(episodeId, EpisodeTurnType.RETRY, "Retry attempt", null);
	}

	@Transactional
	public Map<String, Object> createNextAttempt(String episodeId, EpisodeTurnType turnType, String turnContent,
			String requestId) {
		List<String> episodes = jdbc.query("SELECT id FROM qw_episode WHERE id = ? FOR UPDATE",
				(rs, rowNum) -> rs.getString(1), episodeId);
		if (episodes.isEmpty()) {
			throw new IllegalArgumentException("Episode not found: " + episodeId);
		}
		Integer nextAttempt = jdbc.queryForObject(
				"SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM qw_attempt WHERE episode_id = ?", Integer.class,
				episodeId);
		jdbc.update("""
				UPDATE qw_episode
				SET status = 'RUNNING', outcome = NULL, accepted_attempt_id = NULL, result_semantic_version_id = NULL,
				    error_type = NULL, duration_ms = NULL, completed_time = NULL, update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", episodeId);
		EpisodeTurnType effectiveTurnType = turnType == null ? EpisodeTurnType.RETRY : turnType;
		String role = effectiveTurnType == EpisodeTurnType.CORRECTION ? "USER" : "SYSTEM";
		episodeApplicationService.appendTurn(episodeId, effectiveTurnType, role,
				StringUtils.hasText(turnContent) ? turnContent : effectiveTurnType.name(), Map.of(),
				StringUtils.hasText(requestId) ? requestId
						: effectiveTurnType.name().toLowerCase() + ":" + episodeId + ":"
								+ (nextAttempt == null ? 1 : nextAttempt));
		return createAttempt(episodeId, nextAttempt == null ? 1 : nextAttempt);
	}

	@Transactional
	public Map<String, Object> createAttempt(String episodeId, int attemptNo) {
		List<Map<String, Object>> existing = jdbc
			.queryForList("SELECT * FROM qw_attempt WHERE episode_id = ? AND attempt_no = ?", episodeId, attemptNo);
		if (!existing.isEmpty()) {
			return existing.get(0);
		}
		String id = id();
		int inserted = jdbc.update("""
				INSERT INTO qw_attempt
				(id, episode_id, attempt_no, status, semantic_version_id, semantic_state_hash, create_time, update_time)
				SELECT ?, e.id, ?, 'RUNNING', e.base_semantic_version_id, e.accepted_semantic_state_hash,
				       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				FROM qw_episode e WHERE e.id = ?
				ON CONFLICT (episode_id, attempt_no) DO NOTHING
				""", id, attemptNo, episodeId);
		if (inserted == 1) {
			return one("SELECT * FROM qw_attempt WHERE id = ?", id);
		}
		return one("SELECT * FROM qw_attempt WHERE episode_id = ? AND attempt_no = ?", episodeId, attemptNo);
	}

	@Transactional
	public ExecutionBinding createFirstAttemptAndBind(String runId, String threadId, EpisodeRequest request) {
		String episodeId = Objects.toString(createEpisode(request).get("id"));
		String attemptId = Objects.toString(createAttempt(episodeId, 1).get("id"));
		QueryRun run = runService.bindExecution(runId, episodeId, attemptId, threadId);
		jdbc.update("UPDATE qw_attempt SET run_id = ? WHERE id = ?", runId, attemptId);
		return new ExecutionBinding(episodeId, attemptId, run);
	}

	@Transactional
	public ExecutionBinding createNextAttemptAndBind(String runId, String episodeId, String threadId) {
		return createNextAttemptAndBind(runId, episodeId, threadId, EpisodeTurnType.RETRY, "Retry attempt", null);
	}

	@Transactional
	public ExecutionBinding createNextAttemptAndBind(String runId, String episodeId, String threadId,
			EpisodeTurnType turnType, String turnContent, String requestId) {
		String attemptId = Objects.toString(createNextAttempt(episodeId, turnType, turnContent, requestId).get("id"));
		QueryRun run = runService.bindExecution(runId, episodeId, attemptId, threadId);
		jdbc.update("UPDATE qw_attempt SET run_id = ? WHERE id = ?", runId, attemptId);
		return new ExecutionBinding(episodeId, attemptId, run);
	}

	@Transactional
	public Map<String, Object> recordNodeTrace(String attemptId, TraceRequest request) {
		return insertTrace("qw_node_trace", attemptId, request);
	}

	@Transactional
	public Map<String, Object> recordSqlTrace(String attemptId, SqlTraceRequest request) {
		List<Map<String, Object>> existing = jdbc.queryForList(
				"SELECT * FROM qw_sql_trace WHERE attempt_id = ? AND idempotency_key = ?", attemptId,
				request.idempotencyKey());
		if (!existing.isEmpty()) {
			return existing.get(0);
		}
		assertAttemptAcceptsRuntimeEffects(attemptId);
		String id = id();
		int inserted = jdbc.update("""
				INSERT INTO qw_sql_trace
				(id, attempt_id, idempotency_key, sql_text, guard_summary, cost_summary, explain_summary,
				 preview_summary, result_summary, status, retry_count, duration_ms, error_type, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				ON CONFLICT (attempt_id, idempotency_key) DO NOTHING
				""", id, attemptId, request.idempotencyKey(), truncate(request.sqlText(), 100000),
				json(request.guardSummary()), json(request.costSummary()), json(request.explainSummary()),
				json(request.previewSummary()), json(request.resultSummary()), request.status(), request.retryCount(),
				request.durationMs(), request.errorType());
		if (inserted == 1) {
			return one("SELECT * FROM qw_sql_trace WHERE id = ?", id);
		}
		return one("SELECT * FROM qw_sql_trace WHERE attempt_id = ? AND idempotency_key = ?", attemptId,
				request.idempotencyKey());
	}

	@Transactional
	public Map<String, Object> completeEpisode(String episodeId, CompletionRequest request) {
		String acceptedAttemptId = jdbc.query("""
				SELECT id FROM qw_attempt WHERE episode_id = ? ORDER BY attempt_no DESC LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), episodeId).stream().findFirst().orElse(null);
		episodeApplicationService.complete(episodeId, request.status(), request.status(), acceptedAttemptId, null);
		jdbc.update("""
				UPDATE qw_episode SET error_type = ?, token_count = ?, duration_ms = ?, update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", request.errorType(), request.tokenCount(), request.durationMs(), episodeId);
		jdbc.update("""
				UPDATE qw_attempt SET status = ?, error_type = ?, update_time = CURRENT_TIMESTAMP
				WHERE episode_id = ? AND status = 'RUNNING'
				""", request.status(), request.errorType(), episodeId);
		Map<String, Object> completed = episode(episodeId);
		if ("SUCCEEDED".equals(request.status())) {
			queryExampleService.captureEligibleCandidate(episodeId);
		}
		queryExampleService.recordEpisodeOutcome(episodeId, request.status());
		scheduleTrajectoryAnalysisAfterCommit(episodeId);
		return completed;
	}

	@Transactional
	public Map<String, Object> feedback(String episodeId, FeedbackRequest request) {
		String key = episodeId + ":" + request.userId();
		if (jdbc.queryForObject("SELECT COUNT(*) FROM qw_feedback WHERE idempotency_key = ?", Integer.class, key) > 0) {
			jdbc.update("UPDATE qw_feedback SET rating = ?, adopted = ?, comment_text = ? WHERE idempotency_key = ?",
					request.rating(), request.adopted(), truncate(request.comment(), 4000), key);
		}
		else {
			jdbc.update("""
					INSERT INTO qw_feedback(id, episode_id, idempotency_key, rating, adopted, comment_text, create_time)
					VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
					""", id(), episodeId, key, request.rating(), request.adopted(), truncate(request.comment(), 4000));
		}
		Map<String, Object> feedback = one("SELECT * FROM qw_feedback WHERE idempotency_key = ?", key);
		boolean adopted = Boolean.TRUE.equals(request.adopted()) || (request.rating() != null && request.rating() >= 4);
		queryExampleService.recordEpisodeAdoption(episodeId, adopted);
		if (adopted) {
			queryExampleService.captureEligibleCandidate(episodeId);
		}
		else {
			jdbc.queryForList("SELECT run_id FROM qw_query_run WHERE episode_id = ?", String.class, episodeId)
				.forEach(runId -> patternTemplateService.invalidateByRun(runId,
						"User marked the result or interpretation as incorrect"));
		}
		return feedback;
	}

	public List<Map<String, Object>> listEpisodes(Long projectId, int limit) {
		return jdbc.queryForList("""
				SELECT id, request_id, agent_id, project_id, project_version_id, datasource_id, catalog_hash,
				status, error_type, token_count, duration_ms, create_time, update_time
				FROM qw_episode WHERE project_id = ? ORDER BY create_time DESC LIMIT ?
				""", projectId, Math.max(1, Math.min(limit, 200)));
	}

	@Transactional
	public Map<String, Object> createGoldenCase(Long projectId, GoldenCaseRequest request) {
		String id = id();
		GoldenReplayMode replayMode = GoldenReplayMode.from(request.replayMode());
		if (replayMode == GoldenReplayMode.FIXTURE
				&& !org.springframework.util.StringUtils.hasText(request.datasetVersion())) {
			throw new IllegalArgumentException("FIXTURE Golden Case requires datasetVersion");
		}
		jdbc.update("""
				INSERT INTO qw_golden_case
				(id, project_id, case_code, question, replay_mode, dataset_version, expected_json, enabled,
				 create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, projectId, request.caseCode(), request.question(), replayMode.name(), request.datasetVersion(),
				versionedJson.write(JsonPayloadRegistry.GOLDEN_CASE_EXPECTED, request.expected()),
				request.enabled() == null || request.enabled());
		return one("SELECT * FROM qw_golden_case WHERE id = ?", id);
	}

	public List<Map<String, Object>> listGoldenCases(Long projectId) {
		return jdbc.queryForList("SELECT * FROM qw_golden_case WHERE project_id = ? ORDER BY case_code", projectId);
	}

	@Transactional
	public Map<String, Object> createJob(Long projectId, JobRequest request) {
		if (projectId == null) {
			throw new IllegalArgumentException("projectId is required");
		}
		if (request == null || request.jobType() == null || request.jobType().isBlank()) {
			throw new IllegalArgumentException("jobType is required");
		}
		if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		String type = request.jobType().toUpperCase(Locale.ROOT);
		if (!JOB_TYPES.contains(type)) {
			throw new IllegalArgumentException("Unsupported job type: " + request.jobType());
		}
		List<Map<String, Object>> existing = jdbc.queryForList(
				"SELECT * FROM qw_evaluation_job WHERE project_id = ? AND idempotency_key = ?", projectId,
				request.idempotencyKey());
		if (!existing.isEmpty()) {
			return reuseExistingJob(projectId, request, type, existing.get(0));
		}
		String id = stableJobId(projectId, request.idempotencyKey());
		QueryRun run = createJobRun(id, projectId, request.projectVersionId(), type, request.idempotencyKey(),
				request.options());
		int inserted = jdbc.update("""
				INSERT INTO qw_evaluation_job
				(id, run_id, project_id, project_version_id, job_type, idempotency_key, status, progress,
				 request_json, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (project_id, idempotency_key) DO NOTHING
				""", id, run.runId(), projectId, request.projectVersionId(), type, request.idempotencyKey(),
				json(request.options() == null ? Map.of() : request.options()));
		if (inserted == 0) {
			Map<String, Object> concurrent = one(
					"SELECT * FROM qw_evaluation_job WHERE project_id = ? AND idempotency_key = ?", projectId,
					request.idempotencyKey());
			return reuseExistingJob(projectId, request, type, concurrent);
		}
		scheduleAfterCommit(id);
		return job(id);
	}

	private Map<String, Object> reuseExistingJob(Long projectId, JobRequest request, String type,
			Map<String, Object> current) {
		assertSameJobCommand(projectId, request, type, current);
		ensureJobRun(current);
		String currentStatus = Objects.toString(current.get("status"));
		if ("PENDING".equals(currentStatus) || "RUNNING".equals(currentStatus)) {
			scheduleAfterCommit(Objects.toString(current.get("id")));
		}
		return job(Objects.toString(current.get("id")));
	}

	private void assertSameJobCommand(Long projectId, JobRequest request, String type, Map<String, Object> current) {
		Map<String, Object> requestedOptions = request.options() == null ? Map.of() : request.options();
		Map<String, Object> persistedOptions = readJson(Objects.toString(current.get("request_json"), "{}"));
		if (!Objects.equals(projectId, number(current.get("project_id")))
				|| !Objects.equals(request.projectVersionId(), number(current.get("project_version_id")))
				|| !Objects.equals(type, Objects.toString(current.get("job_type")))
				|| !Objects.equals(requestedOptions, persistedOptions)) {
			throw new IllegalArgumentException("idempotencyKey is already bound to a different job command");
		}
	}

	private String stableJobId(Long projectId, String idempotencyKey) {
		return UUID
			.nameUUIDFromBytes(("semevosql-job:" + projectId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
			.toString();
	}

	public Map<String, Object> job(String id) {
		return one("SELECT * FROM qw_evaluation_job WHERE id = ?", id);
	}

	public List<Map<String, Object>> listJobs(Long projectId, int limit) {
		return jdbc.queryForList("""
				SELECT * FROM qw_evaluation_job WHERE project_id = ?
				ORDER BY create_time DESC LIMIT ?
				""", projectId, Math.max(1, Math.min(limit, 200)));
	}

	@Transactional
	public Map<String, Object> retryJob(String id) {
		Map<String, Object> current = job(id);
		String status = Objects.toString(current.get("status"));
		if (!Set.of("FAILED", "CANCELLED").contains(status)) {
			throw new IllegalStateException("Only FAILED or CANCELLED jobs can be retried");
		}
		String runId = Objects.toString(current.get("run_id"), "");
		if ("FAILED".equals(status) && !runId.isBlank()) {
			runService.resume(runId, "job-retry:" + id);
		}
		else {
			QueryRun replacement = createJobRun(id, number(current.get("project_id")),
					number(current.get("project_version_id")), Objects.toString(current.get("job_type")),
					Objects.toString(current.get("idempotency_key")) + ":retry:" + id(),
					readJson(Objects.toString(current.get("request_json"), "{}")));
			runId = replacement.runId();
		}
		jdbc.update("""
				UPDATE qw_evaluation_job SET run_id = ?, status = 'PENDING', progress = 0, error_message = NULL,
				result_json = NULL, finished_time = NULL, update_time = CURRENT_TIMESTAMP WHERE id = ?
				""", runId, id);
		scheduleAfterCommit(id);
		return job(id);
	}

	@Transactional
	public Map<String, Object> cancelJob(String id) {
		Map<String, Object> current = job(id);
		int cancelled = jdbc.update("""
				UPDATE qw_evaluation_job SET status = 'CANCELLED', error_message = 'cancelled by user',
				finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status IN ('PENDING','RUNNING')
				""", id);
		if (cancelled == 0) {
			return job(id);
		}
		String runId = Objects.toString(current.get("run_id"), "");
		if (!runId.isBlank()) {
			QueryRun run = runService.get(runId);
			if (!run.terminal() && run.status() != RunStatus.CANCEL_REQUESTED) {
				run = runService.cancel(runId, "job-cancel:" + id);
			}
			if (run.status() == RunStatus.CANCEL_REQUESTED) {
				runService.appendEvent(runId, "JOB_CANCELLED", "evaluation-job", null, "Job cancelled",
						"job-cancelled:" + runId);
				runService.acknowledgeCancelled(runId);
			}
		}
		return job(id);
	}

	void executeJob(String jobId) {
		String runId = null;
		try {
			Map<String, Object> initial = job(jobId);
			runId = Objects.toString(initial.get("run_id"), "");
			if (runId.isBlank()) {
				runId = ensureJobRun(initial).runId();
			}
			String initialStatus = Objects.toString(initial.get("status"));
			if ("CANCELLED".equals(initialStatus)) {
				acknowledgeJobCancellation(runId);
				return;
			}
			QueryRun existingRun = runService.get(runId);
			if (cancellationInProgress(existingRun)) {
				markJobCancelled(jobId, "cancelled before job reconciliation");
				acknowledgeJobCancellation(runId);
				return;
			}
			QueryRun leased = runService.acquireLease(runId);
			activeJobRuns.add(runId);
			if ("SUCCEEDED".equals(initialStatus)) {
				reconcileSucceededJob(jobId, runId, initial, leased);
				return;
			}
			if ("FAILED".equals(initialStatus)) {
				reconcileFailedJob(jobId, runId, initial, leased);
				return;
			}
			if (leased.status() == RunStatus.QUEUED) {
				runService.transition(runId, RunStatus.RUNNING, "evaluation-job", null, null);
			}
			int started = jdbc.update("""
					UPDATE qw_evaluation_job SET status = 'RUNNING', progress = 5, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status IN ('PENDING','RUNNING')
					""", jobId);
			if (started == 0) {
				if ("CANCELLED".equals(Objects.toString(job(jobId).get("status")))) {
					acknowledgeJobCancellation(runId);
					return;
				}
				throw new IllegalStateException("Job is not executable in its current state: " + jobId);
			}
			runService.appendEvent(runId, "JOB_STARTED", "evaluation-job", null, "Job execution started",
					"job-start:" + jobId);
			runService.saveCheckpoint(runId, "job:" + jobId, "evaluation-job",
					json(Map.of("jobId", jobId, "status", "RUNNING")), "job-start:" + jobId);
			Map<String, Object> current = job(jobId);
			String type = Objects.toString(current.get("job_type"));
			Long projectId = number(current.get("project_id"));
			Long versionId = number(current.get("project_version_id"));
			Map<String, Object> result = switch (type) {
				case "REPLAY", "RELEASE_VALIDATION" -> replay(projectId, versionId);
				case "SCHEMA_DRIFT" -> schemaDrift(projectId, versionId);
				default -> Map.of("accepted", true, "message", type + " job completed by durable execution");
			};
			if (deadlineExceededJobIds.contains(jobId)) {
				throw deadlineFailure(jobId);
			}
			if ("CANCELLED".equals(Objects.toString(job(jobId).get("status")))) {
				acknowledgeJobCancellation(runId);
				return;
			}
			int completed = jdbc.update("""
					UPDATE qw_evaluation_job SET status = 'SUCCEEDED', progress = 100, result_json = ?,
					finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
					WHERE id = ? AND status = 'RUNNING'
					""", json(result), jobId);
			if (completed == 0) {
				if ("CANCELLED".equals(Objects.toString(job(jobId).get("status")))) {
					acknowledgeJobCancellation(runId);
					return;
				}
				throw new IllegalStateException("Job is no longer RUNNING and cannot be completed: " + jobId);
			}
			QueryRun currentRun = runService.get(runId);
			if (currentRun.status() == RunStatus.CANCEL_REQUESTED) {
				jdbc.update(
						"""
								UPDATE qw_evaluation_job SET status = 'CANCELLED', error_message = 'cancelled during completion',
								finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = ? AND status = 'SUCCEEDED'
								""",
						jobId);
				acknowledgeJobCancellation(runId);
				return;
			}
			runService.saveCheckpoint(runId, "job:" + jobId, "job-complete",
					json(Map.of("jobId", jobId, "status", "SUCCEEDED", "result", result)), "job-complete:" + jobId);
			runService.appendEvent(runId, "JOB_SUCCEEDED", "job-complete", json(result), "Job completed",
					"job-success:" + jobId);
			runService.transition(runId, RunStatus.SUCCEEDED, "job-complete", null, null);
		}
		catch (RunLeaseUnavailableException ex) {
			return;
		}
		catch (IllegalStateException ex) {
			failJob(jobId, runId, normalizedJobFailure(jobId, ex));
		}
		catch (RuntimeException ex) {
			failJob(jobId, runId, normalizedJobFailure(jobId, ex));
		}
		finally {
			if (runId != null && !runId.isBlank()) {
				activeJobRuns.remove(runId);
				try {
					runService.releaseLease(runId);
				}
				catch (RuntimeException ignored) {
					// Lease may have expired or been released by cancellation takeover.
				}
			}
		}
	}

	@Scheduled(fixedDelayString = "${semevosql.run.recovery-scan-ms:10000}")
	public void recoverDurableJobs() {
		for (QueryRun run : runService.recoverable()) {
			if (run.runType() == RunType.INTERACTIVE_QUERY || run.status() == RunStatus.WAITING_HUMAN
					|| run.threadId() == null || !run.threadId().startsWith("job:")) {
				continue;
			}
			scheduleNow(run.threadId().substring("job:".length()));
		}
	}

	@Scheduled(fixedDelayString = "${semevosql.run.lease-renew-ms:10000}")
	public void renewDurableJobLeases() {
		for (String runId : List.copyOf(activeJobRuns)) {
			try {
				QueryRun run = runService.get(runId);
				if (run.status() == RunStatus.RUNNING) {
					runService.renewLease(runId);
				}
			}
			catch (RuntimeException ignored) {
				activeJobRuns.remove(runId);
			}
		}
	}

	private QueryRun ensureJobRun(Map<String, Object> job) {
		String existingRunId = Objects.toString(job.get("run_id"), "");
		if (!existingRunId.isBlank()) {
			return runService.get(existingRunId);
		}
		String jobId = Objects.toString(job.get("id"));
		QueryRun created = createJobRun(jobId, number(job.get("project_id")), number(job.get("project_version_id")),
				Objects.toString(job.get("job_type")), Objects.toString(job.get("idempotency_key")),
				readJson(Objects.toString(job.get("request_json"), "{}")));
		jdbc.update("UPDATE qw_evaluation_job SET run_id = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
				created.runId(), jobId);
		return created;
	}

	private QueryRun createJobRun(String jobId, Long projectId, Long versionId, String jobType, String idempotencyKey,
			Map<String, Object> options) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobId", jobId);
		payload.put("jobType", jobType);
		payload.put("projectId", projectId);
		payload.put("projectVersionId", versionId);
		payload.put("options", options == null ? Map.of() : options);
		return runService.create(new CreateRunCommand(runType(jobType), projectId, versionId, "job:" + jobId, jobId,
				"job:" + projectId + ":" + idempotencyKey, json(payload)));
	}

	private RunType runType(String jobType) {
		return switch (jobType) {
			case "INITIALIZATION" -> RunType.INITIALIZATION;
			case "MATERIAL_PARSE" -> RunType.MATERIAL_IMPORT;
			case "REPLAY" -> RunType.REPLAY;
			default -> RunType.EVALUATION;
		};
	}

	private void scheduleAfterCommit(String jobId) {
		Runnable schedule = () -> scheduleNow(jobId);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			schedule.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				schedule.run();
			}
		});
	}

	private void scheduleNow(String jobId) {
		if (!scheduledJobIds.add(jobId)) {
			return;
		}
		try {
			Map<String, Object> current = job(jobId);
			String status = Objects.toString(current.get("status"));
			if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
				scheduledJobIds.remove(jobId);
				return;
			}
			boolean initialization = initializationJob(Objects.toString(current.get("job_type")));
			Executor executor = initialization ? initializationExecutor : evaluationExecutor;
			long timeoutMs = initialization ? initializationTaskTimeoutMs : evaluationTaskTimeoutMs;
			AtomicBoolean finished = new AtomicBoolean();
			AtomicReference<Thread> worker = new AtomicReference<>();
			executor.execute(() -> {
				worker.set(Thread.currentThread());
				ScheduledFuture<?> deadline = DEADLINE_SCHEDULER.schedule(() -> {
					if (!finished.compareAndSet(false, true)) {
						return;
					}
					deadlineExceededJobIds.add(jobId);
					Thread running = worker.get();
					if (running != null) {
						running.interrupt();
					}
				}, timeoutMs, TimeUnit.MILLISECONDS);
				try {
					executeJob(jobId);
				}
				finally {
					if (finished.compareAndSet(false, true)) {
						deadline.cancel(false);
					}
					scheduledJobIds.remove(jobId);
					deadlineExceededJobIds.remove(jobId);
					Thread.interrupted();
				}
			});
		}
		catch (RejectedExecutionException ignored) {
			scheduledJobIds.remove(jobId);
			// The bounded domain queue is full. The recovery scan will retry after
			// capacity is available.
		}
		catch (RuntimeException ex) {
			scheduledJobIds.remove(jobId);
			throw ex;
		}
	}

	private boolean initializationJob(String jobType) {
		return "INITIALIZATION".equals(jobType) || "MATERIAL_PARSE".equals(jobType);
	}

	private void reconcileSucceededJob(String jobId, String runId, Map<String, Object> job, QueryRun leased) {
		if (cancellationInProgress(leased)) {
			markJobCancelled(jobId, "cancelled during success reconciliation");
			acknowledgeJobCancellation(runId);
			return;
		}
		QueryRun current = leased;
		if (current.status() == RunStatus.QUEUED) {
			current = runService.transition(runId, RunStatus.RUNNING, "job-complete", null, null);
		}
		if (current.status() != RunStatus.RUNNING) {
			throw new IllegalStateException("Succeeded job cannot reconcile run in status: " + current.status());
		}
		String resultJson = Objects.toString(job.get("result_json"), "{}");
		runService.saveCheckpoint(runId, "job:" + jobId, "job-complete",
				json(Map.of("jobId", jobId, "status", "SUCCEEDED", "resultJson", resultJson)), "job-complete:" + jobId);
		runService.appendEvent(runId, "JOB_SUCCEEDED", "job-complete", resultJson,
				"Job completion reconciled after restart", "job-success:" + jobId);
		runService.transition(runId, RunStatus.SUCCEEDED, "job-complete", null, null);
	}

	private void reconcileFailedJob(String jobId, String runId, Map<String, Object> job, QueryRun leased) {
		if (cancellationInProgress(leased)) {
			markJobCancelled(jobId, "cancelled during failure reconciliation");
			acknowledgeJobCancellation(runId);
			return;
		}
		if (leased.status() != RunStatus.QUEUED && leased.status() != RunStatus.RUNNING) {
			throw new IllegalStateException("Failed job cannot reconcile run in status: " + leased.status());
		}
		String errorMessage = Objects.toString(job.get("error_message"), "Job failed before run status was persisted");
		runService.appendEvent(runId, "JOB_FAILED", "evaluation-job", null, truncate(errorMessage, 500),
				"job-failed:" + jobId);
		runService.transition(runId, RunStatus.FAILED, "evaluation-job", "JOB_FAILED", errorMessage);
	}

	private void acknowledgeJobCancellation(String runId) {
		QueryRun run = runService.get(runId);
		if (run.status() != RunStatus.CANCEL_REQUESTED && !run.terminal()) {
			runService.cancel(runId, "job-worker-cancel:" + runId);
			run = runService.get(runId);
		}
		if (run.status() == RunStatus.CANCEL_REQUESTED) {
			runService.appendEvent(runId, "JOB_CANCELLED", "evaluation-job", null, "Job cancelled",
					"job-cancelled:" + runId);
			runService.acknowledgeCancelled(runId);
		}
	}

	private RuntimeException normalizedJobFailure(String jobId, RuntimeException failure) {
		if (!Thread.currentThread().isInterrupted() && !deadlineExceededJobIds.contains(jobId)) {
			return failure;
		}
		return deadlineFailure(jobId);
	}

	private TaskDeadlineExceededException deadlineFailure(String jobId) {
		Map<String, Object> current = job(jobId);
		boolean initialization = initializationJob(Objects.toString(current.get("job_type")));
		long timeoutMs = initialization ? initializationTaskTimeoutMs : evaluationTaskTimeoutMs;
		return new TaskDeadlineExceededException(initialization ? "initialization" : "evaluation", timeoutMs);
	}

	private void failJob(String jobId, String runId, RuntimeException ex) {
		if (runId != null && !runId.isBlank()) {
			QueryRun beforeFailure = runService.get(runId);
			if (cancellationInProgress(beforeFailure)) {
				markJobCancelled(jobId, "cancelled during failure handling");
				acknowledgeJobCancellation(runId);
				return;
			}
		}
		jdbc.update("""
				UPDATE qw_evaluation_job SET status = 'FAILED', error_message = ?, finished_time = CURRENT_TIMESTAMP,
				update_time = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('PENDING','RUNNING')
				""", truncate(ex.getMessage(), 4000), jobId);
		Map<String, Object> persistedJob = job(jobId);
		String persistedStatus = Objects.toString(persistedJob.get("status"));
		if ("SUCCEEDED".equals(persistedStatus)) {
			log.warn("Job {} already succeeded; preserving its run for terminal reconciliation", jobId, ex);
			return;
		}
		if (runId == null || runId.isBlank()) {
			return;
		}
		QueryRun run = runService.get(runId);
		if (cancellationInProgress(run)) {
			markJobCancelled(jobId, "cancelled during failure handling");
			acknowledgeJobCancellation(runId);
			return;
		}
		if (!run.terminal()) {
			try {
				runService.appendEvent(runId, "JOB_FAILED", "evaluation-job", null, truncate(ex.getMessage(), 500),
						"job-failed:" + jobId);
				runService.transition(runId, RunStatus.FAILED, "evaluation-job", ex.getClass().getSimpleName(),
						ex.getMessage());
			}
			catch (IllegalStateException race) {
				QueryRun latest = runService.get(runId);
				if (!cancellationInProgress(latest)) {
					throw race;
				}
				markJobCancelled(jobId, "cancelled during failure handling");
				acknowledgeJobCancellation(runId);
			}
		}
	}

	private boolean cancellationInProgress(QueryRun run) {
		return run.status() == RunStatus.CANCEL_REQUESTED || run.status() == RunStatus.CANCELLED;
	}

	private void markJobCancelled(String jobId, String message) {
		jdbc.update("""
				UPDATE qw_evaluation_job SET status = 'CANCELLED', error_message = ?,
				finished_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status <> 'CANCELLED'
				""", message, jobId);
	}

	private Map<String, Object> replay(Long projectId, Long versionId) {
		Map<String, Object> result = new LinkedHashMap<>(goldenReplayRunner.replay(projectId, versionId));
		result.put("safetyPassed", readiness.assess(projectId, versionId).ready());
		return java.util.Collections.unmodifiableMap(result);
	}

	private Map<String, Object> schemaDrift(Long projectId, Long versionId) {
		SemanticCatalogSnapshot catalog = catalogCache.get(projectId, versionId);
		List<String> invalid = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> model.getDatasourceId() == null || model.getPhysicalTable() == null
					|| model.getPhysicalTable().isBlank())
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.toList();
		return Map.of("catalogModels", catalog.getModels().size(), "invalidModels", invalid, "driftDetected",
				!invalid.isEmpty());
	}

	public Map<String, Object> createRelease(Long projectId, ReleaseRequest request, OperatorContext operator) {
		return controlledReleaseService.create(projectId, request, operator);
	}

	public List<Map<String, Object>> listReleases(Long projectId) {
		return controlledReleaseService.list(projectId);
	}

	public Map<String, Object> recordShadow(String releaseId, ShadowResult request, OperatorContext operator) {
		return controlledReleaseService.recordShadow(releaseId, request, operator);
	}

	public Long assignVersion(String releaseId, String requestId) {
		return controlledReleaseService.assignVersion(releaseId, requestId);
	}

	public Map<String, Object> advanceCanary(String releaseId, CanaryRequest request, OperatorContext operator) {
		return controlledReleaseService.advanceCanary(releaseId, request, operator);
	}

	public Map<String, Object> rollback(String releaseId, String reason, OperatorContext operator) {
		return controlledReleaseService.rollback(releaseId, reason, operator);
	}

	public Map<String, Object> dashboard(Long projectId) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("episodes", count("SELECT COUNT(*) FROM qw_episode WHERE project_id = ?", projectId));
		result.put("successfulEpisodes",
				count("SELECT COUNT(*) FROM qw_episode WHERE project_id = ? AND status = 'SUCCEEDED'", projectId));
		result.put("guardRejected", count(
				"SELECT COUNT(*) FROM qw_episode WHERE project_id = ? AND error_type = 'GUARD_REJECTED'", projectId));
		result.put("taskMetrics", taskMetrics(projectId));
		result.put("queryExampleCandidates", count(
				"SELECT COUNT(*) FROM qw_query_example WHERE project_id = ? AND status = 'CANDIDATE'", projectId));
		result.put("approvedQueryExamples",
				count("SELECT COUNT(*) FROM qw_query_example WHERE project_id = ? AND status = 'APPROVED'", projectId));
		result.put("goldenCases",
				count("SELECT COUNT(*) FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE", projectId));
		result.put("runningJobs",
				count("SELECT COUNT(*) FROM qw_evaluation_job WHERE project_id = ? AND status IN ('PENDING','RUNNING')",
						projectId));
		result.put("releases", count("SELECT COUNT(*) FROM qw_release WHERE project_id = ?", projectId));
		result.put("catalogCache", catalogCache.stats());
		return result;
	}

	private Map<String, Object> taskMetrics(Long projectId) {
		Map<String, Object> episode = jdbc.queryForMap(
				"""
						SELECT COUNT(*) AS task_count,
						       COUNT(*) FILTER (WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')) AS terminal_task_count,
						       COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded_task_count,
						       COUNT(*) FILTER (WHERE error_type = 'GUARD_REJECTED') AS guard_rejected_count,
						       COUNT(duration_ms) FILTER (
						           WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
						       ) AS duration_sample_count,
						       AVG(duration_ms) FILTER (
						           WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') AND duration_ms IS NOT NULL
						       ) AS mean_duration_ms,
						       PERCENTILE_DISC(0.50) WITHIN GROUP (ORDER BY duration_ms)
						                FILTER (WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
						                        AND duration_ms IS NOT NULL) AS p50_duration_ms,
						       PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms)
						                FILTER (WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
						                        AND duration_ms IS NOT NULL) AS p95_duration_ms,
						       PERCENTILE_DISC(0.99) WITHIN GROUP (ORDER BY duration_ms)
						                FILTER (WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
						                        AND duration_ms IS NOT NULL) AS p99_duration_ms
						FROM qw_episode WHERE project_id = ?
						""",
				projectId);
		Map<String, Object> attempts = jdbc.queryForMap(
				"""
						SELECT COALESCE(SUM(attempt_count), 0) AS attempt_count,
						       COUNT(*) FILTER (WHERE attempt_count > 1) AS retried_task_count,
						       COUNT(*) FILTER (WHERE attempt_count > 1 AND status = 'SUCCEEDED') AS recovered_retry_task_count,
						       COUNT(*) FILTER (
						           WHERE status = 'SUCCEEDED' AND attempt_count = 1 AND first_attempt_succeeded
						       ) AS first_pass_success_count
						FROM (
						    SELECT e.id, e.status, COUNT(a.id) AS attempt_count,
						           COALESCE(BOOL_OR(a.attempt_no = 1 AND a.status = 'SUCCEEDED'), FALSE) AS first_attempt_succeeded
						    FROM qw_episode e
						    LEFT JOIN qw_attempt a ON a.episode_id = e.id
						    WHERE e.project_id = ?
						    GROUP BY e.id, e.status
						) task_attempts
						""",
				projectId);
		Map<String, Object> sql = jdbc.queryForMap("""
				SELECT COUNT(*) AS sql_execution_count,
				       COUNT(*) FILTER (WHERE s.status = 'SUCCEEDED') AS sql_succeeded_count,
				       COUNT(s.duration_ms) AS duration_sample_count,
				       AVG(s.duration_ms) AS mean_duration_ms,
				       PERCENTILE_DISC(0.50) WITHIN GROUP (ORDER BY s.duration_ms)
				           FILTER (WHERE s.duration_ms IS NOT NULL) AS p50_duration_ms,
				       PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY s.duration_ms)
				           FILTER (WHERE s.duration_ms IS NOT NULL) AS p95_duration_ms,
				       PERCENTILE_DISC(0.99) WITHIN GROUP (ORDER BY s.duration_ms)
				           FILTER (WHERE s.duration_ms IS NOT NULL) AS p99_duration_ms
				FROM qw_sql_trace s
				JOIN qw_attempt a ON a.id = s.attempt_id
				JOIN qw_episode e ON e.id = a.episode_id
				WHERE e.project_id = ?
				""", projectId);
		Map<String, Object> queueWait = jdbc.queryForMap("""
				SELECT COUNT(*) AS duration_sample_count,
				       AVG(queue_wait_ms) AS mean_duration_ms,
				       PERCENTILE_DISC(0.50) WITHIN GROUP (ORDER BY queue_wait_ms) AS p50_duration_ms,
				       PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY queue_wait_ms) AS p95_duration_ms,
				       PERCENTILE_DISC(0.99) WITHIN GROUP (ORDER BY queue_wait_ms) AS p99_duration_ms
				FROM (
				    SELECT EXTRACT(EPOCH FROM (MIN(event.create_time) - r.create_time)) * 1000 AS queue_wait_ms
				    FROM qw_query_run r
				    JOIN qw_run_event event ON event.run_id = r.run_id AND event.event_type = 'RUN_STARTED'
				    WHERE r.project_id = ?
				    GROUP BY r.run_id, r.create_time
				) queue_wait
				""", projectId);
		Map<String, Object> clarification = jdbc.queryForMap("""
				SELECT COUNT(*) FILTER (WHERE clarification_required) AS clarification_task_count,
				       COUNT(*) FILTER (
				           WHERE clarification_required AND clarification_answered
				       ) AS clarification_answered_task_count
				FROM (
				    SELECT r.episode_id,
				           BOOL_OR(event.event_type = 'CLARIFICATION_REQUIRED') AS clarification_required,
				           BOOL_OR(event.event_type = 'CLARIFICATION_ANSWERED') AS clarification_answered
				    FROM qw_query_run r
				    JOIN qw_episode e ON e.id = r.episode_id
				    JOIN qw_run_event event ON event.run_id = r.run_id
				    WHERE e.project_id = ?
				    GROUP BY r.episode_id
				) clarification_tasks
				""", projectId);
		Map<String, Object> feedback = jdbc.queryForMap("""
				SELECT COUNT(DISTINCT f.episode_id) AS feedback_task_count,
				       COUNT(DISTINCT f.episode_id) FILTER (WHERE f.adopted = TRUE) AS adopted_task_count
				FROM qw_feedback f
				JOIN qw_episode e ON e.id = f.episode_id
				WHERE e.project_id = ?
				""", projectId);
		long queryCaseRecalledTaskCount = count("""
				SELECT COUNT(DISTINCT e.id)
				FROM qw_episode e
				JOIN qw_query_run r ON r.episode_id = e.id
				JOIN qw_query_case_usage usage ON usage.run_id = r.run_id AND usage.recalled = TRUE
				WHERE e.project_id = ?
				""", projectId);

		long taskCount = longValue(episode.get("task_count"));
		long terminalTaskCount = longValue(episode.get("terminal_task_count"));
		long succeededTaskCount = longValue(episode.get("succeeded_task_count"));
		long guardRejectedCount = longValue(episode.get("guard_rejected_count"));
		long attemptCount = longValue(attempts.get("attempt_count"));
		long retriedTaskCount = longValue(attempts.get("retried_task_count"));
		long recoveredRetryTaskCount = longValue(attempts.get("recovered_retry_task_count"));
		long firstPassSuccessCount = longValue(attempts.get("first_pass_success_count"));
		long sqlExecutionCount = longValue(sql.get("sql_execution_count"));
		long sqlSucceededCount = longValue(sql.get("sql_succeeded_count"));
		long clarificationTaskCount = longValue(clarification.get("clarification_task_count"));
		long clarificationAnsweredTaskCount = longValue(clarification.get("clarification_answered_task_count"));
		long feedbackTaskCount = longValue(feedback.get("feedback_task_count"));
		long adoptedTaskCount = longValue(feedback.get("adopted_task_count"));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("taskCount", taskCount);
		result.put("terminalTaskCount", terminalTaskCount);
		result.put("succeededTaskCount", succeededTaskCount);
		result.put("taskSuccessRate", ratio(succeededTaskCount, terminalTaskCount));
		result.put("firstPassSuccessCount", firstPassSuccessCount);
		result.put("firstPassSuccessRate", ratio(firstPassSuccessCount, terminalTaskCount));
		result.put("retriedTaskCount", retriedTaskCount);
		result.put("retryRecoveryRate", ratio(recoveredRetryTaskCount, retriedTaskCount));
		result.put("averageAttempts", ratio(attemptCount, taskCount));
		result.put("clarificationTaskCount", clarificationTaskCount);
		result.put("clarificationRate", ratio(clarificationTaskCount, taskCount));
		result.put("clarificationResumeRate", ratio(clarificationAnsweredTaskCount, clarificationTaskCount));
		result.put("sqlExecutionCount", sqlExecutionCount);
		result.put("sqlExecutionSuccessRate", ratio(sqlSucceededCount, sqlExecutionCount));
		result.put("queryCaseRecalledTaskCount", queryCaseRecalledTaskCount);
		result.put("queryCaseRecallRate", ratio(queryCaseRecalledTaskCount, taskCount));
		result.put("guardRejectionRate", ratio(guardRejectedCount, terminalTaskCount));
		result.put("feedbackTaskCount", feedbackTaskCount);
		result.put("adoptedFeedbackRate", ratio(adoptedTaskCount, feedbackTaskCount));
		result.put("durationMs", durationSummary(episode));
		result.put("queueWaitMs", durationSummary(queueWait));
		result.put("sqlExecutionDurationMs", durationSummary(sql));
		return result;
	}

	private Map<String, Object> durationSummary(Map<String, Object> values) {
		Map<String, Object> duration = new LinkedHashMap<>();
		duration.put("sampleCount", longValue(values.get("duration_sample_count")));
		duration.put("mean", nullableDoubleValue(values.get("mean_duration_ms")));
		duration.put("p50", nullableDoubleValue(values.get("p50_duration_ms")));
		duration.put("p95", nullableDoubleValue(values.get("p95_duration_ms")));
		duration.put("p99", nullableDoubleValue(values.get("p99_duration_ms")));
		return duration;
	}

	public SemanticCatalogCache.CacheStats cacheStats() {
		return catalogCache.stats();
	}

	public Map<String, Object> replaySummary(Long projectId, Long versionId) {
		return replay(projectId, versionId);
	}

	private void scheduleTrajectoryAnalysisAfterCommit(String episodeId) {
		Runnable analyze = () -> analyzeTrajectoryBestEffort(episodeId);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			analyze.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				analyze.run();
			}
		});
	}

	private void analyzeTrajectoryBestEffort(String episodeId) {
		try {
			trajectoryAnalysisService.analyzeEpisode(episodeId);
		}
		catch (RuntimeException ex) {
			log.warn("Unable to analyze completed SemEvoSQL trajectory for episode {}: {}", episodeId,
					ex.getMessage());
		}
	}

	private Map<String, Object> insertTrace(String table, String attemptId, TraceRequest request) {
		List<Map<String, Object>> existing = jdbc.queryForList(
				"SELECT * FROM " + table + " WHERE attempt_id = ? AND idempotency_key = ?", attemptId,
				request.idempotencyKey());
		if (!existing.isEmpty()) {
			return existing.get(0);
		}
		assertAttemptAcceptsRuntimeEffects(attemptId);
		String id = id();
		int inserted = jdbc.update("INSERT INTO " + table
				+ "(id, attempt_id, idempotency_key, node_name, status, input_summary, output_summary, decision_summary, effect_summary, contribution_score, cost_json, result_proof_json, reused, correction_type, duration_ms, error_type, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT (attempt_id, idempotency_key) DO NOTHING",
				id, attemptId, request.idempotencyKey(), request.nodeName(), request.status(),
				truncate(request.inputSummary(), 4000), truncate(request.outputSummary(), 4000),
				truncate(request.decisionSummary(), 8000), truncate(request.effectSummary(), 8000),
				request.contributionScore(), json(request.cost()), json(request.resultProof()),
				Boolean.TRUE.equals(request.reused()), request.correctionType(), request.durationMs(),
				request.errorType());
		if (inserted == 1) {
			return one("SELECT * FROM " + table + " WHERE id = ?", id);
		}
		return one("SELECT * FROM " + table + " WHERE attempt_id = ? AND idempotency_key = ?", attemptId,
				request.idempotencyKey());
	}

	private void assertAttemptAcceptsRuntimeEffects(String attemptId) {
		Integer writable = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_attempt a
				LEFT JOIN qw_query_run r ON r.run_id = a.run_id
				WHERE a.id = ? AND a.status = 'RUNNING'
				  AND (a.run_id IS NULL OR (r.status = 'RUNNING' AND r.attempt_id = a.id))
				""", Integer.class, attemptId);
		if (writable == null || writable != 1) {
			throw new LateRunResultDroppedException(
					"Attempt no longer accepts runtime effects: " + attemptId);
		}
	}

	private Map<String, Object> episode(String id) {
		return one("SELECT * FROM qw_episode WHERE id = ?", id);
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> values = jdbc.queryForList(sql, args);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("SemEvoSQL production record not found");
		}
		return values.get(0);
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private long longValue(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	private Double nullableDoubleValue(Object value) {
		return value == null ? null : ((Number) value).doubleValue();
	}

	private Double ratio(long numerator, long denominator) {
		return denominator <= 0 ? null : (double) numerator / denominator;
	}

	private String id() {
		return UUID.randomUUID().toString();
	}

	private String json(Object value) {
		try {
			return value == null ? null : JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid JSON payload", ex);
		}
	}

	private Map<String, Object> readJson(String value) {
		try {
			return JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid golden case expectation", ex);
		}
	}

	private String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	public record EpisodeRequest(String requestId, String agentId, Long projectId, Long projectVersionId,
			Integer datasourceId, String catalogHash, String conversationId, String parentEpisodeId,
			EpisodeRelationType relationType, String originalQuestion, String normalizedQuestion, String modelName,
			String promptVersion) {
	}

	public record ExecutionBinding(String episodeId, String attemptId, QueryRun run) {
	}

	public record CompletionRequest(String status, String errorType, Long tokenCount, Long durationMs) {
	}

	public record TraceRequest(String idempotencyKey, String nodeName, String status, String inputSummary,
			String outputSummary, String decisionSummary, String effectSummary, Double contributionScore,
			Map<String, Object> cost, Map<String, Object> resultProof, Boolean reused, String correctionType,
			Long durationMs, String errorType) {

		public TraceRequest(String idempotencyKey, String nodeName, String status, String inputSummary,
				String outputSummary, Long durationMs, String errorType) {
			this(idempotencyKey, nodeName, status, inputSummary, outputSummary, null, null, null, Map.of(), Map.of(),
					false, null, durationMs, errorType);
		}
	}

	public record SqlTraceRequest(String idempotencyKey, String sqlText, Map<String, Object> guardSummary,
			Map<String, Object> costSummary, Map<String, Object> explainSummary, Map<String, Object> previewSummary,
			Map<String, Object> resultSummary, String status, Integer retryCount, Long durationMs, String errorType) {
	}

	public record FeedbackRequest(String userId, Integer rating, Boolean adopted, String comment) {
	}

	public record GoldenCaseRequest(String caseCode, String question, Map<String, Object> expected, String replayMode,
			String datasetVersion, Boolean enabled) {

		public GoldenCaseRequest(String caseCode, String question, Map<String, Object> expected, Boolean enabled) {
			this(caseCode, question, expected, GoldenReplayMode.LIVE.name(), null, enabled);
		}
	}

	public record JobRequest(Long projectVersionId, String jobType, String idempotencyKey,
			Map<String, Object> options) {
	}

	public record ReleaseRequest(Long baselineVersionId, Long candidateVersionId, String releaseType,
			String policyVersion) {
	}

	public record ShadowResult(boolean passed, Map<String, Object> metrics) {
	}

	public record CanaryRequest(int trafficPercent, double failureRate, long p95LatencyMs, int safetyViolations) {
	}

}
