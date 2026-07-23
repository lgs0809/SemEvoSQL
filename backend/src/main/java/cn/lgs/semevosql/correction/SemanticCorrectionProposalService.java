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
package cn.lgs.semevosql.correction;

import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.PlanningPolicyDistillationService;
import cn.lgs.semevosql.evolution.PlanningPolicyDistillationService.DistilledPolicy;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.evolution.application.LegacyEvolutionChangeSetBridge;
import cn.lgs.semevosql.learning.QueryCaseGovernanceProperties;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Converts an explicit user definition correction into a governed Semantic Evolution
 * proposal. No Catalog mutation occurs here; a valid SemanticPatch + replay is still
 * required before publication.
 */
@Service
public class SemanticCorrectionProposalService {

	private final JdbcTemplate jdbc;

	private final ExecutionSnapshotService snapshotService;

	private final PlanningPolicyDistillationService planningPolicyDistillationService;

	private final SemanticCatalogRepository catalogRepository;

	private final QueryCaseGovernanceProperties governanceProperties;

	private final SemanticCatalogPatchAnalyzer patchAnalyzer;

	private final LegacyEvolutionChangeSetBridge changeSetBridge;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private final VersionedJson versionedJson = new VersionedJson();

	public SemanticCorrectionProposalService(JdbcTemplate jdbc, ExecutionSnapshotService snapshotService,
			PlanningPolicyDistillationService planningPolicyDistillationService, SemanticCatalogRepository catalogRepository,
			QueryCaseGovernanceProperties governanceProperties, SemanticCatalogPatchAnalyzer patchAnalyzer,
			LegacyEvolutionChangeSetBridge changeSetBridge) {
		this.jdbc = jdbc;
		this.snapshotService = snapshotService;
		this.planningPolicyDistillationService = planningPolicyDistillationService;
		this.catalogRepository = catalogRepository;
		this.governanceProperties = governanceProperties;
		this.patchAnalyzer = patchAnalyzer;
		this.changeSetBridge = changeSetBridge;
	}

	@Transactional
	public ProposalResult propose(QueryRun run, String category, String correctionText, String principal) {
		String normalizedCategory = required(category, "category").toUpperCase(java.util.Locale.ROOT);
		if (!java.util.Set.of("DEFINITION", "TIME", "FILTER", "RELATIONSHIP", "PLANNING").contains(normalizedCategory)) {
			throw new IllegalArgumentException(
					"Only governed semantic or planning corrections can create proposals: " + normalizedCategory);
		}
		String text = required(correctionText, "correctionText");
		SemanticBlueprint plan = snapshotService.readTyped(run.executionSnapshot())
			.map(snapshot -> snapshot.semanticPlan())
			.orElse(null);
		String catalogHash = jdbc.queryForObject("SELECT catalog_hash FROM qw_project_version WHERE id = ?",
				String.class, run.projectVersionId());
		if (!StringUtils.hasText(catalogHash)) {
			throw new IllegalStateException("Project version has no catalog hash for semantic correction proposal");
		}
		String candidateType = "USER_" + normalizedCategory + "_CORRECTION";
		String riskLevel = switch (normalizedCategory) {
			case "RELATIONSHIP", "PLANNING" -> "HIGH";
			default -> "MEDIUM";
		};
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("source", "USER_CORRECTION");
		evidence.put("runId", run.runId());
		evidence.put("episodeId", Objects.toString(run.episodeId(), ""));
		evidence.put("principal", Objects.toString(principal, ""));
		evidence.put("category", normalizedCategory);
		evidence.put("correctionText", text);
		if (plan != null) {
			evidence.put("semanticPlan", plan);
		}
		ProposalMaterial material = "PLANNING".equals(normalizedCategory)
				? planningProposal(run, plan, catalogHash, text)
				: new ProposalMaterial(target(normalizedCategory, plan, run.runId()), proposalOnly(run, catalogHash), Map.of());
		AssetTarget target = material.target();
		String proposalPatch = material.patchJson();
		evidence.putAll(material.evidence());
		String candidateId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO qw_semantic_evolution_candidate
				(id, project_id, source_version_id, source_catalog_hash, candidate_type, asset_type, asset_key,
				 status, confidence, risk_level, patch_json, evidence_summary, mapping_classification,
				 distinct_conversation_count, distinct_user_count, distinct_root_evidence_count,
				 distinct_time_window_count, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 1.0, ?, CAST(? AS JSONB), ?, 'USER_CONFIRMED',
				        1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (source_version_id, source_catalog_hash, candidate_type, asset_type, asset_key, status)
				DO UPDATE SET confidence = 1.0, risk_level = EXCLUDED.risk_level, evidence_summary = EXCLUDED.evidence_summary,
				              update_time = CURRENT_TIMESTAMP
				""", candidateId, run.projectId(), run.projectVersionId(), catalogHash, candidateType,
				target.assetType(), target.assetKey(), riskLevel, proposalPatch, json(evidence));
		String persistedId = jdbc.queryForObject("""
				SELECT id FROM qw_semantic_evolution_candidate
				WHERE source_version_id = ? AND source_catalog_hash = ? AND candidate_type = ?
				  AND asset_type = ? AND asset_key = ? AND status = 'CANDIDATE'
				""", String.class, run.projectVersionId(), catalogHash, candidateType, target.assetType(),
				target.assetKey());
		Integer existingEvidence = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_candidate_evidence
				WHERE candidate_id = ? AND evidence_type = 'USER_CORRECTION'
				  AND COALESCE(episode_id, '') = COALESCE(?, '')
				""", Integer.class, persistedId, run.episodeId());
		if (existingEvidence == null || existingEvidence == 0) {
			jdbc.update("""
					INSERT INTO qw_candidate_evidence
					(id, candidate_id, evidence_type, episode_id, weight, evidence_json, create_time)
					VALUES (?, ?, 'USER_CORRECTION', ?, 1.0, CAST(? AS JSONB), CURRENT_TIMESTAMP)
					""", UUID.randomUUID().toString(), persistedId, run.episodeId(), json(evidence));
		}
		String semanticChangeSetId = null;
		if (changeSetBridge.hasSemanticOperations(proposalPatch)) {
			semanticChangeSetId = changeSetBridge.linkCandidate(run.projectId(), run.projectVersionId(), persistedId,
					candidateType, target.assetType(), target.assetKey(), riskLevel, proposalPatch, evidence, run.episodeId(),
					principal).semanticChangeSetId();
		}
		return new ProposalResult(persistedId, semanticChangeSetId, candidateType, target.assetType(), target.assetKey(),
				"CANDIDATE");
	}

	private ProposalMaterial planningProposal(QueryRun run, SemanticBlueprint rejectedPlan, String catalogHash,
			String correctionText) {
		AssetTarget manualTarget = new AssetTarget("PLANNING_POLICY", "PLANNING:RUN:" + run.runId());
		if (rejectedPlan == null) {
			return ProposalMaterial.manual(manualTarget, proposalOnly(run, catalogHash));
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(run.projectId(), run.projectVersionId());
		List<SemanticCatalogSnapshot.Rule> rules = catalog.getRules() == null ? List.of() : catalog.getRules();
		List<Map<String, Object>> existingPolicies = rules.stream()
			.filter(rule -> "PLANNING_POLICY".equalsIgnoreCase(Objects.toString(rule.getRuleType(), "")))
			.map(rule -> Map.<String, Object>of("ruleCode", Objects.toString(rule.getRuleCode(), ""), "expression",
					Objects.toString(rule.getExpression(), ""), "description", Objects.toString(rule.getDescription(), "")))
			.toList();
		Optional<DistilledPolicy> distilled;
		try {
			distilled = planningPolicyDistillationService.distillExplicitCorrection(question(run.episodeId()), rejectedPlan,
					correctionText, existingPolicies);
		}
		catch (RuntimeException ex) {
			distilled = Optional.empty();
		}
		if (distilled.isEmpty()
				|| distilled.orElseThrow().confidence() < governanceProperties.getPlanningPolicyMinDistillationConfidence()) {
			return new ProposalMaterial(manualTarget, proposalOnly(run, catalogHash),
					Map.of("proposalMode", "MANUAL_POLICY_EDIT_REQUIRED"));
		}
		DistilledPolicy policy = distilled.orElseThrow();
		String identity = run.projectId() + "|" + correctionText.trim().toLowerCase(java.util.Locale.ROOT);
		String ruleCode = "learned_planning_user_" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
			.toString().replace("-", "").substring(0, 16);
		SemanticCatalogSnapshot.Rule existingRule = rules.stream()
			.filter(rule -> Objects.equals(ruleCode, rule.getRuleCode()))
			.findFirst()
			.orElse(null);
		if (existingRule != null && !"PLANNING_POLICY".equalsIgnoreCase(Objects.toString(existingRule.getRuleType(), ""))) {
			return new ProposalMaterial(manualTarget, proposalOnly(run, catalogHash),
					Map.of("proposalMode", "MANUAL_POLICY_EDIT_REQUIRED", "reason", "RULE_CODE_COLLISION"));
		}
		Map<String, Object> values = new LinkedHashMap<>();
		if (existingRule == null) {
			values.put("ruleCode", ruleCode);
			values.put("ruleType", "PLANNING_POLICY");
		}
		values.put("businessName", "User-confirmed planning policy proposal");
		values.put("expression", policy.policyText());
		values.put("severity", "INFO");
		values.put("description", planningPolicyDescription(policy));
		if (existingRule == null) {
			values.put("evidence", "Explicit user planning correction; requires replay and human review before publication");
		}
		OperationType operationType = existingRule == null ? OperationType.ADD_RULE : OperationType.UPDATE_RULE;
		String fingerprint = existingRule == null ? null : patchAnalyzer.fingerprintAsset(AssetType.RULE, existingRule);
		SemanticPatch patch = new SemanticPatch(1, run.projectVersionId(), catalogHash,
				List.of(new Operation(operationType, "RULE", ruleCode, fingerprint, values, List.of())));
		return new ProposalMaterial(new AssetTarget("RULE", ruleCode),
				versionedJson.write(JsonPayloadRegistry.SEMANTIC_PATCH, patch),
				Map.of("proposalMode", "AUTO_DISTILLED_PLANNING_POLICY", "distillation",
						distillationEvidence(policy)));
	}

	private String proposalOnly(QueryRun run, String catalogHash) {
		return json(Map.of("schemaVersion", 1, "sourceVersionId", run.projectVersionId(), "sourceCatalogHash", catalogHash,
				"proposalOnly", true, "operations", List.of()));
	}

	private String question(String episodeId) {
		if (!StringUtils.hasText(episodeId)) {
			return "";
		}
		List<String> values = jdbc.queryForList("""
				SELECT COALESCE(NULLIF(normalized_question, ''), original_question)
				FROM qw_episode WHERE id = ? LIMIT 1
				""", String.class, episodeId);
		return values.isEmpty() ? "" : Objects.toString(values.get(0), "");
	}

	private String planningPolicyDescription(DistilledPolicy policy) {
		String counters = policy.counterExamples().isEmpty() ? "none supplied" : String.join(" | ", policy.counterExamples());
		return "Applicability: " + policy.applicability() + ". Counterexamples: " + counters;
	}

	private Map<String, Object> distillationEvidence(DistilledPolicy policy) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("policyText", policy.policyText());
		evidence.put("applicability", policy.applicability());
		evidence.put("counterExamples", policy.counterExamples());
		evidence.put("confidence", policy.confidence());
		if (policy.modelEvidence() != null) {
			evidence.put("model", Map.of("callId", Objects.toString(policy.modelEvidence().callId(), ""), "latencyMs",
					policy.modelEvidence().latencyMs(), "promptTokens", policy.modelEvidence().promptTokens(),
					"completionTokens", policy.modelEvidence().completionTokens()));
		}
		return Map.copyOf(evidence);
	}

	private AssetTarget target(String category, SemanticBlueprint plan, String runId) {
		if (plan != null && "DEFINITION".equals(category) && plan.getMetrics().size() == 1) {
			return new AssetTarget("METRIC", plan.getMetrics().get(0).getMetricCode());
		}
		if (plan != null && "TIME".equals(category) && plan.getTimeRange() != null) {
			return new AssetTarget("TIME_SEMANTICS",
					plan.getTimeRange().getModelCode() + ":" + plan.getTimeRange().getTimeColumn());
		}
		if (plan != null && "FILTER".equals(category) && plan.getFilters().size() == 1) {
			var filter = plan.getFilters().get(0);
			return new AssetTarget("FILTER_RULE", filter.getModelCode() + ":"
					+ Objects.toString(filter.getColumnName(), Objects.toString(filter.getExpression(), "filter")));
		}
		if (plan != null && "RELATIONSHIP".equals(category) && plan.getRelationships().size() == 1) {
			return new AssetTarget("RELATIONSHIP", plan.getRelationships().get(0).getRelationshipCode());
		}
		return new AssetTarget("PROJECT_SEMANTIC", category + ":RUN:" + runId);
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize semantic correction proposal", ex);
		}
	}

	private static String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private record AssetTarget(String assetType, String assetKey) {
	}

	private record ProposalMaterial(AssetTarget target, String patchJson, Map<String, Object> evidence) {

		private static ProposalMaterial manual(AssetTarget target, String patchJson) {
			return new ProposalMaterial(target, patchJson, Map.of("proposalMode", "MANUAL_POLICY_EDIT_REQUIRED"));
		}
	}

	public record ProposalResult(String candidateId, String semanticChangeSetId, String candidateType, String assetType,
			String assetKey, String status) {
	}

}
