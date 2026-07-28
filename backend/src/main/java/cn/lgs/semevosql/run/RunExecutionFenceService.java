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
						|| Objects.equals(current.ownerInstance(), runService.instanceId()));
		if (!active) {
			throw new LateRunResultDroppedException("Late Graph result dropped for run=" + runId + ", attempt="
					+ attemptId + "; currentStatus=" + current.status() + ", currentAttempt=" + current.attemptId());
		}
	}

	private void assertDeadline(Long deadlineEpochMillis) {
		if (deadlineEpochMillis != null && System.currentTimeMillis() >= deadlineEpochMillis) {
			throw new RunDeadlineExceededException("Interactive Run deadline exhausted");
		}
	}

	public record ExecutionToken(String runId, String attemptId, Long deadlineEpochMillis) {
	}
}
