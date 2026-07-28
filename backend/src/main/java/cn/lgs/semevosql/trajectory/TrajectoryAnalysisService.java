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
package cn.lgs.semevosql.trajectory;

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.LowRiskSemanticEvolutionCandidateEvent;
import cn.lgs.semevosql.evolution.PlanningPolicyDistillationService;
import cn.lgs.semevosql.evolution.PlanningPolicyDistillationService.DistilledPolicy;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatch;
import cn.lgs.semevosql.learning.QueryCaseGovernanceProperties;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.learning.QueryPatternTemplateService.CaptureMode;
import cn.lgs.semevosql.learning.ValidatedSemanticSqlPatternService;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatch.PolicyAssetType;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatchService;
import cn.lgs.semevosql.evolution.application.LegacyEvolutionChangeSetBridge;
import cn.lgs.semevosql.multisource.MultiSourcePolicyService;
import cn.lgs.semevosql.run.ExecutionSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.SemanticPlanSnapshotService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Converts durable Episode/Attempt/Trace facts into comparable query patterns, execution
 * paths, Pareto profiles and explainable detour signals. This service is deliberately
 * observation-only: it never changes a published catalog or runtime path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrajectoryAnalysisService {

	private final JdbcTemplate jdbc;

	private final ExecutionSnapshotService executionSnapshotService;

	private final SemanticPlanSnapshotService semanticPlanSnapshots;

	private final TrajectoryPathProfileService pathProfileService;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final QueryPatternTemplateService patternTemplateService;

	private final ValidatedSemanticSqlPatternService semanticSqlPatternService;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final VersionedJson versionedJson = new VersionedJson();

	private final MultiSourcePolicyPatchService policyPatchService;

	private final MultiSourcePolicyService multiSourcePolicyService;

	private final QueryCaseGovernanceProperties queryCaseProperties;

	private final SemanticEvolutionEvidenceService evolutionEvidenceService;

	private final PlanningPolicyDistillationService planningPolicyDistillationService;

	private LegacyEvolutionChangeSetBridge changeSetBridge;

	private ApplicationEventPublisher eventPublisher;

	@Autowired
	public void setLowRiskEvolutionAutomation(LegacyEvolutionChangeSetBridge changeSetBridge,
			ApplicationEventPublisher eventPublisher) {
		this.changeSetBridge = changeSetBridge;
		this.eventPublisher = eventPublisher;
	}

	@Value("${semevosql.trajectory.minimum-episodes:3}")
	private int minimumEpisodes;

	@Value("${semevosql.trajectory.minimum-recurrence-rate:0.60}")
	private double minimumRecurrenceRate;

	@Value("${semevosql.trajectory.high-confidence-episodes:5}")
	private int highConfidenceEpisodes;

	@Value("${semevosql.trajectory.high-confidence-rate:0.80}")
	private double highConfidenceRate;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Map<String, Object>> analyzeEpisode(String episodeId) {
		Map<String, Object> episode = one("SELECT * FROM qw_episode WHERE id = ?", episodeId);
		List<Map<String, Object>> attempts = jdbc
			.queryForList("SELECT * FROM qw_attempt WHERE episode_id = ? ORDER BY attempt_no, create_time", episodeId);
		List<Map<String, Object>> analyzed = new ArrayList<>();
		for (Map<String, Object> attempt : attempts) {
			String attemptId = text(attempt.get("id"));
			Optional<Map<String, Object>> existing = optional("SELECT * FROM qw_trajectory_path WHERE attempt_id = ?",
					attemptId);
			if (existing.isPresent()) {
				analyzed.add(existing.orElseThrow());
				continue;
			}
			if ("RUNNING".equals(text(attempt.get("status")))) {
				continue;
			}
			analyzed.add(analyzeAttempt(episode, attempt));
		}
		return analyzed;
	}

	public Optional<Map<String, Object>> findPatternForPlan(Long projectId, Long projectVersionId,
			String executionCompatibilityHash, SemanticBlueprint plan) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(executionCompatibilityHash)
				|| plan == null) {
			return Optional.empty();
		}
		PatternShape shape = patternShape(Map.of("normalized_question", plan.getCanonicalQuery()), plan, List.of(),
				List.of(), List.of());
		return optional("""
				SELECT * FROM qw_query_pattern
				WHERE project_id = ? AND project_version_id = ? AND execution_compatibility_hash = ?
				  AND shape_hash = ? AND status IN ('OBSERVE_ONLY','PLAN_ONLY','EXECUTABLE')
				ORDER BY success_count DESC, episode_count DESC, last_seen_time DESC LIMIT 1
				""", projectId, projectVersionId, executionCompatibilityHash, shape.shapeHash());
	}

	public List<Map<String, Object>> listPatterns(Long projectId, Long projectVersionId, int limit) {
		if (projectVersionId == null) {
			return jdbc.queryForList("""
					SELECT * FROM qw_query_pattern WHERE project_id = ?
					ORDER BY last_seen_time DESC LIMIT ?
					""", projectId, bounded(limit));
		}
		return jdbc.queryForList("""
				SELECT * FROM qw_query_pattern WHERE project_id = ? AND project_version_id = ?
				ORDER BY last_seen_time DESC LIMIT ?
				""", projectId, projectVersionId, bounded(limit));
	}

	public Map<String, Object> pattern(String patternId) {
		Map<String, Object> result = new LinkedHashMap<>(one("SELECT * FROM qw_query_pattern WHERE id = ?", patternId));
		result.put("profiles", jdbc.queryForList("""
				SELECT * FROM qw_query_path_profile WHERE pattern_id = ?
				ORDER BY dominated, pareto_rank, sample_count DESC
				""", patternId));
		result.put("detours", jdbc.queryForList("""
				SELECT * FROM qw_detour_signal WHERE pattern_id = ?
				ORDER BY confidence DESC, create_time DESC
				""", patternId));
		return result;
	}

	public List<Map<String, Object>> listPaths(String patternId, int limit) {
		return jdbc.queryForList("""
				SELECT * FROM qw_trajectory_path WHERE pattern_id = ?
				ORDER BY create_time DESC LIMIT ?
				""", patternId, bounded(limit));
	}

	public List<Map<String, Object>> listDetours(Long projectId, String status, int limit) {
		if (StringUtils.hasText(status)) {
			return jdbc.queryForList("""
					SELECT * FROM qw_detour_signal WHERE project_id = ? AND status = ?
					ORDER BY confidence DESC, create_time DESC LIMIT ?
					""", projectId, status.toUpperCase(Locale.ROOT), bounded(limit));
		}
		return jdbc.queryForList("""
				SELECT * FROM qw_detour_signal WHERE project_id = ?
				ORDER BY confidence DESC, create_time DESC LIMIT ?
				""", projectId, bounded(limit));
	}

	@Transactional
	public Map<String, Object> recomputePattern(String patternId) {
		pathProfileService.recompute(patternId);
		refreshDetourRecurrence(patternId);
		generateCandidates(patternId);
		return pattern(patternId);
	}

	private Map<String, Object> analyzeAttempt(Map<String, Object> episode, Map<String, Object> attempt) {
		String episodeId = text(episode.get("id"));
		String attemptId = text(attempt.get("id"));
		Long projectId = number(episode.get("project_id"));
		Long projectVersionId = number(episode.get("project_version_id"));
		String catalogHash = text(episode.get("catalog_hash"));
		Map<String, Object> run = optional("SELECT * FROM qw_query_run WHERE attempt_id = ?", attemptId)
			.or(() -> optional("SELECT * FROM qw_query_run WHERE episode_id = ? ORDER BY create_time DESC LIMIT 1",
					episodeId))
			.orElse(Map.of());
		SnapshotView snapshot = snapshot(run, catalogHash, projectVersionId);
		List<Map<String, Object>> nodes = jdbc.queryForList("""
				SELECT * FROM qw_node_trace WHERE attempt_id = ? ORDER BY create_time, id
				""", attemptId);
		List<Map<String, Object>> sqlTraces = jdbc.queryForList("""
				SELECT * FROM qw_sql_trace WHERE attempt_id = ? ORDER BY create_time, id
				""", attemptId);
		List<Map<String, Object>> feedback = jdbc.queryForList("SELECT * FROM qw_feedback WHERE episode_id = ?",
				episodeId);
		String runId = text(run.get("run_id"));
		List<Map<String, Object>> postExecutionReviews = postExecutionReviews(runId);
		Map<String, Object> postExecutionReview = postExecutionReviews.isEmpty() ? Map.of()
				: postExecutionReviews.get(postExecutionReviews.size() - 1);
		boolean postExecutionReviewPassed = "PASS".equalsIgnoreCase(text(postExecutionReview.get("decision")));
		List<Map<String, Object>> clarifications = StringUtils.hasText(runId) ? jdbc.queryForList(
				"SELECT * FROM qw_runtime_clarification WHERE run_id = ? ORDER BY create_time", runId) : List.of();
		List<Map<String, Object>> sources = StringUtils.hasText(runId)
				? jdbc.queryForList("SELECT * FROM qw_source_sub_run WHERE run_id = ? ORDER BY datasource_id", runId)
				: List.of();
		List<Map<String, Object>> merges = StringUtils.hasText(runId)
				? jdbc.queryForList("SELECT * FROM qw_merge_execution WHERE run_id = ? ORDER BY create_time", runId)
				: List.of();
		List<Map<String, Object>> artifacts = StringUtils.hasText(runId)
				? jdbc.queryForList("SELECT * FROM qw_result_artifact WHERE run_id = ? ORDER BY create_time", runId)
				: List.of();

		String normalizedQuestion = normalize(text(episode.get("normalized_question")), text(episode.get("original_question")));
		PatternShape patternShape = patternShape(episode, snapshot.plan(), sources, clarifications, sqlTraces);
		boolean succeeded = "SUCCEEDED".equals(text(attempt.get("status")));
		String patternId = upsertPattern(projectId, projectVersionId, catalogHash, snapshot.compatibilityHash(),
				patternShape, succeeded);
		boolean corrected = feedback.stream().anyMatch(this::negativeOrCorrectionFeedback);
		if (succeeded) {
			CaptureMode captureMode = patternTemplateService.captureSuccessful(patternId, projectId, projectVersionId,
					catalogHash, runId, attemptId, snapshot.plan(), sqlTraces, !clarifications.isEmpty(), corrected,
					postExecutionReviewPassed);
			if (captureMode != CaptureMode.NONE) {
				promotePatternReuseMode(patternId, captureMode);
			}
			semanticSqlPatternService.captureSuccessful(projectId, projectVersionId, catalogHash, runId, attemptId,
					snapshot.plan(), sqlTraces, corrected, postExecutionReviewPassed);
		}
		semanticSqlPatternService.recordRunOutcome(runId, succeeded && postExecutionReviewPassed && !corrected);
		List<String> nodeSequence = nodeSequence(nodes, runId);
		List<Map<String, Object>> decisions = decisions(nodes, clarifications, sources, postExecutionReview);
		List<Map<String, Object>> sourceSequence = sources.stream().map(this::safeSource).toList();
		int retries = sqlTraces.stream().mapToInt(this::logicalRetryCount).sum();
		Scores scores = scores(episode, attempt, run, sqlTraces, feedback, sources, artifacts, retries,
				clarifications.size());
		String pathSignature = hash(json(Map.of("nodes", nodeSequence, "decisions",
				decisions.stream().map(item -> item.getOrDefault("decision", "")).toList(), "sources",
				sourceSequence.stream().map(item -> item.get("datasourceId")).toList())));
		String pathId = UUID.randomUUID().toString();
		Map<String, Object> cost = new LinkedHashMap<>();
		cost.put("latencyMs", numberOrZero(episode.get("duration_ms")));
		cost.put("tokenCount", numberOrZero(episode.get("token_count")));
		cost.put("retryCount", retries);
		cost.put("clarificationCount", clarifications.size());
		cost.put("nodeCount", nodeSequence.size());
		cost.put("sourceCount", sources.size());
		cost.put("mergeCount", merges.size());
		Map<String, Object> proof = resultProof(episode, attempt, run, sqlTraces, feedback, sources, artifacts);
		jdbc.update("""
				INSERT INTO qw_trajectory_path
				(id, project_id, project_version_id, episode_id, attempt_id, run_id, pattern_id,
				 execution_compatibility_hash, path_signature, node_sequence_json, decision_sequence_json,
				 source_sequence_json, status, correctness_score, safety_score, coverage_score,
				 freshness_score, stability_score, latency_ms, token_count, retry_count,
				 clarification_count, source_count, merge_count, cost_json, result_proof_json, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", pathId, projectId, projectVersionId, episodeId, attemptId, nullIfBlank(runId), patternId,
				snapshot.compatibilityHash(), pathSignature, json(nodeSequence), json(decisions), json(sourceSequence),
				text(attempt.get("status")), scores.correctness(), scores.safety(), scores.coverage(),
				scores.freshness(), scores.stability(), nullableLong(episode.get("duration_ms")),
				nullableLong(episode.get("token_count")), retries, clarifications.size(), sources.size(), merges.size(),
				json(cost), json(proof));
		detectDetours(projectId, projectVersionId, patternId, pathId, nodes, sqlTraces, clarifications, sources, merges,
				retries, runId, normalizedQuestion, postExecutionReviews);
		pathProfileService.recompute(patternId);
		refreshDetourRecurrence(patternId);
		generateCandidates(patternId);
		return one("SELECT * FROM qw_trajectory_path WHERE id = ?", pathId);
	}

	private String upsertPattern(Long projectId, Long versionId, String catalogHash, String compatibilityHash,
			PatternShape shape, boolean succeeded) {
		Optional<Map<String, Object>> existing = optional("""
				SELECT * FROM qw_query_pattern
				WHERE project_version_id = ? AND execution_compatibility_hash = ? AND shape_hash = ?
				""", versionId, compatibilityHash, shape.shapeHash());
		if (existing.isPresent()) {
			String id = text(existing.orElseThrow().get("id"));
			jdbc.update("""
					UPDATE qw_query_pattern
					SET instance_hash = ?, pattern_json = ?, ambiguity_level = ?, risk_level = ?,
					    episode_count = episode_count + 1, success_count = success_count + ?,
					    last_seen_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", shape.instanceHash(), json(shape.payload()), shape.ambiguity(), shape.risk(),
					succeeded ? 1 : 0, id);
			return id;
		}
		String id = UUID.randomUUID().toString();
		try {
			jdbc.update("""
					INSERT INTO qw_query_pattern
					(id, project_id, project_version_id, catalog_hash, execution_compatibility_hash,
					 shape_hash, instance_hash, intent_type, pattern_json, ambiguity_level, risk_level,
					 episode_count, success_count, status, first_seen_time, last_seen_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'OBSERVE_ONLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					""", id, projectId, versionId, catalogHash, compatibilityHash, shape.shapeHash(),
					shape.instanceHash(), shape.intentType(), json(shape.payload()), shape.ambiguity(), shape.risk(),
					succeeded ? 1 : 0);
			return id;
		}
		catch (DuplicateKeyException ex) {
			return upsertPattern(projectId, versionId, catalogHash, compatibilityHash, shape, succeeded);
		}
	}

	private PatternShape patternShape(Map<String, Object> episode, SemanticBlueprint plan,
			List<Map<String, Object>> sources, List<Map<String, Object>> clarifications,
			List<Map<String, Object>> sqlTraces) {
		Map<String, Object> shape = new TreeMap<>();
		String normalized = normalize(text(episode.get("normalized_question")), text(episode.get("original_question")));
		if (plan != null) {
			shape.put("models",
					sorted(plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).toList()));
			shape.put("metrics",
					sorted(plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList()));
			shape.put("dimensions",
					sorted(plan.getDimensions()
						.stream()
						.map(SemanticBlueprint.DimensionSelection::getDimensionCode)
						.toList()));
			shape.put("grains",
					sorted(plan.getGrains().stream().map(SemanticBlueprint.GrainSelection::getGrainCode).toList()));
			shape.put("relationships",
					sorted(plan.getRelationships()
						.stream()
						.map(SemanticBlueprint.RelationshipSelection::getRelationshipCode)
						.toList()));
			shape.put("rules",
					sorted(plan.getRules().stream().map(SemanticBlueprint.RuleSelection::getRuleCode).toList()));
			shape.put("computationCapabilities", plan.getComputationIntent() == null ? List.of()
					: plan.getComputationIntent().capabilities().stream().map(Enum::name).sorted().toList());
			shape.put("computationRequirements", plan.getComputationIntent() == null ? List.of()
					: plan.getComputationIntent().canonicalRequirements());
			shape.put("groupBy", plan.getGroupBy().stream()
				.map(group -> Map.of("modelCode", Objects.toString(group.getModelCode(), ""), "columnName",
						Objects.toString(group.getColumnName(), ""), "alias", Objects.toString(group.getAlias(), ""),
						"timeBucketGranularity", Objects.toString(group.getTimeBucketGranularity(), "")))
				.toList());
			shape.put("expectedResultGrain",
					plan.getExpectedResult() == null ? "" : Objects.toString(plan.getExpectedResult().getGrain(), ""));
			shape.put("projectionTypes", sorted(plan.getProjections().stream()
				.map(SemanticBlueprint.ProjectionSelection::getProjectionType).toList()));
			shape.put("sourceShape",
					plan.getSourceSubPlans()
						.stream()
						.map(source -> Map.of("datasourceId", source.getDatasourceId(), "modelCount",
								source.getModelCodes().size(), "tableCount", source.getPhysicalTables().size()))
						.sorted(Comparator.comparing(item -> Objects.toString(item.get("datasourceId"))))
						.toList());
			shape.put("mergeType", plan.getMergePlan() == null ? "NONE" : text(plan.getMergePlan().getMergeType()));
		}
		else {
			shape.put("models", List.of());
			shape.put("metrics", List.of());
			shape.put("dimensions", List.of());
			shape.put("sourceShape",
					sources.stream().map(item -> integer(item.get("datasource_id"))).sorted().toList());
			shape.put("lexicalShape", lexicalShape(normalized));
		}
		String intent = intent(plan, sources);
		shape.put("intent", intent);
		shape.put("hasClarification", !clarifications.isEmpty());
		shape.put("hasSqlRepair", sqlTraces.stream().anyMatch(item -> logicalRetryCount(item) > 0));
		String ambiguity = clarifications.size() > 1 ? "HIGH" : clarifications.isEmpty() ? "LOW" : "MEDIUM";
		boolean risky = sqlTraces.stream()
			.anyMatch(item -> !Set.of("SUCCEEDED", "SUCCESS").contains(text(item.get("status"))));
		String risk = risky ? "HIGH" : sources.size() > 1 ? "MEDIUM" : "LOW";
		String shapeHash = hash(json(shape));
		String instanceHash = hash(json(Map.of("shapeHash", shapeHash, "normalizedQuestion", normalized)));
		return new PatternShape(intent, shapeHash, instanceHash, ambiguity, risk, Map.copyOf(shape));
	}

	private void detectDetours(Long projectId, Long versionId, String patternId, String pathId,
			List<Map<String, Object>> nodes, List<Map<String, Object>> sqlTraces,
			List<Map<String, Object>> clarifications, List<Map<String, Object>> sources,
			List<Map<String, Object>> merges, int retries, String runId, String normalizedQuestion,
			List<Map<String, Object>> postExecutionReviews) {
		Map<String, Long> nodeCounts = nodes.stream()
			.collect(Collectors.groupingBy(item -> text(item.get("node_name")), LinkedHashMap::new,
					Collectors.counting()));
		if (nodeCounts.values().stream().anyMatch(count -> count > 1)) {
			persistDetour(projectId, versionId, patternId, pathId, "DUPLICATE_NODE", "RUNTIME_OPTIMIZATION", 0.75,
					Map.of("nodeCounts", nodeCounts));
		}
		for (int reviewIndex = 0; reviewIndex < postExecutionReviews.size(); reviewIndex++) {
			Map<String, Object> postExecutionReview = postExecutionReviews.get(reviewIndex);
			String reviewDecision = text(postExecutionReview.get("decision"));
			if (!StringUtils.hasText(reviewDecision) || "PASS".equalsIgnoreCase(reviewDecision)) {
				continue;
			}
			String originalIssue = defaultText(postExecutionReview.get("issueType"), SemanticIssueType.UNKNOWN.name());
			String persistedIssue = originalIssue;
			String normalizedDecision = reviewDecision.toUpperCase(Locale.ROOT);
			boolean semanticRepair = Set.of("REBIND_SEMANTIC", "REPLAN", "RERETRIEVE", "CLARIFY")
					.contains(normalizedDecision);
			String rootCause = semanticRepair || Set.of("RETRIEVAL_MISS", "DEFINITION_GAP").contains(originalIssue)
					? "SEMANTIC_EVOLUTION" : "PLANNER_DEFECT";
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("decision", reviewDecision);
			evidence.put("issueType", originalIssue);
			evidence.put("runId", runId);
			evidence.put("question", normalizedQuestion);
			evidence.put("confidence", postExecutionReview.get("confidence"));
			evidence.put("suspectedAssetKeys", postExecutionReview.getOrDefault("suspectedAssetKeys", List.of()));
			evidence.put("reviewEvidence", postExecutionReview.getOrDefault("evidence", List.of()));
			Map<String, Object> rejectedPlan = mapValue(postExecutionReview.get("typedPlan"));
			if (!rejectedPlan.isEmpty()) {
				evidence.put("rejectedPlan", rejectedPlan);
			}
			String executionPlan = text(postExecutionReview.get("executionPlan"));
			if (StringUtils.hasText(executionPlan)) {
				evidence.put("executionPlan", executionPlan);
			}
			Map<String, Object> acceptedReview = acceptedReviewAfter(postExecutionReviews, reviewIndex);
			Map<String, Object> acceptedPlan = mapValue(acceptedReview.get("typedPlan"));
			if (Set.of("REBIND_SEMANTIC", "REPLAN").contains(normalizedDecision) && !rejectedPlan.isEmpty()
					&& !acceptedPlan.isEmpty()) {
				rootCause = "SEMANTIC_EVOLUTION";
				persistedIssue = SemanticIssueType.PLANNING_POLICY_GAP.name();
				evidence.put("acceptedPlan", acceptedPlan);
				evidence.put("decisionDelta", semanticPlanDelta(rejectedPlan, acceptedPlan));
				evidence.put("originalIssueType", originalIssue);
			}
			persistDetour(projectId, versionId, patternId, pathId, "POST_EXECUTION_REVIEW", rootCause, persistedIssue,
					null, null, 0.70, evidence);
		}
		if (retries > 0) {
			String errors = sqlTraces.stream()
				.map(item -> text(item.get("error_type")))
				.collect(Collectors.joining(" "));
			SemanticIssueType issueType = classifySqlIssue(errors);
			String root = semanticEvolutionIssue(issueType.name()) ? "SEMANTIC_EVOLUTION" : "PLANNER_DEFECT";
			persistDetour(projectId, versionId, patternId, pathId, "REPEATED_SQL_REPAIR", root, issueType.name(), null,
					null, Math.min(1.0, 0.55 + retries * 0.1), Map.of("retryCount", retries, "errorTypes", errors));
		}
		for (Map<String, Object> clarification : clarifications) {
			String issueType = defaultText(clarification.get("issue_type"), SemanticIssueType.UNKNOWN.name());
			String assetType = nullIfBlank(text(clarification.get("asset_type")));
			String assetKey = nullIfBlank(text(clarification.get("asset_key")));
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("clarificationId", clarification.get("clarification_id"));
			evidence.put("question", clarification.get("question"));
			evidence.put("rawExpression", clarification.get("raw_expression"));
			evidence.put("resolvedValue", clarification.get("resolved_value"));
			evidence.put("resolutionSource", clarification.get("resolution_source"));
			evidence.put("selectedOption", clarification.get("selected_option"));
			String rootCause = semanticEvolutionIssue(issueType) ? "SEMANTIC_EVOLUTION" : "PLANNER_DEFECT";
			persistDetour(projectId, versionId, patternId, pathId, "RUNTIME_CLARIFICATION", rootCause, issueType,
					assetType, assetKey, clarifications.size() > 1 ? 0.85 : 0.65, evidence);
		}
		boolean correction = nodes.stream()
			.anyMatch(item -> StringUtils.hasText(text(item.get("correction_type")))
					|| text(item.get("status")).contains("RETRY") || text(item.get("status")).contains("REJECT"));
		if (correction) {
			persistDetour(projectId, versionId, patternId, pathId, "DECISION_REVERSAL", "PLANNER_DEFECT", 0.7,
					Map.of("correctedNodes",
							nodes.stream()
								.filter(item -> StringUtils.hasText(text(item.get("correction_type"))))
								.map(item -> text(item.get("node_name")))
								.toList()));
		}
		if (sources.size() > 1 && merges.isEmpty()) {
			persistDetour(projectId, versionId, patternId, pathId, "MULTI_TO_SINGLE_CORRECTION", "SEMANTIC_EVOLUTION",
					0.7, Map.of("sourceCount", sources.size(), "mergeCount", merges.size()));
		}
	}

	private void persistDetour(Long projectId, Long versionId, String patternId, String pathId, String signalType,
			String rootCause, double confidence, Map<String, Object> evidence) {
		persistDetour(projectId, versionId, patternId, pathId, signalType, rootCause, null, null, null, confidence,
				evidence);
	}

	private void persistDetour(Long projectId, Long versionId, String patternId, String pathId, String signalType,
			String rootCause, String issueType, String assetType, String assetKey, double confidence,
			Map<String, Object> evidence) {
		try {
			String fingerprint = hash(signalType + "|" + rootCause + "|" + Objects.toString(issueType, "") + "|"
					+ Objects.toString(assetType, "") + "|" + Objects.toString(assetKey, ""));
			jdbc.update(
					"""
							INSERT INTO qw_detour_signal
							(id, project_id, project_version_id, pattern_id, path_id, signal_type, root_cause,
							 issue_type, asset_type, asset_key, signal_fingerprint, confidence, occurrence_count, recurrence_rate,
							 evidence_json, status, create_time, update_time)
							VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, 'OBSERVE_ONLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
							""",
					UUID.randomUUID().toString(), projectId, versionId, patternId, pathId, signalType, rootCause,
					issueType, assetType, assetKey, fingerprint, confidence, json(evidence));
		}
		catch (DuplicateKeyException ignored) {
			// Attempt analysis is idempotent; a repeated detector run must not duplicate
			// evidence.
		}
	}

	private void refreshDetourRecurrence(String patternId) {
		long episodes = count("SELECT COUNT(*) FROM qw_trajectory_path WHERE pattern_id = ?", patternId);
		if (episodes == 0) {
			return;
		}
		List<Map<String, Object>> groups = jdbc.queryForList("""
				SELECT signal_type, root_cause, issue_type, asset_type, asset_key, COUNT(*) occurrence_count
				FROM qw_detour_signal WHERE pattern_id = ?
				GROUP BY signal_type, root_cause, issue_type, asset_type, asset_key
				""", patternId);
		for (Map<String, Object> group : groups) {
			double rate = numberOrZero(group.get("occurrence_count")) / (double) episodes;
			jdbc.update(
					"""
							UPDATE qw_detour_signal SET occurrence_count = ?, recurrence_rate = ?, update_time = CURRENT_TIMESTAMP
							WHERE pattern_id = ? AND signal_type = ? AND root_cause = ?
							 AND issue_type IS NOT DISTINCT FROM ?
							 AND asset_type IS NOT DISTINCT FROM ?
							 AND asset_key IS NOT DISTINCT FROM ?
							""",
					group.get("occurrence_count"), rate, patternId, group.get("signal_type"), group.get("root_cause"),
					group.get("issue_type"), group.get("asset_type"), group.get("asset_key"));
		}
	}

	private void generateCandidates(String patternId) {
		Map<String, Object> pattern = one("SELECT * FROM qw_query_pattern WHERE id = ?", patternId);
		List<Map<String, Object>> recurring = jdbc.queryForList("""
				SELECT signal_type, root_cause, issue_type, asset_type, asset_key,
				 MAX(confidence) confidence, MAX(occurrence_count) occurrence_count,
				 MAX(recurrence_rate) recurrence_rate
				FROM qw_detour_signal WHERE pattern_id = ?
				GROUP BY signal_type, root_cause, issue_type, asset_type, asset_key
				HAVING MAX(occurrence_count) >= ? AND MAX(recurrence_rate) >= ?
				""", patternId, Math.max(1, minimumEpisodes), minimumRecurrenceRate);
		for (Map<String, Object> signal : recurring) {
			String rootCause = text(signal.get("root_cause"));
			if ("SEMANTIC_EVOLUTION".equals(rootCause)) {
				createSemanticCandidate(pattern, signal);
			}
			else if (Set.of("RUNTIME_OPTIMIZATION", "PLANNER_DEFECT").contains(rootCause)) {
				createRuntimeCandidate(pattern, signal);
			}
		}
	}

	private void createSemanticCandidate(Map<String, Object> pattern, Map<String, Object> signal) {
		if (policyEvolutionIssue(text(signal.get("issue_type")))) {
			createPolicyCandidate(pattern, signal);
			return;
		}
		SemanticEvolutionEvidenceService evidenceService = evolutionEvidenceService;
		SemanticEvolutionEvidenceService.IndependentEvidence independent = evidenceService
			.independentEvidence(text(pattern.get("id")), signal);
		if (!evidenceService.eligible(independent)) {
			return;
		}
		String issueType = text(signal.get("issue_type"));
		if (SemanticIssueType.PLANNING_POLICY_GAP.name().equals(issueType)) {
			createPlanningPolicyCandidate(pattern, signal, independent);
			return;
		}
		SemanticEvolutionEvidenceService.MappingDistribution distribution = null;
		if (mappingIssue(issueType)) {
			Map<String, Object> representative = representativeEvidence(text(pattern.get("id")), signal);
			String raw = defaultText(representative.get("rawExpression"), representative.get("alias"));
			distribution = evidenceService.mappingDistribution(number(pattern.get("project_id")),
					number(pattern.get("project_version_id")), raw, text(signal.get("asset_type")),
					mappingScope(representative, text(signal.get("asset_key"))));
			if ("LOW_SAMPLE".equals(distribution.classification())) {
				return;
			}
			if ("TRUE_AMBIGUITY".equals(distribution.classification())) {
				createAmbiguityCandidate(pattern, signal, independent, distribution, raw);
				return;
			}
			if (!Objects.equals(distribution.dominantResolution(), text(signal.get("asset_key")))) {
				return;
			}
		}
		Optional<Operation> proposed = semanticOperation(pattern, signal);
		if (proposed.isEmpty()) {
			return;
		}
		Operation operation = proposed.orElseThrow();
		String candidateType = defaultText(signal.get("issue_type"), operation.operation().name());
		String assetType = operation.assetType().toUpperCase(Locale.ROOT);
		String assetKey = operation.assetKey();
		if (activeCandidateExists(pattern, candidateType, assetType, assetKey)) {
			return;
		}
		double confidence = confidence(signal);
		SemanticPatch patch = new SemanticPatch(1, number(pattern.get("project_version_id")),
				text(pattern.get("catalog_hash")), List.of(operation));
		String candidateId = UUID.randomUUID().toString();
		String riskLevel = semanticRisk(issueType, operation, confidence,
				distribution == null ? null : distribution.classification());
		insertSemanticCandidate(candidateId, pattern, candidateType, assetType, assetKey, confidence, riskLevel, patch,
				distribution == null ? null : distribution.classification(), independent, distribution, signal);
		attachEvidence(candidateId, text(pattern.get("id")), signal, "DETOUR_AGGREGATE");
	}

	private void createPlanningPolicyCandidate(Map<String, Object> pattern, Map<String, Object> signal,
			SemanticEvolutionEvidenceService.IndependentEvidence independent) {
		List<Map<String, Object>> evidence = planningPolicyEvidence(text(pattern.get("id")), signal);
		if (evidence.isEmpty()) {
			return;
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(number(pattern.get("project_id")),
				number(pattern.get("project_version_id")));
		List<SemanticCatalogSnapshot.Rule> catalogRules = catalog.getRules() == null ? List.of() : catalog.getRules();
		String candidateType = SemanticIssueType.PLANNING_POLICY_GAP.name();
		String stablePolicyKey = defaultText(pattern.get("shape_hash"), pattern.get("id"));
		String ruleCode = "learned_planning_" + hash(stablePolicyKey + "|" + candidateType).substring(0, 16);
		if (activeCandidateExists(pattern, candidateType, AssetType.RULE.name(), ruleCode)) {
			return;
		}
		SemanticCatalogSnapshot.Rule existingRule = catalogRules.stream()
			.filter(rule -> Objects.equals(ruleCode, rule.getRuleCode()))
			.findFirst()
			.orElse(null);
		if (existingRule != null && !"PLANNING_POLICY".equalsIgnoreCase(text(existingRule.getRuleType()))) {
			log.warn("Planning policy evolution skipped because deterministic rule code {} collides with a non-policy rule",
					ruleCode);
			return;
		}
		List<Map<String, Object>> existingPolicies = catalogRules.stream()
			.filter(rule -> "PLANNING_POLICY".equalsIgnoreCase(text(rule.getRuleType())))
			.map(rule -> Map.<String, Object>of("ruleCode", Objects.toString(rule.getRuleCode(), ""), "expression",
					Objects.toString(rule.getExpression(), ""), "description", Objects.toString(rule.getDescription(), "")))
			.toList();
		DistilledPolicy distilled;
		try {
			distilled = planningPolicyDistillationService.distill(pattern, evidence, existingPolicies).orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("Planning policy distillation skipped for pattern {}: {}", pattern.get("id"), ex.getMessage());
			return;
		}
		if (distilled == null
				|| distilled.confidence() < queryCaseProperties.getPlanningPolicyMinDistillationConfidence()) {
			return;
		}
		Map<String, Object> values = new LinkedHashMap<>();
		if (existingRule == null) {
			values.put("ruleCode", ruleCode);
			values.put("ruleType", "PLANNING_POLICY");
		}
		values.put("businessName", "Learned planning policy");
		values.put("expression", distilled.policyText());
		values.put("severity", "INFO");
		values.put("description", planningPolicyDescription(distilled));
		values.put("evidence", "Recurring independently evidenced rejected-plan to accepted-plan corrections");
		OperationType operationType = existingRule == null ? OperationType.ADD_RULE : OperationType.UPDATE_RULE;
		String expectedFingerprint = existingRule == null ? null : patchAnalyzer.fingerprintAsset(AssetType.RULE, existingRule);
		Operation operation = new Operation(operationType, AssetType.RULE.name(), ruleCode, expectedFingerprint, values,
				evidenceCaseIds(text(pattern.get("id")), number(pattern.get("project_version_id")), signal));
		SemanticPatch patch = new SemanticPatch(1, number(pattern.get("project_version_id")),
				text(pattern.get("catalog_hash")), List.of(operation));
		double confidence = Math.min(confidence(signal), distilled.confidence());
		Map<String, Object> candidateSignal = new LinkedHashMap<>(signal);
		candidateSignal.put("distillation", planningPolicyDistillationEvidence(distilled));
		String candidateId = UUID.randomUUID().toString();
		insertSemanticCandidate(candidateId, pattern, candidateType, AssetType.RULE.name(), ruleCode, confidence, "HIGH",
				patch, "DISTILLED_PLANNING_POLICY", independent, null, candidateSignal);
		attachEvidence(candidateId, text(pattern.get("id")), signal, "PLANNING_POLICY_DISTILLATION");
	}

	private String planningPolicyDescription(DistilledPolicy policy) {
		String counters = policy.counterExamples().isEmpty() ? "none supplied" : String.join(" | ", policy.counterExamples());
		return "Applicability: " + policy.applicability() + ". Counterexamples: " + counters;
	}

	private Map<String, Object> planningPolicyDistillationEvidence(DistilledPolicy policy) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("policyText", policy.policyText());
		evidence.put("applicability", policy.applicability());
		evidence.put("counterExamples", policy.counterExamples());
		evidence.put("confidence", policy.confidence());
		if (policy.modelEvidence() != null) {
			Map<String, Object> model = new LinkedHashMap<>();
			model.put("callId", policy.modelEvidence().callId());
			model.put("latencyMs", policy.modelEvidence().latencyMs());
			model.put("promptTokens", policy.modelEvidence().promptTokens());
			model.put("completionTokens", policy.modelEvidence().completionTokens());
			evidence.put("model", model);
		}
		return Map.copyOf(evidence);
	}

	private List<Map<String, Object>> planningPolicyEvidence(String patternId, Map<String, Object> signal) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT evidence_json FROM qw_detour_signal
				WHERE pattern_id = ? AND signal_type = ? AND root_cause = ?
				 AND issue_type IS NOT DISTINCT FROM ?
				ORDER BY confidence DESC, create_time DESC LIMIT 12
				""", patternId, signal.get("signal_type"), signal.get("root_cause"), signal.get("issue_type"));
		return rows.stream().map(row -> readJson(text(row.get("evidence_json"))))
			.filter(item -> !mapValue(item.get("rejectedPlan")).isEmpty() && !mapValue(item.get("acceptedPlan")).isEmpty()
					&& !mapValue(item.get("decisionDelta")).isEmpty())
			.toList();
	}

	private void createAmbiguityCandidate(Map<String, Object> pattern, Map<String, Object> signal,
			SemanticEvolutionEvidenceService.IndependentEvidence independent,
			SemanticEvolutionEvidenceService.MappingDistribution distribution, String rawExpression) {
		String candidateType = defaultText(signal.get("issue_type"), "RUNTIME_CLARIFICATION_REQUIRED");
		String assetType = text(signal.get("asset_type")).toUpperCase(Locale.ROOT);
		Map<String, Object> representative = representativeEvidence(text(pattern.get("id")), signal);
		String scope = mappingScope(representative, text(signal.get("asset_key")));
		String assetKey = "AMBIGUITY:" + hash(normalize(rawExpression) + "|" + assetType + "|" + normalize(scope));
		if (activeCandidateExists(pattern, candidateType, assetType, assetKey)) {
			return;
		}
		SemanticPatch noAutomaticPatch = new SemanticPatch(1, number(pattern.get("project_version_id")),
				text(pattern.get("catalog_hash")), List.of());
		String candidateId = UUID.randomUUID().toString();
		insertSemanticCandidate(candidateId, pattern, candidateType, assetType, assetKey, confidence(signal), "HIGH",
				noAutomaticPatch, distribution.classification(), independent, distribution, signal);
		attachEvidence(candidateId, text(pattern.get("id")), signal, "AMBIGUITY_DISTRIBUTION");
	}

	private boolean activeCandidateExists(Map<String, Object> pattern, String candidateType, String assetType,
			String assetKey) {
		return count("""
				SELECT COUNT(*) FROM qw_semantic_evolution_candidate
				WHERE source_version_id = ? AND source_catalog_hash = ? AND candidate_type = ?
				 AND asset_type = ? AND asset_key = ?
				 AND status IN ('CANDIDATE','APPROVED','DRAFT_CREATED','PATCH_APPLIED','REPLAY_RUNNING',
				                'REPLAY_PASSED','REPLAY_FAILED','READY_FOR_PUBLISH')
				""", pattern.get("project_version_id"), pattern.get("catalog_hash"), candidateType, assetType,
				assetKey) > 0;
	}

	private void insertSemanticCandidate(String candidateId, Map<String, Object> pattern, String candidateType,
			String assetType, String assetKey, double confidence, String riskLevel, Object patch,
			String mappingClassification, SemanticEvolutionEvidenceService.IndependentEvidence independent,
			SemanticEvolutionEvidenceService.MappingDistribution distribution, Map<String, Object> signal) {
		Map<String, Object> evidenceSummary = new LinkedHashMap<>();
		evidenceSummary.put("signal", signal);
		evidenceSummary.put("independentEvidence", independent);
		if (distribution != null) {
			evidenceSummary.put("mappingDistribution", distribution);
		}
		int inserted = jdbc.update("""
				INSERT INTO qw_semantic_evolution_candidate
				(id, project_id, source_version_id, source_catalog_hash, candidate_type, asset_type, asset_key,
				 status, confidence, risk_level, patch_json, evidence_summary, mapping_classification,
				 evidence_distribution_json, distinct_conversation_count, distinct_user_count,
				 distinct_root_evidence_count, distinct_time_window_count, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", candidateId, pattern.get("project_id"), pattern.get("project_version_id"),
				pattern.get("catalog_hash"), candidateType, assetType, assetKey, confidence, riskLevel,
				persistentPatchJson(patch), json(evidenceSummary), mappingClassification,
				distribution == null ? null : json(distribution), independent.distinctConversationCount(),
				independent.distinctUserCount(), independent.distinctRootEvidenceCount(),
				independent.distinctTimeWindowCount());
		if (inserted == 1 && "LOW".equals(riskLevel) && patch instanceof SemanticPatch semanticPatch
				&& !semanticPatch.operations().isEmpty() && changeSetBridge != null) {
			changeSetBridge.linkCandidate(number(pattern.get("project_id")), number(pattern.get("project_version_id")),
					candidateId, candidateType, assetType, assetKey, riskLevel, persistentPatchJson(semanticPatch),
					Map.copyOf(evidenceSummary), representativeEpisodeId(text(pattern.get("id")), signal), "semevosql-system");
			if (eventPublisher != null) {
				eventPublisher.publishEvent(new LowRiskSemanticEvolutionCandidateEvent(candidateId));
			}
		}
	}

	private String semanticRisk(String issueType, Operation operation, double confidence, String mappingClassification) {
		if (operation == null) {
			return "HIGH";
		}
		boolean stableMapping = Set.of("TERM_ALIAS_MISSING", "ENUM_MAPPING_MISSING").contains(issueType)
				&& !"TRUE_AMBIGUITY".equals(mappingClassification) && confidence >= 0.80d;
		boolean additiveAlias = operation.operation() == OperationType.ADD_COLUMN_SYNONYM
				|| operation.operation() == OperationType.ADD_ENUM_ALIAS;
		if (stableMapping && additiveAlias) {
			return "LOW";
		}
		return switch (operation.operation()) {
			case UPDATE_METRIC, UPDATE_RELATIONSHIP, UPDATE_GRAIN, ADD_METRIC, ADD_RELATIONSHIP, ADD_GRAIN -> "HIGH";
			default -> "MEDIUM";
		};
	}

	private boolean mappingIssue(String issueType) {
		return Set.of("TERM_ALIAS_MISSING", "ENUM_MAPPING_MISSING", "ENUM_MAPPING_AMBIGUOUS").contains(issueType);
	}

	private String mappingScope(Map<String, Object> evidence, String assetKey) {
		String explicit = text(evidence.get("assetScope"));
		if (StringUtils.hasText(explicit)) {
			return explicit;
		}
		String modelCode = text(evidence.get("modelCode"));
		String columnName = text(evidence.get("columnName"));
		if (StringUtils.hasText(modelCode) && StringUtils.hasText(columnName)) {
			return modelCode + ":" + columnName;
		}
		return StringUtils.hasText(assetKey) && assetKey.contains(":")
				? assetKey.substring(0, assetKey.lastIndexOf(':')) : "GLOBAL";
	}

	private void createPolicyCandidate(Map<String, Object> pattern, Map<String, Object> signal) {
		SemanticEvolutionEvidenceService evidenceService = evolutionEvidenceService;
		SemanticEvolutionEvidenceService.IndependentEvidence independent = evidenceService
			.independentEvidence(text(pattern.get("id")), signal);
		if (!evidenceService.eligible(independent)) {
			return;
		}
		String issueType = text(signal.get("issue_type"));
		Map<String, Object> evidence = representativeEvidence(text(pattern.get("id")), signal);
		Map<String, Object> values = mapValue(evidence.get("patchValues"));
		if (values.isEmpty()) {
			return;
		}
		PolicyAssetType assetType = policyAssetType(issueType,
				defaultText(evidence.get("policyAssetType"), signal.get("asset_type")));
		String assetKey = text(signal.get("asset_key"));
		if (!StringUtils.hasText(assetKey)) {
			return;
		}
		Long projectId = number(pattern.get("project_id"));
		Long sourceVersionId = number(pattern.get("project_version_id"));
		Object current = policyPatchService.findAsset(multiSourcePolicyService.get(projectId, sourceVersionId),
				assetType, assetKey);
		MultiSourcePolicyPatch.Operation operation = new MultiSourcePolicyPatch.Operation(
				current == null ? MultiSourcePolicyPatch.OperationType.ADD
						: MultiSourcePolicyPatch.OperationType.UPDATE,
				assetType, assetKey, current == null ? null : policyPatchService.fingerprint(assetType, current),
				values, evidenceCaseIds(text(pattern.get("id")), sourceVersionId, signal));
		if (count("""
				SELECT COUNT(*) FROM qw_semantic_evolution_candidate
				WHERE source_version_id = ? AND source_catalog_hash = ? AND candidate_type = ?
				 AND asset_type = ? AND asset_key = ?
				 AND status IN ('CANDIDATE','APPROVED','DRAFT_CREATED','PATCH_APPLIED','REPLAY_RUNNING',
				                'REPLAY_PASSED','REPLAY_FAILED','READY_FOR_PUBLISH')
				""", sourceVersionId, pattern.get("catalog_hash"), issueType, "POLICY_" + assetType, assetKey) > 0) {
			return;
		}
		MultiSourcePolicyPatch patch = new MultiSourcePolicyPatch(1, sourceVersionId, text(pattern.get("catalog_hash")),
				List.of(operation));
		String candidateId = UUID.randomUUID().toString();
		insertSemanticCandidate(candidateId, pattern, issueType, "POLICY_" + assetType, assetKey, confidence(signal),
				"HIGH", patch, null, independent, null, signal);
		attachEvidence(candidateId, text(pattern.get("id")), signal, "MULTI_SOURCE_POLICY_DETOUR");
	}

	private PolicyAssetType policyAssetType(String issueType, String proposed) {
		if ("DATASOURCE_AUTHORITY_INCORRECT".equals(issueType)) {
			return PolicyAssetType.AUTHORITY_RULE;
		}
		String normalized = proposed.toUpperCase(Locale.ROOT).replace("POLICY_", "");
		try {
			return PolicyAssetType.valueOf(normalized);
		}
		catch (IllegalArgumentException ex) {
			return PolicyAssetType.MERGE_POLICY;
		}
	}

	private boolean policyEvolutionIssue(String issueType) {
		return Set.of("DATASOURCE_AUTHORITY_INCORRECT", "MULTI_SOURCE_POLICY_INCORRECT").contains(issueType);
	}

	private Optional<Operation> semanticOperation(Map<String, Object> pattern, Map<String, Object> signal) {
		String issueText = text(signal.get("issue_type"));
		String assetTypeText = text(signal.get("asset_type")).toUpperCase(Locale.ROOT);
		String assetKey = text(signal.get("asset_key"));
		if (!StringUtils.hasText(issueText) || !StringUtils.hasText(assetTypeText) || !StringUtils.hasText(assetKey)) {
			return Optional.empty();
		}
		SemanticIssueType issue;
		AssetType assetType;
		try {
			issue = SemanticIssueType.valueOf(issueText);
			assetType = AssetType.valueOf(assetTypeText);
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		Map<String, Object> evidence = representativeEvidence(text(pattern.get("id")), signal);
		String raw = defaultText(evidence.get("rawExpression"), evidence.get("alias"));
		String resolved = defaultText(evidence.get("resolvedValue"), evidence.get("selectedOption"));
		Map<String, Object> patchValues = mapValue(evidence.get("patchValues"));
		Long projectId = number(pattern.get("project_id"));
		Long versionId = number(pattern.get("project_version_id"));
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, versionId);
		List<String> evidenceCases = evidenceCaseIds(text(pattern.get("id")), versionId, signal);
		return switch (issue) {
			case TERM_ALIAS_MISSING -> aliasOperation(catalog, assetType, assetKey, raw, evidenceCases);
			case ENUM_MAPPING_MISSING, ENUM_MAPPING_AMBIGUOUS ->
				enumAliasOperation(catalog, assetType, assetKey, raw, evidenceCases);
			case METRIC_FORMULA_INCORRECT -> updateOperation(catalog, assetType, assetKey, OperationType.UPDATE_METRIC,
					Map.of("expression", resolved), evidenceCases);
			case METRIC_TIME_COLUMN_INCORRECT -> updateOperation(catalog, assetType, assetKey,
					OperationType.UPDATE_METRIC, Map.of("timeColumn", resolved), evidenceCases);
			case METRIC_FILTER_INCOMPLETE -> updateOperation(catalog, assetType, assetKey, OperationType.UPDATE_METRIC,
					Map.of("filterExpression", resolved), evidenceCases);
			case RELATIONSHIP_INCORRECT, CARDINALITY_INCORRECT, JOIN_CONDITION_INCORRECT -> updateOperation(catalog,
					assetType, assetKey, OperationType.UPDATE_RELATIONSHIP, patchValues, evidenceCases);
			case GRAIN_INCORRECT ->
				updateOperation(catalog, assetType, assetKey, OperationType.UPDATE_GRAIN, patchValues, evidenceCases);
			case DIMENSION_AMBIGUOUS -> updateOperation(catalog, assetType, assetKey, OperationType.UPDATE_DIMENSION,
					patchValues, evidenceCases);
			case METRIC_MISSING ->
				addOperation(assetType, assetKey, OperationType.ADD_METRIC, patchValues, evidenceCases);
			case DIMENSION_MISSING ->
				addOperation(assetType, assetKey, OperationType.ADD_DIMENSION, patchValues, evidenceCases);
			case RELATIONSHIP_MISSING ->
				addOperation(assetType, assetKey, OperationType.ADD_RELATIONSHIP, patchValues, evidenceCases);
			case GRAIN_MISSING ->
				addOperation(assetType, assetKey, OperationType.ADD_GRAIN, patchValues, evidenceCases);
			default -> Optional.empty();
		};
	}

	private Optional<Operation> aliasOperation(SemanticCatalogSnapshot catalog, AssetType assetType, String assetKey,
			String alias, List<String> evidenceCases) {
		if (!StringUtils.hasText(alias)) {
			return Optional.empty();
		}
		if (assetType == AssetType.COLUMN) {
			return updateOperation(catalog, assetType, assetKey, OperationType.ADD_COLUMN_SYNONYM,
					Map.of("synonym", alias), evidenceCases);
		}
		if (assetType == AssetType.ENUM_VALUE) {
			return enumAliasOperation(catalog, assetType, assetKey, alias, evidenceCases);
		}
		return Optional.empty();
	}

	private Optional<Operation> enumAliasOperation(SemanticCatalogSnapshot catalog, AssetType assetType,
			String assetKey, String alias, List<String> evidenceCases) {
		if (assetType != AssetType.ENUM_VALUE || !StringUtils.hasText(alias)) {
			return Optional.empty();
		}
		return updateOperation(catalog, assetType, assetKey, OperationType.ADD_ENUM_ALIAS, Map.of("alias", alias),
				evidenceCases);
	}

	private Optional<Operation> updateOperation(SemanticCatalogSnapshot catalog, AssetType assetType, String assetKey,
			OperationType operation, Map<String, Object> values, List<String> evidenceCases) {
		if (values == null || values.isEmpty()
				|| values.values().stream().allMatch(value -> !StringUtils.hasText(text(value)))) {
			return Optional.empty();
		}
		Object current = findAsset(catalog, assetType, assetKey).orElse(null);
		if (current == null) {
			return Optional.empty();
		}
		return Optional.of(new Operation(operation, assetType.name(), assetKey,
				patchAnalyzer.fingerprintAsset(assetType, current), values, evidenceCases));
	}

	private Optional<Operation> addOperation(AssetType assetType, String assetKey, OperationType operation,
			Map<String, Object> values, List<String> evidenceCases) {
		if (values == null || values.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Operation(operation, assetType.name(), assetKey, null, values, evidenceCases));
	}

	private Optional<Object> findAsset(SemanticCatalogSnapshot catalog, AssetType type, String key) {
		return switch (type) {
			case MODEL -> catalog.getModels()
				.stream()
				.filter(value -> Objects.equals(value.getModelCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case COLUMN -> catalog.getColumns()
				.stream()
				.filter(value -> Objects.equals(value.getModelCode() + ":" + value.getColumnName(), key))
				.map(Object.class::cast)
				.findFirst();
			case METRIC -> catalog.getMetrics()
				.stream()
				.filter(value -> Objects.equals(value.getMetricCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case DIMENSION -> catalog.getDimensions()
				.stream()
				.filter(value -> Objects.equals(value.getDimensionCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case RELATIONSHIP -> catalog.getRelationships()
				.stream()
				.filter(value -> Objects.equals(value.getRelationshipCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case GRAIN -> catalog.getGrains()
				.stream()
				.filter(value -> Objects.equals(value.getModelCode() + ":" + value.getGrainCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case ENUM_VALUE -> catalog.getEnumValues()
				.stream()
				.filter(value -> Objects
					.equals(value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode(), key))
				.map(Object.class::cast)
				.findFirst();
			case RULE -> catalog.getRules()
				.stream()
				.filter(value -> Objects.equals(value.getRuleCode(), key))
				.map(Object.class::cast)
				.findFirst();
		};
	}

	private String representativeEpisodeId(String patternId, Map<String, Object> signal) {
		return optional("""
				SELECT p.episode_id
				FROM qw_detour_signal d
				JOIN qw_trajectory_path p ON p.id = d.path_id
				WHERE d.pattern_id = ? AND d.signal_type = ? AND d.root_cause = ?
				 AND d.issue_type IS NOT DISTINCT FROM ?
				 AND d.asset_type IS NOT DISTINCT FROM ?
				 AND d.asset_key IS NOT DISTINCT FROM ?
				ORDER BY d.confidence DESC, d.create_time DESC LIMIT 1
				""", patternId, signal.get("signal_type"), signal.get("root_cause"), signal.get("issue_type"),
				signal.get("asset_type"), signal.get("asset_key"))
			.map(row -> text(row.get("episode_id")))
			.filter(StringUtils::hasText)
			.orElse(null);
	}

	private Map<String, Object> representativeEvidence(String patternId, Map<String, Object> signal) {
		return optional("""
				SELECT evidence_json FROM qw_detour_signal
				WHERE pattern_id = ? AND signal_type = ? AND root_cause = ?
				 AND issue_type IS NOT DISTINCT FROM ?
				 AND asset_type IS NOT DISTINCT FROM ?
				 AND asset_key IS NOT DISTINCT FROM ?
				ORDER BY confidence DESC, create_time DESC LIMIT 1
				""", patternId, signal.get("signal_type"), signal.get("root_cause"), signal.get("issue_type"),
				signal.get("asset_type"), signal.get("asset_key"))
			.map(row -> readJson(text(row.get("evidence_json"))))
			.orElse(Map.of());
	}

	private List<String> evidenceCaseIds(String patternId, Long versionId, Map<String, Object> signal) {
		return jdbc.queryForList("""
				SELECT DISTINCT q.id
				FROM qw_detour_signal d
				JOIN qw_trajectory_path p ON p.id = d.path_id
				JOIN qw_query_example q ON q.episode_id = p.episode_id
				WHERE d.pattern_id = ? AND d.signal_type = ? AND d.root_cause = ?
				 AND d.issue_type IS NOT DISTINCT FROM ?
				 AND d.asset_type IS NOT DISTINCT FROM ?
				 AND d.asset_key IS NOT DISTINCT FROM ?
				 AND q.project_version_id = ? AND q.status = 'APPROVED'
				ORDER BY q.id
				""", String.class, patternId, signal.get("signal_type"), signal.get("root_cause"),
				signal.get("issue_type"), signal.get("asset_type"), signal.get("asset_key"), versionId);
	}

	private void createRuntimeCandidate(Map<String, Object> pattern, Map<String, Object> signal) {
		String optimizationType = "DUPLICATE_NODE".equals(text(signal.get("signal_type")))
				? "EARLY_TERMINATION_OR_NODE_SKIP" : "PLANNER_START_HINT";
		if (count("""
				SELECT COUNT(*) FROM qw_runtime_optimization_candidate
				WHERE pattern_id = ? AND execution_compatibility_hash = ? AND optimization_type = ?
				 AND status IN ('CANDIDATE','SHADOW','APPROVED','ENABLED')
				""", pattern.get("id"), pattern.get("execution_compatibility_hash"), optimizationType) > 0) {
			return;
		}
		Map<String, Object> baseline = bestProfile(text(pattern.get("id")));
		Map<String, Object> proposal = Map.of("type", optimizationType, "mode", "START_HINT_ONLY",
				"mustPassApplicabilityCheck", true, "mustRunGuard", true, "signalType", signal.get("signal_type"));
		jdbc.update("""
				INSERT INTO qw_runtime_optimization_candidate
				(id, project_id, project_version_id, pattern_id, execution_compatibility_hash,
				 optimization_type, status, applicability_json, proposal_json, baseline_metrics_json,
				 confidence, risk_level, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, ?, ?, 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", UUID.randomUUID().toString(), pattern.get("project_id"), pattern.get("project_version_id"),
				pattern.get("id"), pattern.get("execution_compatibility_hash"), optimizationType,
				json(Map.of("patternId", pattern.get("id"), "executionCompatibilityHash",
						pattern.get("execution_compatibility_hash"), "catalogHash", pattern.get("catalog_hash"))),
				json(proposal), json(baseline), confidence(signal));
	}

	private void attachEvidence(String candidateId, String patternId, Map<String, Object> signal, String type) {
		List<Map<String, Object>> evidence = jdbc.queryForList("""
				SELECT d.id detour_id, d.path_id, p.episode_id, d.evidence_json, d.confidence
				FROM qw_detour_signal d JOIN qw_trajectory_path p ON p.id = d.path_id
				WHERE d.pattern_id = ? AND d.signal_type = ? AND d.root_cause = ?
				 AND d.issue_type IS NOT DISTINCT FROM ?
				 AND d.asset_type IS NOT DISTINCT FROM ?
				 AND d.asset_key IS NOT DISTINCT FROM ?
				ORDER BY d.confidence DESC, d.create_time DESC
				""", patternId, signal.get("signal_type"), signal.get("root_cause"), signal.get("issue_type"),
				signal.get("asset_type"), signal.get("asset_key"));
		for (Map<String, Object> item : evidence) {
			try {
				jdbc.update("""
						INSERT INTO qw_candidate_evidence
						(id, candidate_id, evidence_type, episode_id, path_id, detour_signal_id, weight,
						 evidence_json, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
						""", UUID.randomUUID().toString(), candidateId, type, item.get("episode_id"),
						item.get("path_id"), item.get("detour_id"), item.get("confidence"), item.get("evidence_json"));
			}
			catch (DuplicateKeyException ignored) {
				// Evidence identity is immutable.
			}
		}
	}

	private Scores scores(Map<String, Object> episode, Map<String, Object> attempt, Map<String, Object> run,
			List<Map<String, Object>> sqlTraces, List<Map<String, Object>> feedback, List<Map<String, Object>> sources,
			List<Map<String, Object>> artifacts, int retries, int clarificationCount) {
		boolean success = "SUCCEEDED".equals(text(attempt.get("status")))
				&& (run.isEmpty() || "SUCCEEDED".equals(text(run.get("status"))));
		boolean adopted = feedback.stream().anyMatch(item -> truth(item.get("adopted")));
		int bestRating = feedback.stream().mapToInt(item -> integer(item.get("rating"))).max().orElse(0);
		double correctness = success ? adopted || bestRating >= 4 ? 1.0 : 0.8 : 0.0;
		boolean guardFailure = sqlTraces.stream()
			.anyMatch(item -> text(item.get("status")).contains("GUARD")
					|| text(item.get("error_type")).toUpperCase(Locale.ROOT).contains("GUARD"));
		double safety = guardFailure ? 0.0 : 1.0;
		double coverage = success ? !artifacts.isEmpty() || !sqlTraces.isEmpty() ? 1.0 : 0.75 : 0.0;
		long freshSources = sources.stream()
			.filter(item -> StringUtils.hasText(text(item.get("freshness_as_of"))))
			.count();
		double freshness = sources.isEmpty() ? 1.0 : freshSources / (double) sources.size();
		double stability = Math.max(0.0, 1.0 - Math.min(1.0, retries * 0.15 + clarificationCount * 0.1));
		return new Scores(correctness, safety, coverage, freshness, stability);
	}

	private Map<String, Object> resultProof(Map<String, Object> episode, Map<String, Object> attempt,
			Map<String, Object> run, List<Map<String, Object>> sqlTraces, List<Map<String, Object>> feedback,
			List<Map<String, Object>> sources, List<Map<String, Object>> artifacts) {
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("episodeStatus", episode.get("status"));
		proof.put("attemptStatus", attempt.get("status"));
		proof.put("runStatus", run.get("status"));
		proof.put("runErrorCode", run.get("error_code"));
		proof.put("sqlStatuses", sqlTraces.stream().map(item -> item.get("status")).toList());
		proof.put("feedback",
				feedback.stream()
					.map(item -> Map.of("rating", value(item.get("rating")), "adopted", truth(item.get("adopted"))))
					.toList());
		proof.put("sources", sources.stream().map(this::safeSource).toList());
		proof.put("artifacts", artifacts.stream()
			.map(item -> Map.of("artifactId", value(item.get("artifact_id")), "type", value(item.get("artifact_type")),
					"rowCount", value(item.get("row_count")), "contentHash", value(item.get("content_hash"))))
			.toList());
		return proof;
	}

	private SnapshotView snapshot(Map<String, Object> run, String catalogHash, Long versionId) {
		String runId = text(run.get("run_id"));
		String compatibilityHash = hash("legacy:" + versionId + ":" + catalogHash);
		SemanticBlueprint plan = semanticPlanSnapshots.latest(runId).orElse(null);
		String json = text(run.get("execution_snapshot"));
		if (StringUtils.hasText(json)) {
			try {
				Optional<ExecutionSnapshot> snapshot = executionSnapshotService.readTyped(json);
				if (snapshot.isPresent()) {
					compatibilityHash = snapshot.orElseThrow().compatibilityHash();
				}
			}
			catch (RuntimeException ex) {
				log.warn("Ignoring unreadable execution snapshot during trajectory analysis for run {}: {}", runId,
						ex.getMessage());
			}
		}
		return new SnapshotView(compatibilityHash, plan);
	}

	static Optional<SemanticBlueprint> decodeSemanticPlanSnapshot(String payload) {
		if (!StringUtils.hasText(payload)) {
			return Optional.empty();
		}
		try {
			return Optional.of(JsonUtil.getObjectMapper().readValue(payload, SemanticBlueprint.class));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	private List<Map<String, Object>> postExecutionReviews(String runId) {
		if (!StringUtils.hasText(runId)) {
			return List.of();
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT sequence, payload FROM qw_run_event
				WHERE run_id = ? AND event_type = 'POST_EXECUTION_REVIEW'
				ORDER BY sequence
				""", runId);
		List<Map<String, Object>> reviews = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> payload = readJson(text(row.get("payload")));
			Map<String, Object> review = new LinkedHashMap<>(mapValue(payload.get("review")));
			if (review.isEmpty()) {
				continue;
			}
			review.put("sequence", row.get("sequence"));
			review.put("step", payload.get("step"));
			Map<String, Object> typedPlan = mapValue(payload.get("typedPlan"));
			if (!typedPlan.isEmpty()) {
				review.put("typedPlan", typedPlan);
			}
			reviews.add(review);
		}
		return List.copyOf(reviews);
	}

	private Map<String, Object> acceptedReviewAfter(List<Map<String, Object>> reviews, int rejectedIndex) {
		Object rejectedStep = reviews.get(rejectedIndex).get("step");
		for (int index = rejectedIndex + 1; index < reviews.size(); index++) {
			Map<String, Object> review = reviews.get(index);
			boolean sameStep = rejectedStep == null || Objects.equals(rejectedStep, review.get("step"));
			if (sameStep && "PASS".equalsIgnoreCase(text(review.get("decision")))) {
				return review;
			}
		}
		return Map.of();
	}

	private Map<String, Object> semanticPlanDelta(Map<String, Object> rejected, Map<String, Object> accepted) {
		Map<String, Object> delta = new LinkedHashMap<>();
		for (String key : List.of("models", "metrics", "dimensions", "grains", "relationships", "rules", "filters",
				"timeRange", "groupBy", "orderBy", "projections", "expectedResultShape", "compilerMode")) {
			Object before = rejected.get(key);
			Object after = accepted.get(key);
			if (!Objects.equals(before, after)) {
				delta.put(key, Map.of("before", before == null ? "" : before, "after", after == null ? "" : after));
			}
		}
		return Map.copyOf(delta);
	}

	private List<String> nodeSequence(List<Map<String, Object>> nodes, String runId) {
		if (!nodes.isEmpty()) {
			return nodes.stream().map(item -> text(item.get("node_name"))).filter(StringUtils::hasText).toList();
		}
		if (!StringUtils.hasText(runId)) {
			return List.of();
		}
		return jdbc.queryForList("""
				SELECT node_name FROM qw_run_event WHERE run_id = ? AND node_name IS NOT NULL ORDER BY sequence
				""", String.class, runId);
	}

	private List<Map<String, Object>> decisions(List<Map<String, Object>> nodes,
			List<Map<String, Object>> clarifications, List<Map<String, Object>> sources,
			Map<String, Object> postExecutionReview) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> node : nodes) {
			String decision = text(node.get("decision_summary"));
			if (!StringUtils.hasText(decision)) {
				decision = text(node.get("correction_type"));
			}
			if (StringUtils.hasText(decision)) {
				result.add(Map.of("node", text(node.get("node_name")), "decision", decision, "reused",
						truth(node.get("reused"))));
			}
		}
		for (Map<String, Object> clarification : clarifications) {
			result.add(Map.of("node", "runtime-clarification", "decision", text(clarification.get("selected_option")),
					"status", text(clarification.get("status"))));
		}
		if (!sources.isEmpty()) {
			result.add(Map.of("node", "source-planning", "decision",
					sources.stream().map(item -> integer(item.get("datasource_id"))).sorted().toList()));
		}
		if (postExecutionReview != null && !postExecutionReview.isEmpty()) {
			Map<String, Object> reviewDecision = new LinkedHashMap<>();
			reviewDecision.put("node", "post-execution-review");
			reviewDecision.put("decision", postExecutionReview.get("decision"));
			reviewDecision.put("issueType", postExecutionReview.get("issueType"));
			reviewDecision.put("confidence", postExecutionReview.get("confidence"));
			result.add(reviewDecision);
		}
		return result;
	}

	private Map<String, Object> safeSource(Map<String, Object> source) {
		Map<String, Object> safe = new LinkedHashMap<>();
		safe.put("datasourceId", source.get("datasource_id"));
		safe.put("status", source.get("status"));
		safe.put("rowCount", source.get("row_count"));
		safe.put("freshnessAsOf", source.get("freshness_as_of"));
		safe.put("sqlHash", hash(text(source.get("sql_text"))));
		return safe;
	}

	private Map<String, Object> bestProfile(String patternId) {
		return optional("""
				SELECT * FROM qw_query_path_profile WHERE pattern_id = ?
				ORDER BY dominated, pareto_rank, correctness_rate DESC, safety_rate DESC,
				 avg_latency_ms, avg_token_count LIMIT 1
				""", patternId).orElse(Map.of());
	}

	private double confidence(Map<String, Object> signal) {
		long occurrences = numberOrZero(signal.get("occurrence_count"));
		double rate = decimal(signal, "recurrence_rate");
		if (occurrences >= highConfidenceEpisodes && rate >= highConfidenceRate) {
			return Math.max(0.8, decimal(signal, "confidence"));
		}
		return Math.max(0.6, Math.min(0.79, decimal(signal, "confidence")));
	}

	static SemanticIssueType classifySqlIssue(String errors) {
		String value = Objects.toString(errors, "").toUpperCase(Locale.ROOT);
		// Raw SQL/JDBC error wording is not authoritative business-semantic evidence. Date functions, WEEK/LAG,
		// JOIN syntax, unknown aliases, cost rejections and ordinary column hallucinations stay execution defects.
		// Only explicit governed semantic error codes are allowed to promote repeated SQL repair into Semantic Evolution.
		if (value.contains("ENUM_MAPPING_MISSING")) {
			return SemanticIssueType.ENUM_MAPPING_MISSING;
		}
		if (value.contains("SEMANTIC_METRIC_NOT_FOUND")) {
			return SemanticIssueType.METRIC_MISSING;
		}
		if (value.contains("SEMANTIC_RELATIONSHIP_NOT_FOUND")) {
			return SemanticIssueType.RELATIONSHIP_MISSING;
		}
		if (value.contains("SEMANTIC_GRAIN_NOT_FOUND")) {
			return SemanticIssueType.GRAIN_MISSING;
		}
		if (value.contains("SCHEMA_DRIFT")) {
			return SemanticIssueType.SCHEMA_DRIFT;
		}
		return SemanticIssueType.LLM_SQL_GENERATION_DEFECT;
	}

	private boolean semanticEvolutionIssue(String issueType) {
		if (!StringUtils.hasText(issueType)) {
			return false;
		}
		try {
			return switch (SemanticIssueType.valueOf(issueType)) {
				case TERM_ALIAS_MISSING, ENUM_MAPPING_MISSING, ENUM_MAPPING_AMBIGUOUS, METRIC_MISSING,
						METRIC_FORMULA_INCORRECT, METRIC_TIME_COLUMN_INCORRECT, METRIC_FILTER_INCOMPLETE,
						DIMENSION_MISSING, RELATIONSHIP_MISSING, RELATIONSHIP_INCORRECT, CARDINALITY_INCORRECT,
						JOIN_CONDITION_INCORRECT, GRAIN_MISSING, GRAIN_INCORRECT, TIME_SEMANTICS_MISSING,
						TIME_SEMANTICS_AMBIGUOUS, PLANNING_POLICY_GAP, DATASOURCE_AUTHORITY_INCORRECT,
						MULTI_SOURCE_POLICY_INCORRECT, SCHEMA_DRIFT ->
					true;
				default -> false;
			};
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private String defaultText(Object... values) {
		for (Object value : values) {
			String text = Objects.toString(value, "").trim();
			if (!text.isBlank()) {
				return text;
			}
		}
		return "";
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> mapValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, item) -> result.put(Objects.toString(key), item));
			return Collections.unmodifiableMap(result);
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				return mapper.readValue(text, new TypeReference<>() {
				});
			}
			catch (Exception ignored) {
				return Map.of();
			}
		}
		return Map.of();
	}

	private String intent(SemanticBlueprint plan, List<Map<String, Object>> sources) {
		if (sources.size() > 1 || plan != null && plan.getSourceSubPlans().size() > 1) {
			return "MULTI_SOURCE_ANALYTICS";
		}
		if (plan != null && !plan.getMetrics().isEmpty() && !plan.getDimensions().isEmpty()) {
			return "GROUPED_AGGREGATION";
		}
		if (plan != null && !plan.getMetrics().isEmpty()) {
			return "AGGREGATION";
		}
		return "ENTITY_LOOKUP";
	}

	private List<String> lexicalShape(String normalized) {
		return Set
			.of("today", "yesterday", "week", "month", "year", "top", "bottom", "compare", "trend", "今天", "昨天", "本周",
					"本月", "今年", "前", "后", "对比", "趋势")
			.stream()
			.filter(normalized.toLowerCase(Locale.ROOT)::contains)
			.sorted()
			.toList();
	}

	private List<String> sorted(List<String> values) {
		return values.stream().filter(StringUtils::hasText).distinct().sorted().toList();
	}

	private String normalize(String normalized, String original) {
		String value = StringUtils.hasText(normalized) ? normalized : Objects.toString(original, "");
		return normalize(value);
	}

	private String normalize(String value) {
		return Objects.toString(value, "").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private Map<String, Object> one(String sql, Object... args) {
		return optional(sql, args).orElseThrow(() -> new IllegalArgumentException("Trajectory record not found"));
	}

	private Optional<Map<String, Object>> optional(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		return rows.stream().findFirst();
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private String persistentPatchJson(Object value) {
		if (value instanceof SemanticPatch patch) {
			return versionedJson.write(JsonPayloadRegistry.SEMANTIC_PATCH, patch);
		}
		if (value instanceof MultiSourcePolicyPatch patch) {
			return versionedJson.write(JsonPayloadRegistry.MULTI_SOURCE_POLICY_PATCH, patch);
		}
		throw new IllegalArgumentException("Unsupported persistent Patch type " + value.getClass().getName());
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize trajectory payload", ex);
		}
	}

	public Map<String, Object> readJson(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return mapper.readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid trajectory JSON", ex);
		}
	}

	private String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for (byte item : digest) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private void promotePatternReuseMode(String patternId, CaptureMode mode) {
		if (mode == CaptureMode.NONE) {
			return;
		}
		String target = mode == CaptureMode.EXECUTABLE ? "EXECUTABLE" : "PLAN_ONLY";
		jdbc.update("""
				UPDATE qw_query_pattern
				SET status = CASE WHEN status = 'EXECUTABLE' THEN status ELSE ? END, last_seen_time = CURRENT_TIMESTAMP
				WHERE id = ?
				""", target, patternId);
	}

	private boolean negativeOrCorrectionFeedback(Map<String, Object> feedback) {
		Object adopted = feedback.get("adopted");
		if (adopted instanceof Boolean value && !value) {
			return true;
		}
		String comment = text(feedback.get("comment_text")).toUpperCase(Locale.ROOT);
		return comment.startsWith("CORRECTION[");
	}

	private int logicalRetryCount(Map<String, Object> trace) {
		// SQL_GENERATE_COUNT becomes 1 after the normal first generation. Only counts
		// above
		// that represent an actual repair/retry.
		return Math.max(0, integer(trace.get("retry_count")) - 1);
	}

	private int bounded(int limit) {
		return Math.max(1, Math.min(limit, 500));
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private Object value(Object value) {
		return value == null ? "" : value;
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private Long nullableLong(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private long numberOrZero(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private int integer(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

	private boolean truth(Object value) {
		return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
	}

	private double decimal(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? 0.0 : ((Number) value).doubleValue();
	}

	private String nullIfBlank(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	private record SnapshotView(String compatibilityHash, SemanticBlueprint plan) {
	}

	private record PatternShape(String intentType, String shapeHash, String instanceHash, String ambiguity, String risk,
			Map<String, Object> payload) {
	}

	private record Scores(double correctness, double safety, double coverage, double freshness, double stability) {
	}

}
