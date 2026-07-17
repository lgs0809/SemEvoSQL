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
package cn.lgs.semevosql.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import lombok.RequiredArgsConstructor;
import cn.lgs.semevosql.util.StateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 * Human feedback node for plan review and modification.
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanFeedbackNode implements NodeAction {

	private final QueryRepairPolicy repairPolicy;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Map<String, Object> updated = new HashMap<>();

		// Retain the old count for observation only; the durable per-category budget owns admission.
		int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);

		Map<String, Object> feedbackData = StateUtil.getObjectValue(state, HUMAN_FEEDBACK_DATA, Map.class, Map.of());
		if (feedbackData.isEmpty()) {
			updated.put("human_next_node", "WAIT_FOR_FEEDBACK");
			return updated;
		}

		// 处理反馈结果
		Object approvedValue = feedbackData.getOrDefault("feedback", true);
		boolean approved = approvedValue instanceof Boolean approvedBoolean ? approvedBoolean
				: Boolean.parseBoolean(approvedValue.toString());

		if (approved) {
			log.info("Query understanding approved → governed semantic execution");
			updated.put("human_next_node", SEMANTIC_EXECUTION_NODE);
			updated.put(HUMAN_REVIEW_ENABLED, false);
			updated.put(APPROVED_PLAN_RECOVERY, false);
		}
		else {
			var current = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET,
					QueryRepairPolicy.RepairBudget.class, QueryRepairPolicy.RepairBudget.empty());
			var decision = repairPolicy.consumeTransition(current, Decision.REBIND_SEMANTIC);
			if (!decision.allowed()) {
				throw new IllegalStateException(decision.reason() + ": user requested another semantic re-plan");
			}
			updated.put(QUERY_REPAIR_BUDGET, decision.budget());
			log.info("Query understanding rejected → semantic re-plan (attempt {})", repairCount + 1);
			updated.put("human_next_node", SEMANTIC_PLAN_NODE);
			updated.put(PLAN_REPAIR_COUNT, repairCount + 1);
			updated.put(PLAN_CURRENT_STEP, 1);
			updated.put(HUMAN_REVIEW_ENABLED, true);
			updated.put(FORCE_SEMANTIC_REPLAN, true);

			String feedbackContent = feedbackData.getOrDefault("feedback_content", "").toString();
			String correction = StringUtils.hasLength(feedbackContent) ? feedbackContent : "Plan rejected by user";
			updated.put(PLAN_VALIDATION_ERROR, correction);
			updated.put(SEMANTIC_REPLAN_FEEDBACK, correction);
			// Clear the rejected execution plan and all derived parse/validation caches. The Semantic Blueprint
			// will be rebuilt by SemanticBlueprintNode from the original question plus the user's explicit correction.
			updated.put(PLANNER_NODE_OUTPUT, "");
			updated.put(PLAN_PARSED_OBJECT, null);
			updated.put(PLAN_PARSED_OUTPUT_HASH, "");
			updated.put(PLAN_VALIDATED_OUTPUT_HASH, "");
		}

		return updated;
	}

}
