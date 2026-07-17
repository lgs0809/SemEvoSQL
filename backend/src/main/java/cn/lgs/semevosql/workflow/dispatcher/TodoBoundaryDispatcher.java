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

import static cn.lgs.semevosql.constant.Constant.REQUEST_SYNTHESIS_NODE;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_PLAN_NODE;
import static cn.lgs.semevosql.constant.Constant.TODO_BOUNDARY_DECISION;
import static cn.lgs.semevosql.workflow.node.TodoBoundaryNode.FINISH_SIMPLE;
import static cn.lgs.semevosql.workflow.node.TodoBoundaryNode.FINISH_TODOS;
import static cn.lgs.semevosql.workflow.node.TodoBoundaryNode.NEXT_TODO;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

/** Deterministic routing at the optional Todo boundary. */
public class TodoBoundaryDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String decision = StateUtil.getStringValue(state, TODO_BOUNDARY_DECISION, FINISH_SIMPLE);
		return switch (decision) {
			case NEXT_TODO -> SEMANTIC_PLAN_NODE;
			case FINISH_TODOS -> REQUEST_SYNTHESIS_NODE;
			case FINISH_SIMPLE -> END;
			default -> throw new IllegalStateException("Unsupported Todo boundary decision: " + decision);
		};
	}
}
