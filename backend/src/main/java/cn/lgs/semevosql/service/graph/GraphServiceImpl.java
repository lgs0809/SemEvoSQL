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
package cn.lgs.semevosql.service.graph;

import cn.lgs.semevosql.service.langfuse.LangfuseService;
import cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.concurrency.CapacityRejectedException;
import cn.lgs.semevosql.concurrency.SemEvoSQLConcurrencyProperties;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectRuntimeProfileService;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.ExecutionSnapshotService.ExecutionSnapshotMismatchException;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunErrorPresenter;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.run.RunInProgressException;
import cn.lgs.semevosql.run.RunLeaseUnavailableException;
import cn.lgs.semevosql.run.ThreadExecutionGuardService;
import cn.lgs.semevosql.run.ThreadExecutionGuardService.ThreadExecutionConflictException;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.workflow.node.PlannerNode;
import cn.lgs.semevosql.dto.GraphRequest;
import cn.lgs.semevosql.service.graph.Context.MultiTurnContextManager;
import cn.lgs.semevosql.service.graph.Context.StreamContext;
import cn.lgs.semevosql.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;

import static cn.lgs.semevosql.constant.Constant.*;

@Slf4j
@Service
public class GraphServiceImpl implements GraphService {

	private static final String REPLAN_AFTER_REJECTION = "REPLAN_AFTER_REJECTION";

	private static final String EXECUTE_APPROVED_PLAN_AFTER_CHECKPOINT_LOSS = "EXECUTE_APPROVED_PLAN_AFTER_CHECKPOINT_LOSS";

	private static final int MAX_MODEL_PROVIDER_RUN_RECOVERIES = 2;

	private final CompiledGraph compiledGraph;

	/**
	 * Native graph checkpoints are only used to resume the currently active run. A conversation thread can start a
	 * subsequent durable run after the previous one has completed, so its old native state must not be merged into the
	 * new run's initial state.
	 */
	private final MemorySaver graphCheckpointer = new MemorySaver();

	private final Executor executor;

	private final ConcurrentHashMap<String, StreamContext> streamContextMap = new ConcurrentHashMap<>();

	private final MultiTurnContextManager multiTurnContextManager;

	private final LangfuseService langfuseReporter;

	private final ProjectRuntimeGate projectRuntimeGate;

	private final ProjectRuntimeProfileService runtimeProfileService;

	private final SemEvoSQLProductionService productionService;

	private final QueryRunService runService;

	private final RunExecutionFenceService executionFence;

	private final QueryRunErrorPresenter runErrorPresenter;

	private final RuntimeClarificationService clarificationService;

	private final ThreadExecutionGuardService threadExecutionGuardService;

	private final ExecutionSnapshotService executionSnapshotService;

	private final GraphDurableRecoveryPlanner durableRecoveryPlanner;

	private final long interactiveRetryAfterSeconds;

	public GraphServiceImpl(StateGraph stateGraph, @Qualifier("semEvoSQLInteractiveExecutor") Executor executor,
			MultiTurnContextManager multiTurnContextManager, LangfuseService langfuseReporter,
			ProjectRuntimeGate projectRuntimeGate, ProjectRuntimeProfileService runtimeProfileService,
			SemEvoSQLProductionService productionService, QueryRunService runService,
			RunExecutionFenceService executionFence,
			QueryRunErrorPresenter runErrorPresenter, RuntimeClarificationService clarificationService,
			ThreadExecutionGuardService threadExecutionGuardService,
			ExecutionSnapshotService executionSnapshotService, GraphDurableRecoveryPlanner durableRecoveryPlanner,
			SemEvoSQLConcurrencyProperties concurrencyProperties)
			throws GraphStateException {
		this.compiledGraph = stateGraph.compile(CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(graphCheckpointer).build())
			.interruptBefore(HUMAN_FEEDBACK_NODE)
			.build());
		this.executor = executor;
		this.multiTurnContextManager = multiTurnContextManager;
		this.langfuseReporter = langfuseReporter;
		this.projectRuntimeGate = projectRuntimeGate;
		this.runtimeProfileService = runtimeProfileService;
		this.productionService = productionService;
		this.runService = runService;
		this.executionFence = executionFence;
		this.runErrorPresenter = runErrorPresenter;
		this.clarificationService = clarificationService;
		this.threadExecutionGuardService = threadExecutionGuardService;
		this.executionSnapshotService = executionSnapshotService;
		this.durableRecoveryPlanner = durableRecoveryPlanner;
		this.interactiveRetryAfterSeconds = retryAfterSeconds(
				concurrencyProperties.getInteractiveQuery().getQueueTimeoutMs());
	}

	@Override
	public String generateSqlForProjectSource(String naturalQuery, Long projectId, Integer datasourceId,
			List<String> physicalTables, String requestId, String idempotencyKey) throws GraphRunnerException {
		if (!StringUtils.hasText(naturalQuery) || projectId == null) {
			throw new IllegalArgumentException("naturalQuery and projectId are required");
		}
		ProjectRuntimeContext projectContext = projectRuntimeGate.requireReadyByProject(projectId);
		String agentId = projectRuntimeSubject(projectId);
		String resolvedRequestId = StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
		String resolvedIdempotencyKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey
				: "source-sql:" + resolvedRequestId;
		String threadId = "source-sql:" + resolvedRequestId;
		List<String> resolvedPhysicalTables = physicalTables == null ? List.of() : List.copyOf(physicalTables);
		GraphRequest durableRequest = GraphRequest.builder()
			.projectId(projectId)
			.agentId(agentId)
			.threadId(threadId)
			.requestId(resolvedRequestId)
			.idempotencyKey(resolvedIdempotencyKey)
			.query(naturalQuery)
			.forcedDatasourceId(datasourceId)
			.forcedPhysicalTables(resolvedPhysicalTables)
			.build();
		QueryRun run = runService.findByIdempotencyKey(resolvedIdempotencyKey).orElse(null);
		if (run != null) {
			projectContext = projectRuntimeGate.requireReadyVersion(projectId, run.projectVersionId());
			if (!compatibleSourceSqlRun(run, projectContext, resolvedRequestId, threadId, naturalQuery, datasourceId,
					resolvedPhysicalTables)) {
				throw new IllegalArgumentException("idempotencyKey is already bound to a different source SQL request");
			}
		}
		else {
			String executionSnapshot = executionSnapshotService.capture(projectContext,
					runtimeProfileService.require(projectId), null, false);
			run = runService.create(new CreateRunCommand(RunType.INTERACTIVE_QUERY, projectContext.projectId(),
					projectContext.projectVersionId(), threadId, resolvedRequestId, resolvedIdempotencyKey,
					toJson(durableRequest), executionSnapshot));
		}
		if (run.status() == RunStatus.SUCCEEDED) {
			return runService.latestEvent(run.runId(), "SOURCE_SQL_GENERATED").payload();
		}
		if (run.status() == RunStatus.WAITING_HUMAN) {
			var clarification = clarificationService.getPending(run.runId());
			throw new RuntimeClarificationRequiredException(run.runId(), clarification.clarificationId());
		}
		if (run.terminal()) {
			throw new IllegalStateException(
					"Source SQL generation run is terminal with status " + run.status() + "; runId=" + run.runId());
		}
		assertExecutionSnapshotCompatible(run);
		claimThreadOrReject(run);
		try {
			runService.acquireLease(run.runId());
		}
		catch (RunLeaseUnavailableException ex) {
			throw new RunInProgressException(run.runId(), "Source SQL generation is executing on another instance");
		}
		String episodeId = run.episodeId();
		String attemptId = run.attemptId();
		long startNanos = System.nanoTime();
		try {
			if (!StringUtils.hasText(episodeId)) {
				var binding = productionService.createFirstAttemptAndBind(run.runId(), run.threadId(),
						episodeRequest(run.requestId(), agentId, run.threadId(), naturalQuery, projectContext));
				episodeId = binding.episodeId();
				attemptId = binding.attemptId();
				run = binding.run();
			}
			long deadlineEpochMillis = requireRunDeadline(run);
			var clarification = clarificationService
				.detect(run.runId(), projectContext.projectId(), projectContext.projectVersionId(), naturalQuery,
						physicalTables)
				.orElse(null);
			if (clarification != null) {
				throw new RuntimeClarificationRequiredException(run.runId(), clarification.clarificationId());
			}
			String effectiveQuery = clarificationService.applyResolvedAnswer(run.runId(), naturalQuery);
			runService.saveCheckpoint(run.runId(), run.threadId(), "source-sql-start", toJson(durableRequest), "");
			runService.appendEvent(run.runId(), "RUN_STARTED", "source-sql-start", null, "Source SQL generation started",
					"run-start:" + run.runId() + ":" + attemptId);
			Map<String, Object> initialState = new HashMap<>();
			initialState.put(SQL_GENERATION_ONLY, true);
			initialState.put(INPUT_KEY, effectiveQuery);
			initialState.put(AGENT_ID, agentId);
			initialState.put(PROJECT_ID, projectContext.projectId());
			initialState.put(PROJECT_VERSION_ID, projectContext.projectVersionId());
			initialState.put(CATALOG_HASH, projectContext.catalogHash());
			initialState.put(EPISODE_ID, episodeId);
			initialState.put(ATTEMPT_ID, attemptId);
			initialState.put(RUN_ID, run.runId());
			initialState.put(RUN_DEADLINE_EPOCH_MILLIS, deadlineEpochMillis);
			if (datasourceId != null) {
				initialState.put(FORCED_DATASOURCE_ID, datasourceId);
			}
			if (physicalTables != null && !physicalTables.isEmpty()) {
				initialState.put(FORCED_PHYSICAL_TABLES, List.copyOf(physicalTables));
			}
			OverAllState state = invokeWithinDeadline(initialState, run.threadId(), deadlineEpochMillis);
			String sql = state.value(SQL_GENERATE_OUTPUT, "");
			if (!StringUtils.hasText(sql)) {
				throw new IllegalStateException("Source SQL generation completed without generated SQL");
			}
			QueryRun beforeComplete = runService.get(run.runId());
			if (beforeComplete.status() == RunStatus.CANCEL_REQUESTED
					|| beforeComplete.status() == RunStatus.CANCELLED) {
				finishSynchronousCancellation(run.runId(), episodeId, attemptId, elapsedMillis(startNanos));
				throw new IllegalStateException("Source SQL generation run was cancelled; runId=" + run.runId());
			}
			runService.appendEvent(run.runId(), "SOURCE_SQL_GENERATED", null, sql, "Generated SQL",
					"source-sql-result:" + run.runId() + ":" + attemptId);
			try {
				runService.transition(run.runId(), attemptId, RunStatus.SUCCEEDED, "source-sql", null, null);
			}
			catch (RuntimeException transitionError) {
				QueryRun raced = runService.get(run.runId());
				if (raced.status() == RunStatus.CANCEL_REQUESTED || raced.status() == RunStatus.CANCELLED) {
					finishSynchronousCancellation(run.runId(), episodeId, attemptId, elapsedMillis(startNanos));
					throw new IllegalStateException("Source SQL generation run was cancelled; runId=" + run.runId(), transitionError);
				}
				throw transitionError;
			}
			completeEpisodeBestEffort(episodeId, "SUCCEEDED", null, attemptId, elapsedMillis(startNanos));
			return sql;
		}
		catch (RuntimeClarificationRequiredException | RunInProgressException ex) {
			throw ex;
		}
		catch (Exception ex) {
			QueryRun current = runService.get(run.runId());
			if (!current.terminal() && current.status() != RunStatus.WAITING_HUMAN
					&& current.status() != RunStatus.CANCEL_REQUESTED) {
				runService.transition(run.runId(), attemptId, RunStatus.FAILED, "source-sql", ex.getClass().getSimpleName(),
						ex.getMessage());
				runService.appendEvent(run.runId(), "RUN_FAILED", "source-sql", null, summarize(ex.getMessage()),
						"run-failed:" + run.runId() + ":" + Objects.toString(attemptId, "unbound"));
			}
			if (current.status() == RunStatus.CANCEL_REQUESTED || current.status() == RunStatus.CANCELLED) {
				finishSynchronousCancellation(run.runId(), episodeId, attemptId, elapsedMillis(startNanos));
			}
			else if (current.status() != RunStatus.WAITING_HUMAN) {
				completeEpisodeBestEffort(episodeId, "FAILED", ex.getClass().getSimpleName(), attemptId,
						elapsedMillis(startNanos));
			}
			if (ex instanceof GraphRunnerException graphRunnerException) {
				throw graphRunnerException;
			}
			if (ex instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Source SQL generation failed", ex);
		}
		finally {
			try {
				runService.releaseLease(run.runId());
			}
			catch (RuntimeException ex) {
				log.debug("Unable to release source SQL generation run lease {}: {}", run.runId(), ex.getMessage());
			}
			try {
				releaseThreadIfTerminal(run.runId());
			}
			catch (RuntimeException ex) {
				log.debug("Unable to release source SQL generation thread guard {}: {}", run.runId(), ex.getMessage());
			}
		}
	}

	private static boolean compatibleSourceSqlRun(QueryRun run, ProjectRuntimeContext projectContext, String requestId,
			String currentThreadId, String naturalQuery, Integer datasourceId, List<String> physicalTables) {
		if (run.runType() != RunType.INTERACTIVE_QUERY || !Objects.equals(run.projectId(), projectContext.projectId())
				|| !Objects.equals(run.projectVersionId(), projectContext.projectVersionId())
				|| !Objects.equals(run.requestId(), requestId) || !Objects.equals(run.threadId(), currentThreadId)) {
			return false;
		}
		try {
			GraphRequest persisted = JsonUtil.getObjectMapper().readValue(run.requestPayload(), GraphRequest.class);
			List<String> persistedTables = persisted.getForcedPhysicalTables() == null ? List.of()
					: List.copyOf(persisted.getForcedPhysicalTables());
			return Objects.equals(persisted.getProjectId(), projectContext.projectId())
					&& Objects.equals(persisted.getRequestId(), requestId)
					&& Objects.equals(persisted.getQuery(), naturalQuery)
					&& Objects.equals(persisted.getForcedDatasourceId(), datasourceId)
					&& Objects.equals(persistedTables, physicalTables)
					&& Objects.equals(persisted.getThreadId(), currentThreadId);
		}
		catch (Exception ex) {
			return false;
		}
	}

	private OverAllState invokeWithinDeadline(Map<String, Object> initialState, String threadId,
			long deadlineEpochMillis) throws GraphRunnerException {
		long remainingMillis = deadlineEpochMillis - System.currentTimeMillis();
		if (remainingMillis <= 0L) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted before Graph invocation");
		}
		CompletableFuture<java.util.Optional<OverAllState>> invocation = CompletableFuture.supplyAsync(() -> {
			try {
				return compiledGraph.invoke(initialState, RunnableConfig.builder().threadId(threadId).build());
			}
			catch (Exception ex) {
				throw new java.util.concurrent.CompletionException(ex);
			}
		}, executor);
		try {
			return invocation.get(remainingMillis, TimeUnit.MILLISECONDS)
				.orElseThrow(() -> new IllegalStateException("Graph invocation returned no state"));
		}
		catch (TimeoutException ex) {
			invocation.cancel(true);
			throw new RunDeadlineExceededException("Interactive Run exceeded its absolute execution deadline");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			invocation.cancel(true);
			throw new RunDeadlineExceededException("Interactive Graph invocation was interrupted");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
				cause = completion.getCause();
			}
			if (cause instanceof GraphRunnerException graphError) {
				throw graphError;
			}
			if (cause instanceof RuntimeException runtimeError) {
				throw runtimeError;
			}
			throw new IllegalStateException("Graph invocation failed", cause);
		}
	}

	@Override
	public String graphStreamProcess(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest graphRequest) {
		prepareProjectRuntimeIdentity(graphRequest);
		if (!StringUtils.hasText(graphRequest.getThreadId()) && !StringUtils.hasText(graphRequest.getRunId())) {
			graphRequest.setThreadId(UUID.randomUUID().toString());
		}
		boolean feedbackRequest = StringUtils.hasText(graphRequest.getHumanFeedbackContent());
		QueryRun run = resolveRun(graphRequest);
		if (!StringUtils.hasText(graphRequest.getThreadId())) {
			graphRequest.setThreadId(run.threadId());
		}
		assertRunRequestScope(run, graphRequest);
		if (graphRequest.isDurableRecoveryTakeover()) {
			SemanticBlueprint recoverablePlan = durableRecoveryPlanner.recoverableSemanticPlan(run);
			if (recoverablePlan != null) {
				graphRequest.setRecoveredSemanticPlan(recoverablePlan);
			}
			String recoverablePlannerOutput = durableRecoveryPlanner.recoverablePlannerOutput(run, recoverablePlan);
			if (StringUtils.hasText(recoverablePlannerOutput)) {
				graphRequest.setRecoveredPlannerOutput(recoverablePlannerOutput);
			}
		}
		if (!run.terminal()) {
			assertExecutionSnapshotCompatible(run);
		}
		String threadId = graphRequest.getThreadId();
		graphRequest.setRunId(run.runId());
		emitRunEstablished(sink, graphRequest);
		String feedbackKey = null;
		RunEvent acceptedFeedback = null;
		if (feedbackRequest) {
			feedbackKey = StringUtils.hasText(graphRequest.getIdempotencyKey()) ? graphRequest.getIdempotencyKey()
					: "human-feedback:" + run.runId() + ":" + Integer.toHexString(
							Objects.hash(graphRequest.isRejectedPlan(), graphRequest.getHumanFeedbackContent()));
			graphRequest.setIdempotencyKey(feedbackKey);
			acceptedFeedback = runService.eventByIdempotency(run.runId(), "human-feedback:" + feedbackKey).orElse(null);
			if (acceptedFeedback != null) {
				assertSameHumanFeedbackCommand(acceptedFeedback, graphRequest);
			}
		}
		StreamContext active = streamContextMap.get(threadId);
		if (acceptedFeedback != null) {
			if (active != null && !active.isCleaned() && run.runId().equals(active.getRunId())) {
				replayAndAttach(active, sink, graphRequest.getAfterSequence());
			}
			else {
				completeIdempotentFeedbackStream(sink, graphRequest, run);
			}
			return run.runId();
		}
		if (run.terminal() || (!feedbackRequest && run.status() == RunStatus.WAITING_HUMAN)) {
			completeImmediateStream(sink, graphRequest, run);
			return run.runId();
		}
		if (active != null && !active.isCleaned()) {
			if (run.runId().equals(active.getRunId())) {
				if (feedbackRequest) {
					throw new RunInProgressException(run.runId(),
							"The graph has not completed its transition to WAITING_HUMAN yet");
				}
				replayAndAttach(active, sink, graphRequest.getAfterSequence());
				return run.runId();
			}
			throw CapacityRejectedException.tooManyRequests("thread", interactiveRetryAfterSeconds,
					"Another query is already running in this conversation thread");
		}
		if (feedbackRequest) {
			run = runService.resume(run.runId(), feedbackKey);
		}
		claimThreadOrReject(run);
		QueryRun leaseCandidate = run;
		try {
			run = runService.acquireLease(run.runId());
			if (graphRequest.isDurableRecoveryTakeover() && leaseCandidate.status() == RunStatus.RUNNING) {
				runService.appendEvent(run.runId(), "RUN_RECOVERED", leaseCandidate.currentNode(),
						toJson(Map.of("previousOwner", Objects.toString(leaseCandidate.ownerInstance(), ""),
								"previousLeaseExpireTime", Objects.toString(leaseCandidate.leaseExpireTime(), ""), "recoveredNode",
								Objects.toString(leaseCandidate.currentNode(), ""), "recoverySemantics", "AT_LEAST_ONCE_REPLAY",
								"previousVisibleNodePasses", graphRequest.getDurableRecoveryReplayNodeSequence().size(),
								"semanticPlanReused", graphRequest.getRecoveredSemanticPlan() != null)),
						"Durable run recovered after lease expiry",
						"run-recovered:" + run.runId() + ":" + leaseCandidate.revision());
			}
		}
		catch (RunLeaseUnavailableException ex) {
			throw new RunInProgressException(run.runId(), "Durable run is executing on another instance");
		}
		StreamContext context = new StreamContext();
		context.setRunId(run.runId());
		context.setSink(sink);
		synchronized (streamContextMap) {
			active = streamContextMap.get(threadId);
			if (active != null && !active.isCleaned()) {
				if (run.runId().equals(active.getRunId())) {
					context.cleanup();
					if (feedbackRequest) {
						throw new RunInProgressException(run.runId(),
								"The graph has not completed its transition to WAITING_HUMAN yet");
					}
					replayAndAttach(active, sink, graphRequest.getAfterSequence());
					return run.runId();
				}
				releaseExecutionResources(context, false);
				throw CapacityRejectedException.tooManyRequests("thread", interactiveRetryAfterSeconds,
						"Another query is already running in this conversation thread");
			}
			streamContextMap.put(threadId, context);
		}
		replayAndAttach(context, sink, graphRequest.getAfterSequence());
		try {
			if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
				handleHumanFeedback(graphRequest);
			}
			else {
				handleNewProcess(graphRequest);
			}
			return run.runId();
		}
		catch (CapacityRejectedException ex) {
			streamContextMap.remove(threadId, context);
			releaseExecutionResources(context, false);
			throw ex;
		}
		catch (RuntimeException ex) {
			QueryRun current = runService.get(run.runId());
			if (current.status() == RunStatus.CANCEL_REQUESTED || current.status() == RunStatus.CANCELLED) {
				streamContextMap.remove(threadId, context);
				finishCancellationIfRequested(context);
				multiTurnContextManager.discardRun(run.runId(), threadId);
				return run.runId();
			}
			if (!current.terminal() && current.status() != RunStatus.WAITING_HUMAN) {
					runService.transition(run.runId(), run.attemptId(), RunStatus.FAILED, current.currentNode(),
							ex.getClass().getSimpleName(), ex.getMessage());
				multiTurnContextManager.resetPendingForRetry(threadId);
			}
			streamContextMap.remove(threadId, context);
			releaseExecutionResources(context, true);
			throw ex;
		}
	}

	private void replayAndAttach(StreamContext context, Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink,
			long afterSequence) {
		synchronized (context) {
			context.setSink(sink);
			replayDurableOutput(sink, context.getRunId(), afterSequence);
		}
	}

	private void replayDurableOutput(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String runId,
			long afterSequence) {
		long cursor = Math.max(0, afterSequence);
		while (true) {
			List<RunEvent> events = runService.events(runId, cursor, 1000);
			if (events.isEmpty()) {
				return;
			}
			for (RunEvent event : events) {
				cursor = Math.max(cursor, event.sequence());
				if (!"NODE_OUTPUT".equals(event.eventType()) || !StringUtils.hasText(event.payload())) {
					continue;
				}
				try {
					GraphNodeResponse response = JsonUtil.getObjectMapper()
						.readValue(event.payload(), GraphNodeResponse.class);
					Sinks.EmitResult result = sink
						.tryEmitNext(ServerSentEvent.builder(response).id(Long.toString(event.sequence())).build());
					if (result.isFailure()) {
						log.debug("Unable to replay durable event {} for run {}: {}", event.sequence(), runId, result);
						return;
					}
				}
				catch (Exception ex) {
					log.warn("Skipping unreadable durable NODE_OUTPUT event {} for run {}", event.sequence(), runId,
							ex);
				}
			}
			if (events.size() < 1000) {
				return;
			}
		}
	}

	private void completeImmediateStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest request,
			QueryRun run) {
		replayDurableOutput(sink, run.runId(), request.getAfterSequence());
		String agentId = request.getAgentId();
		String threadId = request.getThreadId();
		if (run.status() == RunStatus.WAITING_HUMAN) {
			GraphNodeResponse waiting = GraphNodeResponse.builder()
				.agentId(agentId)
				.threadId(threadId)
				.runId(run.runId())
				.nodeName(run.currentNode())
				.textType(TextType.TEXT)
				.text("任务正在等待人工输入，请继续回答当前澄清或审核问题。")
				.build();
			sink.tryEmitNext(ServerSentEvent.builder(waiting).event("waiting-human").build());
		}
		else if (run.status() == RunStatus.SUCCEEDED) {
			ServerSentEvent.Builder<GraphNodeResponse> completed = ServerSentEvent
				.builder(GraphNodeResponse.complete(agentId, threadId, run.runId()))
				.event(STREAM_EVENT_COMPLETE);
			if (run.lastEventSequence() > 0) {
				completed.id(Long.toString(run.lastEventSequence()));
			}
			sink.tryEmitNext(completed.build());
		}
		else {
			String message = runErrorPresenter.present(run).message();
			ServerSentEvent.Builder<GraphNodeResponse> failed = ServerSentEvent
				.builder(GraphNodeResponse.error(agentId, threadId, run.runId(), message))
				.event(STREAM_EVENT_ERROR);
			if (run.lastEventSequence() > 0) {
				failed.id(Long.toString(run.lastEventSequence()));
			}
			sink.tryEmitNext(failed.build());
		}
		sink.tryEmitComplete();
	}

	private void completeIdempotentFeedbackStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink,
			GraphRequest request, QueryRun run) {
		if (run.terminal() || run.status() == RunStatus.WAITING_HUMAN) {
			completeImmediateStream(sink, request, run);
			return;
		}
		replayDurableOutput(sink, run.runId(), request.getAfterSequence());
		GraphNodeResponse accepted = GraphNodeResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.runId(run.runId())
			.nodeName(run.currentNode())
			.textType(TextType.TEXT)
			.text("该人工反馈命令已被接收，当前 Run 状态为 " + run.status() + "，不会重复应用。")
			.build();
		sink.tryEmitNext(ServerSentEvent.builder(accepted).event("feedback-accepted").build());
		sink.tryEmitComplete();
	}

	static void assertSameHumanFeedbackCommand(RunEvent existing, GraphRequest request) {
		try {
			var payload = JsonUtil.getObjectMapper().readTree(existing.payload());
			if (payload.hasNonNull("answerPayload")) {
				payload = JsonUtil.getObjectMapper().readTree(payload.path("answerPayload").asText());
			}
			boolean approved = payload.path("approved").asBoolean();
			String feedback = payload.path("feedback").asText();
			if (approved != !request.isRejectedPlan() || !Objects.equals(feedback, request.getHumanFeedbackContent())) {
				throw new IllegalArgumentException(
						"idempotencyKey is already bound to a different human feedback command");
			}
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Persisted human feedback event is unreadable", ex);
		}
	}

	private void assertRunRequestScope(QueryRun run, GraphRequest request) {
		if (StringUtils.hasText(run.threadId()) && !Objects.equals(run.threadId(), request.getThreadId())) {
			throw new IllegalArgumentException("Run does not belong to threadId: " + request.getThreadId());
		}
		if (StringUtils.hasText(request.getRequestId()) && StringUtils.hasText(run.requestId())
				&& !Objects.equals(run.requestId(), request.getRequestId())) {
			throw new IllegalArgumentException("Run does not belong to requestId: " + request.getRequestId());
		}
		if (StringUtils.hasText(request.getHumanFeedbackContent()) || StringUtils.hasText(request.getRecoveryMode())) {
			return;
		}
		if (!StringUtils.hasText(run.requestPayload())) {
			throw new IllegalStateException("Durable run request identity is unavailable: " + run.runId());
		}
		try {
			GraphRequest persisted = JsonUtil.getObjectMapper().readValue(run.requestPayload(), GraphRequest.class);
			String expected = toJson(durableRequestIdentity(persisted));
			String actual = toJson(durableRequestIdentity(request));
			if (!Objects.equals(expected, actual)) {
				throw new IllegalArgumentException("Run is already bound to a different graph request");
			}
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to verify durable run request identity", ex);
		}
	}

	private void prepareProjectRuntimeIdentity(GraphRequest request) {
		if (request == null || request.getProjectId() == null) {
			throw new IllegalArgumentException("projectId is required for SemEvoSQL graph execution");
		}
		projectRuntimeGate.requireReadyByProject(request.getProjectId());
		String resolvedRuntimeSubject = projectRuntimeSubject(request.getProjectId());
		if (StringUtils.hasText(request.getAgentId())
				&& !Objects.equals(request.getAgentId(), resolvedRuntimeSubject)) {
			throw new IllegalArgumentException("Graph request contains a runtime identity outside the project profile");
		}
		request.setAgentId(resolvedRuntimeSubject);
	}

	private String projectRuntimeSubject(Long projectId) {
		return "project:" + projectId + ":" + runtimeProfileService.require(projectId).getRuntimeProfileId();
	}

	private QueryRun resolveRun(GraphRequest request) {
		if (StringUtils.hasText(request.getRunId())) {
			return runService.get(request.getRunId());
		}
		if (StringUtils.hasText(request.getHumanFeedbackContent())) {
			return runService.getLatestByThreadId(request.getThreadId());
		}
		ProjectRuntimeContext projectContext = projectRuntimeGate.requireReadyByProject(request.getProjectId());
		String requestId = StringUtils.hasText(request.getRequestId()) ? request.getRequestId()
				: UUID.randomUUID().toString();
		String idempotencyKey = StringUtils.hasText(request.getIdempotencyKey()) ? request.getIdempotencyKey()
				: "graph:" + requestId;
		request.setRequestId(requestId);
		request.setIdempotencyKey(idempotencyKey);
		String executionSnapshot = executionSnapshotService.capture(projectContext,
				runtimeProfileService.require(request.getProjectId()), null,
				request.isHumanFeedback());
		return runService.create(new CreateRunCommand(RunType.INTERACTIVE_QUERY, projectContext.projectId(),
				projectContext.projectVersionId(), request.getThreadId(), requestId, idempotencyKey,
				toJson(durableRequestIdentity(request)), executionSnapshot));
	}

	static GraphRequest durableRequestIdentity(GraphRequest request) {
		return GraphRequest.builder()
			.projectId(request.getProjectId())
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.requestId(request.getRequestId())
			.idempotencyKey(request.getIdempotencyKey())
			.query(request.getQuery())
			.forcedDatasourceId(request.getForcedDatasourceId())
			.forcedPhysicalTables(request.getForcedPhysicalTables())
			.humanFeedback(request.isHumanFeedback())
			.humanFeedbackContent(request.getHumanFeedbackContent())
			.rejectedPlan(request.isRejectedPlan())
			.recoveryMode(request.getRecoveryMode())
			.recoveredPlannerOutput(request.getRecoveredPlannerOutput())
			.recoveredSemanticPlan(request.getRecoveredSemanticPlan())
			.build();
	}

	/**
	 * 停止指定 threadId 的流式处理 线程安全：使用 remove 操作确保只有一个线程能获取到 context
	 * @param threadId 线程ID
	 */
	@Scheduled(fixedDelayString = "${semevosql.run.recovery-scan-ms:10000}")
	public void recoverDurableRuns() {
		for (QueryRun run : runService.recoverable()) {
			if (run.runType() == RunType.INTERACTIVE_QUERY && run.deadlineEpochMillis() != null
					&& System.currentTimeMillis() >= run.deadlineEpochMillis()) {
				try {
					runService.failIfDeadlineExceeded(run.runId());
				}
				catch (RuntimeException expiryError) {
					log.warn("Unable to fail expired durable Run {}: {}", run.runId(), expiryError.getMessage());
				}
				continue;
			}
			if (run.runType() != RunType.INTERACTIVE_QUERY || run.status() == RunStatus.WAITING_HUMAN
					|| "semantic-planning-clarification".equals(run.currentNode())
					|| !StringUtils.hasText(run.threadId())
					|| (!StringUtils.hasText(run.recoveryPayload()) && !StringUtils.hasText(run.requestPayload()))
					|| streamContextMap.containsKey(run.threadId())) {
				continue;
			}
			try {
				String recoveryPayload = StringUtils.hasText(run.recoveryPayload()) ? run.recoveryPayload()
						: run.requestPayload();
				GraphRequest request = JsonUtil.getObjectMapper().readValue(recoveryPayload, GraphRequest.class);
				request.setRunId(run.runId());
				request.setDurableRecoveryTakeover(true);
				request.setDurableRecoveryReplayNodeSequence(durableRecoveryPlanner.replayNodeSequence(run));
				Sinks.Many<ServerSentEvent<GraphNodeResponse>> detachedSink = Sinks.many()
					.multicast()
					.onBackpressureBuffer(16, false);
				graphStreamProcess(detachedSink, request);
			}
			catch (Exception ex) {
				log.debug("Durable run {} was not taken over in this scan: {}", run.runId(), ex.getMessage());
			}
		}
	}

	@Scheduled(fixedDelayString = "${semevosql.run.lease-renew-ms:10000}")
	public void renewActiveRunLeases() {
		streamContextMap.values()
			.stream()
			.map(StreamContext::getRunId)
			.filter(StringUtils::hasText)
			.distinct()
			.forEach(runId -> {
				try {
					QueryRun run = runService.get(runId);
					if (run.runType() == RunType.INTERACTIVE_QUERY && run.deadlineEpochMillis() != null
							&& System.currentTimeMillis() >= run.deadlineEpochMillis()) {
						runService.failIfDeadlineExceeded(runId);
						return;
					}
					if (run.status() == RunStatus.RUNNING) {
						runService.renewLease(runId);
					}
				}
				catch (RuntimeException ex) {
					log.warn("Unable to renew durable run lease for {}: {}", runId, ex.getMessage());
				}
			});
	}

	@Override
	public void stopStreamProcessing(String threadId) {
		if (!StringUtils.hasText(threadId)) {
			return;
		}
		log.info("Stopping stream processing for threadId: {}", threadId);
		multiTurnContextManager.discardPending(threadId);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null) {
			if (!finishCancellationIfRequested(context)) {
				releaseExecutionResources(context, false);
			}
			log.info("Cleaned up explicitly cancelled stream context for threadId: {}", threadId);
		}
	}

	private void handleNewProcess(GraphRequest graphRequest) {
		String query = graphRequest.getQuery();
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		boolean humanReviewEnabled = graphRequest.isHumanFeedback();
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(query)) {
			throw new IllegalArgumentException("Invalid arguments");
		}
		StreamContext context = streamContextMap.get(threadId);
		if (context == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}
		QueryRun run = runService.get(context.getRunId());
		ProjectRuntimeContext projectContext = projectRuntimeGate.requireReadyVersion(run.projectId(),
				run.projectVersionId());
		if (!Objects.equals(run.projectId(), graphRequest.getProjectId())) {
			throw new IllegalStateException("Durable run belongs to another semantic project");
		}
		context.setProjectId(projectContext.projectId());
		context.setProjectVersionId(projectContext.projectVersionId());
		context.setCatalogHash(projectContext.catalogHash());
		if (REPLAN_AFTER_REJECTION.equals(graphRequest.getRecoveryMode())
				|| EXECUTE_APPROVED_PLAN_AFTER_CHECKPOINT_LOSS.equals(graphRequest.getRecoveryMode())) {
			assertPersistedRecoveryRequest(run, graphRequest);
			run = preparePlanReviewRecovery(graphRequest, context, run);
			if (run == null) {
				return;
			}
		}
		String episodeId = run.episodeId();
		String attemptId = run.attemptId();
		if (!StringUtils.hasText(episodeId)) {
			var binding = productionService.createFirstAttemptAndBind(run.runId(), threadId,
					episodeRequest(run.requestId(), agentId, threadId, query, projectContext));
			episodeId = binding.episodeId();
			attemptId = binding.attemptId();
			run = binding.run();
		}
		context.setEpisodeId(episodeId);
		context.setAttemptId(attemptId);
		context.setEpisodeStartNanos(System.nanoTime());
			context.setDeadlineEpochMillis(requireRunDeadline(run));
		if (run.status() == RunStatus.QUEUED) {
			run = runService.transition(run.runId(), run.attemptId(), RunStatus.RUNNING, run.currentNode(), null, null);
		}
		if (clarificationService
			.detect(context.getRunId(), projectContext.projectId(), projectContext.projectVersionId(), query)
			.isPresent()) {
			emitControlMessage(context, agentId, threadId, "runtime-clarification", "该查询存在真实语义歧义，任务已暂停并等待确认。",
					"clarification");
			if (context.getSink() != null) {
				context.getSink().tryEmitComplete();
			}
			streamContextMap.remove(threadId, context);
			releaseExecutionResources(context, false);
			return;
		}
		String effectiveQuery = clarificationService.applyResolvedAnswer(context.getRunId(), query);
		run = runService.get(run.runId());
		if (run.status() == RunStatus.CANCEL_REQUESTED || run.status() == RunStatus.CANCELLED) {
			finishCancellationIfRequested(context);
			multiTurnContextManager.discardRun(run.runId(), threadId);
			return;
		}
		runService.saveCheckpoint(run.runId(), threadId, "graph-start", toJson(graphRequest), "");
		runService.appendEvent(run.runId(), "RUN_STARTED", "graph-start", null, "Graph execution started",
				"run-start:" + run.runId() + ":" + attemptId);
		// 开始 Langfuse 追踪
		Span span = langfuseReporter.startLLMSpan("graph-stream", graphRequest);
		context.setSpan(span);

		var preparedContext = multiTurnContextManager.prepareContext(threadId, effectiveQuery);
		String multiTurnContext = preparedContext.rendered();
		multiTurnContextManager.beginTurn(run.runId(), threadId, query);
		Map<String, Object> initialState = new HashMap<>();
		initialState.put(INPUT_KEY, effectiveQuery);
		initialState.put(EVIDENCE, "无");
		initialState.put(AGENT_ID, agentId);
		initialState.put(PROJECT_ID, projectContext.projectId());
		initialState.put(PROJECT_VERSION_ID, projectContext.projectVersionId());
		initialState.put(CATALOG_HASH, projectContext.catalogHash());
		initialState.put(EPISODE_ID, episodeId);
		initialState.put(ATTEMPT_ID, attemptId);
		initialState.put(RUN_ID, run.runId());
		initialState.put(RUN_DEADLINE_EPOCH_MILLIS, context.getDeadlineEpochMillis());
		initialState.put(PRINCIPAL_ID, graphRequest.getPrincipalId());
		initialState.put(APPROVAL_REQUIRED, humanReviewEnabled);
		initialState.put(HUMAN_REVIEW_ENABLED, humanReviewEnabled);
		initialState.put(MULTI_TURN_CONTEXT, multiTurnContext);
		initialState.put(CONVERSATION_CONTEXT_ENVELOPE, preparedContext.envelope());
		initialState.put(TRACE_THREAD_ID, threadId);
		if (graphRequest.getForcedDatasourceId() != null) {
			initialState.put(FORCED_DATASOURCE_ID, graphRequest.getForcedDatasourceId());
		}
		if (graphRequest.getForcedPhysicalTables() != null && !graphRequest.getForcedPhysicalTables().isEmpty()) {
			initialState.put(FORCED_PHYSICAL_TABLES, List.copyOf(graphRequest.getForcedPhysicalTables()));
		}
		if (StringUtils.hasText(graphRequest.getRecoveredPlannerOutput())) {
			initialState.put(RECOVERED_PLANNER_OUTPUT, graphRequest.getRecoveredPlannerOutput());
		}
		if (graphRequest.getRecoveredSemanticPlan() != null) {
			initialState.put(TYPED_SEMANTIC_PLAN, graphRequest.getRecoveredSemanticPlan());
			initialState.put(APPROVED_PLAN_RECOVERY, true);
		}
		resetNativeGraphCheckpoint(threadId);
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(initialState,
				RunnableConfig.builder().threadId(threadId).build());
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	private void resetNativeGraphCheckpoint(String threadId) {
		if (!StringUtils.hasText(threadId)) {
			return;
		}
		try {
			graphCheckpointer.release(RunnableConfig.builder().threadId(threadId).build());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to reset native graph checkpoint for threadId: " + threadId, ex);
		}
	}

	private void handleHumanFeedback(GraphRequest graphRequest) {
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		String feedbackContent = graphRequest.getHumanFeedbackContent();
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(feedbackContent)) {
			throw new IllegalArgumentException("Invalid arguments");
		}
		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}
		QueryRun run = runService.get(context.getRunId());
		ProjectRuntimeContext projectContext = projectRuntimeGate.requireReadyByProject(graphRequest.getProjectId());
		if (!Objects.equals(run.projectId(), projectContext.projectId())
				|| !Objects.equals(run.projectVersionId(), projectContext.projectVersionId())) {
			throw new IllegalStateException("Durable run semantic version no longer matches the requested agent");
		}
		context.setProjectId(projectContext.projectId());
		context.setProjectVersionId(projectContext.projectVersionId());
		context.setCatalogHash(projectContext.catalogHash());
		context.setEpisodeId(run.episodeId());
		context.setAttemptId(run.attemptId());
		context.setEpisodeStartNanos(System.nanoTime());
		context.setDeadlineEpochMillis(requireRunDeadline(run));
		if (run.status() == RunStatus.QUEUED || run.status() == RunStatus.WAITING_HUMAN) {
			run = runService.transition(run.runId(), run.attemptId(), RunStatus.RUNNING, HUMAN_FEEDBACK_NODE, null, null);
		}
		Map<String, Object> feedbackData = Map.of("feedback", !graphRequest.isRejectedPlan(), "feedback_content",
				feedbackContent);
		GraphRequest planReviewRecovery = planReviewRecoveryRequest(run, graphRequest, feedbackContent);
		runService.persistHumanFeedbackRecovery(run.runId(), toJson(planReviewRecovery),
				toJson(Map.of("approved", !graphRequest.isRejectedPlan(), "feedback", feedbackContent)),
				graphRequest.getIdempotencyKey());
		QueryRun afterFeedback = runService.get(run.runId());
		if (afterFeedback.status() == RunStatus.CANCEL_REQUESTED || afterFeedback.status() == RunStatus.CANCELLED) {
			finishCancellationIfRequested(context);
			multiTurnContextManager.discardRun(run.runId(), threadId);
			return;
		}
		Span span = langfuseReporter.startLLMSpan("graph-feedback", graphRequest);
		context.setSpan(span);
		if (graphRequest.isRejectedPlan()) {
			multiTurnContextManager.restartCurrentTurn(run.runId(), threadId, planReviewRecovery.getQuery());
		}
		var feedbackContext = multiTurnContextManager.prepareContext(threadId, planReviewRecovery.getQuery());
		Map<String, Object> stateUpdate = new HashMap<>();
		stateUpdate.put(AGENT_ID, agentId);
		stateUpdate.put(PROJECT_ID, projectContext.projectId());
		stateUpdate.put(PROJECT_VERSION_ID, projectContext.projectVersionId());
		stateUpdate.put(CATALOG_HASH, projectContext.catalogHash());
		stateUpdate.put(EPISODE_ID, run.episodeId());
		stateUpdate.put(ATTEMPT_ID, run.attemptId());
		stateUpdate.put(RUN_ID, run.runId());
		stateUpdate.put(RUN_DEADLINE_EPOCH_MILLIS, context.getDeadlineEpochMillis());
		stateUpdate.put(HUMAN_FEEDBACK_DATA, feedbackData);
		stateUpdate.put(MULTI_TURN_CONTEXT, feedbackContext.rendered());
		stateUpdate.put(CONVERSATION_CONTEXT_ENVELOPE, feedbackContext.envelope());
		GraphRequest originalRequest = readRecoveryRequest(run);
		if (originalRequest.getForcedDatasourceId() != null) {
			stateUpdate.put(FORCED_DATASOURCE_ID, originalRequest.getForcedDatasourceId());
		}
		if (originalRequest.getForcedPhysicalTables() != null && !originalRequest.getForcedPhysicalTables().isEmpty()) {
			stateUpdate.put(FORCED_PHYSICAL_TABLES, List.copyOf(originalRequest.getForcedPhysicalTables()));
		}
		// Approval recovery may resume against a stale native checkpoint whose state no longer contains the exact
		// approved plan. Rebind the durable plan and query before resuming so SemanticExecutionNode can never fall back
		// to an ungoverned or missing plan merely because the in-memory checkpoint was superseded.
		if (planReviewRecovery.getRecoveredSemanticPlan() != null) {
			stateUpdate.put(INPUT_KEY, planReviewRecovery.getQuery());
			stateUpdate.put(ACTIVE_QUERY, planReviewRecovery.getQuery());
			stateUpdate.put(TYPED_SEMANTIC_PLAN, planReviewRecovery.getRecoveredSemanticPlan());
			stateUpdate.put(APPROVED_PLAN_RECOVERY, true);
		}

		RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
		RunnableConfig updatedConfig;
		try {
			updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
		}
		catch (Exception checkpointError) {
			log.warn("Native graph checkpoint is unavailable for run {}; restarting from the durable entry",
					run.runId(), checkpointError);
			restartPlanReviewFromEntry(planReviewRecovery, context, run, checkpointError);
			return;
		}
		runService.appendEvent(run.runId(), "HUMAN_FEEDBACK_APPLIED", HUMAN_FEEDBACK_NODE,
				toJson(Map.of("approved", !graphRequest.isRejectedPlan())), "Human plan feedback applied",
				"human-feedback-applied:" + graphRequest.getIdempotencyKey());
		RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
			.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
			.build();
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	private GraphRequest planReviewRecoveryRequest(QueryRun run, GraphRequest feedbackRequest, String feedbackContent) {
		GraphRequest base = readRecoveryRequest(run);
		String originalQuery = StringUtils.hasText(base.getQuery()) ? base.getQuery() : feedbackRequest.getQuery();
		if (!StringUtils.hasText(originalQuery)) {
			throw new IllegalStateException("Original query is unavailable for plan-review recovery");
		}
		boolean rejected = feedbackRequest.isRejectedPlan();
		String recoveryQuery = rejected ? originalQuery + "\n[用户对上一版执行计划的驳回意见]\n" + feedbackContent.trim()
				: originalQuery;
		SemanticBlueprint recoveredSemanticPlan = rejected ? null : requireApprovedSemanticPlan(run.runId());
		return GraphRequest.builder()
			.projectId(feedbackRequest.getProjectId())
			.agentId(feedbackRequest.getAgentId())
			.threadId(run.threadId())
			.runId(run.runId())
			.requestId(run.requestId())
			.idempotencyKey((rejected ? "safe-replan:" : "safe-approved-plan:") + feedbackRequest.getIdempotencyKey())
			.query(recoveryQuery)
			.forcedDatasourceId(base.getForcedDatasourceId())
			.forcedPhysicalTables(base.getForcedPhysicalTables())
			.humanFeedback(rejected)
			.humanFeedbackContent(null)
			.rejectedPlan(false)
			.recoveryMode(rejected ? REPLAN_AFTER_REJECTION : EXECUTE_APPROVED_PLAN_AFTER_CHECKPOINT_LOSS)
			.recoveredSemanticPlan(recoveredSemanticPlan)
			.build();
	}

	private SemanticBlueprint requireApprovedSemanticPlan(String runId) {
		RunEvent snapshot = runService.latestEvent(runId, "APPROVAL_PLAN_SNAPSHOT");
		if (snapshot == null || !StringUtils.hasText(snapshot.payload())) {
			throw new IllegalStateException("Approved Semantic Blueprint snapshot is unavailable for run: " + runId);
		}
		try {
			return JsonUtil.getObjectMapper().readValue(snapshot.payload(), SemanticBlueprint.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to deserialize approved Semantic Blueprint for run: " + runId, ex);
		}
	}

	private GraphRequest readRecoveryRequest(QueryRun run) {
		String payload = StringUtils.hasText(run.recoveryPayload()) ? run.recoveryPayload() : run.requestPayload();
		if (!StringUtils.hasText(payload)) {
			throw new IllegalStateException("Durable graph request is unavailable for run: " + run.runId());
		}
		try {
			return JsonUtil.getObjectMapper().readValue(payload, GraphRequest.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to deserialize durable graph request for run: " + run.runId(), ex);
		}
	}

	private void assertPersistedRecoveryRequest(QueryRun run, GraphRequest request) {
		if (!StringUtils.hasText(run.episodeId()) || !StringUtils.hasText(run.attemptId())) {
			throw new IllegalArgumentException("Graph recoveryMode is internal and requires an existing execution");
		}
		GraphRequest persisted = readRecoveryRequest(run);
		if (!Objects.equals(persisted.getProjectId(), request.getProjectId())
				|| !Objects.equals(persisted.getRecoveryMode(), request.getRecoveryMode())
				|| !Objects.equals(persisted.getIdempotencyKey(), request.getIdempotencyKey())
				|| !Objects.equals(persisted.getQuery(), request.getQuery())
				|| !Objects.equals(persisted.getRunId(), request.getRunId())
				|| !Objects.equals(persisted.getThreadId(), request.getThreadId())
				|| !Objects.equals(persisted.getForcedDatasourceId(), request.getForcedDatasourceId())
				|| !Objects.equals(persisted.getForcedPhysicalTables(), request.getForcedPhysicalTables())
				|| persisted.isHumanFeedback() != request.isHumanFeedback()
				|| !Objects.equals(persisted.getRecoveredPlannerOutput(), request.getRecoveredPlannerOutput())
				|| !Objects.equals(persisted.getRecoveredSemanticPlan(), request.getRecoveredSemanticPlan())) {
			throw new IllegalArgumentException("Graph recovery request does not match the persisted durable payload");
		}
	}

	private QueryRun preparePlanReviewRecovery(GraphRequest recoveryRequest, StreamContext context, QueryRun run) {
		String runId = run.runId();
		String threadId = run.threadId();
		if (run.status() == RunStatus.CANCEL_REQUESTED || run.status() == RunStatus.CANCELLED) {
			finishCancellationIfRequested(context);
			multiTurnContextManager.discardRun(runId, threadId);
			return null;
		}
		if (!StringUtils.hasText(run.episodeId()) || !StringUtils.hasText(run.attemptId())) {
			throw new IllegalStateException("Plan-review recovery requires an active episode and attempt");
		}
		boolean rejected = REPLAN_AFTER_REJECTION.equals(recoveryRequest.getRecoveryMode());
		if (!rejected && recoveryRequest.getRecoveredSemanticPlan() == null) {
			throw new IllegalStateException("Approved-plan recovery requires the exact persisted Semantic Blueprint");
		}
		productionService.completeAttempt(run.episodeId(), run.attemptId(), rejected ? "REJECTED" : "FAILED",
				"GRAPH_CHECKPOINT_UNAVAILABLE");
		String replayKey = recoveryRequest.getIdempotencyKey() + ":" + run.attemptId();
		QueryRun queued = runService.requeueForEntryReplay(runId, replayKey);
		if (queued.status() == RunStatus.CANCEL_REQUESTED || queued.status() == RunStatus.CANCELLED) {
			finishCancellationIfRequested(context);
			multiTurnContextManager.discardRun(runId, threadId);
			return null;
		}
		String eventType = rejected ? "HUMAN_FEEDBACK_REPLAN_RESTARTED" : "HUMAN_FEEDBACK_APPROVED_PLAN_RESTARTED";
		String summary = rejected
				? "Native checkpoint unavailable; rejected plan is being regenerated and will require review again"
				: "Native checkpoint unavailable; exact approved plan is being replayed without another review";
		runService.appendEvent(runId, eventType, HUMAN_FEEDBACK_NODE, toJson(Map.of("rejected", rejected)), summary,
				"human-feedback-entry-replay:" + replayKey);
		multiTurnContextManager.restartCurrentTurn(runId, threadId, recoveryRequest.getQuery());
		QueryRun updated = runService.updateRecoveryPayload(runId, toJson(recoveryRequest));
		String checkpointNode = rejected ? "human-feedback-safe-replan" : "human-feedback-approved-plan-replay";
		runService.saveCheckpoint(runId, threadId, checkpointNode, toJson(recoveryRequest), "");
		return updated;
	}

	private void restartPlanReviewFromEntry(GraphRequest recoveryRequest, StreamContext context, QueryRun run,
			Exception checkpointError) {
		String runId = run.runId();
		String threadId = run.threadId();
		if (finishCancellationIfRequested(context)) {
			multiTurnContextManager.discardRun(runId, threadId);
			return;
		}
		closeFeedbackSpan(context, threadId, new IllegalStateException(
				"Native checkpoint unavailable; restarting durable plan review", checkpointError));
		String checkpointNode = REPLAN_AFTER_REJECTION.equals(recoveryRequest.getRecoveryMode())
				? "human-feedback-safe-replan" : "human-feedback-approved-plan-replay";
		runService.saveCheckpoint(runId, threadId, checkpointNode, toJson(recoveryRequest), "");
		handleNewProcess(recoveryRequest);
	}

	private void closeFeedbackSpan(StreamContext context, String threadId, RuntimeException error) {
		if (context.getSpan() == null) {
			return;
		}
		try {
			langfuseReporter.endSpanError(context.getSpan(), threadId, error);
		}
		catch (RuntimeException spanError) {
			log.warn("Unable to close feedback span for run {}: {}", context.getRunId(), spanError.getMessage());
		}
		context.setSpan(null);
	}

	/**
	 * 订阅 Flux 并原子性地设置 Disposable 线程安全：使用 synchronized 确保 Disposable 设置的原子性
	 * @param context 流式处理上下文
	 * @param nodeOutputFlux 节点输出流
	 * @param graphRequest 图请求
	 * @param agentId 代理ID
	 * @param threadId 线程ID
	 */
	private void subscribeToFlux(StreamContext context, Flux<NodeOutput> nodeOutputFlux, GraphRequest graphRequest,
			String agentId, String threadId) {
		try {
			CompletableFuture.runAsync(() -> {
				try {
					if (context.isCleaned()) {
						log.debug("StreamContext cleaned before subscription for threadId: {}", threadId);
						return;
					}
					Disposable disposable = enforceAbsoluteDeadline(nodeOutputFlux, context.getDeadlineEpochMillis())
						.subscribe(output -> handleNodeOutput(context, graphRequest, output),
								error -> handleStreamError(context, agentId, threadId, error),
								() -> handleStreamComplete(context, graphRequest, agentId, threadId));
					synchronized (context) {
						if (context.isCleaned()) {
							if (disposable != null && !disposable.isDisposed()) {
								disposable.dispose();
							}
						}
						else {
							context.setDisposable(disposable);
						}
					}
				}
				catch (Throwable error) {
					if (!context.isCleaned()) {
						handleStreamError(context, agentId, threadId, error);
					}
				}
			}, executor);
		}
		catch (RuntimeException ex) {
			throw CapacityRejectedException.serviceUnavailable("interactive-query", interactiveRetryAfterSeconds,
					"Interactive query execution queue is full");
		}
	}

	static <T> Flux<T> enforceAbsoluteDeadline(Flux<T> source, long deadlineEpochMillis) {
		long remainingMs = deadlineEpochMillis - System.currentTimeMillis();
		if (remainingMs <= 0L) {
			return Flux.error(new RunDeadlineExceededException("Interactive Run deadline exhausted before subscription"));
		}
		Mono<Long> deadlineSignal = Mono.delay(Duration.ofMillis(remainingMs))
			.flatMap(ignored -> Mono
				.error(new RunDeadlineExceededException("Interactive Run exceeded its absolute execution deadline")));
		return source.takeUntilOther(deadlineSignal);
	}

	private long requireRunDeadline(QueryRun run) {
		if (run == null || run.deadlineEpochMillis() == null) {
			throw new IllegalStateException("Durable interactive run deadline is unavailable: "
					+ (run == null ? "unknown" : run.runId()));
		}
		if (System.currentTimeMillis() >= run.deadlineEpochMillis()) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted before Graph execution");
		}
		return run.deadlineEpochMillis();
	}

	/**
	 * 处理流式错误 线程安全：使用 remove 操作确保只有一个线程能获取到 context
	 */
	private void handleStreamError(StreamContext context, String agentId, String threadId, Throwable error) {
		if (causedBy(error, LateRunResultDroppedException.class)) {
			log.info("Discarded late Graph result for run={}, attempt={}: {}", context.getRunId(), context.getAttemptId(),
					error.getMessage());
			streamContextMap.remove(threadId, context);
			context.cleanup();
			return;
		}
		log.error("Error in stream processing for threadId: {}: ", threadId, error);
		if (!streamContextMap.remove(threadId, context)) {
			// A recovered attempt may already own the same conversation thread. Never let this callback remove or
			// finalize that newer StreamContext.
			context.cleanup();
			return;
		}
		if (context.isCleaned()) {
			return;
		}
		if (finishCancellationIfRequested(context)) {
			multiTurnContextManager.discardRun(context.getRunId(), threadId);
			return;
		}
		QueryRun interrupted = runService.get(context.getRunId());
		if (interrupted.status() == RunStatus.WAITING_HUMAN) {
			// Semantic planning/review can create a governed clarification from inside the Graph and then terminate
			// the current reactive stream by exception. That is a durable human interrupt, not a failed Run.
			multiTurnContextManager.persistPending(threadId);
			try {
				var clarification = clarificationService.getPending(context.getRunId());
				emitControlMessage(context, agentId, threadId, interrupted.currentNode(), clarification.question(),
						"runtime-clarification");
			}
			catch (RuntimeException missingClarification) {
				log.debug("Run {} entered WAITING_HUMAN without a runtime clarification payload: {}", context.getRunId(),
						missingClarification.getMessage());
			}
			if (context.getSink() != null) {
				context.getSink().tryEmitComplete();
			}
			releaseExecutionResources(context, false);
			return;
		}
		try {
			String durableErrorCode = GraphFailureClassifier.errorCode(error);
			String durableErrorMessage = GraphFailureClassifier.publicMessage(error);
			if (GraphFailureClassifier.recoverableModelFailure(error) && scheduleModelProviderRecovery(context, agentId, threadId,
					durableErrorCode, durableErrorMessage)) {
				return;
			}
			try {
				runService.transition(context.getRunId(), context.getAttemptId(), RunStatus.FAILED, null, durableErrorCode,
						durableErrorMessage);
			}
			catch (RuntimeException transitionError) {
				if (finishCancellationIfRequested(context)) {
					multiTurnContextManager.discardRun(context.getRunId(), threadId);
					return;
				}
				throw transitionError;
			}
			RunEvent failedEvent = null;
			try {
				failedEvent = runService.appendEvent(context.getRunId(), "RUN_FAILED", null, null,
						summarize(durableErrorMessage),
						"run-failed:" + context.getRunId() + ":" + Objects.toString(context.getAttemptId(), "unbound"));
			}
			catch (RuntimeException eventError) {
				log.warn("Unable to append failure event for run {}: {}", context.getRunId(), eventError.getMessage());
			}
			completeEpisodeBestEffort(context.getEpisodeId(), "FAILED", durableErrorCode, context.getAttemptId(),
					elapsedMillis(context));
			multiTurnContextManager.resetPendingForRetry(threadId);
			if (context.getSpan() != null) {
				try {
					langfuseReporter.endSpanError(context.getSpan(), threadId,
							error instanceof Exception ? (Exception) error : new RuntimeException(error));
				}
				catch (RuntimeException spanError) {
					log.warn("Unable to close tracing span for failed run {}: {}", context.getRunId(),
							spanError.getMessage());
				}
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				ServerSentEvent.Builder<GraphNodeResponse> failed = ServerSentEvent
					.builder(GraphNodeResponse.error(agentId, threadId, context.getRunId(), durableErrorMessage))
					.event(STREAM_EVENT_ERROR);
				if (failedEvent != null) {
					failed.id(Long.toString(failedEvent.sequence()));
				}
				context.getSink().tryEmitNext(failed.build());
				context.getSink().tryEmitComplete();
			}
		}
		finally {
			if (!context.isCleaned()) {
				releaseExecutionResources(context, true);
			}
		}
	}

	/**
	 * 处理流式完成 线程安全：使用 remove 操作确保只有一个线程能获取到 context
	 */
	private void handleStreamComplete(StreamContext context, GraphRequest request, String agentId, String threadId) {
		log.info("Stream processing completed successfully for threadId: {}", threadId);
		if (streamContextMap.get(threadId) == context && !context.isCleaned()) {
			try {
				executionFence.assertActive(context.getRunId(), context.getAttemptId());
			}
			catch (LateRunResultDroppedException dropped) {
				streamContextMap.remove(threadId, context);
				context.cleanup();
				return;
			}
			catch (RunDeadlineExceededException deadline) {
				handleStreamError(context, agentId, threadId, deadline);
				return;
			}
			if (finishCancellationIfRequested(context)) {
				streamContextMap.remove(threadId, context);
				multiTurnContextManager.discardPending(threadId);
				return;
			}
			boolean waitingAtHumanFeedback;
			try {
				waitingAtHumanFeedback = request.isHumanFeedback()
						&& isWaitingAtHumanFeedback(threadId);
			}
			catch (RuntimeException ex) {
				handleStreamError(context, agentId, threadId, ex);
				return;
			}
			streamContextMap.remove(threadId, context);
			if (waitingAtHumanFeedback) {
				multiTurnContextManager.persistPending(threadId);
				QueryRun current = runService.get(context.getRunId());
				if (current.status() == RunStatus.RUNNING) {
					runService.transition(context.getRunId(), context.getAttemptId(), RunStatus.WAITING_HUMAN,
						HUMAN_FEEDBACK_NODE, null, null);
				}
				runService.saveCheckpoint(context.getRunId(), threadId, HUMAN_FEEDBACK_NODE,
						toJson(Map.of("kind", "NATIVE_PLAN_REVIEW", "threadId", threadId)), "");
				runService.appendEvent(context.getRunId(), "HUMAN_FEEDBACK_REQUIRED", HUMAN_FEEDBACK_NODE, null,
						"Generated plan is waiting for human review",
						"human-feedback-required:" + context.getRunId() + ":" + current.revision());
				emitControlMessage(context, agentId, threadId, HUMAN_FEEDBACK_NODE, "执行计划已持久化暂停，等待人工确认。",
						"human-feedback");
				if (context.getSink() != null) {
					context.getSink().tryEmitComplete();
				}
				releaseExecutionResources(context, false);
				return;
			}
			finishSuccessfulRun(request, context, agentId, threadId);
		}
	}

	private void finishSuccessfulRun(GraphRequest request, StreamContext context, String agentId, String threadId) {
		try {
			multiTurnContextManager.persistPending(threadId);
			try {
				runService.transition(context.getRunId(), context.getAttemptId(), RunStatus.SUCCEEDED, null, null, null);
			}
			catch (RuntimeException ex) {
				if (finishCancellationIfRequested(context)) {
					multiTurnContextManager.discardRun(context.getRunId(), threadId);
					return;
				}
				throw ex;
			}
			RunEvent succeededEvent = runService.appendEvent(context.getRunId(), "RUN_SUCCEEDED", null, null,
					"Run completed", "run-succeeded:" + context.getRunId() + ":" + context.getAttemptId());
			multiTurnContextManager.finishTurn(threadId);
			if (StringUtils.hasText(context.getEpisodeId())) {
				productionService.completeEpisode(context.getEpisodeId(),
						new SemEvoSQLProductionService.CompletionRequest("SUCCEEDED", null, null,
								elapsedMillis(context)), context.getAttemptId());
			}
			if (context.getSpan() != null) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(
							ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId, context.getRunId()))
								.event(STREAM_EVENT_COMPLETE)
								.id(Long.toString(succeededEvent.sequence()))
								.build());
				context.getSink().tryEmitComplete();
			}
		}
		catch (RuntimeException ex) {
			QueryRun terminal = runService.get(context.getRunId());
			if (terminal.status() == RunStatus.SUCCEEDED) {
				log.error("Run {} succeeded but post-completion finalization failed", context.getRunId(), ex);
				runService.appendEvent(context.getRunId(), "RUN_FINALIZATION_WARNING", null, null,
						summarize(ex.getMessage()),
						"run-finalization-warning:" + context.getRunId() + ":" + context.getAttemptId());
				if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
					context.getSink().tryEmitComplete();
				}
			}
			else if (!terminal.terminal()) {
				log.error("Unable to finalize run {} successfully", context.getRunId(), ex);
				String errorCode = finalizationErrorCode(ex);
					runService.transition(context.getRunId(), context.getAttemptId(), RunStatus.FAILED, terminal.currentNode(), errorCode,
						ex.getMessage());
				runService.appendEvent(context.getRunId(), "RUN_FAILED", terminal.currentNode(), null,
						summarize(ex.getMessage()),
						"run-failed:" + context.getRunId() + ":" + Objects.toString(context.getAttemptId(), "unbound"));
				if (StringUtils.hasText(context.getEpisodeId())) {
					try {
						productionService.completeEpisode(context.getEpisodeId(),
								new SemEvoSQLProductionService.CompletionRequest("FAILED", errorCode, null,
										elapsedMillis(context)), context.getAttemptId());
					}
					catch (RuntimeException episodeError) {
						log.warn("Unable to mark episode {} failed after run finalization error: {}",
								context.getEpisodeId(), episodeError.getMessage());
					}
				}
				multiTurnContextManager.resetPendingForRetry(threadId);
				if (context.getSpan() != null) {
					langfuseReporter.endSpanError(context.getSpan(), threadId, ex);
				}
				if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
					context.getSink()
						.tryEmitNext(ServerSentEvent
							.builder(GraphNodeResponse.error(agentId, threadId, context.getRunId(),
									"Unable to finalize run: " + ex.getMessage()))
							.event(STREAM_EVENT_ERROR)
							.build());
					context.getSink().tryEmitComplete();
				}
			}
		}
		finally {
			releaseExecutionResources(context, true);
		}
	}

	private static String finalizationErrorCode(RuntimeException error) {
		return error.getClass().getSimpleName();
	}

	private boolean isWaitingAtHumanFeedback(String threadId) {
		try {
			return HUMAN_FEEDBACK_NODE
				.equals(compiledGraph.getState(RunnableConfig.builder().threadId(threadId).build()).next());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to inspect native graph checkpoint for threadId: " + threadId, ex);
		}
	}

	private boolean finishCancellationIfRequested(StreamContext context) {
		QueryRun run = runService.get(context.getRunId());
		if (run.status() != RunStatus.CANCEL_REQUESTED && run.status() != RunStatus.CANCELLED) {
			return false;
		}
		if (run.status() == RunStatus.CANCEL_REQUESTED) {
			run = runService.acknowledgeCancelled(context.getRunId());
		}
		RunEvent cancelledEvent = null;
		try {
			cancelledEvent = runService.appendEvent(context.getRunId(), "RUN_CANCELLED", run.currentNode(), null,
					"Run cancelled", "run-cancelled:" + context.getRunId());
		}
		catch (RuntimeException eventError) {
			log.warn("Unable to append cancellation event for run {}: {}", context.getRunId(), eventError.getMessage());
		}
		if (StringUtils.hasText(context.getEpisodeId())) {
			try {
				productionService.completeEpisode(context.getEpisodeId(),
						new SemEvoSQLProductionService.CompletionRequest("CANCELLED", null, null,
								elapsedMillis(context)), context.getAttemptId());
			}
			catch (RuntimeException episodeError) {
				log.warn("Unable to mark episode {} cancelled: {}", context.getEpisodeId(), episodeError.getMessage());
			}
		}
		if (context.getSpan() != null && context.getSpan().isRecording()) {
			try {
				langfuseReporter.endSpanSuccess(context.getSpan(), run.threadId(), context.getCollectedOutput());
			}
			catch (RuntimeException spanError) {
				log.warn("Unable to close tracing span for cancelled run {}: {}", context.getRunId(),
						spanError.getMessage());
			}
		}
		if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
			ServerSentEvent.Builder<GraphNodeResponse> cancelled = ServerSentEvent
				.builder(GraphNodeResponse.error(null, run.threadId(), run.runId(), "Run cancelled"))
				.event(STREAM_EVENT_ERROR);
			if (cancelledEvent != null) {
				cancelled.id(Long.toString(cancelledEvent.sequence()));
			}
			context.getSink().tryEmitNext(cancelled.build());
			context.getSink().tryEmitComplete();
		}
		releaseExecutionResources(context, true);
		return true;
	}

	private void finishSynchronousCancellation(String runId, String episodeId, String attemptId, long elapsedMillis) {
		QueryRun current = runService.get(runId);
		if (current.status() == RunStatus.CANCEL_REQUESTED) {
			current = runService.acknowledgeCancelled(runId);
		}
		try {
			runService.appendEvent(runId, "RUN_CANCELLED", current.currentNode(), null, "Run cancelled",
					"run-cancelled:" + runId);
		}
		catch (RuntimeException eventError) {
			log.warn("Unable to append cancellation event for synchronous run {}: {}", runId, eventError.getMessage());
		}
		completeEpisodeBestEffort(episodeId, "CANCELLED", null, attemptId, elapsedMillis);
	}

	private void completeEpisodeBestEffort(String episodeId, String status, String errorCode, String attemptId,
			long elapsedMillis) {
		if (!StringUtils.hasText(episodeId)) {
			return;
		}
		try {
			productionService.completeEpisode(episodeId,
					new SemEvoSQLProductionService.CompletionRequest(status, errorCode, null, elapsedMillis), attemptId);
		}
		catch (RuntimeException episodeError) {
			log.warn("Unable to mark episode {} as {}: {}", episodeId, status, episodeError.getMessage());
		}
	}

	private SemEvoSQLProductionService.EpisodeRequest episodeRequest(String requestId, String agentId,
			String conversationId, String query, ProjectRuntimeContext context) {
		return new SemEvoSQLProductionService.EpisodeRequest(requestId, agentId, context.projectId(),
				context.projectVersionId(), null, context.catalogHash(), conversationId, null, null, query, query, null, "v1");
	}

	private long elapsedMillis(StreamContext context) {
		return elapsedMillis(context.getEpisodeStartNanos());
	}

	private long elapsedMillis(long startNanos) {
		return startNanos == 0 ? 0 : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	/**
	 * 处理节点输出
	 */
	private void handleNodeOutput(StreamContext context, GraphRequest request, NodeOutput output) {
		if (context == null || context.isCleaned()) {
			return;
		}
		// A non-streaming node can still return after Reactor cancellation when its underlying
		// blocking work cannot be interrupted. Fence it before handling any output.
		executionFence.assertActive(context.getRunId(), context.getAttemptId());
		log.debug("Received output: {}", output.getClass().getSimpleName());
		if (output instanceof StreamingOutput streamingOutput) {
			handleStreamNodeOutput(context, request, streamingOutput);
		}
	}

	private void emitRunEstablished(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest request) {
		GraphNodeResponse established = GraphNodeResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.runId(request.getRunId())
			.nodeName("durable-run-established")
			.textType(TextType.TEXT)
			.text("")
			.build();
		Sinks.EmitResult result = sink
			.tryEmitNext(ServerSentEvent.builder(established).event(STREAM_EVENT_RUN_ESTABLISHED).build());
		if (result.isFailure()) {
			log.debug("Unable to emit durable run bootstrap for run {}: {}", request.getRunId(), result);
		}
	}

	private void emitControlMessage(StreamContext context, String agentId, String threadId, String node, String text,
			String event) {
		GraphNodeResponse response = GraphNodeResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.runId(context.getRunId())
			.nodeName(node)
			.text(text)
			.textType(TextType.TEXT)
			.build();
		if (context.getSink() != null) {
			Sinks.EmitResult result = context.getSink()
				.tryEmitNext(ServerSentEvent.builder(response).event(event).build());
			if (result.isFailure()) {
				log.debug("Unable to emit control event {} for run {}: {}", event, context.getRunId(), result);
			}
		}
	}

	private void assertExecutionSnapshotCompatible(QueryRun run) {
		try {
			executionSnapshotService.assertCompatible(run);
		}
		catch (ExecutionSnapshotMismatchException ex) {
			QueryRun failed = runService.failExecutionSnapshotMismatch(run.runId(), ex.getMessage());
			if (StringUtils.hasText(failed.threadId())) {
				threadExecutionGuardService.release(failed.threadId(), failed.runId());
			}
			throw ex;
		}
	}

	private void claimThreadOrReject(QueryRun run) {
		if (!StringUtils.hasText(run.threadId())) {
			return;
		}
		try {
			threadExecutionGuardService.claim(run.threadId(), run.runId());
		}
		catch (ThreadExecutionConflictException ex) {
			runService.appendEvent(run.runId(), "RUN_DEFERRED", run.currentNode(), null,
					"Conversation thread is already executing another run",
					"thread-deferred:" + run.runId() + ":" + ex.activeRunId() + ":" + run.revision());
			throw CapacityRejectedException.tooManyRequests("thread", interactiveRetryAfterSeconds,
					"Another query is already running in this conversation thread; activeRunId=" + ex.activeRunId());
		}
	}

	private void releaseThreadIfTerminal(String runId) {
		QueryRun current = runService.get(runId);
		if (current.terminal() && StringUtils.hasText(current.threadId())) {
			threadExecutionGuardService.release(current.threadId(), current.runId());
		}
	}

	private void releaseExecutionResources(StreamContext context, boolean releaseThreadGuard) {
		try {
			runService.releaseLease(context.getRunId());
		}
		catch (RuntimeException leaseError) {
			log.warn("Unable to release lease for run {}: {}", context.getRunId(), leaseError.getMessage());
		}
		if (releaseThreadGuard) {
			try {
				releaseThreadIfTerminal(context.getRunId());
			}
			catch (RuntimeException guardError) {
				log.warn("Unable to release thread guard for run {}: {}", context.getRunId(), guardError.getMessage());
			}
		}
		context.cleanup();
	}

	private boolean scheduleModelProviderRecovery(StreamContext context, String agentId, String threadId,
			String errorCode, String errorMessage) {
		long priorRecoveries = runService.events(context.getRunId(), 0, 1000)
			.stream()
			.filter(event -> "MODEL_PROVIDER_RETRY_SCHEDULED".equals(event.eventType()))
			.count();
		if (priorRecoveries >= MAX_MODEL_PROVIDER_RUN_RECOVERIES) {
			return false;
		}
		int recoveryAttempt = (int) priorRecoveries + 1;
		Map<String, Object> payload = Map.of(
				"errorCode", errorCode,
				"attempt", recoveryAttempt,
				"maxAttempts", MAX_MODEL_PROVIDER_RUN_RECOVERIES,
				"currentNode", Objects.toString(runService.get(context.getRunId()).currentNode(), ""));
		runService.appendEvent(context.getRunId(), "MODEL_PROVIDER_RETRY_SCHEDULED",
				runService.get(context.getRunId()).currentNode(), toJson(payload),
				"Transient model-provider failure; durable Run will resume from persisted state",
				"model-provider-retry:" + context.getRunId() + ":" + recoveryAttempt);
		multiTurnContextManager.persistPending(threadId);
		emitControlMessage(context, agentId, threadId, runService.get(context.getRunId()).currentNode(),
				"模型服务暂时不可用，当前进度已持久化，系统会自动继续执行。", "model-provider-retry");
		if (context.getSink() != null) {
			context.getSink().tryEmitComplete();
		}
		log.warn("Transient model-provider failure for run {}; scheduled durable recovery {}/{}: {}",
				context.getRunId(), recoveryAttempt, MAX_MODEL_PROVIDER_RUN_RECOVERIES, errorMessage);
		return true;
	}

	private static long retryAfterSeconds(long millis) {
		return Math.max(1, (Math.max(0, millis) + 999) / 1000);
	}

	private static String summarize(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= 500 ? value : value.substring(0, 500);
	}

	private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
		for (Throwable current = error; current != null && current.getCause() != current; current = current.getCause()) {
			if (type.isInstance(current)) {
				return true;
			}
		}
		return false;
	}

	private boolean suppressDurableReplayOutput(GraphRequest request, String node) {
		if (!request.isDurableRecoveryTakeover()) {
			return false;
		}
		List<String> sequence = request.getDurableRecoveryReplayNodeSequence();
		if (sequence == null || sequence.isEmpty()) {
			return false;
		}
		synchronized (request) {
			int index = request.getDurableRecoveryReplayNodeIndex();
			if (index >= sequence.size()) {
				return false;
			}
			String current = request.getDurableRecoveryReplayCurrentNode();
			if (!StringUtils.hasText(current)) {
				String expected = sequence.get(index);
				if (!Objects.equals(expected, node)) {
					log.info("Durable replay diverged before previously visible node pass; duplicate-output suppression stopped. expected={}, actual={}",
							expected, node);
					request.setDurableRecoveryReplayNodeIndex(sequence.size());
					return false;
				}
				request.setDurableRecoveryReplayCurrentNode(node);
				return true;
			}
			if (Objects.equals(current, node)) {
				return true;
			}
			int next = index + 1;
			request.setDurableRecoveryReplayNodeIndex(next);
			request.setDurableRecoveryReplayCurrentNode(null);
			if (next >= sequence.size()) {
				return false;
			}
			String expected = sequence.get(next);
			if (!Objects.equals(expected, node)) {
				log.info("Durable replay diverged from previously visible node pass sequence; duplicate-output suppression stopped. expected={}, actual={}",
						expected, node);
				request.setDurableRecoveryReplayNodeIndex(sequence.size());
				return false;
			}
			request.setDurableRecoveryReplayCurrentNode(node);
			return true;
		}
	}

	private static String toJson(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize durable graph state", ex);
		}
	}

	@SuppressWarnings("deprecation") // Spring AI Alibaba 1.1.2.0 has no equivalent getter for explicit chunk outputs.
	private void handleStreamNodeOutput(StreamContext context, GraphRequest request, StreamingOutput output) {
		String threadId = request.getThreadId();
		// 检查是否已经停止处理
		if (streamContextMap.get(threadId) != context || context.isCleaned() || context.getSink() == null) {
			log.debug("Stream processing already stopped for threadId: {}, skipping output", threadId);
			return;
		}
		executionFence.assertActive(context.getRunId(), context.getAttemptId());
		String node = output.node();
		String chunk = output.chunk();
		log.debug("Received Stream output: {}", chunk);

		if (chunk == null || chunk.isEmpty()) {
			return;
		}
		if (suppressDurableReplayOutput(request, node)) {
			return;
		}

		// 如果是文本标记符号，则更新文本类型
		TextType originType = context.getTextType();
		TextType textType;
		boolean isTypeSign = false;
		if (originType == null) {
			textType = TextType.getTypeByStratSign(chunk);
			if (textType != TextType.TEXT) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		else {
			textType = TextType.getType(originType, chunk);
			if (textType != originType) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		// 文本标记符号不返回给前端
		if (!isTypeSign) {
			context.appendOutput(chunk);
			if (textType == TextType.SQL) {
				context.appendSql(node, chunk);
			}
			if (PlannerNode.class.getSimpleName().equals(node)) {
				multiTurnContextManager.appendPlannerChunk(threadId, chunk);
			}
			GraphNodeResponse response = GraphNodeResponse.builder()
				.agentId(request.getAgentId())
				.threadId(threadId)
				.runId(context.getRunId())
				.nodeName(node)
				.text(chunk)
				.textType(textType)
				.build();
			String eventKey = context.getAttemptId() + ":" + node + ":" + context.getCollectedOutput().length() + ":"
					+ Integer.toHexString(chunk.hashCode());
			synchronized (context) {
				RunEvent persistedEvent = runService.appendEvent(context.getRunId(), "NODE_OUTPUT", node,
						toJson(response), summarize(chunk), eventKey);
				runService.saveCheckpoint(context.getRunId(), threadId, node,
						toJson(Map.of("collectedOutput", context.getCollectedOutput(), "node", node)), "");
				if (context.getSink() != null) {
					Sinks.EmitResult result = context.getSink()
						.tryEmitNext(
								ServerSentEvent.builder(response).id(Long.toString(persistedEvent.sequence())).build());
					if (result.isFailure()) {
						log.debug(
								"Client transport is unavailable for threadId: {}, result: {}; run continues durably.",
								threadId, result);
					}
				}
			}
		}
	}

}
