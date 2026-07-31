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
import static cn.lgs.semevosql.constant.Constant.ADVANCED_EXECUTION_FALLBACK;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.APPROVAL_REQUIRED;
import static cn.lgs.semevosql.constant.Constant.FORCE_SEMANTIC_REPLAN;
import static cn.lgs.semevosql.constant.Constant.HUMAN_REVIEW_ENABLED;
import static cn.lgs.semevosql.constant.Constant.INPUT_KEY;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_RESULT_PAYLOAD;
import static cn.lgs.semevosql.constant.Constant.PLAN_CURRENT_STEP;
import static cn.lgs.semevosql.constant.Constant.POST_EXECUTION_REVIEW_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_HINT;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_QUERY;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_REPLAN_FEEDBACK;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTED_QUERY_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_COUNT;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_MEMORY_BY_STEP;
import static cn.lgs.semevosql.constant.Constant.TODO_BOUNDARY_DECISION;
import static cn.lgs.semevosql.constant.Constant.TODO_ENABLED;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;

import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.QueryTask;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.task.QueryTaskRepository.AcceptedFactSource;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiny task boundary used after Post-Execution Review PASS in Todo mode. The node never plans, executes, or generates
 * a per-task report; it persists the accepted typed result and either activates the next Todo or finishes the request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoBoundaryNode implements NodeAction {

	public static final String FINISH_SIMPLE = "FINISH_SIMPLE";
	public static final String NEXT_TODO = "NEXT_TODO";
	public static final String FINISH_TODOS = "FINISH_TODOS";

	private final QueryTaskRepository taskRepository;

	private final QueryRunService runService;

	private final RunExecutionFenceService executionFence;

	@Override
	@Transactional
	public Map<String, Object> apply(OverAllState state) {
		boolean todoEnabled = state.value(TODO_ENABLED, false);
		if (!todoEnabled) {
			return Map.of(TODO_BOUNDARY_DECISION, FINISH_SIMPLE);
		}

		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		if (!runId.isBlank() && !attemptId.isBlank()) {
			executionFence.assertActive(runId, attemptId);
		}
		QueryTask active = taskRepository.active(runId)
			.orElseThrow(() -> new IllegalStateException("Todo mode has no ACTIVE task for run " + runId));
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		PostExecutionReview review = StateUtil.getObjectValue(state, POST_EXECUTION_REVIEW_OUTPUT, PostExecutionReview.class,
				(PostExecutionReview) null);
		if (review == null || review.decision() != PostExecutionReview.Decision.PASS) {
			throw new IllegalStateException("Todo may advance only after Post-Execution Review PASS");
		}
		if (plan == null) {
			throw new IllegalStateException("Todo completion requires its Semantic Blueprint");
		}

		// Saving here is idempotent with the SemanticBlueprintNode persistence and guarantees synthesis has the final plan.
		taskRepository.savePlan(runId, active.taskId(), plan);
		Map<String, Object> accepted = new HashMap<>();
		accepted.put("report", "");
		accepted.put("resultPayload", StateUtil.getStringValue(state, LAST_SQL_RESULT_PAYLOAD, ""));
		taskRepository.saveAcceptedResult(runId, active.taskId(), accepted, review);
		persistAcceptedPlanFacts(runId, active.taskId(), plan);
		runService.appendEvent(runId, attemptId, "TODO_COMPLETED", "todo-boundary",
				json(Map.of("taskId", active.taskId(), "ordinal", active.ordinal())), "Query Todo completed after Review PASS",
				"todo-completed:" + runId + ":" + active.taskId());

		var next = taskRepository.nextRunnable(runId);
		if (next.isEmpty()) {
			if (!taskRepository.allDone(runId)) {
				throw new IllegalStateException("No runnable Todo remains but the request still has unfinished tasks");
			}
			return Map.of(TODO_BOUNDARY_DECISION, FINISH_TODOS, ACTIVE_TODO_ID, "");
		}

		QueryTask nextTask = next.orElseThrow();
		if (!runId.isBlank() && !attemptId.isBlank()) {
			executionFence.assertActive(runId, attemptId);
		}
		taskRepository.activate(runId, nextTask.taskId());
		runService.appendEvent(runId, attemptId, "TODO_ACTIVATED", "todo-boundary",
				json(Map.of("taskId", nextTask.taskId(), "ordinal", nextTask.ordinal())), "Next Query Todo activated",
				"todo-activated:" + runId + ":" + nextTask.taskId());

		Map<String, Object> update = resetForNextTask(nextTask, state.value(APPROVAL_REQUIRED, false));
		update.put(TODO_BOUNDARY_DECISION, NEXT_TODO);
		return update;
	}

	private Map<String, Object> resetForNextTask(QueryTask next, boolean approvalRequired) {
		Map<String, Object> update = new HashMap<>();
		update.put(ACTIVE_TODO_ID, next.taskId());
		update.put(ACTIVE_QUERY, next.question());
		update.put(INPUT_KEY, next.question());
		update.put(FORCE_SEMANTIC_REPLAN, false);
		update.put(ADVANCED_EXECUTION_FALLBACK, false);
		update.put(HUMAN_REVIEW_ENABLED, approvalRequired);
		update.put(SEMANTIC_REPLAN_FEEDBACK, "");
		update.put(RETRIEVAL_REPAIR_QUERY, "");
		update.put(RETRIEVAL_REPAIR_HINT, "");
		update.put(QUERY_REPAIR_BUDGET, RepairBudget.empty());
		update.put(SQL_GENERATE_COUNT, 0);
		update.put(SQL_RESULT_MEMORY_BY_STEP, Map.of());
		update.put(SQL_RESULT_LIST_MEMORY, new ArrayList<>());
		update.put(SQL_EXECUTE_NODE_OUTPUT, Map.of());
		update.put(SQL_EXECUTED_QUERY_OUTPUT, Map.of());
		update.put(LAST_SQL_RESULT_PAYLOAD, "");
		update.put(PLAN_CURRENT_STEP, 1);
		return update;
	}

	private void persistAcceptedPlanFacts(String runId, String taskId, SemanticBlueprint plan) {
		plan.getMetrics().forEach(metric -> taskRepository.addAcceptedFact(runId, taskId, "METRIC", metric.getMetricCode(),
				metric, AcceptedFactSource.REVIEW_PASS));
		plan.getDimensions().forEach(dimension -> taskRepository.addAcceptedFact(runId, taskId, "DIMENSION",
				dimension.getDimensionCode(), dimension, AcceptedFactSource.REVIEW_PASS));
		plan.getGrains().forEach(grain -> taskRepository.addAcceptedFact(runId, taskId, "GRAIN", grain.getGrainCode(), grain,
				AcceptedFactSource.REVIEW_PASS));
		plan.getRules().forEach(rule -> taskRepository.addAcceptedFact(runId, taskId, "RULE", rule.getRuleCode(), rule,
				AcceptedFactSource.REVIEW_PASS));
		if (plan.getTimeRange() != null) {
			taskRepository.addAcceptedFact(runId, taskId, "TIME",
					plan.getTimeRange().getModelCode() + ":" + plan.getTimeRange().getTimeColumn(), plan.getTimeRange(),
					AcceptedFactSource.REVIEW_PASS);
		}
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize Todo event", ex);
		}
	}
}
