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

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 */
@Slf4j
public class SemanticConsistenceDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		Boolean validate = (Boolean) state.value(SEMANTIC_CONSISTENCY_NODE_OUTPUT).orElse(false);
		log.info("语义一致性校验结果: {}，跳转节点配置", validate);
		if (validate) {
			log.info("语义一致性校验通过，跳转到SQL运行节点。");
			return SQL_EXECUTE_NODE;
		}
		else {
			String replanFeedback = state.value(PLAN_VALIDATION_ERROR, "");
			if (replanFeedback.startsWith("EXECUTION_REPLAN_REQUIRED:")) {
				log.info("语义SQL局部修复预算耗尽，保留语义绑定并回到Planner重新规划执行策略。");
				return PLANNER_NODE;
			}
			log.info("语义一致性校验未通过，跳转到SQL生成节点进行有界局部修复。");
			return SQL_GENERATE_NODE;
		}
	}

}
