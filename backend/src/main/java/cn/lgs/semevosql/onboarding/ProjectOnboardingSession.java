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
package cn.lgs.semevosql.onboarding;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record ProjectOnboardingSession(String sessionId, Long projectId, Long projectVersionId, SessionStatus status,
		boolean summaryConfirmed, String confirmedBy, String idempotencyKey, String confirmationIdempotencyKey,
		Long confirmationRevision, long revision, LocalDateTime createTime, LocalDateTime updateTime,
		LocalDateTime completeTime) {

	public enum SessionStatus {

		ACTIVE, AWAITING_CONFIRMATION, COMPLETED

	}

}
