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

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static cn.lgs.semevosql.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.PLANNER_NODE;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

@Slf4j
public class FeasibilityAssessmentDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		Object typedPlan = state.value(TYPED_SEMANTIC_PLAN).orElse(null);
		if (typedPlan instanceof SemanticBlueprint plan && plan.isExecutable()) {
			log.info("[FeasibilityAssessmentNodeDispatcher]可执行 Semantic Blueprint 已建立，进入PlannerNode节点");
			return PLANNER_NODE;
		}

		// value的值是和 resources/feasibility-assessment.txt的输出一致，例如
		// 【需求类型】：《数据分析》
		// 【语种类型】：《中文》
		// 【需求内容】：查询所有“核心用户”的数量
		String value = state.value(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, END);

		if (value != null && value.contains("【需求类型】：《数据分析》")) {
			log.info("[FeasibilityAssessmentNodeDispatcher]需求类型为数据分析，进入PlannerNode节点");
			return PLANNER_NODE;
		}
		else {
			log.info("[FeasibilityAssessmentNodeDispatcher]需求类型非数据分析，返回END节点");
			return END;
		}
	}

}
