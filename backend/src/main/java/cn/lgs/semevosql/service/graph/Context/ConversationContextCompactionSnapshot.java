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
package cn.lgs.semevosql.service.graph.Context;

import java.time.LocalDateTime;

/** Rebuildable cumulative projection over older completed conversation turns. */
public record ConversationContextCompactionSnapshot(String threadId, long coveredThroughSequence, String summaryJson,
		String sourceDigest, int summaryVersion, LocalDateTime createTime, LocalDateTime updateTime,
		long revision, boolean valid) {

	public ConversationContextCompactionSnapshot(String threadId, long coveredThroughSequence, String summaryJson,
			String sourceDigest, int summaryVersion, LocalDateTime createTime, LocalDateTime updateTime) {
		this(threadId, coveredThroughSequence, summaryJson, sourceDigest, summaryVersion, createTime, updateTime, 0, true);
	}
}
