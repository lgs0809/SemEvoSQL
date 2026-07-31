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

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.dto.planner.Plan;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.lgs.semevosql.prompt.PromptConstant;
import cn.lgs.semevosql.prompt.PromptHelper;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import cn.lgs.semevosql.run.RunDeadlineUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 */
@Slf4j
@Component
@AllArgsConstructor
public class PlannerNode implements NodeAction {

	private final LlmService llmService;

	private final QueryRunService queryRunService;

	private final CanonicalJson canonicalJson;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Boolean sqlGenerationOnly = state.value(SQL_GENERATION_ONLY, false);
		String recoveredPlannerOutput = StateUtil.getStringValue(state, RECOVERED_PLANNER_OUTPUT, null);

		Flux<ChatResponse> flux = StringUtils.hasText(recoveredPlannerOutput)
				? Flux.just(ChatResponseUtil.createPureResponse(recoveredPlannerOutput))
				: sqlGenerationOnly ? handleSqlGenerationOnly() : handlePlanGenerate(state);
		if (StringUtils.hasText(recoveredPlannerOutput)) {
			log.info("Reusing exact persisted planner output for durable approval recovery");
		}

		Flux<ChatResponse> chatResponseFlux = Flux.concat(
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())), flux,
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					String plannerOutput = v.substring(TextType.JSON.getStartSign().length(),
							v.length() - TextType.JSON.getEndSign().length());
					persistPlannerSnapshot(state, plannerOutput);
					return Map.of(PLANNER_NODE_OUTPUT, plannerOutput);
				}, chatResponseFlux);

		return Map.of(PLANNER_NODE_OUTPUT, generator);
	}

	private void persistPlannerSnapshot(OverAllState state, String plannerOutput) {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		if (!StringUtils.hasText(runId) || !StringUtils.hasText(plannerOutput)) {
			return;
		}
		String activeTodoId = StateUtil.getStringValue(state, ACTIVE_TODO_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		String scope = StringUtils.hasText(activeTodoId) ? activeTodoId : "simple";
		SemanticBlueprint semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		String semanticHash = semanticPlan == null ? "none" : canonicalJson.hash(semanticPlan);
	queryRunService.appendEvent(runId, attemptId, "PLANNER_PLAN_SNAPSHOT", "planner", plannerOutput,
				"Exact execution Planner output persisted for diagnosis and durable recovery",
				"planner-plan-snapshot:" + runId + ":" + scope + ":sem-" + semanticHash + ":"
						+ Integer.toHexString(plannerOutput.hashCode()));
	}

	private Flux<ChatResponse> handlePlanGenerate(OverAllState state) {
		// 获取查询增强节点的输出
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		log.info("Using processed query for planning: {}", canonicalQuery);

		// 检查是否为修复模式
		String validationError = StateUtil.getStringValue(state, PLAN_VALIDATION_ERROR, null);
		if (validationError != null) {
			log.info("Regenerating plan with user feedback: {}", validationError);
		}
		else {
			log.info("Generating initial plan");
		}

		// 构建提示参数
		String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
		SemanticBlueprint semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		semanticModel = semanticModel + "\n\n# Semantic Blueprint\n" + serializeSemanticPlan(semanticPlan);
		Map<String, Object> preferredPlan = StateUtil.getObjectValue(state, PREFERRED_EXECUTION_PLAN, Map.class,
				Map.of());
		if (!preferredPlan.isEmpty()) {
			semanticModel = semanticModel + "\n\n# Preferred Execution Start Hint (NON-AUTHORITATIVE)\n"
					+ preferredPlanHint(preferredPlan)
					+ "\nThis is only a starting hint. Revalidate applicability, Semantic Blueprint, Catalog, SQL Guard, "
					+ "cost policy and human review. Ignore it whenever any condition differs.";
		}
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String schemaStr = PromptHelper.buildMixMacSqlDbPrompt(schemaDTO, true);

		// 构建用户提示
		String userPrompt = buildUserPrompt(canonicalQuery, validationError, state);
		String evidence = StateUtil.getStringValue(state, EVIDENCE);

		// 构建模板参数
		BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
		Map<String, Object> params = Map.of("user_question", userPrompt, "schema", schemaStr, "evidence", evidence,
				"semantic_model", semanticModel, "plan_validation_error", formatValidationError(validationError),
				"format", beanOutputConverter.getFormat());
		// 生成计划
		String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
		log.debug("Planner prompt: as follows \n{}\n", plannerPrompt);

		// 调用LLM生成计划
		return llmService.callUserWithin(plannerPrompt, RunDeadlineUtil.remaining(state))
			.filter(response -> StringUtils.hasText(ChatResponseUtil.getText(response)))
			.switchIfEmpty(Flux.defer(() -> {
				// A provider can complete a streaming request without an assistant payload. Keep the governed semantic plan
				// and use the smallest valid SQL-first execution plan; SQL generation still performs the open-ended lowering,
				// preflight, guard, cost and result-review checks before anything executes.
				log.warn("Planner returned no content; using the generic SQL-first execution plan");
				return Flux.just(ChatResponseUtil.createPureResponse(Plan.nl2SqlPlan()));
			}));
	}

	private Flux<ChatResponse> handleSqlGenerationOnly() {
		return Flux.just(ChatResponseUtil.createPureResponse(Plan.nl2SqlPlan()));
	}

	private String buildUserPrompt(String input, String validationError, OverAllState state) {
		if (validationError == null) {
			return input;
		}

		String previousPlan = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT, "");
		return String.format(
				"IMPORTANT: The previous execution plan requires replanning with this authoritative feedback: \"%s\"\n\n"
						+ "Original question: %s\n\n" + "Previous execution plan:\n%s\n\n"
						+ "CRITICAL: Generate a replacement execution plan that incorporates the feedback while preserving governed semantic bindings unless the feedback explicitly says they are wrong (\"%s\")",
				validationError, input, previousPlan, validationError);
	}

	private String formatValidationError(String validationError) {
		return validationError != null ? String
			.format("**REPLAN FEEDBACK (CRITICAL)**: %s\n\n**Must incorporate this feedback.**", validationError) : "";
	}

	private String serializeSemanticPlan(SemanticBlueprint semanticPlan) {
		if (semanticPlan == null) {
			return "{}";
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(semanticPlan);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize Semantic Blueprint", ex);
		}
	}

	private String preferredPlanHint(Map<String, Object> preferredPlan) {
		Map<String, Object> safe = new java.util.LinkedHashMap<>();
		safe.put("preferredPlanId", preferredPlan.get("id"));
		safe.put("candidateId", preferredPlan.get("candidate_id"));
		safe.put("proposal", preferredPlan.get("plan_json"));
		safe.put("startHintOnly", true);
		safe.put("guardStillRequired", true);
		safe.put("reviewStillRequired", true);
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(safe);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize preferred execution start hint", ex);
		}
	}

}
