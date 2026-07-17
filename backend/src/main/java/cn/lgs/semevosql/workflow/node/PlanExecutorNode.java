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

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import lombok.RequiredArgsConstructor;
import cn.lgs.semevosql.dto.planner.ExecutionStep;
import cn.lgs.semevosql.dto.planner.Plan;
import cn.lgs.semevosql.util.PlanProcessUtil;
import cn.lgs.semevosql.util.StateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 * Plan execution and validation node, decides next execution node based on plan, and
 * validates before execution.
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExecutorNode implements NodeAction {

	private final QueryRepairPolicy repairPolicy;

	// Supported node types
	private static final Set<String> SUPPORTED_NODES = Set.of(SQL_GENERATE_NODE, PYTHON_GENERATE_NODE,
			REPORT_GENERATOR_NODE);

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		PlanProcessUtil.PlanSnapshot snapshot;
		try {
			snapshot = PlanProcessUtil.resolvePlan(state);
		}
		catch (Exception e) {
			log.error("Plan validation failed due to a parsing error.", e);
			return buildValidationResult(state, Map.of(PLAN_VALIDATED_OUTPUT_HASH, ""),
					"Validation failed: The plan is not a valid JSON structure. Error: " + e.getMessage());
		}

		Plan plan = snapshot.plan();
		Map<String, Object> result = planCache(snapshot);
		String validatedHash = StateUtil.getStringValue(state, PLAN_VALIDATED_OUTPUT_HASH, "");
		boolean validationReused = snapshot.outputHash().equals(validatedHash)
				&& state.value(PLAN_VALIDATION_STATUS, false);
		if (!validationReused) {
			String validationError = validatePlan(plan);
			if (validationError != null) {
				result.put(PLAN_VALIDATED_OUTPUT_HASH, "");
				return buildValidationResult(state, result, validationError);
			}
			result.put(PLAN_VALIDATED_OUTPUT_HASH, snapshot.outputHash());
			log.info("Plan validation successful for hash {}", snapshot.outputHash());
		}
		else {
			log.debug("Reusing validated plan hash {}", snapshot.outputHash());
		}
		result.put(PLAN_VALIDATION_STATUS, true);
		// Once a replacement/initial plan validates, stale replan feedback must not leak into later steps or tasks.
		result.put(PLAN_VALIDATION_ERROR, "");

		Boolean humanReviewEnabled = state.value(HUMAN_REVIEW_ENABLED, false);
		if (Boolean.TRUE.equals(humanReviewEnabled)) {
			log.info("Human review enabled: routing to human_feedback node");
			result.put(PLAN_NEXT_NODE, HUMAN_FEEDBACK_NODE);
			return result;
		}

		int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		boolean sqlGenerationOnly = state.value(SQL_GENERATION_ONLY, false);
		if (currentStep < 1) {
			throw new IllegalStateException("Plan current step must be >= 1, actual=" + currentStep);
		}
		if (currentStep > executionPlan.size()) {
			log.info("Plan completed, current step: {}, total steps: {}", currentStep, executionPlan.size());
			result.put(PLAN_CURRENT_STEP, 1);
			result.put(PLAN_NEXT_NODE, sqlGenerationOnly ? StateGraph.END : REPORT_GENERATOR_NODE);
			return result;
		}

		ExecutionStep executionStep = executionPlan.get(currentStep - 1);
		result.putAll(determineNextNode(executionStep.getToolToUse()));
		return result;
	}

	private Map<String, Object> planCache(PlanProcessUtil.PlanSnapshot snapshot) {
		Map<String, Object> result = new HashMap<>();
		result.put(PLAN_PARSED_OBJECT, snapshot.plan());
		result.put(PLAN_PARSED_OUTPUT_HASH, snapshot.outputHash());
		return result;
	}

	String validatePlan(Plan plan) {
		if (!validateExecutionPlanStructure(plan)) {
			return "Validation failed: The generated plan is empty or has no execution steps.";
		}
		for (ExecutionStep step : plan.getExecutionPlan()) {
			String validationResult = validateExecutionStep(step);
			if (validationResult != null) {
				return validationResult;
			}
		}
		return null;
	}

	/**
	 * Determine the next node to execute
	 */
	private Map<String, Object> determineNextNode(String toolToUse) {
		if (SUPPORTED_NODES.contains(toolToUse)) {
			log.info("Determined next execution node: {}", toolToUse);
			return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
		}
		else if (HUMAN_FEEDBACK_NODE.equals(toolToUse)) {
			log.info("Determined next execution node: {}", toolToUse);
			return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
		}
		else {
			// This case should ideally not be reached if validation is done correctly
			// before.
			return Map.of(PLAN_VALIDATION_STATUS, false, PLAN_VALIDATION_ERROR, "Unsupported node type: " + toolToUse);
		}
	}

	/**
	 * Validate the execution plan structure
	 */
	private boolean validateExecutionPlanStructure(Plan plan) {
		return plan != null && plan.getExecutionPlan() != null && !plan.getExecutionPlan().isEmpty();
	}

	/**
	 * Validate a single execution step
	 * @return error message if validation fails, null if validation passes
	 */
	private String validateExecutionStep(ExecutionStep step) {
		// Validate tool name
		if (step.getToolToUse() == null || !SUPPORTED_NODES.contains(step.getToolToUse())) {
			return "Validation failed: Plan contains an invalid tool name: '" + step.getToolToUse() + "' in step "
					+ step.getStep();
		}

		// Validate tool parameters
		if (step.getToolParameters() == null) {
			return "Validation failed: Tool parameters are missing for step " + step.getStep();
		}

		// Validate specific parameters based on node type
		switch (step.getToolToUse()) {
			case SQL_GENERATE_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getInstruction())) {
					return "Validation failed: SQL generation node is missing description in step " + step.getStep();
				}
				break;

			case PYTHON_GENERATE_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getInstruction())) {
					return "Validation failed: Python generation node is missing instruction in step " + step.getStep();
				}
				break;

			case REPORT_GENERATOR_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getSummaryAndRecommendations())) {
					return "Validation failed: Report generation node is missing summary_and_recommendations in step "
							+ step.getStep();
				}
				break;

			default:
				// This should not happen due to the earlier validation
				break;
		}

		return null; // Validation passed
	}

	private Map<String, Object> buildValidationResult(OverAllState state, Map<String, Object> base,
			String errorMessage) {
		Map<String, Object> result = new HashMap<>(base);
		int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
		var current = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET,
				QueryRepairPolicy.RepairBudget.class, QueryRepairPolicy.RepairBudget.empty());
		var decision = repairPolicy.consumeTransition(current, Decision.REPLAN_EXECUTION);
		if (!decision.allowed()) {
			throw new IllegalStateException(decision.reason() + ": " + errorMessage);
		}
		result.put(QUERY_REPAIR_BUDGET, decision.budget());
		result.put(PLAN_VALIDATION_STATUS, false);
		result.put(PLAN_VALIDATION_ERROR, errorMessage);
		result.put(PLAN_REPAIR_COUNT, repairCount + 1);
		return result;
	}

}
