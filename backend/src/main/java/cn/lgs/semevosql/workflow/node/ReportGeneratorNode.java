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

import cn.lgs.semevosql.dto.planner.ExecutionStep;
import cn.lgs.semevosql.dto.planner.Plan;
import cn.lgs.semevosql.prompt.PromptHelper;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.enums.TextType;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.PlanProcessUtil;
import cn.lgs.semevosql.util.StateUtil;
import cn.lgs.semevosql.run.RunDeadlineUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 * Report generation node that creates comprehensive analysis reports based on execution
 * results.
 *
 * This node is responsible for: - Generating detailed analysis reports from SQL execution
 * results - Summarizing data insights and findings - Providing comprehensive answers to
 * user queries - Creating structured final output for users
 *
 */
@Slf4j
@Component
public class ReportGeneratorNode implements NodeAction {

	private final LlmService llmService;

	public ReportGeneratorNode(LlmService llmService) {
		this.llmService = llmService;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// Get necessary input parameters
		String userInput = StateUtil.getCanonicalQuery(state);
		Integer currentStep = StateUtil.getObjectValue(state, PLAN_CURRENT_STEP, Integer.class, 1);
		@SuppressWarnings("unchecked")
		Map<String, String> executionResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());
		@SuppressWarnings("unchecked")
		Map<String, String> executedQueries = StateUtil.getObjectValue(state, SQL_EXECUTED_QUERY_OUTPUT, Map.class,
				new HashMap<>());

		SemanticBlueprint typedPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		Plan plan = typedPlan == null ? PlanProcessUtil.getPlan(state) : null;
		ExecutionStep executionStep = typedPlan == null ? getCurrentExecutionStep(plan, currentStep) : null;
		String summaryAndRecommendations = typedPlan == null
				? executionStep.getToolParameters().getSummaryAndRecommendations()
				: "请只根据系统提供的已验证业务口径与实际执行结果回答，不补充未实际执行的过滤条件或业务规则。输出只使用用户可理解的业务名称，不展示内部计划、模型/指标代码、字段名、数据源 ID、SQL 或其它实现标识。";

		// Generate report streaming flux
		Flux<ChatResponse> reportGenerationFlux = generateReport(userInput, plan, typedPlan, executionResults,
				executedQueries, summaryAndRecommendations, RunDeadlineUtil.remaining(state));

		TextType reportTextType = TextType.MARK_DOWN;

		// Use utility class to create streaming generator with content collection
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始生成报告...", "报告生成完成！", reportContent -> {
					log.info("Generated report content: {}", reportContent);
					Map<String, Object> result = new HashMap<>();
					result.put(RESULT, reportContent);
					result.put(SQL_EXECUTE_NODE_OUTPUT, null);
					result.put(SQL_EXECUTED_QUERY_OUTPUT, null);
					result.put(PLAN_CURRENT_STEP, null);
					result.put(PLANNER_NODE_OUTPUT, null);
					result.put(PLAN_PARSED_OBJECT, null);
					result.put(PLAN_PARSED_OUTPUT_HASH, null);
					result.put(PLAN_VALIDATED_OUTPUT_HASH, null);
					return result;
				},
				Flux.concat(Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getStartSign())),
						reportGenerationFlux,
						Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getEndSign()))));

		return Map.of(RESULT, generator);
	}

	/**
	 * Gets the current execution step from the plan.
	 */
	private ExecutionStep getCurrentExecutionStep(Plan plan, Integer currentStep) {
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		if (executionPlan == null || executionPlan.isEmpty()) {
			throw new IllegalStateException("Execution plan is empty");
		}

		int stepIndex = currentStep - 1;
		if (stepIndex < 0 || stepIndex >= executionPlan.size()) {
			throw new IllegalStateException("Current step index out of range: " + stepIndex);
		}

		return executionPlan.get(stepIndex);
	}

	/**
	 * Generates the analysis report.
	 */
	private Flux<ChatResponse> generateReport(String userInput, Plan plan, SemanticBlueprint typedPlan,
			Map<String, String> executionResults, Map<String, String> executedQueries, String summaryAndRecommendations,
			Duration runBudget) {
		// SemEvoSQL reports must be grounded in the governed Semantic Blueprint and actual
		// execution evidence. The advanced planner narrative is not an execution fact.
		String userRequirementsAndPlan = buildUserRequirementsAndPlan(userInput, plan, typedPlan);

		// Build analysis steps and data results description
		String analysisStepsAndData = buildAnalysisStepsAndData(plan, executionResults, executedQueries, typedPlan);

		String reportPrompt = PromptHelper.buildReportGeneratorPrompt(userRequirementsAndPlan, analysisStepsAndData,
				summaryAndRecommendations);
		log.debug("Report Node Prompt: \n {} \n", reportPrompt);
		Flux<ChatResponse> generated = llmService == null ? Flux.empty() : llmService.callUserWithin(reportPrompt, runBudget);
		Flux<ChatResponse> fallback = Flux.just(ChatResponseUtil.createPureResponse(
				fallbackReport(executionResults, typedPlan)));
		return generated.filter(response -> hasText(ChatResponseUtil.getText(response)))
			.switchIfEmpty(fallback)
			.onErrorResume(error -> {
				log.warn("Report generation unavailable; using deterministic result summary: {}", error.getMessage());
				return fallback;
			});
	}

	/**
	 * Produces a small, data-agnostic answer when the optional narrative model cannot
	 * respond within the remaining Run budget. The actual result is still the
	 * persisted, post-review execution evidence; this fallback never invents a
	 * conclusion or a business-specific label.
	 */
	String fallbackReport(Map<String, String> executionResults, SemanticBlueprint typedPlan) {
		if (executionResults == null || executionResults.isEmpty()) {
			return "查询已完成，但没有返回可展示的数据。";
		}
		StringBuilder report = new StringBuilder("查询已完成，结果已通过业务口径与执行校验。\n\n");
		int resultIndex = 0;
		for (Map.Entry<String, String> entry : executionResults.entrySet()) {
			if (entry.getKey() == null || entry.getKey().endsWith("_analysis") || !hasText(entry.getValue())) {
				continue;
			}
			resultIndex++;
			report.append("结果 ").append(resultIndex).append("：\n```json\n");
			report.append(typedPlan == null ? entry.getValue() : sanitizeExecutionResult(entry.getValue(), typedPlan));
			report.append("\n```\n");
		}
		return resultIndex == 0 ? "查询已完成，但没有返回可展示的数据。" : report.toString().trim();
	}

	/**
	 * Builds user requirements and plan description.
	 */
	String buildUserRequirementsAndPlan(String userInput, Plan plan, SemanticBlueprint typedPlan) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 用户原始需求\n");
		sb.append(userInput).append("\n\n");

		if (typedPlan != null) {
			sb.append("## 系统内部业务事实（仅用于生成回答）\n");
			sb.append("以下内容已经去除内部模型、字段、数据源和执行标识。生成给用户的报告时只能使用其中的业务名称与事实，不得提及本节标题、内部计划结构或实现术语。\n");
			sb.append("```json\n").append(json(governedReportFacts(typedPlan))).append("\n```\n\n");
			return sb.toString();
		}

		sb.append("## 执行计划概述\n");
		sb.append("**思考过程**: ").append(plan.getThoughtProcess()).append("\n\n");

		sb.append("## 详细执行步骤\n");
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		for (int i = 0; i < executionPlan.size(); i++) {
			ExecutionStep step = executionPlan.get(i);
			sb.append("### 步骤 ").append(i + 1).append(": 步骤编号 ").append(step.getStep()).append("\n");
			sb.append("**工具**: ").append(step.getToolToUse()).append("\n");
			if (step.getToolParameters() != null) {
				sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
			}
			sb.append("\n");
		}

		return sb.toString();
	}

	/**
	 * Builds analysis steps and data results description.
	 */
	String buildAnalysisStepsAndData(Plan plan, Map<String, String> executionResults,
			Map<String, String> executedQueries) {
		return buildAnalysisStepsAndData(plan, executionResults, executedQueries, null);
	}

	String buildAnalysisStepsAndData(Plan plan, Map<String, String> executionResults,
			Map<String, String> executedQueries, SemanticBlueprint typedPlan) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 数据执行结果\n");

		if (executionResults.isEmpty()) {
			sb.append("暂无执行结果数据\n");
		}
		else {
			boolean governedTypedPlan = typedPlan != null;
			List<ExecutionStep> executionPlan = plan == null || plan.getExecutionPlan() == null ? List.of()
					: plan.getExecutionPlan();
			int visibleResultIndex = 0;
			for (Map.Entry<String, String> entry : executionResults.entrySet()) {
				String stepKey = entry.getKey();
				String stepResult = entry.getValue();

				if (stepKey.endsWith("_analysis")) {
					continue;
				}

				visibleResultIndex++;
				sb.append(governedTypedPlan ? "### 执行结果 " + visibleResultIndex + "\n" : "### " + stepKey + "\n");

				// Legacy planner reports retain execution detail. Governed semantic reports keep
				// SQL/tool internals out of the user-facing report context.
				if (!governedTypedPlan) {
					try {
						int stepIndex = Integer.parseInt(stepKey.replace("step_", "")) - 1;
						if (stepIndex >= 0 && stepIndex < executionPlan.size()) {
							ExecutionStep step = executionPlan.get(stepIndex);
							sb.append("**步骤编号**: ").append(step.getStep()).append("\n");
							sb.append("**使用工具**: ").append(step.getToolToUse()).append("\n");
							if (step.getToolParameters() != null) {
								sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
							}
							String executedSql = SqlExecutionLineage.queryForStep(executedQueries, stepIndex + 1);
							if (executedSql != null) {
								sb.append("**执行SQL**: \n```sql\n").append(executedSql).append("\n```\n");
							}
						}
					}
					catch (NumberFormatException e) {
						// Ignore parsing errors
					}
				}

				String reportResult = governedTypedPlan ? sanitizeExecutionResult(stepResult, typedPlan) : stepResult;
				sb.append("**执行结果**: \n```json\n").append(reportResult).append("\n```\n\n");
				String analysisKey = stepKey + "_analysis";
				String analysisResult = executionResults.get(analysisKey);
				if (analysisResult != null && !analysisResult.trim().isEmpty()) {
					sb.append("**分析结果**: ").append(analysisResult).append(" ");
				}
			}
		}

		return sb.toString();
	}

	private Map<String, Object> governedReportFacts(SemanticBlueprint typedPlan) {
		Map<String, Object> facts = new LinkedHashMap<>();
		List<String> modelLabels = new java.util.ArrayList<>();
		for (int index = 0; index < typedPlan.getModels().size(); index++) {
			SemanticBlueprint.ModelSelection model = typedPlan.getModels().get(index);
			modelLabels.add(userFacingLabel(model.getBusinessName(), "业务数据源 " + (index + 1), model.getModelCode(),
					model.getPhysicalTable()));
		}
		facts.put("业务范围", modelLabels.stream().distinct().toList());
		facts.put("指标", typedPlan.getMetrics().stream().map(metric -> metricDisplayName(metric, typedPlan)).distinct().toList());
		facts.put("维度", typedPlan.getDimensions().stream()
				.map(dimension -> userFacingLabel(dimension.getBusinessName(), "业务维度", dimension.getDimensionCode(),
						dimension.getColumnName()))
				.distinct().toList());
		facts.put("业务规则", typedPlan.getRules().stream()
				.map(rule -> userFacingLabel(rule.getBusinessName(), "业务规则", rule.getRuleCode(), rule.getExpression()))
				.distinct().toList());
		facts.put("已解析业务值", typedPlan.getEnumResolutions().stream()
				.map(resolution -> userFacingLabel(resolution.getBusinessName(), "业务值", resolution.getValueCode()))
				.distinct().toList());
		if (typedPlan.getTimeRange() != null) {
			Map<String, Object> timeRange = new LinkedHashMap<>();
			if (hasText(typedPlan.getTimeRange().getStartInclusive())) {
				timeRange.put("开始（含）", typedPlan.getTimeRange().getStartInclusive());
			}
			if (hasText(typedPlan.getTimeRange().getEndExclusive())) {
				timeRange.put("结束（不含）", typedPlan.getTimeRange().getEndExclusive());
			}
			if (!timeRange.isEmpty()) {
				facts.put("时间范围", timeRange);
			}
		}
		return facts;
	}

	private String sanitizeExecutionResult(String rawResult, SemanticBlueprint typedPlan) {
		if (!hasText(rawResult)) {
			return rawResult;
		}
		String sanitized = rawResult;
		Map<String, String> fieldLabels = new LinkedHashMap<>();
		for (SemanticBlueprint.MetricSelection metric : typedPlan.getMetrics()) {
			if (hasText(metric.getMetricCode())) {
				fieldLabels.put(metric.getMetricCode(), metricDisplayName(metric, typedPlan));
			}
		}
		for (SemanticBlueprint.DimensionSelection dimension : typedPlan.getDimensions()) {
			if (!hasText(dimension.getBusinessName())) {
				continue;
			}
			if (hasText(dimension.getDimensionCode())) {
				fieldLabels.put(dimension.getDimensionCode(), dimension.getBusinessName());
			}
			if (hasText(dimension.getColumnName())) {
				fieldLabels.put(dimension.getColumnName(), dimension.getBusinessName());
			}
		}
		SemanticBlueprint.MergePlan mergePlan = typedPlan.getMergePlan();
		if (mergePlan != null) {
			if (hasText(mergePlan.getLeftInputKey())) {
				fieldLabels.putIfAbsent(mergePlan.getLeftInputKey(), "来源结果 1");
			}
			if (hasText(mergePlan.getRightInputKey())) {
				fieldLabels.putIfAbsent(mergePlan.getRightInputKey(), "来源结果 2");
			}
			if (hasText(mergePlan.getOutputKey())) {
				fieldLabels.put(mergePlan.getOutputKey(), "派生结果");
			}
			else if (hasText(mergePlan.getCalculationExpression()) && mergePlan.getCalculationExpression().contains("=")) {
				String derivedKey = mergePlan.getCalculationExpression().substring(0,
						mergePlan.getCalculationExpression().indexOf('=')).trim();
				if (hasText(derivedKey)) {
					fieldLabels.put(derivedKey, "差值");
				}
			}
		}
		for (Map.Entry<String, String> label : fieldLabels.entrySet()) {
			sanitized = sanitized.replace(json(label.getKey()), json(label.getValue()));
		}
		return sanitized;
	}

	private String metricDisplayName(SemanticBlueprint.MetricSelection metric, SemanticBlueprint typedPlan) {
		String metricName = userFacingLabel(metric.getBusinessName(), "业务指标", metric.getMetricCode(), metric.getExpression());
		SemanticBlueprint.ModelSelection model = typedPlan.getModels().stream()
				.filter(candidate -> hasText(metric.getModelCode()) && metric.getModelCode().equals(candidate.getModelCode()))
				.findFirst().orElse(null);
		String modelName = model == null ? null
				: userFacingLabel(model.getBusinessName(), null, model.getModelCode(), model.getPhysicalTable());
		String displayName = hasText(modelName) ? modelName + " · " + metricName : metricName;
		String unit = userFacingLabel(metric.getUnit(), null);
		return hasText(unit) ? displayName + "（单位：" + unit + "）" : displayName;
	}

	private String userFacingLabel(String candidate, String fallback, String... internalValues) {
		if (!hasText(candidate) || candidate.contains("_")) {
			return fallback;
		}
		for (String internalValue : internalValues) {
			if (hasText(internalValue) && candidate.equalsIgnoreCase(internalValue)) {
				return fallback;
			}
		}
		return candidate;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize governed report facts", ex);
		}
	}

}
