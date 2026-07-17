/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.*;
import static org.assertj.core.api.Assertions.*;

import cn.lgs.semevosql.dto.planner.Plan;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.workflow.dispatcher.PlanExecutorDispatcher;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanReviewRepairBudgetTest {
    private final QueryRepairPolicy policy = new QueryRepairPolicy();
    private final HumanFeedbackNode feedback = new HumanFeedbackNode(policy);
    private final PlanExecutorNode executor = new PlanExecutorNode(policy);

    @Test void approvalAndWaitingDoNotSpendRepairsOrObeyTheOldGlobalCounter() throws Exception {
        var spent = new RepairBudget(2, 2, 2, 3, 2, 8);
        var original = new HashMap<String, Object>(Map.of(PLAN_REPAIR_COUNT, 50, QUERY_REPAIR_BUDGET, spent));
        assertThat(feedback.apply(restored(original))).containsEntry("human_next_node", "WAIT_FOR_FEEDBACK")
            .doesNotContainKey(QUERY_REPAIR_BUDGET);
        original.put(HUMAN_FEEDBACK_DATA, Map.of("feedback", true));
        assertThat(feedback.apply(restored(original))).containsEntry("human_next_node", SEMANTIC_EXECUTION_NODE)
            .doesNotContainKey(QUERY_REPAIR_BUDGET);
    }

    @Test void userRebindAndInvalidExecutionPlanShareTwoAttemptsAcrossSerializedRecovery() throws Exception {
        var original = new HashMap<String, Object>(Map.of(PLAN_REPAIR_COUNT, 40,
            QUERY_REPAIR_BUDGET, new RepairBudget(2, 0, 2, 1, 2, 6),
            HUMAN_FEEDBACK_DATA, Map.of("feedback", false, "feedback_content", "改为下单时间")));
        var first = feedback.apply(restored(original));
        assertThat((RepairBudget) first.get(QUERY_REPAIR_BUDGET)).isEqualTo(new RepairBudget(2, 1, 2, 1, 2, 7));
        assertThat(first).containsEntry(PLAN_REPAIR_COUNT, 41).containsEntry(FORCE_SEMANTIC_REPLAN, true);
        original.putAll(first);
        original.put(PLANNER_NODE_OUTPUT, "{}");
        var second = executor.apply(restored(original));
        assertThat((RepairBudget) second.get(QUERY_REPAIR_BUDGET)).isEqualTo(new RepairBudget(2, 2, 2, 1, 2, 8));
        assertThat(new PlanExecutorDispatcher().apply(new OverAllState(second))).isEqualTo(PLANNER_NODE);
        original.putAll(second);
        assertThatThrownBy(() -> feedback.apply(restored(original))).hasMessageContaining("SEMANTIC_REPLAN_BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> executor.apply(restored(original))).hasMessageContaining("SEMANTIC_REPLAN_BUDGET_EXHAUSTED");
    }

    @Test void successfulAndCachedPlanValidationPreserveDurableRepairCounters() throws Exception {
        var spent = new RepairBudget(2, 2, 1, 4, 1, 6);
        var original = new HashMap<String, Object>(Map.of(PLANNER_NODE_OUTPUT, Plan.nl2SqlPlan(),
            PLAN_REPAIR_COUNT, 25, QUERY_REPAIR_BUDGET, spent, PLAN_CURRENT_STEP, 1));
        var first = executor.apply(restored(original));
        assertThat(first).containsEntry(PLAN_VALIDATION_STATUS, true).containsEntry(PLAN_NEXT_NODE, SQL_GENERATE_NODE)
            .doesNotContainKeys(PLAN_REPAIR_COUNT, QUERY_REPAIR_BUDGET);
        original.putAll(first);
        var second = executor.apply(restored(original));
        assertThat(second).doesNotContainKeys(PLAN_REPAIR_COUNT, QUERY_REPAIR_BUDGET);
        original.putAll(second);
        assertThat(restored(original).value(PLAN_REPAIR_COUNT)).contains(25);
        assertThat(cn.lgs.semevosql.util.StateUtil.getObjectValue(restored(original), QUERY_REPAIR_BUDGET,
            RepairBudget.class)).isEqualTo(spent);
    }

    @Test void parseFailureSpendsExecutionReplanBeforeRoutingInsteadOfAnIndependentCounterCeiling() throws Exception {
        var original = new HashMap<String, Object>(Map.of(PLAN_REPAIR_COUNT, 100));
        var first = executor.apply(restored(original));
        assertThat((RepairBudget) first.get(QUERY_REPAIR_BUDGET)).isEqualTo(new RepairBudget(0, 1, 0, 0, 0, 1));
        assertThat(first).containsEntry(PLAN_REPAIR_COUNT, 101).containsEntry(PLAN_VALIDATION_STATUS, false);
        assertThat(new PlanExecutorDispatcher().apply(new OverAllState(first))).isEqualTo(PLANNER_NODE);
    }

    @SuppressWarnings("unchecked")
    private static OverAllState restored(Map<String, Object> input) throws Exception {
        return new OverAllState(JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(input), Map.class));
    }
}
