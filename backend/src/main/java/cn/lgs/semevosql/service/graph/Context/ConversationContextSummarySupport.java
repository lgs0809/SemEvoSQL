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

import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import cn.lgs.semevosql.util.JsonUtil;

/** Shared tolerant reader for deterministic completed-turn summaries. */
final class ConversationContextSummarySupport {

	private ConversationContextSummarySupport() {
	}

	static ConversationTurnSummary read(ConversationTurn turn) {
		if (turn.contextSummaryJson() != null && !turn.contextSummaryJson().isBlank()) {
			try {
				return JsonUtil.getObjectMapper().readValue(turn.contextSummaryJson(), ConversationTurnSummary.class);
			}
			catch (Exception ignored) {
				// Incomplete durable summaries fall back to the persisted question and planner output.
			}
		}
		return ConversationTurnSummary.fallback(turn.userQuestion(), turn.plannerOutput());
	}

}
