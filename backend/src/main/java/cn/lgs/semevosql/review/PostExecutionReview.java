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
package cn.lgs.semevosql.review;

import java.util.List;
import java.util.Set;

/** Durable, structured post-execution assessment. It intentionally contains no chain-of-thought. */
public record PostExecutionReview(Decision decision, IssueType issueType, double confidence,
		Set<String> suspectedAssetKeys, List<String> evidence, List<String> deterministicErrors,
		List<String> deterministicWarnings, boolean semanticReviewerUsed, ModelEvidence modelEvidence) {

	public PostExecutionReview {
		decision = decision == null ? Decision.FAIL : decision;
		issueType = issueType == null ? IssueType.REVIEW_INVALID : issueType;
		confidence = Math.max(0.0d, Math.min(1.0d, confidence));
		suspectedAssetKeys = Set.copyOf(suspectedAssetKeys == null ? Set.of() : suspectedAssetKeys);
		evidence = List.copyOf(evidence == null ? List.of() : evidence);
		deterministicErrors = List.copyOf(deterministicErrors == null ? List.of() : deterministicErrors);
		deterministicWarnings = List.copyOf(deterministicWarnings == null ? List.of() : deterministicWarnings);
	}

	public static PostExecutionReview deterministicPass(List<String> warnings) {
		return new PostExecutionReview(Decision.PASS, IssueType.NONE, 1.0d, Set.of(), List.of(), List.of(), warnings,
				false, null);
	}

	public static PostExecutionReview deterministicRetry(List<String> errors, List<String> warnings) {
		return new PostExecutionReview(Decision.RETRY_SQL, IssueType.RESULT_SHAPE_MISMATCH, 1.0d, Set.of(), errors,
				errors, warnings, false, null);
	}

	public enum Decision {
		PASS,
		RETRY_SQL,
		/** Semantic binding is still valid, but the current execution strategy should be rebuilt. */
		REPLAN_EXECUTION,
		/** The selected governed semantic assets themselves are materially suspect. */
		REBIND_SEMANTIC,
		/** Backward-compatible alias for persisted review payloads created before replan scopes were split. */
		REPLAN,
		RERETRIEVE,
		CLARIFY,
		FAIL
	}

	public enum IssueType {
		NONE,
		RESULT_SHAPE_MISMATCH,
		RESULT_DOMAIN_VIOLATION,
		RESULT_SEMANTIC_MISMATCH,
		SEMANTIC_BINDING_SUSPECTED,
		SQL_REPAIRABLE,
		RETRIEVAL_MISS,
		DEFINITION_GAP,
		AMBIGUITY,
		POLICY_FATAL,
		REVIEW_INVALID,
		REPAIR_BUDGET_EXHAUSTED,
		CLARIFICATION_UNAVAILABLE
	}

	public record ModelEvidence(String callId, long latencyMs, long inputTokens, long outputTokens) {
	}

}
