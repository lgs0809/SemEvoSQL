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

import static cn.lgs.semevosql.constant.Constant.ACTIVE_QUERY;
import static cn.lgs.semevosql.constant.Constant.ACTIVE_TODO_ID;
import static cn.lgs.semevosql.constant.Constant.APPROVED_PLAN_RECOVERY;
import static cn.lgs.semevosql.constant.Constant.INPUT_KEY;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATION_ONLY;
import static cn.lgs.semevosql.constant.Constant.ORIGINAL_REQUEST;
import static cn.lgs.semevosql.constant.Constant.REQUEST_ANALYSIS;
import static cn.lgs.semevosql.constant.Constant.RESULT;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.TODO_ENABLED;

import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.task.QueryDecompositionService;
import cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis;
import cn.lgs.semevosql.task.QueryDecompositionService.RequestType;
import cn.lgs.semevosql.task.QueryTask;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Single request-level analysis node. It replaces the old separate intent + decomposition passes and only enables
 * Todo state when the user explicitly asks for multiple independent answer goals.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestAnalysisNode implements NodeAction {

	private final QueryDecompositionService queryDecompositionService;

	private final QueryTaskRepository taskRepository;

	private final QueryRunService runService;

	private final RunExecutionFenceService executionFence;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String original = StateUtil.getStringValue(state, INPUT_KEY, "");
		if (!StringUtils.hasText(original)) {
			throw new IllegalArgumentException("Request analysis requires the original query");
		}
		String runId = StateUtil.getStringValue(state, RUN_ID, null);
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, null);
		if (StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			executionFence.assertActive(runId, attemptId);
		}
		RequestAnalysis analysis = loadPersisted(runId);
		if (analysis == null) {
			// Internal source SQL generation and exact approved-plan recovery both represent one already-governed query
			// contract and must not pay another request-decomposition model call.
			analysis = state.value(SQL_GENERATION_ONLY, false) || state.value(APPROVED_PLAN_RECOVERY, false)
					? RequestAnalysis.simpleDataQuery() : queryDecompositionService.analyze(original);
			persist(runId, attemptId, analysis);
		}

		Map<String, Object> result = new HashMap<>();
		result.put(ORIGINAL_REQUEST, original);
		result.put(REQUEST_ANALYSIS, analysis);
		result.put(TODO_ENABLED, analysis.needsTodo());

		String activeQuery = original;
		if (analysis.needsTodo()) {
			assertActiveIfBound(runId, attemptId);
			if (!taskRepository.enabled(runId)) {
				taskRepository.initialize(runId, analysis.tasks());
			}
			if (taskRepository.allDone(runId)) {
				result.put(ACTIVE_TODO_ID, "");
			}
			else {
				assertActiveIfBound(runId, attemptId);
				QueryTask active = taskRepository.activateFirst(runId);
				result.put(ACTIVE_TODO_ID, active.taskId());
				activeQuery = active.question();
				assertActiveIfBound(runId, attemptId);
				persistTodoActivation(runId, attemptId, active);
			}
		}
		else {
			result.put(ACTIVE_TODO_ID, "");
		}
		result.put(ACTIVE_QUERY, activeQuery);
		result.put(INPUT_KEY, activeQuery);

		if (analysis.requestType() == RequestType.NON_DATA_QUERY) {
			result.put(RESULT, "当前 Project Chat 仅处理项目数据查询与分析请求。");
		}
		log.info("Request analysis completed: runId={}, type={}, todoEnabled={}, todoCount={}", runId,
				analysis.requestType(), analysis.needsTodo(), analysis.tasks().size());
		return result;
	}

	private void persistTodoActivation(String runId, String attemptId, QueryTask task) {
		if (!StringUtils.hasText(runId) || task == null) {
			return;
		}
		String idempotencyKey = "todo-activated:" + runId + ":" + task.taskId();
		if (runService.eventByIdempotency(runId, idempotencyKey).isPresent()) {
			return;
		}
		try {
			String payload = JsonUtil.getObjectMapper().writeValueAsString(
					Map.of("taskId", task.taskId(), "ordinal", task.ordinal(), "question", task.question()));
			runService.appendEvent(runId, attemptId, "TODO_ACTIVATED", "request-analysis", payload,
					"Query Todo active for request execution", idempotencyKey);
		}
		catch (Exception ex) {
			if (ex instanceof LateRunResultDroppedException || ex instanceof RunDeadlineExceededException) {
				throw (RuntimeException) ex;
			}
			throw new IllegalStateException("Unable to persist active Todo", ex);
		}
	}

	private void assertActiveIfBound(String runId, String attemptId) {
		if (StringUtils.hasText(runId) && StringUtils.hasText(attemptId)) {
			executionFence.assertActive(runId, attemptId);
		}
	}

	private RequestAnalysis loadPersisted(String runId) {
		if (!StringUtils.hasText(runId)) {
			return null;
		}
		try {
			var event = runService.eventByIdempotency(runId, "request-analysis:" + runId).orElse(null);
			if (event == null || !StringUtils.hasText(event.payload())) {
				return null;
			}
			return JsonUtil.getObjectMapper().readValue(event.payload(), RequestAnalysis.class);
		}
		catch (Exception ex) {
			log.warn("Unable to reuse persisted request analysis for run {}: {}", runId, ex.getMessage());
			return null;
		}
	}

	private void persist(String runId, String attemptId, RequestAnalysis analysis) {
		if (!StringUtils.hasText(runId)) {
			return;
		}
		try {
			String payload = JsonUtil.getObjectMapper().writeValueAsString(analysis);
				runService.appendEvent(runId, attemptId, "REQUEST_ANALYSIS_COMPLETED", "request-analysis", payload,
					analysis.needsTodo() ? "Multiple independent answer goals detected" : "Simple request fast path selected",
					"request-analysis:" + runId);
		}
		catch (Exception ex) {
			if (ex instanceof LateRunResultDroppedException || ex instanceof RunDeadlineExceededException) {
				throw (RuntimeException) ex;
			}
			throw new IllegalStateException("Unable to persist request analysis", ex);
		}
	}
}
