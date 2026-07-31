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

import static cn.lgs.semevosql.constant.Constant.ORIGINAL_REQUEST;
import static cn.lgs.semevosql.constant.Constant.RESULT;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;

import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.task.GroundedRequestSynthesisService;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Final grounded aggregation for an in-run multi-Todo request. */
@Component
@RequiredArgsConstructor
public class RequestSynthesisNode implements NodeAction {

	private final GroundedRequestSynthesisService synthesisService;

	private final QueryRunService runService;

	private final RunExecutionFenceService executionFence;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		if (!runId.isBlank() && !attemptId.isBlank()) {
			executionFence.assertActive(runId, attemptId);
		}
		String original = StateUtil.getStringValue(state, ORIGINAL_REQUEST, StateUtil.getStringValue(state, "input", ""));
		String synthesis = synthesisService.synthesize(runId, original);
		runService.appendEvent(runId, attemptId, "REQUEST_SYNTHESIS", "request-synthesis", synthesis,
				"Grounded request synthesis completed", "request-synthesis:" + runId);
		return Map.of(RESULT, synthesis);
	}
}
