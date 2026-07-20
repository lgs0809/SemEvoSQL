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
package cn.lgs.semevosql.diagnosis;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.SemanticReplayService;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.run.QueryExecutionEvidence;
import cn.lgs.semevosql.run.QueryExecutionExplanation;
import cn.lgs.semevosql.run.QueryExecutionExplanationService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Deterministic diagnosis over persisted SemEvoSQL execution facts.
 *
 * <p>This service never exposes chain-of-thought and never guesses whether a planner error was caused
 * by a prompt or by model capability. It attributes failures only to observable pipeline boundaries.
 */
@Service
public class QueryDiagnosisService {

	private static final Set<String> PLANNER_ERROR_CODES = Set.of("PLANNER_REJECTED", "UNRESOLVABLE",
			"INVALID_PLANNER_OUTPUT", "INVALID_BINDING");

	private final QueryRunService runService;

	private final QueryExecutionExplanationService explanationService;

	private final ProjectScopeService projectScope;

	private final SemanticReplayService replayService;

	private final JdbcTemplate jdbc;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public QueryDiagnosisService(QueryRunService runService, QueryExecutionExplanationService explanationService,
			ProjectScopeService projectScope, SemanticReplayService replayService, JdbcTemplate jdbc) {
		this.runService = runService;
		this.explanationService = explanationService;
		this.projectScope = projectScope;
		this.replayService = replayService;
		this.jdbc = jdbc;
	}

	public DiagnosisView diagnose(String runId, OperatorContext operator) {
		QueryRun run = runService.get(runId);
		projectScope.requireProject(run.projectId(), operator);
		List<RunEvent> events = runService.events(runId, 0, 1000);
		QueryExecutionEvidence planning = planningEvidence(events).orElse(null);
		QueryExecutionExplanation explanation = explanationService.explain(run);
		CorrectionEvidence correction = correctionEvidence(events).orElse(null);
		ReviewEvidence review = reviewEvidence(events).orElse(null);
		RootCauseDecision decision = decide(run, events, planning, explanation, correction, review);
		GovernanceView governance = governance(runId, correction);
		List<QueryExecutionEvidence.RetrievalCandidate> retrieval = planning != null
				? safe(planning.retrievalCandidates()) : List.of();
		AdvancedEvidence advanced = advanced(planning, run, events);
		PipelineEvidence pipeline = pipeline(run, events, explanation, review);
		return new DiagnosisView(run.runId(), run.projectId(), run.projectVersionId(), run.threadId(),
				explanation.understoodQuery(), run.status().name(), decision.rootCause().name(), decision.confidence().name(),
				decision.summary(), stages(run, events, planning, explanation, review),
				planning == null ? null : planning.selectedAssets(), retrieval, correction, governance,
				actions(run, governance), pipeline, advanced);
	}

	private Optional<QueryExecutionEvidence> planningEvidence(List<RunEvent> events) {
		return events.stream()
			.filter(event -> "PLANNING_TRACE".equals(event.eventType()) && StringUtils.hasText(event.payload()))
			.reduce((left, right) -> right)
			.flatMap(event -> read(event.payload(), QueryExecutionEvidence.class));
	}

	private Optional<ReviewEvidence> reviewEvidence(List<RunEvent> events) {
		for (int index = events.size() - 1; index >= 0; index--) {
			RunEvent event = events.get(index);
			if (!"POST_EXECUTION_REVIEW".equals(event.eventType()) || !StringUtils.hasText(event.payload())) {
				continue;
			}
			Map<String, Object> payload = readMap(event.payload());
			Object rawReview = payload.get("review");
			if (!(rawReview instanceof Map<?, ?> review)) {
				continue;
			}
			return Optional.of(new ReviewEvidence(text(review.get("decision")), text(review.get("issueType")),
					text(review.get("confidence")), text(review.get("evidence")), text(payload.get("repairBudget"))));
		}
		return Optional.empty();
	}

	private Optional<CorrectionEvidence> correctionEvidence(List<RunEvent> events) {
		for (int index = events.size() - 1; index >= 0; index--) {
			RunEvent event = events.get(index);
			if (!Set.of("QUERY_BINDING_CORRECTED", "SEMANTIC_DEFINITION_CORRECTION_PROPOSED")
				.contains(event.eventType()) || !StringUtils.hasText(event.payload())) {
				continue;
			}
			Map<String, Object> payload = readMap(event.payload());
			if (payload.isEmpty()) continue;
			return Optional.of(new CorrectionEvidence(event.eventType(), text(payload.get("rawExpression")),
					text(payload.get("assetType")), text(payload.get("assetKey")), text(payload.get("businessLabel")),
					text(payload.get("scope")), text(payload.get("rerunId")), text(payload.get("candidateId")),
					text(payload.get("category"))));
		}
		return Optional.empty();
	}

	private RootCauseDecision decide(QueryRun run, List<RunEvent> events, QueryExecutionEvidence planning,
			QueryExecutionExplanation explanation, CorrectionEvidence correction, ReviewEvidence review) {
		if (correction != null && "QUERY_BINDING_CORRECTED".equals(correction.eventType())) {
			boolean recalled = planning != null && safe(planning.retrievalCandidates()).stream()
				.anyMatch(candidate -> sameAsset(candidate.assetType(), candidate.assetKey(), correction.assetType(),
						correction.assetKey()));
			return recalled
					? decision(RootCause.PLANNER_SELECTION_ERROR, Confidence.HIGH,
							"用户已确认语义映射错误；正确资产当时已经进入 CandidateSet，因此问题定位在 Planner 选择阶段。")
					: decision(RootCause.RETRIEVAL_MISS, Confidence.HIGH,
							"用户已确认语义映射错误；正确资产没有出现在持久化召回候选中，应优先修复别名、检索文本或索引覆盖。 ");
		}
		if (correction != null && "SEMANTIC_DEFINITION_CORRECTION_PROPOSED".equals(correction.eventType())) {
			return decision(RootCause.SEMANTIC_DEFINITION_GAP, Confidence.HIGH,
					"用户已提交项目级语义定义修正，该问题已进入受治理的 Semantic Evolution 流程。 ");
		}
		if (review != null && !"PASS".equals(upper(review.decision()))) {
			return switch (upper(review.issueType())) {
				case "RETRIEVAL_MISS" -> decision(RootCause.RETRIEVAL_MISS, Confidence.HIGH,
						"Post-Execution Review 指向召回缺口；系统已停止无意义重试，应进入 Diagnosis / Semantic Evolution。 ");
				case "DEFINITION_GAP" -> decision(RootCause.SEMANTIC_DEFINITION_GAP, Confidence.HIGH,
						"Post-Execution Review 指向受治理语义定义缺口；该信号仅作为 Evolution evidence，不会直接修改正式 Catalog。 ");
				case "SEMANTIC_BINDING_SUSPECTED", "RESULT_SEMANTIC_MISMATCH" -> decision(
						RootCause.PLANNER_SELECTION_ERROR, Confidence.MEDIUM,
						"Post-Execution Review 发现执行结果与当前 Semantic Blueprint 的语义绑定可疑；已按统一预算进入受限 replan。 ");
				case "AMBIGUITY" -> decision(RootCause.CLARIFICATION_REQUIRED, Confidence.HIGH,
						"Post-Execution Review 检测到真实业务歧义；需要用户确认后继续同一个 Durable Run。 ");
				default -> decision(RootCause.RESULT_REVIEW_ERROR, Confidence.MEDIUM,
						"SQL 已执行，但 Post-Execution Review 未验收通过；应依据持久化 review/repair evidence 定位。 ");
			};
		}
		String code = upper(run.errorCode());
		String error = upper(run.errorMessage());
		if ("RETRIEVAL_MISS".equals(code))
			return decision(RootCause.RETRIEVAL_MISS, Confidence.HIGH, "语义检索没有召回可用于规划的受治理候选资产。 ");
		if ("CANDIDATE_BUILD_EMPTY".equals(code))
			return decision(RootCause.CANDIDATE_BUILD_EMPTY, Confidence.HIGH,
					"检索产生了候选，但无法构造成可用的受治理语义模型。 ");
		if ("PLAN_RESOLUTION_ERROR".equals(code))
			return decision(RootCause.PLAN_RESOLUTION_ERROR, Confidence.HIGH,
					"Planner 已完成语义绑定，但 Semantic Blueprint 的确定性解析/校验没有通过。 ");
		if (PLANNER_ERROR_CODES.contains(code) || code.startsWith("PLANNER_"))
			return decision(RootCause.PLANNER_REJECTED, Confidence.HIGH,
					"候选已经进入 Planner，但 Planner 输出被治理校验拒绝。不能据此进一步武断归因到 Prompt 或模型能力。 ");
		if (hasEvent(events, "CLARIFICATION_REQUIRED") || run.status() == QueryRun.RunStatus.WAITING_HUMAN)
			return decision(RootCause.CLARIFICATION_REQUIRED, Confidence.HIGH,
					"当前问题存在无法安全自动消解的业务歧义，需要用户确认后继续同一个 Durable Run。 ");
		if (containsAny(code + " " + error, "MODEL", "CIRCUIT_OPEN", "CAPACITY", "CHATMODEL", "LLM"))
			return decision(RootCause.MODEL_GATEWAY_ERROR, Confidence.MEDIUM,
					"错误事实指向模型网关、容量或模型调用层；这与语义召回错误分开处理。 ");
		if (containsAny(code + " " + error, "SQL_GUARD", "SQL_ADMISSION", "GUARD"))
			return decision(RootCause.SQL_GUARD_ERROR, Confidence.MEDIUM,
					"SQL 已进入安全/准入边界，但被 Guard 或 Admission 拒绝。 ");
		if (containsAny(code + " " + error, "SQL_COMPILE", "SQL_COMPILER", "COMPILATION"))
			return decision(RootCause.SQL_COMPILATION_ERROR, Confidence.MEDIUM,
					"Typed Plan 已形成，但 SQL 编译阶段失败。 ");
		if (sqlFailed(explanation) || containsAny(code + " " + error, "SQL", "JDBC", "DATASOURCE"))
			return decision(RootCause.SQL_EXECUTION_ERROR, Confidence.MEDIUM,
					"错误事实指向 SQL/数据源执行阶段。 ");
		if (run.status() == QueryRun.RunStatus.SUCCEEDED)
			return decision(RootCause.NO_CONFIRMED_FAILURE, Confidence.LOW,
					"系统执行链路已成功，目前没有已确认的系统根因；如果业务答案不对，请先确认具体映射或定义错误。 ");
		return decision(RootCause.UNKNOWN, Confidence.LOW,
				"现有持久化事实不足以稳定归因；可以查看高级运行事件，但不应把原因直接归结为 Prompt 或模型幻觉。 ");
	}

	private List<StageView> stages(QueryRun run, List<RunEvent> events, QueryExecutionEvidence planning,
			QueryExecutionExplanation explanation, ReviewEvidence review) {
		List<StageView> result = new ArrayList<>();
		String errorCode = upper(run.errorCode());
		if (planning != null) {
			result.add(stage("RETRIEVAL", "语义召回", StageState.PASSED,
					planning.planningTrace().retrievalHitCount() + " 个 retrieval hit"));
			result.add(stage("CANDIDATE", "候选构建", StageState.PASSED,
					planning.planningTrace().candidateModelCount() + " 个模型候选"));
			result.add(stage("PLANNER", "语义绑定", StageState.PASSED, "Planner 输出已通过 governed candidate 校验"));
			result.add(stage("BLUEPRINT", "Semantic Blueprint", StageState.PASSED, "Semantic Blueprint 已解析并通过确定性校验"));
		}
		else {
			result.add(stage("RETRIEVAL", "语义召回", "RETRIEVAL_MISS".equals(errorCode) ? StageState.FAILED
					: StageState.UNKNOWN, "RETRIEVAL_MISS".equals(errorCode) ? Objects.toString(run.errorMessage(), "")
							: "旧 Run 或失败发生在 PlanningTrace 持久化之前"));
			result.add(stage("CANDIDATE", "候选构建", "CANDIDATE_BUILD_EMPTY".equals(errorCode) ? StageState.FAILED
					: StageState.UNKNOWN, "CANDIDATE_BUILD_EMPTY".equals(errorCode) ? Objects.toString(run.errorMessage(), "")
							: "没有完整候选证据"));
			result.add(stage("PLANNER", "语义绑定", errorCode.startsWith("PLANNER_") ? StageState.FAILED
					: hasEvent(events, "CLARIFICATION_REQUIRED") ? StageState.WAITING : StageState.UNKNOWN,
					Objects.toString(run.errorMessage(), "")));
			result.add(stage("PLAN", "Typed Plan", "PLAN_RESOLUTION_ERROR".equals(errorCode) ? StageState.FAILED
					: StageState.UNKNOWN, "PLAN_RESOLUTION_ERROR".equals(errorCode) ? Objects.toString(run.errorMessage(), "") : ""));
		}
		StageState sqlState = explanation.sqlExecutions().isEmpty() ? StageState.UNKNOWN
				: sqlFailed(explanation) ? StageState.FAILED : StageState.PASSED;
		result.add(stage("SQL", "SQL 与数据访问", sqlState,
				explanation.sqlExecutions().isEmpty() ? "没有持久化 SQL 执行事实"
						: explanation.sqlExecutions().size() + " 条 SQL 执行记录"));
		StageState reviewState = review == null ? StageState.UNKNOWN
				: "PASS".equals(upper(review.decision())) ? StageState.PASSED
						: "CLARIFY".equals(upper(review.decision())) ? StageState.WAITING : StageState.FAILED;
		result.add(stage("POST_REVIEW", "执行后验收", reviewState,
				review == null ? "没有持久化 Post-Execution Review 事实"
						: "decision=" + review.decision() + ", issueType=" + review.issueType() + ", evidence="
								+ review.evidence()));
		StageState executionState = switch (run.status()) {
			case SUCCEEDED -> StageState.PASSED;
			case FAILED, CANCELLED, EXPIRED -> StageState.FAILED;
			case WAITING_HUMAN -> StageState.WAITING;
			default -> StageState.UNKNOWN;
		};
		result.add(stage("EXECUTION", "运行结果", executionState,
				StringUtils.hasText(run.errorMessage()) ? run.errorMessage() : run.status().name()));
		return List.copyOf(result);
	}

	private GovernanceView governance(String runId, CorrectionEvidence correction) {
		String candidateId = correction == null ? candidateFromEvidence(runId).orElse("") : correction.candidateId();
		if (!StringUtils.hasText(candidateId)) return null;
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, candidate_type, asset_type, asset_key, status, risk_level, target_draft_version_id,
				       replay_summary_json, reviewed_by, review_comment, patch_json, update_time
				FROM qw_semantic_evolution_candidate WHERE id = ?
				""", candidateId);
		if (rows.isEmpty()) return null;
		Map<String, Object> row = rows.get(0);
		SemanticReplayService.ImpactPreview impact = null;
		try {
			impact = replayService.previewImpact(candidateId);
		}
		catch (RuntimeException ignored) {
			// Diagnosis remains available even if impact preview cannot be reconstructed for a legacy Candidate.
		}
		Map<String, Long> replayCounts = new LinkedHashMap<>();
		for (Map<String, Object> count : jdbc.queryForList("""
				SELECT status, COUNT(*) AS count FROM qw_semantic_replay_result
				WHERE candidate_id = ? GROUP BY status ORDER BY status
				""", candidateId)) {
			replayCounts.put(text(count.get("status")), ((Number) count.get("count")).longValue());
		}
		return new GovernanceView(candidateId, text(row.get("candidate_type")), text(row.get("asset_type")),
				text(row.get("asset_key")), text(row.get("status")), text(row.get("risk_level")),
				number(row.get("target_draft_version_id")), text(row.get("replay_summary_json")),
				patchReady(text(row.get("patch_json"))), impact, Map.copyOf(replayCounts));
	}

	private Optional<String> candidateFromEvidence(String runId) {
		try {
			List<String> ids = jdbc.queryForList("""
					SELECT candidate_id FROM qw_candidate_evidence
					WHERE evidence_json ->> 'runId' = ? ORDER BY create_time DESC LIMIT 1
					""", String.class, runId);
			return ids.stream().findFirst();
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private List<RepairAction> actions(QueryRun run, GovernanceView governance) {
		List<RepairAction> actions = new ArrayList<>();
		actions.add(action("CORRECT_BINDING", "修正语义映射", "QUERY / USER 修正会立即重跑；PROJECT 修正会创建受治理的项目 Alias。",
				run.terminal(), "CORRECTION"));
		actions.add(action("PROPOSE_DEFINITION", "提交语义/规划修正",
				"指标口径、时间、过滤、关系或规划策略问题进入 Semantic Evolution，不直接修改 Published Catalog 或 Planner Prompt。",
				run.terminal(), "EVOLUTION"));
		if (governance == null) return List.copyOf(actions);
		actions.add(action("OPEN_EVOLUTION", "完善修复 Patch", "复杂定义修正需要把用户描述落实为可审计 Semantic Patch。", true,
				"EVOLUTION"));
		String status = governance.status();
		if ("CANDIDATE".equals(status))
			actions.add(action("REVIEW_CANDIDATE", "审核修复建议",
					governance.patchReady() ? "审核通过后才能创建隔离 Draft。" : "请先把用户修正描述落实为可审计 Semantic Patch。",
					governance.patchReady(), "EVOLUTION"));
		if ("APPROVED".equals(status))
			actions.add(action("CREATE_DRAFT", "创建修复草稿", "从当前正式版本 Clone Draft，并原子应用已确认 Patch。", true,
					"EVOLUTION"));
		if ("PATCH_APPLIED".equals(status) || "REPLAY_FAILED".equals(status))
			actions.add(action("START_REPLAY", "运行定向回归", "只跑直接受影响 Case + canonical shape 代表样本，不宣称零回归。", true,
					"REPLAY"));
		if ("REPLAY_PASSED".equals(status))
			actions.add(action("READY_FOR_PUBLISH", "提交发布门禁", "Replay PASS 后进入独立发布决策。", true, "RELEASE"));
		if ("READY_FOR_PUBLISH".equals(status))
			actions.add(action("PUBLISH_DRAFT", "发布修复版本", "发布目标 Draft；发布事件会继续完成 Candidate lineage。", true,
					"RELEASE"));
		if ("PUBLISHED".equals(status) && governance.targetDraftVersionId() != null)
			actions.add(action("ACTIVATE_DRAFT", "激活修复版本", "激活后新会话立即固定使用新的 Published Semantic Catalog。", true,
					"RELEASE"));
		return List.copyOf(actions);
	}

	private PipelineEvidence pipeline(QueryRun run, List<RunEvent> events, QueryExecutionExplanation explanation,
			ReviewEvidence review) {
		String semanticPlanJson = latestEventPayload(events, "SEMANTIC_PLAN_SNAPSHOT");
		String executionPlanJson = latestEventPayload(events, "PLANNER_PLAN_SNAPSHOT");
		Map<String, Object> dryPlanEvent = readMap(latestEventPayload(events, "SEMANTIC_SQL_DRY_PLAN"));
		String semanticSql = text(dryPlanEvent.get("semanticSql"));
		String physicalSql = text(dryPlanEvent.get("physicalSql"));
		Map<String, Object> dryPlan = dryPlanEvent.get("dryPlan") instanceof Map<?, ?> value
				? stringKeyMap(value) : Map.of();
		List<Map<String, Object>> sqlTraces = StringUtils.hasText(run.attemptId()) ? jdbc.queryForList("""
				SELECT sql_text AS sql, guard_summary AS "guardSummary", cost_summary AS "costSummary",
				       explain_summary AS "explainSummary", preview_summary AS "previewSummary",
				       result_summary AS "resultSummary", status, retry_count AS "retryCount",
				       duration_ms AS "durationMs", error_type AS "errorType"
				FROM qw_sql_trace WHERE attempt_id = ? ORDER BY create_time
				""", run.attemptId()) : List.of();
		return new PipelineEvidence(semanticPlanJson, executionPlanJson, semanticSql, physicalSql, dryPlan,
				List.copyOf(sqlTraces), explanation.sqlExecutions(), review == null ? "" : review.decision(),
				review == null ? "" : review.issueType(), review == null ? "" : review.evidence(),
				review == null ? "" : review.repairBudget());
	}

	private String latestEventPayload(List<RunEvent> events, String eventType) {
		for (int index = events.size() - 1; index >= 0; index--) {
			RunEvent event = events.get(index);
			if (eventType.equals(event.eventType()) && StringUtils.hasText(event.payload())) {
				return event.payload();
			}
		}
		return "";
	}

	private Map<String, Object> stringKeyMap(Map<?, ?> source) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, value) -> result.put(Objects.toString(key, ""), value));
		return Map.copyOf(result);
	}

	private AdvancedEvidence advanced(QueryExecutionEvidence planning, QueryRun run, List<RunEvent> events) {
		return new AdvancedEvidence(run.errorCode(), run.currentNode(), planning == null ? List.of()
				: safe(planning.historicalExampleIds()), events.stream().map(RunEvent::eventType).distinct().toList());
	}

	private boolean patchReady(String patchJson) {
		if (!StringUtils.hasText(patchJson)) return false;
		try {
			var node = mapper.readTree(patchJson);
			return !node.path("proposalOnly").asBoolean(false) && node.path("operations").isArray()
					&& !node.path("operations").isEmpty();
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private boolean sqlFailed(QueryExecutionExplanation explanation) {
		return explanation.sqlExecutions()
			.stream()
			.anyMatch(item -> Set.of("FAILED", "REJECTED", "CANCELLED").contains(upper(item.get("status")))
					|| StringUtils.hasText(text(item.get("error"))) || StringUtils.hasText(text(item.get("errorType"))));
	}

	private boolean hasEvent(List<RunEvent> events, String eventType) {
		return events.stream().anyMatch(event -> eventType.equals(event.eventType()));
	}

	private boolean sameAsset(String leftType, String leftKey, String rightType, String rightKey) {
		return upper(leftType).equals(upper(rightType)) && Objects.equals(leftKey, rightKey);
	}

	private boolean containsAny(String source, String... needles) {
		for (String needle : needles) if (source.contains(needle)) return true;
		return false;
	}

	private RootCauseDecision decision(RootCause cause, Confidence confidence, String summary) {
		return new RootCauseDecision(cause, confidence, summary.trim());
	}

	private StageView stage(String code, String label, StageState state, String summary) {
		return new StageView(code, label, state.name(), summary == null ? "" : summary.trim());
	}

	private RepairAction action(String code, String label, String description, boolean enabled, String kind) {
		return new RepairAction(code, label, description, enabled, kind);
	}

	private <T> Optional<T> read(String json, Class<T> type) {
		try {
			return Optional.of(mapper.readValue(json, type));
		}
		catch (Exception ignored) {
			return Optional.empty();
		}
	}

	private Map<String, Object> readMap(String json) {
		try {
			return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private <T> List<T> safe(List<T> value) {
		return value == null ? List.of() : value;
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private String upper(Object value) {
		return text(value).trim().toUpperCase(Locale.ROOT);
	}

	private Long number(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	public enum RootCause {
		RETRIEVAL_MISS, CANDIDATE_BUILD_EMPTY, PLANNER_SELECTION_ERROR, PLANNER_REJECTED, CLARIFICATION_REQUIRED,
		SEMANTIC_DEFINITION_GAP, PLAN_RESOLUTION_ERROR, SQL_COMPILATION_ERROR, SQL_GUARD_ERROR,
		SQL_EXECUTION_ERROR, RESULT_REVIEW_ERROR, MODEL_GATEWAY_ERROR, NO_CONFIRMED_FAILURE, UNKNOWN
	}

	public enum Confidence {
		HIGH, MEDIUM, LOW
	}

	private enum StageState {
		PASSED, FAILED, WAITING, UNKNOWN
	}

	private record RootCauseDecision(RootCause rootCause, Confidence confidence, String summary) {
	}

	public record DiagnosisView(String runId, Long projectId, Long projectVersionId, String conversationId,
			String question, String runStatus, String rootCause, String confidence, String summary, List<StageView> stages,
			QueryExecutionEvidence.SelectedAssets selectedAssets,
			List<QueryExecutionEvidence.RetrievalCandidate> retrievalCandidates, CorrectionEvidence correction,
			GovernanceView governance, List<RepairAction> repairActions, PipelineEvidence pipeline, AdvancedEvidence advanced) {
	}

	public record StageView(String code, String label, String state, String summary) {
	}

	public record CorrectionEvidence(String eventType, String rawExpression, String assetType, String assetKey,
			String businessLabel, String scope, String rerunId, String candidateId, String category) {
	}

	private record ReviewEvidence(String decision, String issueType, String confidence, String evidence,
			String repairBudget) {
	}

	public record GovernanceView(String candidateId, String candidateType, String assetType, String assetKey,
			String status, String riskLevel, Long targetDraftVersionId, String replaySummary, boolean patchReady,
			SemanticReplayService.ImpactPreview impact, Map<String, Long> replayResultCounts) {
	}

	public record RepairAction(String code, String label, String description, boolean enabled, String kind) {
	}

	public record PipelineEvidence(String semanticPlanJson, String executionPlanJson, String semanticSql,
			String physicalSql, Map<String, Object> dryPlan, List<Map<String, Object>> sqlTraces,
			List<Map<String, Object>> sourceExecutions, String reviewDecision, String reviewIssueType,
			String reviewEvidence, String repairBudget) {
	}

	public record AdvancedEvidence(String runErrorCode, String currentNode, List<String> historicalExampleIds,
			List<String> eventTypes) {
	}
}
