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

import cn.lgs.semevosql.common.OptimisticLockingFailureException;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Service
public class QueryRunService {

	private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

	private static final long DEFAULT_INTERACTIVE_DEADLINE_MS = 300_000L;

	private static final ReentrantLock[] EVENT_LOCKS = IntStream.range(0, 256)
		.mapToObj(ignored -> new ReentrantLock())
		.toArray(ReentrantLock[]::new);

	private final QueryRunRepository repository;

	private final String instanceId;

	private final SemEvoSQLMetrics metrics;

	private final long interactiveDeadlineMs;

	private final Map<String, Sinks.Many<RunEvent>> liveEvents = new ConcurrentHashMap<>();

	@Autowired
	public QueryRunService(QueryRunRepository repository, @Value("${semevosql.instance-id:local}") String instanceId,
			SemEvoSQLMetrics metrics,
			@Value("${semevosql.concurrency.interactive-query.task-timeout-ms:300000}") long interactiveDeadlineMs) {
		this.repository = repository;
		this.instanceId = instanceId;
		this.metrics = metrics;
		this.interactiveDeadlineMs = Math.max(1L, interactiveDeadlineMs);
	}

	public QueryRunService(QueryRunRepository repository, String instanceId) {
		this(repository, instanceId, SemEvoSQLMetrics.noop(), DEFAULT_INTERACTIVE_DEADLINE_MS);
	}

	@Transactional
	public QueryRun create(CreateRunCommand command) {
		if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		if (command.projectId() == null) {
			throw new IllegalArgumentException("projectId is required");
		}
		if (command.projectVersionId() == null) {
			throw new IllegalArgumentException("projectVersionId is required");
		}
		QueryRun existing = repository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
		if (existing != null) {
			assertSameCommand(existing, command);
			return existing;
		}
		String runId = UUID.randomUUID().toString();
		QueryRun run = QueryRun.builder()
			.runId(runId)
			.runType(command.runType())
			.projectId(command.projectId())
			.projectVersionId(command.projectVersionId())
			.threadId(command.threadId())
			.status(RunStatus.QUEUED)
			.lastEventSequence(0)
			.requestId(command.requestId())
			.idempotencyKey(command.idempotencyKey())
			.revision(0)
			.requestPayload(command.requestPayload())
			.recoveryPayload(command.requestPayload())
			.executionSnapshot(command.executionSnapshot())
			.deadlineEpochMillis(resolveDeadline(command))
			.build();
		if (repository.insertIfAbsent(run) == 1) {
			metrics.afterCommit(() -> metrics.runCreated(run.runType()));
			return run;
		}
		QueryRun concurrent = repository.findByIdempotencyKey(command.idempotencyKey())
			.orElseThrow(() -> new IllegalStateException("Idempotent run insert lost the conflicting durable row"));
		assertSameCommand(concurrent, command);
		return concurrent;
	}

	private Long resolveDeadline(CreateRunCommand command) {
		if (command.deadlineEpochMillis() != null) {
			return command.deadlineEpochMillis();
		}
		if (command.runType() == RunType.INTERACTIVE_QUERY) {
			return System.currentTimeMillis() + interactiveDeadlineMs;
		}
		return null;
	}

	private static void assertSameCommand(QueryRun existing, CreateRunCommand command) {
		if (existing.runType() != command.runType() || !Objects.equals(existing.projectId(), command.projectId())
				|| !Objects.equals(existing.projectVersionId(), command.projectVersionId())
				|| !Objects.equals(existing.threadId(), command.threadId())
				|| !Objects.equals(existing.requestId(), command.requestId())
				|| !Objects.equals(existing.requestPayload(), command.requestPayload())
				|| !Objects.equals(existing.executionSnapshot(), command.executionSnapshot())) {
			throw new IllegalArgumentException("idempotencyKey is already bound to a different run command");
		}
	}

	public QueryRun get(String runId) {
		return repository.findById(runId)
			.orElseThrow(() -> new IllegalArgumentException("Query run not found: " + runId));
	}

	public Optional<QueryRun> findByIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return Optional.empty();
		}
		return repository.findByIdempotencyKey(idempotencyKey);
	}

	/** Lock one durable run row inside the caller's transaction for atomic state transitions. */
	@Transactional
	public QueryRun lockForUpdate(String runId) {
		return repository.lock(runId);
	}

	public QueryRun getLatestByThreadId(String threadId) {
		return repository.findLatestByThreadId(threadId)
			.orElseThrow(() -> new IllegalArgumentException("Query run not found for thread: " + threadId));
	}

	@Transactional
	public QueryRun prepareQueuedExecution(String runId, RunType runType, String requestPayload, String executionSnapshot) {
		if (runType == null || requestPayload == null || requestPayload.isBlank() || executionSnapshot == null
				|| executionSnapshot.isBlank()) {
			throw new IllegalArgumentException("runType, requestPayload and executionSnapshot are required");
		}
		QueryRun current = repository.lock(runId);
		if (current.status() != RunStatus.QUEUED) {
			throw new IllegalStateException("Only a QUEUED run can be prepared for execution");
		}
		if (repository.prepareQueuedExecution(runId, current.revision(), runType, requestPayload, executionSnapshot) != 1) {
			throw conflict(runId);
		}
		return get(runId);
	}

	@Transactional
	public QueryRun updateRecoveryPayload(String runId, String recoveryPayload) {
		if (recoveryPayload == null || recoveryPayload.isBlank()) {
			throw new IllegalArgumentException("recoveryPayload is required");
		}
		QueryRun current = repository.lock(runId);
		if (current.terminal()) {
			throw new IllegalStateException("Terminal run recovery payload cannot be changed");
		}
		if (Objects.equals(current.recoveryPayload(), recoveryPayload)) {
			return current;
		}
		if (repository.updateRecoveryPayload(runId, current.revision(), recoveryPayload) != 1) {
			throw conflict(runId);
		}
		return get(runId);
	}

	@Transactional
	public QueryRun persistHumanFeedbackRecovery(String runId, String recoveryPayload, String eventPayload,
			String idempotencyKey) {
		if (recoveryPayload == null || recoveryPayload.isBlank()) {
			throw new IllegalArgumentException("recoveryPayload is required");
		}
		String eventKey = commandKey("human-feedback", idempotencyKey);
		String boundEventPayload = feedbackBindingPayload(eventPayload, recoveryPayload);
		RunEvent existing = repository.findEventByIdempotency(runId, eventKey).orElse(null);
		if (existing != null) {
			QueryRun current = get(runId);
			if (!Objects.equals(current.recoveryPayload(), recoveryPayload)) {
				throw new IllegalArgumentException(
						"Human feedback idempotencyKey is already bound to a different recovery command");
			}
			assertSameEvent(existing, "HUMAN_FEEDBACK_ANSWERED", "HUMAN_FEEDBACK_NODE", boundEventPayload,
					"Human plan feedback received");
			return current;
		}
		updateRecoveryPayload(runId, recoveryPayload);
		appendEvent(runId, "HUMAN_FEEDBACK_ANSWERED", "HUMAN_FEEDBACK_NODE", boundEventPayload,
				"Human plan feedback received", eventKey);
		return get(runId);
	}

	public List<RunEvent> events(String runId, long afterSequence, int limit) {
		get(runId);
		return repository.events(runId, Math.max(0, afterSequence), limit);
	}

	public RunEvent latestEvent(String runId, String eventType) {
		get(runId);
		return repository.latestEvent(runId, eventType)
			.orElseThrow(() -> new IllegalArgumentException("Run event not found: " + runId + "/" + eventType));
	}

	public List<String> outputNodeSequence(String runId) {
		get(runId);
		return repository.outputNodeSequence(runId);
	}

	public List<String> outputNodeSequence(String runId, long afterSequence) {
		get(runId);
		return repository.outputNodeSequence(runId, afterSequence);
	}

	public Optional<RunEvent> eventByIdempotency(String runId, String idempotencyKey) {
		get(runId);
		return repository.findEventByIdempotency(runId, idempotencyKey);
	}

	public Flux<RunEvent> stream(String runId, long afterSequence) {
		get(runId);
		long initialSequence = Math.max(0, afterSequence);
		Sinks.Many<RunEvent> signalSink = liveEvents.computeIfAbsent(runId, ignored -> Sinks.many().replay().limit(1));
		return Flux.defer(() -> {
			AtomicLong cursor = new AtomicLong(initialSequence);
			AtomicInteger terminalPolls = new AtomicInteger();
			return durableEvents(runId, cursor, signalSink, terminalPolls).takeUntil(QueryRunStateMachine::terminalEvent)
				.doFinally(signal -> {
					try {
						if (get(runId).terminal()) {
							liveEvents.remove(runId, signalSink);
						}
					}
					catch (RuntimeException ignored) {
						// The run may have been deleted during shutdown; no live sink
						// cleanup is required.
					}
				});
		});
	}

	private Flux<RunEvent> durableEvents(String runId, AtomicLong cursor, Sinks.Many<RunEvent> signalSink,
			AtomicInteger terminalPolls) {
		return Flux.defer(() -> {
			List<RunEvent> batch = repository.events(runId, cursor.get(), 1000);
			if (!batch.isEmpty()) {
				terminalPolls.set(0);
				Flux<RunEvent> page = Flux.fromIterable(batch).doOnNext(event -> cursor.set(event.sequence()));
				return Flux.concat(page, durableEvents(runId, cursor, signalSink, terminalPolls));
			}
			QueryRun current = get(runId);
			if (current.terminal()) {
				if (hasPersistedTerminalEvent(current) || terminalPolls.incrementAndGet() >= 5) {
					return Flux.<RunEvent>empty();
				}
				return Mono.delay(Duration.ofMillis(200))
					.thenMany(durableEvents(runId, cursor, signalSink, terminalPolls));
			}
			terminalPolls.set(0);
			Mono<Long> wakeup = Flux
				.merge(signalSink.asFlux()
					.filter(event -> event.sequence() > cursor.get())
					.map(RunEvent::sequence)
					.take(1), Mono.delay(Duration.ofSeconds(1)))
				.next();
			return wakeup.thenMany(durableEvents(runId, cursor, signalSink, terminalPolls));
		});
	}

	@Transactional
	public RunEvent appendEvent(String runId, String eventType, String nodeName, String payload, String payloadSummary,
			String idempotencyKey) {
		return appendEvent(runId, null, eventType, nodeName, payload, payloadSummary, idempotencyKey);
	}

	/**
	 * Appends an event for a specific attempt. The attempt-aware form locks and validates the
	 * durable Run before allocating the event sequence, preventing a superseded Graph from
	 * appending snapshots or traces into a recovered Run.
	 */
	@Transactional
	public RunEvent appendEvent(String runId, String attemptId, String eventType, String nodeName, String payload,
			String payloadSummary, String idempotencyKey) {
		ReentrantLock eventLock = EVENT_LOCKS[Math.floorMod(runId.hashCode(), EVENT_LOCKS.length)];
		eventLock.lock();
		try {
			if (idempotencyKey == null || idempotencyKey.isBlank()) {
				throw new IllegalArgumentException("Run event idempotencyKey is required");
			}
			RunEvent existing = repository.findEventByIdempotency(runId, idempotencyKey).orElse(null);
			if (existing != null) {
				assertSameEvent(existing, eventType, nodeName, payload, payloadSummary);
				return existing;
			}
			QueryRun locked = repository.lock(runId);
			assertAttemptForEvent(locked, attemptId);
			existing = repository.findEventByIdempotency(runId, idempotencyKey).orElse(null);
			if (existing != null) {
				assertSameEvent(existing, eventType, nodeName, payload, payloadSummary);
				return existing;
			}
			QueryRunStateMachine.assertLateEventAllowed(locked, eventType);
			assertRuntimeDeadlineAllowsEvent(locked, eventType);
			long next = locked.lastEventSequence() + 1;
			RunEvent event = RunEvent.builder()
				.runId(runId)
				.sequence(next)
				.eventType(eventType)
				.nodeName(nodeName)
				.payload(payload)
				.payloadSummary(payloadSummary)
				.idempotencyKey(idempotencyKey)
				.build();
			if (repository.advanceSequenceLocked(runId, next, nodeName) != 1) {
				RunEvent concurrent = repository.findEventByIdempotency(runId, idempotencyKey).orElse(null);
				if (concurrent != null) {
					assertSameEvent(concurrent, eventType, nodeName, payload, payloadSummary);
					return concurrent;
				}
				throw conflict(runId);
			}
			repository.insertEvent(event);
			RunEvent persisted = repository.findEventByIdempotency(runId, idempotencyKey).orElseThrow();
			publishAfterCommit(persisted);
			return persisted;
		}
		finally {
			eventLock.unlock();
		}
	}

	private void assertAttemptForEvent(QueryRun run, String attemptId) {
		if (attemptId == null || attemptId.isBlank()) {
			return;
		}
		if (run.status() != RunStatus.RUNNING || !Objects.equals(run.attemptId(), attemptId)
				|| (StringUtils.hasText(run.ownerInstance()) && !Objects.equals(run.ownerInstance(), instanceId))
				|| (run.leaseExpireTime() != null
						&& System.currentTimeMillis() >= run.leaseExpireTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
							.toEpochMilli())) {
			throw new LateRunResultDroppedException("Late Run event dropped for run=" + run.runId() + ", attempt="
					+ attemptId + "; currentStatus=" + run.status() + ", currentAttempt=" + run.attemptId());
		}
		if (run.deadlineEpochMillis() != null && System.currentTimeMillis() >= run.deadlineEpochMillis()) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted before event " + run.runId());
		}
	}

	@Transactional
	public QueryRun bindExecution(String runId, String episodeId, String attemptId, String threadId) {
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.RUNNING && episodeId.equals(current.episodeId())
				&& attemptId.equals(current.attemptId())) {
			return current;
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.RUNNING,
				"Only a QUEUED run can bind a new execution; current status=" + current.status());
		if (repository.bindExecution(runId, current.revision(), episodeId, attemptId, threadId) != 1) {
			throw conflict(runId);
		}
		return get(runId);
	}

	@Transactional
	public QueryRun transition(String runId, RunStatus target, String currentNode, String errorCode,
			String errorMessage) {
		return transition(runId, null, target, currentNode, errorCode, errorMessage);
	}

	/**
	 * Transitions a Run on behalf of a specific execution attempt. Graph callbacks must use
	 * this overload so an old blocking attempt cannot complete a recovered attempt that has
	 * already been bound to the same durable Run.
	 */
	@Transactional
	public QueryRun transition(String runId, String attemptId, RunStatus target, String currentNode, String errorCode,
			String errorMessage) {
		QueryRun current = repository.lock(runId);
		assertAttemptForTransition(current, attemptId);
		if (current.status() == target) {
			return current;
		}
		assertRuntimeDeadlineAllowsTransition(current, target);
		QueryRunStateMachine.assertTransition(current.status(), target);
		LocalDateTime finishTime = QueryRunStateMachine.terminal(target) ? LocalDateTime.now() : null;
		if (repository.updateStatus(runId, current.revision(), target, currentNode, errorCode, errorMessage,
				finishTime) != 1) {
			throw conflict(runId);
		}
		QueryRun updated = get(runId);
		if (QueryRunStateMachine.terminal(target)) {
			recordTerminalAfterCommit(updated);
		}
		return updated;
	}

	private static void assertAttemptForTransition(QueryRun current, String attemptId) {
		if (attemptId == null || attemptId.isBlank()) {
			return;
		}
		if (!Objects.equals(current.attemptId(), attemptId)) {
			throw new LateRunResultDroppedException("Late Run transition dropped for run=" + current.runId()
					+ "; currentAttempt=" + current.attemptId() + ", callbackAttempt=" + attemptId);
		}
	}

	private static void assertRuntimeDeadlineAllowsEvent(QueryRun run, String eventType) {
		if (run.runType() != RunType.INTERACTIVE_QUERY || run.status() != RunStatus.RUNNING
				|| run.deadlineEpochMillis() == null || System.currentTimeMillis() < run.deadlineEpochMillis()) {
			return;
		}
		if ("RUN_FAILED".equals(eventType) || "RUN_CANCELLED".equals(eventType) || "RUN_EXPIRED".equals(eventType)) {
			return;
		}
		throw new RunDeadlineExceededException("Interactive Run deadline exhausted before event " + eventType);
	}

	private static void assertRuntimeDeadlineAllowsTransition(QueryRun run, RunStatus target) {
		if (run.runType() != RunType.INTERACTIVE_QUERY || run.status() != RunStatus.RUNNING
				|| run.deadlineEpochMillis() == null || System.currentTimeMillis() < run.deadlineEpochMillis()) {
			return;
		}
		if (target == RunStatus.FAILED || target == RunStatus.CANCEL_REQUESTED || target == RunStatus.CANCELLED) {
			return;
		}
		throw new RunDeadlineExceededException("Interactive Run deadline exhausted before transition to " + target);
	}

	@Transactional
	public QueryRun failExecutionSnapshotMismatch(String runId, String errorMessage) {
		QueryRun current = repository.lock(runId);
		if (current.terminal() || current.status() == RunStatus.CANCEL_REQUESTED) {
			return current;
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.FAILED);
		if (repository.updateStatus(runId, current.revision(), RunStatus.FAILED, current.currentNode(),
				"EXECUTION_SNAPSHOT_MISMATCH", errorMessage, LocalDateTime.now()) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, "RUN_FAILED", current.currentNode(), null,
				"Execution snapshot is incompatible with the current runtime",
				"run-failed:execution-snapshot-mismatch:" + runId);
		QueryRun failed = get(runId);
		recordTerminalAfterCommit(failed);
		return failed;
	}

	@Transactional
	public QueryRun cancel(String runId, String idempotencyKey) {
		String key = commandKey("cancel", idempotencyKey);
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.CANCELLED || current.status() == RunStatus.CANCEL_REQUESTED) {
			return current;
		}
		if (current.terminal()) {
			return current;
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.CANCEL_REQUESTED);
		if (repository.updateStatus(runId, current.revision(), RunStatus.CANCEL_REQUESTED, current.currentNode(), null,
				null, null) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, "CANCEL_REQUESTED", current.currentNode(), null, "Cancellation requested", key);
		return get(runId);
	}

	@Transactional
	public QueryRun acknowledgeCancelled(String runId) {
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.CANCELLED) {
			return current;
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.CANCELLED,
				"Run has no pending cancellation request");
		if (repository.updateStatus(runId, current.revision(), RunStatus.CANCELLED, current.currentNode(), null, null,
				LocalDateTime.now()) != 1) {
			throw conflict(runId);
		}
		QueryRun cancelled = get(runId);
		recordTerminalAfterCommit(cancelled);
		return cancelled;
	}

@Transactional
	public QueryRun requeueForReplan(String runId, String idempotencyKey) {
		String key = commandKey("replan", idempotencyKey);
		if (repository.findEventByIdempotency(runId, key).isPresent()) {
			return get(runId);
		}
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.CANCEL_REQUESTED || current.status() == RunStatus.CANCELLED) {
			return current;
		}
		if (current.status() == RunStatus.QUEUED) {
			appendEvent(runId, "REPLAN_QUEUED", current.currentNode(), null, "Rejected plan queued for regeneration",
					key);
			return get(runId);
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.QUEUED,
				"Only RUNNING, WAITING_HUMAN or FAILED runs can be queued for re-plan");
		if (repository.requeueForReplan(runId, current.revision(), current.currentNode()) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, "REPLAN_QUEUED", current.currentNode(), null, "Rejected plan queued for regeneration", key);
		return get(runId);
	}

	@Transactional
	public QueryRun requeueForEntryReplay(String runId, String idempotencyKey) {
		String key = commandKey("entry-replay", idempotencyKey);
		if (repository.findEventByIdempotency(runId, key).isPresent()) {
			return get(runId);
		}
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.CANCEL_REQUESTED || current.status() == RunStatus.CANCELLED) {
			return current;
		}
		if (current.status() == RunStatus.QUEUED) {
			appendEvent(runId, "ENTRY_REPLAY_QUEUED", current.currentNode(), null, "Durable entry replay queued", key);
			return get(runId);
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.QUEUED,
				"Only RUNNING, WAITING_HUMAN or FAILED runs can be queued for entry replay");
		if (repository.requeueForReplan(runId, current.revision(), current.currentNode()) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, "ENTRY_REPLAY_QUEUED", current.currentNode(), null, "Durable entry replay queued", key);
		return get(runId);
	}

	@Transactional
	public QueryRun resume(String runId, String idempotencyKey) {
		String key = commandKey("resume", idempotencyKey);
		if (repository.findEventByIdempotency(runId, key).isPresent()) {
			return get(runId);
		}
		QueryRun current = repository.lock(runId);
		if (current.status() == RunStatus.QUEUED || current.status() == RunStatus.RUNNING) {
			return current;
		}
		QueryRunStateMachine.assertTransition(current.status(), RunStatus.QUEUED,
				"Only WAITING_HUMAN or FAILED runs can be resumed");
		if (repository.updateStatus(runId, current.revision(), RunStatus.QUEUED, current.currentNode(), null, null,
				null) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, "RESUME_REQUESTED", current.currentNode(), null, "Run queued for resume", key);
		return get(runId);
	}

	@Transactional
	public QueryRun acquireLease(String runId) {
		QueryRun current = repository.lock(runId);
		LocalDateTime now = LocalDateTime.now();
		if (repository.acquireLease(runId, current.revision(), instanceId, now.plus(DEFAULT_LEASE), now) != 1) {
			throw new RunLeaseUnavailableException(runId);
		}
		return get(runId);
	}

	@Transactional
	public QueryRun renewLease(String runId) {
		QueryRun current = repository.lock(runId);
		if (repository.renewLease(runId, current.revision(), instanceId,
				LocalDateTime.now().plus(DEFAULT_LEASE)) != 1) {
			throw new IllegalStateException("Run lease cannot be renewed by this instance");
		}
		return get(runId);
	}

	@Transactional
	public void releaseLease(String runId) {
		QueryRun current = repository.lock(runId);
		if (instanceId.equals(current.ownerInstance())) {
			repository.clearLease(runId, current.revision(), instanceId);
		}
	}

	@Transactional
	public RunCheckpoint saveCheckpoint(String runId, String threadId, String currentNode, String stateJson,
			String completedNodeKeys) {
		repository.lock(runId);
		RunCheckpoint existing = repository.checkpoint(runId).orElse(null);
		RunCheckpoint value = RunCheckpoint.builder()
			.runId(runId)
			.threadId(threadId)
			.currentNode(currentNode)
			.stateJson(stateJson)
			.completedNodeKeys(
					mergeCompletedNodeKeys(existing == null ? null : existing.completedNodeKeys(), completedNodeKeys))
			.build();
		if (existing == null) {
			repository.insertCheckpoint(value);
		}
		else if (repository.updateCheckpoint(value, existing.revision()) != 1) {
			throw new OptimisticLockingFailureException("RunCheckpoint", runId,
					repository.checkpoint(runId).map(RunCheckpoint::revision).orElse(-1L));
		}
		return repository.checkpoint(runId).orElseThrow();
	}

	public RunCheckpoint checkpoint(String runId) {
		return repository.checkpoint(runId)
			.orElseThrow(() -> new IllegalArgumentException("Run checkpoint not found: " + runId));
	}

	private static String mergeCompletedNodeKeys(String existing, String additional) {
		Set<String> keys = new LinkedHashSet<>();
		addCompletedNodeKeys(keys, existing);
		addCompletedNodeKeys(keys, additional);
		return String.join("\n", keys);
	}

	private static void addCompletedNodeKeys(Set<String> keys, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		for (String key : value.split("[\\r\\n,]+")) {
			if (!key.isBlank()) {
				keys.add(key.trim());
			}
		}
	}

	public List<QueryRun> recoverable() {
		return repository.recoverable(LocalDateTime.now(), 200);
	}

	/**
	 * Fails an overdue interactive Run while holding its durable row lock. This closes the
	 * recovery gap where a process dies after the Graph deadline signal but before its normal
	 * error callback can persist RUN_FAILED.
	 */
	@Transactional
	public QueryRun failIfDeadlineExceeded(String runId) {
		QueryRun current = repository.lock(runId);
		if (current.runType() != RunType.INTERACTIVE_QUERY || current.terminal()
				|| current.deadlineEpochMillis() == null
				|| System.currentTimeMillis() < current.deadlineEpochMillis()) {
			return current;
		}
		if (current.status() == RunStatus.CANCEL_REQUESTED) {
			return current;
		}
		RunStatus target = current.status() == RunStatus.WAITING_HUMAN ? RunStatus.EXPIRED : RunStatus.FAILED;
		QueryRunStateMachine.assertTransition(current.status(), target);
		if (repository.updateStatus(runId, current.revision(), target, current.currentNode(),
				"RUN_DEADLINE_EXCEEDED", "Interactive Run exceeded its absolute execution deadline", LocalDateTime.now()) != 1) {
			throw conflict(runId);
		}
		appendEvent(runId, target == RunStatus.EXPIRED ? "RUN_EXPIRED" : "RUN_FAILED", current.currentNode(), null,
				"Interactive Run exceeded its absolute execution deadline",
				"run-failed:deadline:" + runId + ":" + current.revision());
		QueryRun failed = get(runId);
		recordTerminalAfterCommit(failed);
		return failed;
	}

	public String instanceId() {
		return instanceId;
	}

	private void recordTerminalAfterCommit(QueryRun run) {
		metrics.afterCommit(() -> metrics.runTerminal(run));
	}

	private void publishAfterCommit(RunEvent event) {
		Runnable publish = () -> {
			Sinks.Many<RunEvent> sink = liveEvents.computeIfAbsent(event.runId(),
					ignored -> Sinks.many().replay().limit(1));
			sink.tryEmitNext(event);
			if (QueryRunStateMachine.terminalEvent(event)) {
				sink.tryEmitComplete();
				liveEvents.remove(event.runId(), sink);
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish.run();
			}
		});
	}

	private OptimisticLockingFailureException conflict(String runId) {
		return new OptimisticLockingFailureException("QueryRun", runId, get(runId).revision());
	}

	private static void assertSameEvent(RunEvent existing, String eventType, String nodeName, String payload,
			String payloadSummary) {
		if (!Objects.equals(existing.eventType(), eventType) || !Objects.equals(existing.nodeName(), nodeName)
				|| !Objects.equals(existing.payload(), payload)
				|| !Objects.equals(existing.payloadSummary(), payloadSummary)) {
			throw new IllegalArgumentException("Run event idempotencyKey is already bound to a different event");
		}
	}

	private static String feedbackBindingPayload(String eventPayload, String recoveryPayload) {
		try {
			Map<String, Object> binding = new LinkedHashMap<>();
			binding.put("answerPayload", Objects.toString(eventPayload, ""));
			binding.put("recoveryHash", sha256(recoveryPayload));
			return JsonUtil.getObjectMapper().writeValueAsString(binding);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to bind human feedback to its recovery command", ex);
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static String commandKey(String command, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey is required");
		}
		return command + ":" + idempotencyKey;
	}

	private boolean hasPersistedTerminalEvent(QueryRun run) {
		String eventType = switch (run.status()) {
			case SUCCEEDED -> "RUN_SUCCEEDED";
			case FAILED -> "RUN_FAILED";
			case CANCELLED -> "RUN_CANCELLED";
			case EXPIRED -> "RUN_EXPIRED";
			default -> null;
		};
		return eventType != null && repository.latestEvent(run.runId(), eventType).isPresent();
	}

	public record CreateRunCommand(RunType runType, Long projectId, Long projectVersionId, String threadId,
			String requestId, String idempotencyKey, String requestPayload, String executionSnapshot,
			Long deadlineEpochMillis) {

		public CreateRunCommand(RunType runType, Long projectId, Long projectVersionId, String threadId,
				String requestId, String idempotencyKey, String requestPayload) {
			this(runType, projectId, projectVersionId, threadId, requestId, idempotencyKey, requestPayload, null, null);
		}

		public CreateRunCommand(RunType runType, Long projectId, Long projectVersionId, String threadId,
				String requestId, String idempotencyKey, String requestPayload, String executionSnapshot) {
			this(runType, projectId, projectVersionId, threadId, requestId, idempotencyKey, requestPayload, executionSnapshot,
				 null);
		}
	}

}
