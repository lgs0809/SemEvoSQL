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
package cn.lgs.semevosql.util;

import com.alibaba.cloud.ai.graph.OverAllState;
import cn.lgs.semevosql.dto.planner.ExecutionStep;
import cn.lgs.semevosql.dto.planner.Plan;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.PLANNER_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.PLAN_CURRENT_STEP;
import static cn.lgs.semevosql.constant.Constant.PLAN_PARSED_OBJECT;
import static cn.lgs.semevosql.constant.Constant.PLAN_PARSED_OUTPUT_HASH;

/**
 * util class for plan-based execution nodes Provides common functionality for nodes that
 * execute based on predefined plans
 *
 */
public final class PlanProcessUtil {

	private static final BeanOutputConverter<Plan> converter;

	private static final String STEP_PREFIX = "step_";

	static {
		converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
		});
	}

	private PlanProcessUtil() {

	}

	/**
	 * Get the current execution step from the plan
	 * @param state the overall state containing plan information
	 * @return the current execution step
	 * @throws IllegalStateException if plan output is empty, plan parsing fails, or step
	 * index is out of range
	 */
	public static ExecutionStep getCurrentExecutionStep(OverAllState state) {
		Plan plan = getPlan(state);
		int currentStep = getCurrentStepNumber(state);
		return getCurrentExecutionStep(plan, currentStep);
	}

	public static String getCurrentExecutionStepInstruction(OverAllState state) {
		String instruction;
		ExecutionStep.ToolParameters currentStepParams = PlanProcessUtil.getCurrentExecutionStep(state)
			.getToolParameters();
		instruction = currentStepParams != null ? currentStepParams.getInstruction() : "无";
		return instruction;
	}

	/**
	 * Get the current execution step from the plan
	 * @param plan the plan object
	 * @param currentStep current step
	 * @return the current execution step
	 * @throws IllegalStateException if plan output is empty, plan parsing fails, or step
	 * index is out of range
	 */
	public static ExecutionStep getCurrentExecutionStep(Plan plan, Integer currentStep) {
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		if (executionPlan == null || executionPlan.isEmpty()) {
			throw new IllegalStateException("执行计划为空");
		}

		int stepIndex = currentStep - 1;
		if (stepIndex < 0 || stepIndex >= executionPlan.size()) {
			throw new IllegalStateException("当前步骤索引超出范围: " + stepIndex);
		}

		return executionPlan.get(stepIndex);
	}

	/**
	 * Get the plan object from state
	 * @param state the overall state containing plan information
	 * @return the parsed plan object
	 * @throws IllegalStateException if plan output is empty or plan parsing fails
	 */
	public static Plan getPlan(OverAllState state) {
		return resolvePlan(state).plan();
	}

	public static PlanSnapshot resolvePlan(OverAllState state) {
		String plannerNodeOutput = state.value(PLANNER_NODE_OUTPUT)
			.map(String.class::cast)
			.filter(output -> !output.isBlank())
			.orElseThrow(() -> new IllegalStateException("计划节点输出为空"));
		String outputHash = hashPlanOutput(plannerNodeOutput);
		String cachedHash = StateUtil.getStringValue(state, PLAN_PARSED_OUTPUT_HASH, null);
		Plan cachedPlan = StateUtil.getObjectValue(state, PLAN_PARSED_OBJECT, Plan.class, (Plan) null);
		if (outputHash.equals(cachedHash) && cachedPlan != null) {
			return new PlanSnapshot(copyPlan(cachedPlan), outputHash, true);
		}
		Plan parsedPlan = converter.convert(plannerNodeOutput);
		if (parsedPlan == null) {
			throw new IllegalStateException("计划解析失败");
		}
		return new PlanSnapshot(parsedPlan, outputHash, false);
	}

	public static String hashPlanOutput(String plannerNodeOutput) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(plannerNodeOutput.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static Plan copyPlan(Plan plan) {
		return JsonUtil.getObjectMapper().convertValue(plan, Plan.class);
	}

	/**
	 * Get the current step number from state
	 * @param state the overall state
	 * @return the current step number (defaults to 1 if not set)
	 */
	public static int getCurrentStepNumber(OverAllState state) {
		return state.value(PLAN_CURRENT_STEP, 1);
	}

	/**
	 * Add step result
	 * @param existingResults existing result collection
	 * @param stepNumber step number
	 * @param result result content
	 * @return updated result collection
	 */
	public static Map<String, String> addStepResult(Map<String, String> existingResults, Integer stepNumber,
			String result) {
		Map<String, String> updatedResults = new HashMap<>(existingResults);
		updatedResults.put(STEP_PREFIX + stepNumber, result);
		return updatedResults;
	}

	public record PlanSnapshot(Plan plan, String outputHash, boolean reused) {
	}

}
