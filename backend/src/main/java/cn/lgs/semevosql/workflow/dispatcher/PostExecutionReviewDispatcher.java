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
package cn.lgs.semevosql.workflow.dispatcher;

import static cn.lgs.semevosql.constant.Constant.PLAN_EXECUTOR_NODE;
import static cn.lgs.semevosql.constant.Constant.PLANNER_NODE;
import static cn.lgs.semevosql.constant.Constant.QUERY_ENHANCE_NODE;
import static cn.lgs.semevosql.constant.Constant.POST_EXECUTION_REVIEW_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.REPORT_GENERATOR_NODE;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_EXECUTION_DECISION;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_PLAN_NODE;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_NODE;
import static cn.lgs.semevosql.constant.Constant.TODO_BOUNDARY_NODE;
import static cn.lgs.semevosql.constant.Constant.TODO_ENABLED;
import static cn.lgs.semevosql.workflow.node.SemanticExecutionNode.EXECUTED;

import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.springframework.stereotype.Component;

@Component
public class PostExecutionReviewDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		PostExecutionReview review = StateUtil.getObjectValue(state, POST_EXECUTION_REVIEW_OUTPUT,
				PostExecutionReview.class, (PostExecutionReview) null);
		if (review == null) {
			throw new IllegalStateException("Post-execution review decision is missing");
		}
		boolean directSemanticExecution = EXECUTED
			.equals(StateUtil.getStringValue(state, SEMANTIC_EXECUTION_DECISION, ""));
		boolean todoEnabled = state.value(TODO_ENABLED, false);
		return switch (review.decision()) {
			case PASS -> directSemanticExecution ? (todoEnabled ? TODO_BOUNDARY_NODE : REPORT_GENERATOR_NODE)
					: PLAN_EXECUTOR_NODE;
			// A deterministic compiler result that still needs physical SQL repair falls back to the bounded
			// advanced execution path while retaining the same Semantic Blueprint.
			case RETRY_SQL -> directSemanticExecution ? QUERY_ENHANCE_NODE : SQL_GENERATE_NODE;
			case REPLAN_EXECUTION -> directSemanticExecution ? QUERY_ENHANCE_NODE : PLANNER_NODE;
			case REBIND_SEMANTIC, REPLAN, RERETRIEVE, CLARIFY -> SEMANTIC_PLAN_NODE;
			case FAIL -> throw new IllegalStateException("Post-execution review failed: " + review.issueType());
		};
	}

}
