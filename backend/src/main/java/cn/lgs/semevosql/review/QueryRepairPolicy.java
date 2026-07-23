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

import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Central bounded-repair policy. The budget object is stored in durable graph state. */
@Component
@ConfigurationProperties(prefix = "semevosql.repair-policy")
@Getter
@Setter
public class QueryRepairPolicy {

	private int maxSqlRepairs = 2;

	private int maxSemanticReplans = 2;

	private int maxRetrievalRepairs = 2;

	private int maxClarifications = 2;

	public BudgetDecision consumeSemanticReview(RepairBudget current) {
		RepairBudget budget = normalize(current);
		return BudgetDecision.allowed(new RepairBudget(budget.sqlRepairsUsed(), budget.semanticReplansUsed(),
				budget.retrievalRepairsUsed(), budget.semanticReviewsUsed() + 1, budget.clarificationsUsed(),
				budget.totalTransitions()), "SEMANTIC_REVIEW_CONSUMED");
	}

	public BudgetDecision consumeTransition(RepairBudget current, Decision decision) {
		RepairBudget budget = normalize(current);
		if (decision == null || decision == Decision.PASS || decision == Decision.FAIL) {
			return BudgetDecision.allowed(budget, "NO_REPAIR_TRANSITION");
		}
		return switch (decision) {
			case RETRY_SQL -> budget.sqlRepairsUsed() >= Math.max(0, maxSqlRepairs)
					? BudgetDecision.rejected(budget, "SQL_REPAIR_BUDGET_EXHAUSTED")
					: BudgetDecision.allowed(new RepairBudget(budget.sqlRepairsUsed() + 1,
							budget.semanticReplansUsed(), budget.retrievalRepairsUsed(), budget.semanticReviewsUsed(),
							budget.clarificationsUsed(), budget.totalTransitions() + 1), "SQL_REPAIR_CONSUMED");
			case REPLAN_EXECUTION, REBIND_SEMANTIC, REPLAN -> budget.semanticReplansUsed() >= Math.max(0, maxSemanticReplans)
					? BudgetDecision.rejected(budget, "SEMANTIC_REPLAN_BUDGET_EXHAUSTED")
					: BudgetDecision.allowed(new RepairBudget(budget.sqlRepairsUsed(), budget.semanticReplansUsed() + 1,
							budget.retrievalRepairsUsed(), budget.semanticReviewsUsed(), budget.clarificationsUsed(),
							budget.totalTransitions() + 1), "SEMANTIC_REPLAN_CONSUMED");
			case RERETRIEVE -> budget.retrievalRepairsUsed() >= Math.max(0, maxRetrievalRepairs)
					? BudgetDecision.rejected(budget, "RETRIEVAL_REPAIR_BUDGET_EXHAUSTED")
					: BudgetDecision.allowed(new RepairBudget(budget.sqlRepairsUsed(), budget.semanticReplansUsed(),
							budget.retrievalRepairsUsed() + 1, budget.semanticReviewsUsed(), budget.clarificationsUsed(),
							budget.totalTransitions() + 1), "RETRIEVAL_REPAIR_CONSUMED");
			case CLARIFY -> budget.clarificationsUsed() >= Math.max(0, maxClarifications)
					? BudgetDecision.rejected(budget, "CLARIFICATION_BUDGET_EXHAUSTED")
					: BudgetDecision.allowed(new RepairBudget(budget.sqlRepairsUsed(), budget.semanticReplansUsed(),
							budget.retrievalRepairsUsed(), budget.semanticReviewsUsed(), budget.clarificationsUsed() + 1,
							budget.totalTransitions() + 1), "CLARIFICATION_CONSUMED");
			default -> BudgetDecision.allowed(budget, "NO_REPAIR_TRANSITION");
		};
	}

    /** The database owns the shared first-question + two-additional-questions count. */
    public static RepairBudget withClarificationsUsed(RepairBudget current, int additionalQuestions) {
        RepairBudget budget = current == null ? RepairBudget.empty() : current;
        int used = Math.max(0, additionalQuestions);
        return new RepairBudget(budget.sqlRepairsUsed(), budget.semanticReplansUsed(), budget.retrievalRepairsUsed(),
            budget.semanticReviewsUsed(), used,
            budget.totalTransitions() + Math.max(0, used - budget.clarificationsUsed()));
    }

	public RepairBudget normalize(RepairBudget budget) {
		return budget == null ? RepairBudget.empty() : budget;
	}

	public record RepairBudget(int sqlRepairsUsed, int semanticReplansUsed, int retrievalRepairsUsed,
			int semanticReviewsUsed, int clarificationsUsed, int totalTransitions) {
		/** Backward-compatible constructor for persisted/test payloads created before retrieval repair existed. */
		public RepairBudget(int sqlRepairsUsed, int semanticReplansUsed, int semanticReviewsUsed, int clarificationsUsed,
				int totalTransitions) {
			this(sqlRepairsUsed, semanticReplansUsed, 0, semanticReviewsUsed, clarificationsUsed, totalTransitions);
		}

		public static RepairBudget empty() {
			return new RepairBudget(0, 0, 0, 0, 0, 0);
		}
	}

	public record BudgetDecision(boolean allowed, RepairBudget budget, String reason) {
		static BudgetDecision allowed(RepairBudget budget, String reason) {
			return new BudgetDecision(true, budget, reason);
		}

		static BudgetDecision rejected(RepairBudget budget, String reason) {
			return new BudgetDecision(false, budget, reason);
		}
	}

}
