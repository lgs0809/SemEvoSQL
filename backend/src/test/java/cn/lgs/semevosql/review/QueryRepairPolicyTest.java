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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import org.junit.jupiter.api.Test;

class QueryRepairPolicyTest {

	@Test
	void executionReplanConsumesBoundedReplanBudgetWithoutResettingSqlRepairs() {
		QueryRepairPolicy policy = new QueryRepairPolicy();
		policy.setMaxSemanticReplans(1);
		RepairBudget exhaustedSqlStrategy = new RepairBudget(2, 0, 0, 0, 0, 2);

		var decision = policy.consumeTransition(exhaustedSqlStrategy, Decision.REPLAN_EXECUTION);

		assertTrue(decision.allowed());
		assertEquals(2, decision.budget().sqlRepairsUsed());
		assertEquals(1, decision.budget().semanticReplansUsed());
		assertEquals(3, decision.budget().totalTransitions());
	}

	@Test
	void semanticRebindAndExecutionReplanShareOneDurableReplanBudget() {
		QueryRepairPolicy policy = new QueryRepairPolicy();
		policy.setMaxSemanticReplans(1);
		RepairBudget budget = RepairBudget.empty();

		var first = policy.consumeTransition(budget, Decision.REBIND_SEMANTIC);
		var second = policy.consumeTransition(first.budget(), Decision.REPLAN_EXECUTION);

		assertTrue(first.allowed());
		assertFalse(second.allowed());
		assertEquals("SEMANTIC_REPLAN_BUDGET_EXHAUSTED", second.reason());
	}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Decision.class,names={"RETRY_SQL","REPLAN_EXECUTION","RERETRIEVE","CLARIFY"})
    void eachRepairCategoryAllowsExactlyTwoAdditionalAttempts(Decision type) {
        var policy=new QueryRepairPolicy();var first=policy.consumeTransition(RepairBudget.empty(),type);
        var second=policy.consumeTransition(first.budget(),type);var third=policy.consumeTransition(second.budget(),type);
        assertTrue(first.allowed());assertTrue(second.allowed());assertFalse(third.allowed());
        assertEquals(2,third.budget().totalTransitions());
    }

    @Test void fourthCrossCategoryTransitionIsAllowedWithoutExtraGlobalCeiling() {
        var policy=new QueryRepairPolicy();var budget=RepairBudget.empty();
        for(var type:java.util.List.of(Decision.RETRY_SQL,Decision.RETRY_SQL,Decision.REBIND_SEMANTIC,Decision.RERETRIEVE)) {
            var next=policy.consumeTransition(budget,type);assertTrue(next.allowed());budget=next.budget();
        }
        assertEquals(4,budget.totalTransitions());assertEquals(2,budget.sqlRepairsUsed());
        var secondReplan=policy.consumeTransition(budget,Decision.REPLAN_EXECUTION);
        assertTrue(secondReplan.allowed());assertFalse(policy.consumeTransition(secondReplan.budget(),Decision.REBIND_SEMANTIC).allowed());
    }

    @Test void requiredRechecksAreObservableButNeverSpendRepairBudget() {
        var policy=new QueryRepairPolicy();var budget=new RepairBudget(2,2,2,0,2,8);
        var first=policy.consumeSemanticReview(budget);var second=policy.consumeSemanticReview(first.budget());
        assertTrue(first.allowed());assertTrue(second.allowed());assertEquals(2,second.budget().semanticReviewsUsed());
        assertEquals(8,second.budget().totalTransitions());assertEquals(2,second.budget().sqlRepairsUsed());
    }

    @Test void firstQuestionIsFreeAndReplayedQuestionDoesNotSpendAgain() {
        var old=new RepairBudget(1,1,0,2,0,2);
        assertEquals(old,QueryRepairPolicy.withClarificationsUsed(old,0));
        var additional=QueryRepairPolicy.withClarificationsUsed(old,1);
        assertEquals(1,additional.clarificationsUsed());assertEquals(3,additional.totalTransitions());
        assertEquals(additional,QueryRepairPolicy.withClarificationsUsed(additional,1));
    }
}
