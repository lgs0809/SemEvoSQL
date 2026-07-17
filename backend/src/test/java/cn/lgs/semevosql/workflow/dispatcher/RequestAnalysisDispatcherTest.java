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
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis;
import cn.lgs.semevosql.task.QueryDecompositionService.RequestType;
import cn.lgs.semevosql.task.QueryTask;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestAnalysisDispatcherTest {

	@Test
	void completedTodoRequestRoutesDirectlyToSynthesisWhenNoTaskIsActive() {
		RequestAnalysis analysis = todoAnalysis();
		assertEquals(REQUEST_SYNTHESIS_NODE, RequestAnalysisDispatcher.route(analysis, ""));
		assertEquals(REQUEST_SYNTHESIS_NODE, RequestAnalysisDispatcher.route(analysis, null));
	}

	@Test
	void activeTodoAndNonDataRequestsKeepTheirExistingRoutes() {
		assertEquals(SEMANTIC_PLAN_NODE, RequestAnalysisDispatcher.route(todoAnalysis(), "task-2"));
		assertEquals(END,
				RequestAnalysisDispatcher.route(new RequestAnalysis(RequestType.NON_DATA_QUERY, false, List.of()), ""));
	}

	private RequestAnalysis todoAnalysis() {
		return new RequestAnalysis(RequestType.DATA_QUERY, true,
				List.of(new QueryTask("task-1", 0, "first", List.of(), null),
						new QueryTask("task-2", 1, "second", List.of("task-1"), null)));
	}

    @Test void semanticManagementRequiresConfirmationInsteadOfSilentlyEndingAsNonData() {
        assertEquals(SEMANTIC_PLAN_NODE,RequestAnalysisDispatcher.route(new RequestAnalysis(RequestType.SEMANTIC_UPDATE,false,List.of()),""));
    }

}
