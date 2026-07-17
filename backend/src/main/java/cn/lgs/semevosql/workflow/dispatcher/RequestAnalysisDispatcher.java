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

import static cn.lgs.semevosql.constant.Constant.ACTIVE_TODO_ID;
import static cn.lgs.semevosql.constant.Constant.REQUEST_ANALYSIS;
import static cn.lgs.semevosql.constant.Constant.REQUEST_SYNTHESIS_NODE;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_PLAN_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

import cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis;
import cn.lgs.semevosql.task.QueryDecompositionService.RequestType;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

/** Routes the single request-analysis result without another model call. */
public class RequestAnalysisDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		RequestAnalysis analysis = StateUtil.getObjectValue(state, REQUEST_ANALYSIS, RequestAnalysis.class);
		return route(analysis, StateUtil.getStringValue(state, ACTIVE_TODO_ID, ""));
	}

	static String route(RequestAnalysis analysis, String activeTodoId) {
		if (analysis == null || analysis.requestType() == RequestType.NON_DATA_QUERY) {
			return END;
		}
		if (analysis.needsTodo() && (activeTodoId == null || activeTodoId.isBlank())) {
			return REQUEST_SYNTHESIS_NODE;
		}
		return SEMANTIC_PLAN_NODE;
	}
}
