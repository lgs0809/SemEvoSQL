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

import static cn.lgs.semevosql.constant.Constant.ACTIVE_TODO_ID;
import static cn.lgs.semevosql.constant.Constant.APPROVAL_REQUIRED;
import static cn.lgs.semevosql.constant.Constant.APPROVED_PLAN_RECOVERY;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.CATALOG_HASH;
import static cn.lgs.semevosql.constant.Constant.FORCE_SEMANTIC_REPLAN;
import static cn.lgs.semevosql.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static cn.lgs.semevosql.constant.Constant.PREFERRED_EXECUTION_PLAN;
import static cn.lgs.semevosql.constant.Constant.PRINCIPAL_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_CASE_HINTS;
import static cn.lgs.semevosql.constant.Constant.QUERY_PATTERN_ID;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_HINT;
import static cn.lgs.semevosql.constant.Constant.RETRIEVAL_REPAIR_QUERY;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_REPLAN_FEEDBACK;
import static cn.lgs.semevosql.constant.Constant.TABLE_RELATION_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.TODO_ENABLED;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;

import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.dto.schema.TableDTO;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService;
import cn.lgs.semevosql.clarification.RuntimeSemanticBindingService.BindingContext;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.optimization.RuntimeOptimizationService;
import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryExecutionEvidence;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.application.SemanticPlanningClarificationRequiredException;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningRequest;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningResult;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.QueryTask;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.task.RequestExecutionContext;
import cn.lgs.semevosql.task.RequestExecutionContext.TaskExecutionResult;
import cn.lgs.semevosql.trajectory.TrajectoryAnalysisService;
import cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves a deterministic, Semantic Blueprint before generic tool planning and SQL
 * generation. The node rejects disconnected semantic models instead of allowing an LLM to
 * invent joins or metric definitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticBlueprintNode implements NodeAction {

	private final SemanticCatalogApplicationService semanticCatalogService;

	private final SemanticBlueprintPipeline semanticBlueprintPipeline;

	private final ValidatedQueryExampleService queryExampleService;

	private final QueryRunService queryRunService;

	private final RunExecutionFenceService executionFence;

	private final ExecutionSnapshotService executionSnapshotService;

	private final TrajectoryAnalysisService trajectoryAnalysisService;

	private final RuntimeOptimizationService runtimeOptimizationService;

	private final ConversationContextDependencyFingerprintService contextFingerprintService;

	private final RuntimeClarificationService runtimeClarificationService;

	private final RuntimeSemanticBindingService runtimeSemanticBindingService;

	private final QueryTaskRepository queryTaskRepository;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String runId = StateUtil.getStringValue(state, RUN_ID, null);
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, null);
		if (runId != null && !runId.isBlank() && attemptId != null && !attemptId.isBlank()) {
			executionFence.assertActive(runId, attemptId);
		}
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		if (state.value(APPROVED_PLAN_RECOVERY, false)) {
			SemanticBlueprint approved = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
					(SemanticBlueprint) null);
			if (approved == null || !approved.isExecutable() || !Objects.equals(projectId, approved.getProjectId())
					|| !Objects.equals(projectVersionId, approved.getProjectVersionId())) {
				throw new IllegalStateException("Approved Semantic Blueprint recovery payload is missing or incompatible");
			}
			log.info("Reusing exact approved Semantic Blueprint for durable checkpoint-loss recovery");
			return Map.of(TYPED_SEMANTIC_PLAN, approved, APPROVED_PLAN_RECOVERY, false, FORCE_SEMANTIC_REPLAN, false);
		}
		String semanticReplanFeedback = StateUtil.getStringValue(state, SEMANTIC_REPLAN_FEEDBACK, "");
		String planningQuery = semanticReplanFeedback.isBlank() ? canonicalQuery
				: canonicalQuery + "\n[运行时语义重规划反馈]\n" + semanticReplanFeedback;
		SchemaDTO schema = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class, (SchemaDTO) null);
		List<String> physicalTables = schema == null || schema.getTable() == null ? List.of()
				: schema.getTable().stream().map(TableDTO::getName).toList();

		String catalogHash = StateUtil.getStringValue(state, CATALOG_HASH, null);
		Long runDeadlineEpochMillis = StateUtil.getObjectValue(state, RUN_DEADLINE_EPOCH_MILLIS, Long.class, (Long) null);
		String contextHash = contextFingerprintService.fingerprint(runId, planningQuery);
		String principalId = StateUtil.getStringValue(state, PRINCIPAL_ID, null);
		BindingContext emptyBindings = new BindingContext(List.of(), QueryCaseHints.empty(), List.of());
		BindingContext runtimeBindings = runtimeSemanticBindingService.resolve(projectId, projectVersionId, principalId,
				planningQuery);
		if (runtimeBindings == null) {
			runtimeBindings = emptyBindings;
		}
		BindingContext clarified = runtimeClarificationService.resolvedBindingContext(runId, projectId,
				projectVersionId);
		if (clarified == null) {
			clarified = emptyBindings;
		}
		BindingContext requestBindings = requestSemanticBindings(runId);
		BindingContext mergedBindings = runtimeSemanticBindingService.merge(List.of(runtimeBindings, requestBindings, clarified));
		if (mergedBindings == null) {
			mergedBindings = emptyBindings;
		}
		QueryCaseHints requiredHints = mergedBindings.hints();
		if (state.value(TODO_ENABLED, false)) {
			requiredHints = mergeHints(requiredHints, acceptedTodoHints(runId, canonicalQuery));
		}
		runtimeSemanticBindingService.recordAppliedBindings(mergedBindings, runId, attemptId);
		boolean forceReplan = state.value(FORCE_SEMANTIC_REPLAN, false);
		String retrievalRepairQuery = StateUtil.getStringValue(state, RETRIEVAL_REPAIR_QUERY, "");
		List<String> planningTables = mergedBindings.additionalPhysicalTables().isEmpty()
				? physicalTables
				: java.util.stream.Stream.concat(physicalTables.stream(), mergedBindings.additionalPhysicalTables().stream())
					.distinct()
					.toList();
		PlanResolution resolution;
		try {
			resolution = resolvePlan(runId, attemptId, runDeadlineEpochMillis, projectId, projectVersionId, catalogHash,
					planningQuery, contextHash, planningTables, requiredHints, forceReplan, retrievalRepairQuery, principalId);
		}
		catch (SemanticPlanningClarificationRequiredException clarificationRequired) {
			executionFence.assertActive(runId, attemptId);
			var clarification = runtimeClarificationService.createPlanningClarification(runId, planningQuery,
					clarificationRequired.clarification());
			throw new RuntimeClarificationRequiredException(runId, clarification.clarificationId());
		}
		executionFence.assertActive(runId, attemptId);
		SemanticBlueprint plan = resolution.plan();
		plan.setBindingDependencies(bindingDependencies(mergedBindings));
		String activeTodoId = StateUtil.getStringValue(state, ACTIVE_TODO_ID, "");
		if (state.value(TODO_ENABLED, false) && !activeTodoId.isBlank()) {
			queryTaskRepository.savePlan(runId, activeTodoId, plan);
		}
		persistSemanticPlanSnapshot(runId, attemptId, activeTodoId, plan);
		if (state.value(APPROVAL_REQUIRED, false)) {
			persistQueryUnderstanding(runId, attemptId, activeTodoId, canonicalQuery, plan);
			persistApprovalPlanSnapshot(runId, attemptId, activeTodoId, plan);
		}
		QueryCaseHints recalledHints = resolution.historicalHints();
		QueryCaseHints caseHints = requiredHints.emptyHints() ? recalledHints : mergeHints(recalledHints, requiredHints);
		log.info("Resolved Semantic Blueprint: models={}, metrics={}, dimensions={}, relationships={}, warnings={}",
				plan.getModels().size(), plan.getMetrics().size(), plan.getDimensions().size(),
				plan.getRelationships().size(), plan.getValidationWarnings());
		if (!plan.isExecutable()) {
			throw new IllegalStateException(
					"Semantic Blueprint is not executable: " + String.join("; ", plan.getValidationErrors()));
		}
		Map<String, Object> result = new HashMap<>();
		result.put(TYPED_SEMANTIC_PLAN, plan);
		if (schema != null) {
			result.put(TABLE_RELATION_OUTPUT, scopeSchema(schema, plan));
		}
		result.put(FORCE_SEMANTIC_REPLAN, false);
		result.put(RETRIEVAL_REPAIR_QUERY, "");
		result.put(RETRIEVAL_REPAIR_HINT, "");
		result.put(SEMANTIC_REPLAN_FEEDBACK, "");
		if (forceReplan) {
			result.put(PREFERRED_EXECUTION_PLAN, Map.of());
			result.put(QUERY_PATTERN_ID, "");
		}
		String scopedSemanticPrompt = semanticCatalogService.renderRuntimePrompt(projectId, projectVersionId,
				plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getPhysicalTable).toList());
		if (scopedSemanticPrompt != null && !scopedSemanticPrompt.isBlank()) {
			result.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, scopedSemanticPrompt);
		}
		if (!caseHints.emptyHints()) {
			result.put(QUERY_CASE_HINTS, caseHints);
		}
		resolvePreferredExecutionPlan(state, projectId, projectVersionId, plan).ifPresent(preferred -> {
			result.put(QUERY_PATTERN_ID, preferred.patternId());
			result.put(PREFERRED_EXECUTION_PLAN, preferred.plan());
		});
		return result;
	}

	private void persistQueryUnderstanding(String runId, String attemptId, String activeTodoId, String query,
			SemanticBlueprint plan) {
		if (runId == null || runId.isBlank() || plan == null) {
			return;
		}
		List<String> facts = new ArrayList<>();
		plan.getMetrics().forEach(metric -> facts.add("指标=" + preferredLabel(metric.getBusinessName(), metric.getMetricCode())));
		plan.getDimensions()
			.forEach(dimension -> facts.add("维度=" + preferredLabel(dimension.getBusinessName(), dimension.getDimensionCode())));
		if (plan.getTimeRange() != null) {
			if (plan.getTimeRange().getTimeColumn() != null && !plan.getTimeRange().getTimeColumn().isBlank()) {
				facts.add("时间字段=" + plan.getTimeRange().getTimeColumn());
			}
			if (plan.getTimeRange().getRelativeExpression() != null && !plan.getTimeRange().getRelativeExpression().isBlank()) {
				facts.add("时间范围=" + plan.getTimeRange().getRelativeExpression());
			}
		}
		String understanding = "我将查询：" + query + (facts.isEmpty() ? "" : "（" + String.join("；", facts) + "）");
		String scope = activeTodoId == null || activeTodoId.isBlank() ? "simple" : activeTodoId;
		queryRunService.appendEvent(runId, attemptId, "QUERY_UNDERSTANDING_READY", "semantic-plan", understanding,
				"Grounded query understanding is ready for approval",
				"query-understanding:" + runId + ":" + scope + ":" + Integer.toHexString(understanding.hashCode()));
	}

	private void persistSemanticPlanSnapshot(String runId, String attemptId, String activeTodoId, SemanticBlueprint plan) {
		if (runId == null || runId.isBlank() || plan == null) {
			return;
		}
		String payload = canonicalJson.write(plan);
		String scope = activeTodoId == null || activeTodoId.isBlank() ? "simple" : activeTodoId;
		queryRunService.appendEvent(runId, attemptId, "SEMANTIC_PLAN_SNAPSHOT", "semantic-plan", payload,
				"Exact Semantic Blueprint snapshot persisted for diagnosis and recovery",
				"semantic-plan-snapshot:" + runId + ":" + scope + ":" + Integer.toHexString(payload.hashCode()));
	}

	private void persistApprovalPlanSnapshot(String runId, String attemptId, String activeTodoId, SemanticBlueprint plan) {
		if (runId == null || runId.isBlank() || plan == null) {
			return;
		}
		String payload = canonicalJson.write(plan);
		String scope = activeTodoId == null || activeTodoId.isBlank() ? "simple" : activeTodoId;
		queryRunService.appendEvent(runId, attemptId, "APPROVAL_PLAN_SNAPSHOT", "semantic-plan", payload,
				"Exact Semantic Blueprint snapshot persisted for approval",
				"approval-plan:" + runId + ":" + scope + ":" + Integer.toHexString(payload.hashCode()));
	}

	private String preferredLabel(String businessName, String code) {
		return businessName == null || businessName.isBlank() ? code : businessName.trim();
	}

	private BindingContext requestSemanticBindings(String runId) {
		BindingContext empty = new BindingContext(List.of(), QueryCaseHints.empty(), List.of());
		if (runId == null || runId.isBlank()) {
			return empty;
		}
		try {
			var event = queryRunService.eventByIdempotency(runId, "request-semantic-bindings:" + runId).orElse(null);
			if (event == null || event.payload() == null || event.payload().isBlank()) {
				return empty;
			}
			return JsonUtil.getObjectMapper().readValue(event.payload(), BindingContext.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to restore explicit request semantic bindings", ex);
		}
	}

	private QueryCaseHints acceptedTodoHints(String runId, String currentQuery) {
		if (runId == null || runId.isBlank() || !queryTaskRepository.enabled(runId)) {
			return QueryCaseHints.empty();
		}
		List<QueryTask> tasks = queryTaskRepository.list(runId);
		if (tasks.isEmpty()) {
			return QueryCaseHints.empty();
		}
		RequestExecutionContext context = new RequestExecutionContext(runId,
				currentQuery == null || currentQuery.isBlank() ? "request" : currentQuery, tasks);
		for (QueryTask task : tasks) {
			if (task.status() != QueryTask.TaskStatus.DONE) {
				continue;
			}
			SemanticBlueprint acceptedPlan = queryTaskRepository.plan(runId, task.taskId());
			if (acceptedPlan == null) {
				continue;
			}
			context.acceptReviewedTask(new TaskExecutionResult(task.taskId(), acceptedPlan, Map.of(),
					PostExecutionReview.deterministicPass(List.of()), List.of()));
		}
		return context.completedTasks().isEmpty() ? QueryCaseHints.empty() : context.acceptedHints();
	}

	private QueryCaseHints mergeHints(QueryCaseHints base, QueryCaseHints clarified) {
		if (clarified == null || clarified.emptyHints()) {
			return base == null ? QueryCaseHints.empty() : base;
		}
		if (base == null || base.emptyHints()) {
			return clarified;
		}
		Set<String> models = new HashSet<>(base.modelCodes());
		models.addAll(clarified.modelCodes());
		Set<String> metrics = new HashSet<>(base.metricCodes());
		metrics.addAll(clarified.metricCodes());
		Set<String> dimensions = new HashSet<>(base.dimensionCodes());
		dimensions.addAll(clarified.dimensionCodes());
		Set<String> grains = new HashSet<>(base.grainCodes());
		grains.addAll(clarified.grainCodes());
		Set<String> relationships = new HashSet<>(base.relationshipCodes());
		relationships.addAll(clarified.relationshipCodes());
		Set<String> rules = new HashSet<>(base.ruleCodes());
		rules.addAll(clarified.ruleCodes());
		List<QueryCaseHints.EnumBindingHint> enums = java.util.stream.Stream
			.concat(base.enumBindings().stream(), clarified.enumBindings().stream())
			.distinct()
			.toList();
		List<QueryCaseHints.FilterBindingHint> filters = java.util.stream.Stream
			.concat(base.filterBindings().stream(), clarified.filterBindings().stream())
			.distinct()
			.toList();
		List<QueryCaseHints.AssetBindingHint> assets = java.util.stream.Stream
			.concat(base.assetBindings().stream(), clarified.assetBindings().stream())
			.distinct()
			.toList();
		List<String> sources = java.util.stream.Stream
			.concat(base.sourceExampleIds().stream(), clarified.sourceExampleIds().stream())
			.distinct()
			.toList();
		Map<String, Double> scores = new HashMap<>(base.componentScores());
		scores.putAll(clarified.componentScores());
		return new QueryCaseHints(models, metrics, dimensions, grains, relationships, rules, enums, filters, assets,
				clarified.timeBinding() == null ? base.timeBinding() : clarified.timeBinding(),
				base.strictAssetBinding() || clarified.strictAssetBinding(), "RUNTIME_CLARIFICATION", sources,
				Math.max(base.confidence(), clarified.confidence()), scores);
	}

	private List<SemanticBlueprint.BindingDependency> bindingDependencies(BindingContext context) {
		if (context == null || context.bindings() == null || context.bindings().isEmpty()) {
			return List.of();
		}
		Map<String, SemanticBlueprint.BindingDependency> unique = new java.util.LinkedHashMap<>();
		context.bindings().forEach(binding -> {
			String key = Objects.toString(binding.normalizedPhrase(), "") + "|" + binding.assetType() + "|"
					+ binding.assetKey() + "|" + binding.source() + "|" + Objects.toString(binding.principalId(), "");
			unique.put(key, SemanticBlueprint.BindingDependency.builder()
				.phrase(binding.displayPhrase())
				.assetType(binding.assetType())
				.assetKey(binding.assetKey())
				.scope(binding.source())
				.source(binding.source())
				.principalId(binding.principalId())
				.sourceRecordId(binding.sourceRecordId())
				.build());
		});
		return List.copyOf(unique.values());
	}

	private PlanResolution resolvePlan(String runId, String attemptId, Long runDeadlineEpochMillis, Long projectId,
			Long projectVersionId, String catalogHash, String canonicalQuery, String contextHash,
			List<String> physicalTables, QueryCaseHints requiredHints, boolean forceReplan, String retrievalRepairQuery,
			String principalId) {
		Optional<SemanticBlueprint> pinned = !forceReplan && (requiredHints == null || requiredHints.emptyHints())
				? pinnedPlan(runId, projectId, projectVersionId, physicalTables) : Optional.empty();
		if (pinned.isPresent()) {
			QueryCaseHints historicalHints = queryExampleService.recallHints(projectId, projectVersionId, catalogHash,
					canonicalQuery, contextHash, principalId, 5);
			executionFence.assertActive(runId, attemptId);
			queryExampleService.recordHintUsage(runId, attemptId, historicalHints);
			return new PlanResolution(pinned.orElseThrow(), historicalHints);
		}
		PlanningResult planning = semanticBlueprintPipeline.plan(new PlanningRequest(projectId, projectVersionId,
				catalogHash, canonicalQuery, contextHash, physicalTables, requiredHints, 20, 5, retrievalRepairQuery,
				principalId, runDeadlineEpochMillis));
		executionFence.assertActive(runId, attemptId);
		queryExampleService.recordHintUsage(runId, attemptId, planning.historicalHints());
		if (runId != null && !runId.isBlank()) {
			QueryExecutionEvidence evidence = QueryExecutionEvidence.semanticPlanning(planning);
			queryRunService.appendEvent(runId, attemptId, "PLANNING_TRACE", "semantic-plan", canonicalJson.write(evidence),
					"Governed semantic planning stages completed", "planning-evidence:" + evidence.evidenceId());
		}
		return new PlanResolution(planning.plan(), planning.historicalHints());
	}

	private SchemaDTO scopeSchema(SchemaDTO schema, SemanticBlueprint plan) {
		Set<String> selectedTables = plan.getModels()
			.stream()
			.map(SemanticBlueprint.ModelSelection::getPhysicalTable)
			.filter(value -> value != null && !value.isBlank())
			.collect(java.util.stream.Collectors.toCollection(HashSet::new));
		SchemaDTO scoped = new SchemaDTO();
		scoped.setName(schema.getName());
		scoped.setDescription(schema.getDescription());
		List<TableDTO> tables = schema.getTable() == null ? List.of()
				: schema.getTable().stream().filter(table -> selectedTables.contains(table.getName())).toList();
		scoped.setTable(tables);
		scoped.setTableCount(tables.size());
		scoped.setForeignKeys(plan.getRelationships()
			.stream()
			.map(SemanticBlueprint.RelationshipSelection::getJoinCondition)
			.filter(value -> value != null && !value.isBlank())
			.distinct()
			.toList());
		return scoped;
	}

	private Optional<SemanticBlueprint> pinnedPlan(String runId, Long projectId, Long projectVersionId,
			List<String> selectedPhysicalTables) {
		if (runId == null || runId.isBlank()) {
			return Optional.empty();
		}
		QueryRun run = queryRunService.get(runId);
		return executionSnapshotService.readTyped(run.executionSnapshot())
			.filter(snapshot -> snapshot.strictComparable() && snapshot.semanticPlan() != null)
			.map(snapshot -> snapshot.semanticPlan())
			.map(plan -> validatePinnedPlan(plan, projectId, projectVersionId, selectedPhysicalTables));
	}

	private SemanticBlueprint validatePinnedPlan(SemanticBlueprint plan, Long projectId, Long projectVersionId,
			List<String> selectedPhysicalTables) {
		if (!projectId.equals(plan.getProjectId()) || !projectVersionId.equals(plan.getProjectVersionId())) {
			throw new IllegalStateException("Pinned semantic plan does not belong to the active project version");
		}
		if (!plan.isExecutable()) {
			throw new IllegalStateException("Pinned semantic plan is not executable");
		}
		Set<String> pinnedTables = plan.getModels()
			.stream()
			.map(SemanticBlueprint.ModelSelection::getPhysicalTable)
			.collect(java.util.stream.Collectors.toCollection(HashSet::new));
		Set<String> selectedTables = new HashSet<>(selectedPhysicalTables);
		if (!pinnedTables.containsAll(selectedTables)) {
			throw new IllegalStateException("Selected Schema tables are outside the pinned semantic plan");
		}
		return plan;
	}

	private Optional<PreferredPlan> resolvePreferredExecutionPlan(OverAllState state, Long projectId,
			Long projectVersionId, SemanticBlueprint plan) {
		String runId = StateUtil.getStringValue(state, RUN_ID, null);
		if (runId == null || runId.isBlank()) {
			return Optional.empty();
		}
		try {
			QueryRun run = queryRunService.get(runId);
			String compatibilityHash = executionSnapshotService.readTyped(run.executionSnapshot())
				.map(snapshot -> snapshot.compatibilityHash())
				.orElse(null);
			if (compatibilityHash == null) {
				return Optional.empty();
			}
			return trajectoryAnalysisService.findPatternForPlan(projectId, projectVersionId, compatibilityHash, plan)
				.flatMap(pattern -> {
					String patternId = pattern.get("id").toString();
					Map<String, Object> facts = Map.of("sourceCount", plan.getSourceSubPlans().size(), "compilerMode",
							plan.getCompilerMode());
					return runtimeOptimizationService
						.findApplicablePlan(projectId, projectVersionId, patternId, compatibilityHash, facts)
						.map(preferred -> new PreferredPlan(patternId, preferred));
				});
		}
		catch (RuntimeException ex) {
			log.warn("Preferred execution plan lookup was skipped for run {}: {}", runId, ex.getMessage());
			return Optional.empty();
		}
	}

	private record PlanResolution(SemanticBlueprint plan, QueryCaseHints historicalHints) {
	}

	private record PreferredPlan(String patternId, Map<String, Object> plan) {
	}

}
