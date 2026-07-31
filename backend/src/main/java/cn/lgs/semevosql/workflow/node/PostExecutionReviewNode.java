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

import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.ADVANCED_EXECUTION_FALLBACK;
import static cn.lgs.semevosql.constant.Constant.FORCE_SEMANTIC_REPLAN;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_EXECUTED_STEP;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_RESULT_PAYLOAD;
import static cn.lgs.semevosql.constant.Constant.PLAN_CURRENT_STEP;
import static cn.lgs.semevosql.constant.Constant.PLAN_VALIDATION_ERROR;
import static cn.lgs.semevosql.constant.Constant.PLANNER_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.POST_EXECUTION_REVIEW_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_HINT;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_QUERY;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_EXECUTION_DECISION;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_REPLAN_FEEDBACK;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTED_QUERY_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_COMPILER_MODE;
import static cn.lgs.semevosql.constant.Constant.SQL_DRY_PLAN_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_COUNT;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_MEMORY_BY_STEP;
import static cn.lgs.semevosql.constant.Constant.SQL_REGENERATE_REASON;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;

import cn.lgs.semevosql.bo.schema.ResultBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.dto.datasource.SqlRetryDto;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.clarification.RuntimeClarification;
import cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.PostExecutionReview.IssueType;
import cn.lgs.semevosql.review.PostExecutionReviewService;
import cn.lgs.semevosql.review.PostExecutionReviewService.ReviewMode;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.QueryRepairPolicy.BudgetDecision;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.review.RetrievalRepairService;
import cn.lgs.semevosql.review.RetrievalRepairService.RepairQuery;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunNodeEffectService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.GraphResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Accepts or rejects an executed candidate result before it is exposed to the user. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostExecutionReviewNode implements NodeAction {

	private final PostExecutionReviewService reviewService;

	private final QueryRepairPolicy repairPolicy;

	private final RetrievalRepairService retrievalRepairService;

	private final RuntimeClarificationService clarificationService;

	private final RunNodeEffectService runNodeEffectService;

	private final QueryRunService queryRunService;

	private final SemEvoSQLProperties properties;

	private final RunExecutionFenceService executionFence;

	private final MultiSourceRunService multiSourceRunService;

	@Override
	@SuppressWarnings("unchecked") // Graph state stores typed query maps behind a raw Map class token.
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		Integer step = StateUtil.getObjectValue(state, LAST_SQL_EXECUTED_STEP, Integer.class,
				Math.max(0, state.value(PLAN_CURRENT_STEP, 1) - 1));
		String resultPayload = StateUtil.getStringValue(state, LAST_SQL_RESULT_PAYLOAD, "");
		if (resultPayload.isBlank()) {
			throw new PostExecutionReviewFailedException("Executed result payload is unavailable for review");
		}
		Map<String, String> executedQueries = StateUtil.getObjectValue(state, SQL_EXECUTED_QUERY_OUTPUT, Map.class,
				Map.of());
		String sql = executedQueries.getOrDefault(String.valueOf(step), "");
		String executionPlan = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT, "");
		String compilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "");
		boolean advancedExecution = state.value(ADVANCED_EXECUTION_FALLBACK, false)
				|| "SEMANTIC_SQL".equalsIgnoreCase(compilerMode);
		ValidationMode validationMode = advancedExecution ? ValidationMode.ADVANCED_EXECUTION
				: ValidationMode.STRICT_SEMANTIC_PLAN;
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		if (plan == null) {
			throw new PostExecutionReviewFailedException("Semantic Blueprint is unavailable for post-execution review");
		}
		RepairBudget budget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class,
				RepairBudget.empty());
		Map<String, Object> effectInput = new LinkedHashMap<>();
		effectInput.put("step", step);
		effectInput.put("sql", sql);
		effectInput.put("resultPayload", resultPayload);
		effectInput.put("typedPlan", plan);
		effectInput.put("executionPlan", executionPlan);
		List<String> dryPlanWarnings = dryPlanWarnings(state);
		effectInput.put("compilerMode", compilerMode);
		effectInput.put("validationMode", validationMode.name());
		effectInput.put("dryPlanWarnings", dryPlanWarnings);
		effectInput.put("budget", budget);
		String inputHash = runNodeEffectService.inputHash(JsonUtil.getObjectMapper().writeValueAsString(effectInput));
		String effectKey = "post-execution-review:" + step;
		String completed = runNodeEffectService.completedPayload(runId, effectKey, inputHash).orElse(null);
		if (completed != null) {
			PostReviewEffect restored = readEffect(completed);
			recordEvidence(runId, attemptId, step, plan, executionPlan, restored.review(), restored.budget(), inputHash);
			return replay(state, restored);
		}

		ResultBO result = JsonUtil.getObjectMapper().readValue(resultPayload, ResultBO.class);
		ResultSetBO resultSet = result.getResultSet();
		ReviewMode reviewMode = repairPolicy.semanticReviewAvailable(budget) ? ReviewMode.CONFIGURED
				: ReviewMode.DETERMINISTIC_ONLY;
		PostExecutionReview review = reviewService.review(StateUtil.getCanonicalQuery(state), plan, sql, resultSet,
				properties.getSqlExecution().getMaxRows(), executionPlan, reviewMode, validationMode, dryPlanWarnings,
				StateUtil.getObjectValue(state, cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS, Long.class,
						(Long) null));
		RepairBudget updatedBudget = budget;
		if (review.semanticReviewerUsed()) {
			BudgetDecision semanticReviewBudget = repairPolicy.consumeSemanticReview(updatedBudget);
			if (!semanticReviewBudget.allowed()) {
				throw new IllegalStateException("Semantic reviewer exceeded its durable budget");
			}
			updatedBudget = semanticReviewBudget.budget();
		}

		Decision effectiveDecision = normalizeNonRepairableIssue(review);
		if (effectiveDecision != review.decision()) {
			review = new PostExecutionReview(effectiveDecision, review.issueType(), review.confidence(),
					review.suspectedAssetKeys(), review.evidence(), review.deterministicErrors(),
					review.deterministicWarnings(), review.semanticReviewerUsed(), review.modelEvidence());
		}
		BudgetDecision transition = repairPolicy.consumeTransition(updatedBudget, review.decision());
		if (!transition.allowed()) {
			review = new PostExecutionReview(Decision.FAIL, IssueType.REPAIR_BUDGET_EXHAUSTED, 1.0d,
					review.suspectedAssetKeys(), List.of(transition.reason()), review.deterministicErrors(),
					review.deterministicWarnings(), review.semanticReviewerUsed(), review.modelEvidence());
		}
		else {
			updatedBudget = transition.budget();
		}
		PostReviewEffect effect = new PostReviewEffect(review, updatedBudget, step, resultPayload, sql);
		runNodeEffectService.recordCompleted(runId, attemptId, effectKey, inputHash, writeEffect(effect));
		recordEvidence(runId, attemptId, step, plan, executionPlan, review, updatedBudget, inputHash);
		return applyEffect(state, effect, false);
	}

	private Decision normalizeNonRepairableIssue(PostExecutionReview review) {
		if (review.decision() == Decision.PASS) {
			return Decision.PASS;
		}
		if (review.issueType() == IssueType.RETRIEVAL_MISS) {
			return Decision.RERETRIEVE;
		}
		if (review.issueType() == IssueType.DEFINITION_GAP) {
			return Decision.CLARIFY;
		}
		return review.decision();
	}

	private Map<String, Object> replay(OverAllState state, PostReviewEffect effect) {
		return applyEffect(state, effect, true);
	}

	private Map<String, Object> applyEffect(OverAllState state, PostReviewEffect effect, boolean replay) {
		PostExecutionReview review = effect.review();
		if (review.decision() == Decision.PASS) {
			persistDirectArtifactIfNeeded(state, effect);
		}
		Map<String, Object> update = new HashMap<>();
		update.put(POST_EXECUTION_REVIEW_OUTPUT, review);
		update.put(QUERY_REPAIR_BUDGET, effect.budget());
		update.put(LAST_SQL_EXECUTED_STEP, effect.step());
		update.put(LAST_SQL_RESULT_PAYLOAD, effect.resultPayload());
		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			if (replay) {
				emitter.next(ChatResponseUtil.createResponse("恢复已持久化的结果验收判定，无需重复调用 Reviewer。"));
			}
			emitter.next(ChatResponseUtil.createResponse("结果验收: " + review.decision().name()));
			if (review.decision() == Decision.PASS) {
				emitter.next(ChatResponseUtil.createResponse("SQL查询结果："));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getStartSign()));
				emitter.next(ChatResponseUtil.createPureResponse(effect.resultPayload()));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getEndSign()));
			}
			emitter.complete();
		});

		switch (review.decision()) {
			case PASS -> {
				update.put(FORCE_SEMANTIC_REPLAN, false);
				update.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
				update.put(SQL_GENERATE_COUNT, 0);
			}
			case RETRY_SQL -> {
				update.put(PLAN_CURRENT_STEP, effect.step());
				update.put(FORCE_SEMANTIC_REPLAN, false);
				if ("EXECUTED".equals(StateUtil.getStringValue(state, SEMANTIC_EXECUTION_DECISION, ""))) {
					// The compiler-first path intentionally skipped advanced-fallback schema preparation. A reviewer-driven
					// SQL repair therefore enters the bounded advanced fallback chain before Planner/SqlGenerate.
					update.put(ADVANCED_EXECUTION_FALLBACK, true);
				}
				update.put(SQL_REGENERATE_REASON,
						SqlRetryDto.sqlExecute("Post-execution review requested SQL repair: " + repairReason(review)));
			}
			case REPLAN_EXECUTION -> {
				// Keep the governed semantic binding fixed and rebuild only the execution strategy. The previous execution
				// artifacts are cleared from graph state so a replacement plan cannot accidentally consume abandoned results.
				// A compiler-first run has not prepared advanced schema context yet, so mark the existing fallback path before
				// the dispatcher sends it through QueryEnhance/SchemaRecall/TableRelation.
				if ("EXECUTED".equals(StateUtil.getStringValue(state, SEMANTIC_EXECUTION_DECISION, ""))) {
					update.put(ADVANCED_EXECUTION_FALLBACK, true);
				}
				update.put(PLAN_CURRENT_STEP, 1);
				update.put(FORCE_SEMANTIC_REPLAN, false);
				update.put(PLAN_VALIDATION_ERROR,
						"Post-execution review requested execution replanning while preserving the governed semantic binding: "
								+ repairReason(review));
				update.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
				update.put(SQL_GENERATE_COUNT, 0);
				update.put(SQL_EXECUTE_NODE_OUTPUT, Map.of());
				update.put(SQL_EXECUTED_QUERY_OUTPUT, Map.of());
				update.put(SQL_RESULT_MEMORY_BY_STEP, Map.of());
				update.put(SQL_RESULT_LIST_MEMORY, List.of());
				update.put(LAST_SQL_EXECUTED_STEP, 0);
				update.put(LAST_SQL_RESULT_PAYLOAD, "");
			}
			case REBIND_SEMANTIC, REPLAN -> {
				update.put(PLAN_CURRENT_STEP, effect.step());
				update.put(FORCE_SEMANTIC_REPLAN, true);
				update.put(PLAN_VALIDATION_ERROR, "");
				String replanFeedback = "结果验收要求修正以下语义绑定问题，并保留其它已验证的受治理绑定：" + repairReason(review);
				update.put(SEMANTIC_REPLAN_FEEDBACK, replanFeedback);
				update.put(RETRIEVAL_REPAIR_QUERY, "");
				update.put(RETRIEVAL_REPAIR_HINT, "");
				update.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
			}
			case RERETRIEVE -> handleRetrievalRepair(state, update, effect);
			case CLARIFY -> handleClarification(state, update, effect);
			case FAIL -> throw new PostExecutionReviewFailedException(
					"Post-execution review failed: " + review.issueType() + "; " + repairReason(review));
		}
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, ignored -> update, displayFlux);
		return Map.of(POST_EXECUTION_REVIEW_OUTPUT, generator);
	}

	private void persistDirectArtifactIfNeeded(OverAllState state, PostReviewEffect effect) {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		if (runId.isBlank() || multiSourceRunService.mergedArtifact(runId).isPresent()) {
			return;
		}
		try {
			ResultBO result = JsonUtil.getObjectMapper().readValue(effect.resultPayload(), ResultBO.class);
			multiSourceRunService.persistDirectResult(runId, result.getResultSet(), attemptId);
		}
		catch (LateRunResultDroppedException | RunDeadlineExceededException late) {
			throw late;
		}
		catch (Exception ex) {
			throw new PostExecutionReviewFailedException("Unable to persist validated result artifact: " + ex.getMessage());
		}
	}

	private void handleRetrievalRepair(OverAllState state, Map<String, Object> update, PostReviewEffect effect) {
		RepairQuery repair = retrievalRepairService.build(StateUtil.getCanonicalQuery(state), List.of(), effect.review());
		update.put(PLAN_CURRENT_STEP, effect.step());
		update.put(FORCE_SEMANTIC_REPLAN, true);
		update.put(RETRIEVAL_REPAIR_QUERY, repair.query());
		update.put(RETRIEVAL_REPAIR_HINT, repair.hint());
		update.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
	}

	private void handleClarification(OverAllState state, Map<String, Object> update, PostReviewEffect effect) {
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
		if (effect.review().issueType() == IssueType.DEFINITION_GAP) {
			SemanticPlanningOutcome.ClarificationRequired gap = new SemanticPlanningOutcome.ClarificationRequired(
					"DEFINITION_GAP", "当前语义目录缺少完成该问题所需的明确业务定义，请说明你希望采用的业务口径。", List.of(),
					repairReason(effect.review()));
			RuntimeClarification clarification = clarificationService.createPlanningClarification(runId,
					StateUtil.getCanonicalQuery(state), gap);
			throw new RuntimeClarificationRequiredException(runId, clarification.clarificationId());
		}
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		List<String> physicalTables = plan == null ? List.of()
				: plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getPhysicalTable).filter(Objects::nonNull)
					.distinct().toList();
		RuntimeClarification clarification = clarificationService
			.detect(runId, projectId, projectVersionId, StateUtil.getCanonicalQuery(state), physicalTables)
			.orElse(null);
		if (clarification != null) {
			throw new RuntimeClarificationRequiredException(runId, clarification.clarificationId());
		}
		// If this is a replay after a previously answered clarification, force one governed replan.
		var resolvedBindings = clarificationService.resolvedBindingContext(runId, projectId, projectVersionId);
		if (resolvedBindings != null && !resolvedBindings.empty()) {
			update.put(PLAN_CURRENT_STEP, effect.step());
			update.put(FORCE_SEMANTIC_REPLAN, true);
			update.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
			return;
		}
		throw new PostExecutionReviewFailedException("Reviewer requested clarification but no governed clarification could be created");
	}

	private String repairReason(PostExecutionReview review) {
		if (!review.evidence().isEmpty()) {
			return String.join("; ", review.evidence());
		}
		if (!review.deterministicErrors().isEmpty()) {
			return String.join("; ", review.deterministicErrors());
		}
		return review.issueType().name();
	}

	@SuppressWarnings("unchecked") // Graph state stores the dry-plan document behind a raw Map class token.
	private List<String> dryPlanWarnings(OverAllState state) {
		Map<String, Object> dryPlan = StateUtil.getObjectValue(state, SQL_DRY_PLAN_OUTPUT, Map.class, Map.of());
		Object rawWarnings = dryPlan.get("warnings");
		if (!(rawWarnings instanceof List<?> values)) {
			return List.of();
		}
		return values.stream().map(Objects::toString).filter(value -> !value.isBlank()).distinct().toList();
	}

	private void recordEvidence(String runId, String attemptId, int step, SemanticBlueprint plan, String executionPlan,
			PostExecutionReview review, RepairBudget budget, String inputHash) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		try {
			if (attemptId != null && !attemptId.isBlank()) {
				executionFence.assertActive(runId, attemptId);
			}
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("step", step);
			payload.put("review", review);
			payload.put("repairBudget", budget);
			payload.put("typedPlan", plan);
			payload.put("executionPlan", executionPlan == null ? "" : executionPlan);
			queryRunService.appendEvent(runId, attemptId, "POST_EXECUTION_REVIEW", "post-execution-review",
					JsonUtil.getObjectMapper().writeValueAsString(payload), "Post-execution review " + review.decision(),
					"post-review:" + step + ":" + inputHash);
		}
		catch (LateRunResultDroppedException | RunDeadlineExceededException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Unable to append post-execution review evidence for run {}: {}", runId, ex.getMessage());
		}
	}

	static PostReviewEffect readEffect(String payload) {
		try {
			return JsonUtil.getObjectMapper().readValue(payload, PostReviewEffect.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to restore persisted post-execution review", ex);
		}
	}

	private String writeEffect(PostReviewEffect effect) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(effect);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to persist post-execution review", ex);
		}
	}

	public record PostReviewEffect(PostExecutionReview review, RepairBudget budget, int step, String resultPayload,
			String sql) {
	}

	public static class PostExecutionReviewFailedException extends IllegalStateException {
		public PostExecutionReviewFailedException(String message) {
			super(message);
		}
	}

}
