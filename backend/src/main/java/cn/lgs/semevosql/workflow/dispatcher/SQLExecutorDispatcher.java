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

import cn.lgs.semevosql.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import cn.lgs.semevosql.util.StateUtil;
import lombok.extern.slf4j.Slf4j;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 */
@Slf4j
public class SQLExecutorDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String replanFeedback = StateUtil.getStringValue(state, PLAN_VALIDATION_ERROR, "");
		if (replanFeedback.startsWith("EXECUTION_REPLAN_REQUIRED:")) {
			log.warn("SQL repair budget exhausted; returning to PlannerNode for execution replanning.");
			return PLANNER_NODE;
		}
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class);
		if (retryDto.sqlExecuteFail()) {
			log.warn("SQL运行失败，需要重新生成！");
			return SQL_GENERATE_NODE;
		}
		else {
			log.info("SQL运行成功，进入PostExecutionReviewNode验收。");
			return POST_EXECUTION_REVIEW_NODE;
		}
	}

}
