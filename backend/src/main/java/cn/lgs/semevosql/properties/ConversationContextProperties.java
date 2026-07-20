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
package cn.lgs.semevosql.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token-aware, retrieval-aware conversation context policy for SemEvoSQL. */
@Getter
@Setter
@ConfigurationProperties(prefix = "semevosql.context")
public class ConversationContextProperties {

	/** Number of most recent successful turns retained verbatim as semantic units. */
	private int recentTurnCount = 6;

	/** Enable best-effort LLM compaction for older completed turns. */
	private boolean compressionEnabled = true;

	/** Trigger compaction when compressible history exceeds this budget ratio. */
	private double compressionTriggerRatio = 0.70D;

	/** Minimum number of older turns required before compaction is attempted. */
	private int minimumCompressibleTurns = 4;

	/** Maximum number of continuous turns compacted in one model call. */
	private int compressionMaxBatchTurns = 20;

	/** Maximum character count for the cumulative semantic summary. */
	private int compressedSummaryMaxChars = 300;

	/** Maximum number of historical corrections retained in the compacted summary. */
	private int compressedCorrectionMaxCount = 5;

	/** Maximum number of unresolved questions retained in the compacted summary. */
	private int compressedQuestionMaxCount = 3;

	/** Number of completed turns inspected when retrieving older relevant history. */
	private int retrievalCandidateCount = 50;

	/** Maximum number of older relevant turns included in one prompt context. */
	private int retrievedTurnCount = 3;

	/** Minimum lexical relevance score required for an older turn. */
	private double minRelevanceScore = 0.08D;

	/**
	 * Relevance boost when the current question contains an explicit historical
	 * reference.
	 */
	private double explicitReferenceBoost = 0.35D;

	/** Maximum approximate tokens for query-enhancement history. */
	private int queryEnhanceMaxTokens = 3000;

	/** Maximum approximate tokens for feasibility-assessment history. */
	private int feasibilityMaxTokens = 2200;

	/** Maximum approximate tokens for the generic compatibility view. */
	private int generalMaxTokens = 3000;

	/** Maximum rows inspected while creating a safe scalar result memory. */
	private int maxResultPreviewRows = 3;

	/** Maximum approximate tokens for the deterministic result memory. */
	private int maxResultSummaryTokens = 600;

	/** Maximum approximate tokens retained by the lightweight fallback turn summary. */
	private int fallbackPlannerMaxTokens = 500;

}
