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

import java.util.List;

/** Structured multi-turn context passed through durable graph state. */
public record ConversationContextEnvelope(int schemaVersion, ConversationState state, CompactedHistory compactedHistory,
		List<TurnView> recentTurns, List<TurnView> retrievedTurns, RetrievalReport retrieval) {

	public static final int CURRENT_SCHEMA_VERSION = 3;

	public ConversationContextEnvelope {
		state = state == null ? ConversationState.empty() : state;
		compactedHistory = compactedHistory == null ? CompactedHistory.empty() : compactedHistory;
		recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
		retrievedTurns = retrievedTurns == null ? List.of() : List.copyOf(retrievedTurns);
		retrieval = retrieval == null ? new RetrievalReport(0, 0, false) : retrieval;
	}

	public ConversationContextEnvelope(int schemaVersion, ConversationState state, List<TurnView> recentTurns,
			List<TurnView> retrievedTurns, RetrievalReport retrieval) {
		this(schemaVersion, state, CompactedHistory.empty(), recentTurns, retrievedTurns, retrieval);
	}

	public static ConversationContextEnvelope empty() {
		return new ConversationContextEnvelope(CURRENT_SCHEMA_VERSION, ConversationState.empty(),
				CompactedHistory.empty(), List.of(), List.of(), new RetrievalReport(0, 0, false));
	}

	public record CompactedHistory(long coveredThroughSequence, String summary, List<String> importantCorrections,
			List<String> unresolvedQuestions) {

		public CompactedHistory {
			summary = summary == null ? "" : summary;
			importantCorrections = importantCorrections == null ? List.of() : List.copyOf(importantCorrections);
			unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
		}

		public static CompactedHistory empty() {
			return new CompactedHistory(0L, "", List.of(), List.of());
		}

		public boolean isEmpty() {
			return summary.isBlank() && importantCorrections.isEmpty() && unresolvedQuestions.isEmpty();
		}
	}

	public record ConversationState(List<ConversationTurnSummary.AssetFact> models,
			List<ConversationTurnSummary.AssetFact> metrics, List<ConversationTurnSummary.AssetFact> dimensions,
			List<ConversationTurnSummary.FilterFact> filters, ConversationTurnSummary.TimeRangeFact timeRange,
			List<String> groupBy, List<ConversationTurnSummary.ClarificationFact> clarifications,
			ConversationTurnSummary.ResultFact lastResult) {

		public ConversationState {
			models = models == null ? List.of() : List.copyOf(models);
			metrics = metrics == null ? List.of() : List.copyOf(metrics);
			dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
			filters = filters == null ? List.of() : List.copyOf(filters);
			groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
			clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
		}

		public static ConversationState empty() {
			return new ConversationState(List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), null);
		}
	}

	public record TurnView(long sequence, String userQuestion, String canonicalQuery, ConversationTurnSummary summary,
			double relevanceScore, int storedTokenEstimate, String sourceRunId, Long sourceRevision) {

		/** Earlier checkpoints lack source identity; leave it unknown rather than looking up newer history. */
		public TurnView(long sequence, String userQuestion, String canonicalQuery, ConversationTurnSummary summary,
				double relevanceScore, int storedTokenEstimate) {
			this(sequence, userQuestion, canonicalQuery, summary, relevanceScore, storedTokenEstimate, null, null);
		}
	}

	public record RetrievalReport(int candidateCount, int selectedCount, boolean explicitHistoricalReference) {
	}

}
