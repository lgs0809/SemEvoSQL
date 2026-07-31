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

import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

/** Durable status/attempt/lease fence shared by every Graph node. */
@Service
public class RunExecutionFenceService {

	private final QueryRunService runService;

	public RunExecutionFenceService(QueryRunService runService) {
		this.runService = runService;
	}

	/**
	 * Checks the immutable execution identity carried by Graph state. States without a durable identity are kept
	 * compatible with isolated node tests and non-Run utilities.
	 */
	public ExecutionToken assertActive(OverAllState state) {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		Long deadline = StateUtil.getObjectValue(state, RUN_DEADLINE_EPOCH_MILLIS, Long.class, (Long) null);
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			return null;
		}
		assertDeadline(deadline);
		assertActive(runId, attemptId);
		return new ExecutionToken(runId, attemptId, deadline);
	}

	public void assertActive(ExecutionToken token) {
		if (token == null) {
			return;
		}
		assertDeadline(token.deadlineEpochMillis());
		assertActive(token.runId(), token.attemptId());
	}

	public void assertActive(String runId, String attemptId) {
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			throw new LateRunResultDroppedException("Durable Run/Attempt identity is unavailable");
		}
		QueryRun current = runService.get(runId);
		boolean active = current.status() == RunStatus.RUNNING && Objects.equals(current.attemptId(), attemptId)
				&& (!StringUtils.hasText(current.ownerInstance())
						|| Objects.equals(current.ownerInstance(), runService.instanceId()))
				&& leaseActive(current);
		if (!active) {
			throw new LateRunResultDroppedException("Late Graph result dropped for run=" + runId + ", attempt="
					+ attemptId + "; currentStatus=" + current.status() + ", currentAttempt=" + current.attemptId());
		}
		assertDeadline(current.deadlineEpochMillis());
	}

	/**
	 * Acquires the durable run row lock and checks its status, attempt and deadline in the same transaction as the
	 * caller's side effect. Callers that write an experience, artifact or node effect must use this entry point so a
	 * terminal transition cannot race the final write.
	 */
	@Transactional
	public void assertActiveAndLock(String runId, String attemptId) {
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			throw new LateRunResultDroppedException("Durable Run/Attempt identity is unavailable");
		}
		QueryRun current = runService.lockForUpdate(runId);
		boolean active = current.status() == RunStatus.RUNNING && Objects.equals(current.attemptId(), attemptId)
				&& (!StringUtils.hasText(current.ownerInstance())
						|| Objects.equals(current.ownerInstance(), runService.instanceId()))
				&& leaseActive(current);
		if (!active) {
			throw new LateRunResultDroppedException("Late Graph side effect dropped for run=" + runId + ", attempt="
					+ attemptId + "; currentStatus=" + current.status() + ", currentAttempt=" + current.attemptId());
		}
		assertDeadline(current.deadlineEpochMillis());
	}

	/**
	 * Locks a run for a post-attempt finalizer. The finalizer may run immediately after the Graph marks the run
	 * terminal, but it must still belong to the same attempt; a recovered/superseded attempt is rejected.
	 */
	@Transactional
	public void assertAttemptOwnsRunAndLock(String runId, String attemptId) {
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			throw new LateRunResultDroppedException("Durable Run/Attempt identity is unavailable");
		}
		QueryRun current = runService.lockForUpdate(runId);
		if (!Objects.equals(current.attemptId(), attemptId)
				|| current.status() == RunStatus.CANCEL_REQUESTED || current.status() == RunStatus.CANCELLED
				|| current.status() == RunStatus.EXPIRED || current.status() == RunStatus.QUEUED
				|| current.status() == RunStatus.WAITING_HUMAN) {
			throw new LateRunResultDroppedException("Late post-attempt side effect dropped for run=" + runId + ", attempt="
					+ attemptId + "; currentStatus=" + current.status() + ", currentAttempt=" + current.attemptId());
		}
		if (current.status() == RunStatus.RUNNING) {
			assertDeadline(current.deadlineEpochMillis());
		}
	}

	/**
	 * Locks a terminal Run for an explicit post-attempt finalizer such as trajectory analysis.
	 * This is deliberately separate from {@link #assertActiveAndLock(String, String)} so a
	 * late Graph node can never reuse a permissive terminal-finalizer check.
	 */
	@Transactional
	public void assertFinalizerOwnsRunAndLock(String runId, String attemptId) {
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(attemptId)) {
			throw new LateRunResultDroppedException("Durable Run/Attempt identity is unavailable");
		}
		QueryRun current = runService.lockForUpdate(runId);
		if (!current.terminal() || !Objects.equals(current.attemptId(), attemptId)) {
			throw new LateRunResultDroppedException("Late post-attempt finalizer dropped for run=" + runId
					+ "; currentStatus=" + current.status() + ", currentAttempt=" + current.attemptId());
		}
	}

	private void assertDeadline(Long deadlineEpochMillis) {
		if (deadlineEpochMillis != null && System.currentTimeMillis() >= deadlineEpochMillis) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted");
		}
	}

	private boolean leaseActive(QueryRun run) {
		return run.leaseExpireTime() == null || System.currentTimeMillis() < run.leaseExpireTime()
				.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	public record ExecutionToken(String runId, String attemptId, Long deadlineEpochMillis) {
	}
}
