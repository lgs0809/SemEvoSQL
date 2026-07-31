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

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record QueryRun(String runId, RunType runType, Long projectId, Long projectVersionId, String episodeId,
		String attemptId, String threadId, RunStatus status, String currentNode, long lastEventSequence,
		String requestId, String idempotencyKey, String ownerInstance, LocalDateTime leaseExpireTime,
		LocalDateTime startTime, LocalDateTime finishTime, String errorCode, String errorMessage, long revision,
		String requestPayload, String recoveryPayload, String executionSnapshot, Long deadlineEpochMillis) {

	public enum RunStatus {

		QUEUED, RUNNING, WAITING_HUMAN, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED, EXPIRED

	}

	public enum RunType {

		INTERACTIVE_QUERY, EXTERNAL_MCP_QUERY, INITIALIZATION, MATERIAL_IMPORT, REPLAY, EVALUATION

	}

	public boolean terminal() {
		return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED
				|| status == RunStatus.EXPIRED;
	}

}
