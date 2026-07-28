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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.JsonPayloadRegistry;
import cn.lgs.semevosql.common.json.VersionedJson;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.evolution.application.LegacyEvolutionChangeSetBridge;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator;
import cn.lgs.semevosql.learning.LearningAssetTrustPolicy;
import cn.lgs.semevosql.project.application.ProjectInitializationApplicationService;
import cn.lgs.semevosql.project.application.ProjectInitializationApplicationService.ProjectInitializationView;
import cn.lgs.semevosql.project.domain.ProjectVersionCreationMode;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Human-governed lifecycle for trajectory-derived semantic changes. Approved patches are
 * applied only to a CLONE Draft, replayed through normal runtime gates, and still require
 * the existing version publication gate; Published Catalogs and security policy are never
 * mutated in place.
 */
@Service
@RequiredArgsConstructor
public class SemanticEvolutionService {

	private static final String TRUE_AMBIGUITY_MESSAGE = "TRUE_AMBIGUITY requires explicit semantic resolution and cannot be auto-approved or applied";

	private static final List<String> ACTIVE_STATUSES = List.of("CANDIDATE", "APPROVED", "DRAFT_CREATED",
			"PATCH_APPLIED", "REPLAY_RUNNING", "REPLAY_PASSED", "REPLAY_FAILED", "READY_FOR_PUBLISH");

	private final JdbcTemplate jdbc;

	private final ProjectInitializationApplicationService initializationService;

	private final SemanticPatchApplicationService patchApplicationService;

	private final SemanticReplayService replayService;

	private final VersionedJson versionedJson = new VersionedJson();

	private final SemanticPatchValidator patchValidator;

	private final SemanticEvolutionAuditService auditService;

	private final SemanticReplayCoordinator replayCoordinator;

	private final MultiSourcePolicyPatchService policyPatchService;

	private final SemanticEvolutionPublicationListener publicationListener;

	private final ManualReplayAttestationService attestationService;

	private final SemanticEvolutionStateMachine stateMachine;

	private final SemanticEvolutionReleaseOrchestrator releaseOrchestrator;

	private final LegacyEvolutionChangeSetBridge changeSetBridge;

	private final LocalOperatorService authorization;

	public List<Map<String, Object>> list(Long projectId, String status, int limit) {
		if (StringUtils.hasText(status)) {
			return jdbc.queryForList("""
					SELECT * FROM qw_semantic_evolution_candidate
					WHERE project_id = ? AND status = ? ORDER BY confidence DESC, create_time DESC LIMIT ?
					""", projectId, status.toUpperCase(Locale.ROOT), bounded(limit));
		}
		return jdbc.queryForList("""
				SELECT * FROM qw_semantic_evolution_candidate
				WHERE project_id = ? ORDER BY create_time DESC LIMIT ?
				""", projectId, bounded(limit));
	}

	public Map<String, Object> get(String candidateId) {
		Map<String, Object> result = new LinkedHashMap<>(candidate(candidateId));
		result.put("evidence", jdbc.queryForList("""
				SELECT * FROM qw_candidate_evidence WHERE candidate_id = ? ORDER BY weight DESC, create_time
				""", candidateId));
		result.put("replayResults", replayResults(candidateId));
		String patchJson = text(result.get("patch_json"));
		boolean proposalOnly = proposalOnly(patchJson);
		result.put("proposalOnly", proposalOnly);
		if (patchValidator != null && !policyCandidate(result)) {
			result.put("assetDiff", proposalOnly ? List.of() : patchValidator.assetDiff(candidateId, parsePatch(patchJson)));
		}
		if (auditService != null) {
			result.put("events", auditService.events(candidateId));
		}
		if (attestationService != null) {
			result.put("manualAttestations", attestationService.attestations(candidateId));
			result.put("releaseDecisions", attestationService.releaseDecisions(candidateId));
		}
		return result;
	}

	public List<Map<String, Object>> replayResults(String candidateId) {
		candidate(candidateId);
		return replayService.results(candidateId);
	}

	@Transactional
	public Map<String, Object> updatePatch(String candidateId, SemanticPatch patch) {
		return updatePatch(candidateId, patch, OperatorContext.system("semantic-patch-edit"));
	}

	@Transactional
	public Map<String, Object> updatePatch(String candidateId, SemanticPatch patch, OperatorContext operator) {
		authorization.require(operator, "edit Semantic Patch");
		Map<String, Object> current = lock(candidateId);
		if (policyCandidate(current)) {
			throw new IllegalArgumentException("Use the separate MultiSourcePolicyPatch endpoint for this candidate");
		}
		if (replayed(candidateId, "PATCH_EDITED", operator, patch)) {
			return get(candidateId);
		}
		if (!"CANDIDATE".equals(text(current.get("status")))) {
			throw new IllegalStateException("Only CANDIDATE semantic patches can be edited");
		}
		assertSourceStillCurrent(current);
		if (patch == null || !Objects.equals(number(current.get("source_version_id")), patch.sourceVersionId())
				|| !Objects.equals(text(current.get("source_catalog_hash")), patch.sourceCatalogHash())) {
			throw new IllegalArgumentException(
					"Semantic Patch must keep the candidate source version and Catalog Hash");
		}
		requireValid(candidateId, patch);
		String effectiveRiskLevel = patchValidator.effectiveRiskLevel(patch, text(current.get("risk_level")));
		String patchJson = versionedJson.write(JsonPayloadRegistry.SEMANTIC_PATCH, patch);
		int updated = jdbc.update("""
				UPDATE qw_semantic_evolution_candidate SET patch_json = ?, patch_hash = NULL, risk_level = ?,
				 revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'CANDIDATE' AND revision = ?
				""", patchJson, effectiveRiskLevel, candidateId, number(current.get("revision")));
		if (updated != 1) {
			throw new IllegalStateException("Semantic Patch edit lost a concurrent Candidate update");
		}
		Map<String, Object> evidence = candidateEvidence(current);
		String episodeId = Objects.toString(evidence.get("episodeId"), "");
		var bridged = changeSetBridge.linkCandidate(number(current.get("project_id")),
				number(current.get("source_version_id")), candidateId, text(current.get("candidate_type")),
				text(current.get("asset_type")), text(current.get("asset_key")), effectiveRiskLevel, patchJson, evidence,
				episodeId, operator.operator());
		appendAudit(candidateId, "PATCH_EDITED", "CANDIDATE", "CANDIDATE", operator, current, null, null, patch,
				Map.of("patch", patch, "semanticChangeSetId", bridged.semanticChangeSetId()));
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> updatePolicyPatch(String candidateId, MultiSourcePolicyPatch patch,
			OperatorContext operator) {
		authorization.require(operator, "edit Multi-Source Policy Patch");
		Map<String, Object> current = lock(candidateId);
		if (!policyCandidate(current)) {
			throw new IllegalArgumentException("Candidate does not use the MultiSourcePolicyPatch DSL");
		}
		if (replayed(candidateId, "POLICY_PATCH_EDITED", operator, patch)) {
			return get(candidateId);
		}
		if (!"CANDIDATE".equals(text(current.get("status")))) {
			throw new IllegalStateException("Only CANDIDATE Policy Patches can be edited");
		}
		assertSourceStillCurrent(current);
		if (patch == null || !Objects.equals(number(current.get("source_version_id")), patch.sourceVersionId())
				|| !Objects.equals(text(current.get("source_catalog_hash")), patch.sourceCatalogHash())) {
			throw new IllegalArgumentException("Policy Patch must keep the candidate source version and Catalog Hash");
		}
		requirePolicyValid(candidateId, patch);
		String patchJson = versionedJson.write(JsonPayloadRegistry.MULTI_SOURCE_POLICY_PATCH, patch);
		int updated = jdbc.update("""
				UPDATE qw_semantic_evolution_candidate SET patch_json = ?, patch_hash = NULL,
				 risk_level = 'HIGH', revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'CANDIDATE' AND revision = ?
				""", patchJson, candidateId, number(current.get("revision")));
		if (updated != 1) {
			throw new IllegalStateException("Policy Patch edit lost a concurrent Candidate update");
		}
		appendAudit(candidateId, "POLICY_PATCH_EDITED", "CANDIDATE", "CANDIDATE", operator, current, null, null, patch,
				Map.of("policyPatch", patch, "manualReplayRequired", true));
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> review(String candidateId, ReviewCommand command) {
		return review(candidateId, command, OperatorContext.system("semantic-review"));
	}

	@Transactional
	public Map<String, Object> review(String candidateId, ReviewCommand command, OperatorContext operator) {
		authorization.require(operator, "review Semantic Evolution Candidate");
		Map<String, Object> current = lock(candidateId);
		if (replayed(candidateId, "CANDIDATE_REVIEWED", operator, command)) {
			return get(candidateId);
		}
		if (!"CANDIDATE".equals(text(current.get("status")))) {
			throw new IllegalStateException("Only CANDIDATE semantic evolution records can be reviewed");
		}
		assertSourceStillCurrent(current);
		if (command.approved()) {
			assertNotTrueAmbiguity(current);
			requireCandidatePatchValid(candidateId, current);
			if ("PROJECT_ALIAS_PROPOSAL".equals(text(current.get("candidate_type")))) {
				LearningAssetTrustPolicy.assertPromotionAllowed(LearningAssetTrustPolicy.AssetClass.PROJECT_ALIAS,
						LearningAssetTrustPolicy.PromotionMode.REVIEW_REQUIRED);
			}
		}
		String reviewer = required(operator.operator(), "operator");
		String status = command.approved() ? "APPROVED" : "REJECTED";
		if (!command.approved() && !StringUtils.hasText(command.comment())) {
			throw new IllegalArgumentException("comment is required when rejecting a semantic candidate");
		}
		stateMachine().transition(candidateId, CandidateStatus.CANDIDATE, number(current.get("revision")),
				CandidateStatus.valueOf(status), Mutation.review(reviewer, trim(command.comment(), 8000)));
		appendAudit(candidateId, command.approved() ? "CANDIDATE_APPROVED" : "CANDIDATE_REJECTED", "CANDIDATE", status,
				operator, current, null, null, command,
				Map.of("approved", command.approved(), "comment", Objects.toString(command.comment(), "")));
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> createDraft(String candidateId, DraftCommand command) {
		return createDraft(candidateId, command, OperatorContext.system("semantic-draft-create"));
	}

	@Transactional
	public Map<String, Object> createDraft(String candidateId, DraftCommand command, OperatorContext operator) {
		authorization.require(operator, "create Semantic Evolution draft");
		Map<String, Object> current = lock(candidateId);
		assertNotTrueAmbiguity(current);
		if (replayed(candidateId, "DRAFT_CREATED", operator, command)) {
			return get(candidateId);
		}
		if ("DRAFT_CREATED".equals(text(current.get("status"))) || "PATCH_APPLIED".equals(text(current.get("status")))
				|| "REPLAY_RUNNING".equals(text(current.get("status")))
				|| "REPLAY_PASSED".equals(text(current.get("status")))
				|| "REPLAY_FAILED".equals(text(current.get("status")))
				|| "READY_FOR_PUBLISH".equals(text(current.get("status")))) {
			return get(candidateId);
		}
		if (!"APPROVED".equals(text(current.get("status")))) {
			throw new IllegalStateException("Semantic candidate must be APPROVED before a draft is created");
		}
		assertSourceStillCurrent(current);
		requireCandidatePatchValid(candidateId, current);
		Long projectId = number(current.get("project_id"));
		Long sourceVersionId = number(current.get("source_version_id"));
		String changeSetId = text(current.get("semantic_change_set_id"));
		if (StringUtils.hasText(changeSetId)) {
			SemanticPatch patch = parsePatch(text(current.get("patch_json")));
			patchApplicationService.previewPatch(projectId, sourceVersionId, patch);
			String patchHash = patchApplicationService.patchHash(patch);
			stateMachine().transition(candidateId, CandidateStatus.APPROVED, number(current.get("revision")),
					CandidateStatus.PATCH_APPLIED, Mutation.patchApplied(patchHash));
			var changeSet = releaseOrchestrator.beginValidation(changeSetId, patchHash,
					Map.of("legacyCandidateId", candidateId, "sourceVersionId", sourceVersionId));
			if (changeSet.status() == Status.VALIDATING) {
				releaseOrchestrator.approveValidation(changeSetId,
						Map.of("patchValidated", true, "legacyCandidateId", candidateId));
			}
			appendAudit(candidateId, "PATCH_VALIDATED", "APPROVED", "PATCH_APPLIED", operator, current, null,
					patchHash, command, Map.of("semanticChangeSetId", changeSetId, "draftVersionCreated", false));
			Map<String, Object> result = new LinkedHashMap<>(get(candidateId));
			result.put("semanticChangeSetId", changeSetId);
			result.put("draftVersion", null);
			result.put("patchApplicationRequired", false);
			result.put("automaticPatchApplied", false);
			result.put("changeSetWorkspace", true);
			return result;
		}
		ProjectInitializationView draft = initializationService.createDraftVersion(projectId,
				required(command.versionNumber(), "versionNumber"), ProjectVersionCreationMode.CLONE, sourceVersionId,
				"semantic-evolution:" + candidateId, operator);
		Long draftVersionId = draft.version().getId();
		stateMachine().transition(candidateId, CandidateStatus.APPROVED, number(current.get("revision")),
				CandidateStatus.DRAFT_CREATED, Mutation.draft(draftVersionId));
		SemanticPatchApplicationService.PatchApplicationResult applied = patchApplicationService
			.applyCandidate(candidateId);
		appendAudit(candidateId, "DRAFT_CREATED", "APPROVED", "DRAFT_CREATED", operator, current, draftVersionId, null,
				command, Map.of("targetDraftVersionId", draftVersionId));
		appendAudit(candidateId, "PATCH_APPLIED", "DRAFT_CREATED", "PATCH_APPLIED",
				withKey(operator, operator.idempotencyKey() + ":patch-applied"), current, draftVersionId,
				applied.patchHash(), command, applied);
		Map<String, Object> result = new LinkedHashMap<>(get(candidateId));
		result.put("draftVersion", draft.version());
		result.put("patchApplicationRequired", false);
		result.put("automaticPatchApplied", true);
		result.put("patchApplication", applied);
		return result;
	}

	@Transactional
	public SemanticReplayService.ReplaySummary replay(String candidateId) {
		return replayService.replayCandidate(candidateId);
	}

	public SemanticReplayCoordinator.ReplayRunView startReplay(String candidateId, OperatorContext operator) {
		authorization.require(operator, "start Semantic Replay");
		return replayCoordinator.start(candidateId, operator);
	}

	@Transactional
	public Map<String, Object> recordReplay(String candidateId, ReplayCommand command) {
		return recordReplay(candidateId, command, OperatorContext.system("semantic-manual-replay"));
	}

	@Transactional
	public Map<String, Object> recordReplay(String candidateId, ReplayCommand command, OperatorContext operator) {
		Map<String, Object> current = candidate(candidateId);
		String status = text(current.get("status"));
		if (command.passed() && "REPLAY_PASSED".equals(status)) {
			return get(candidateId);
		}
		String attestationType = command.passed() ? "EXCEPTION" : "REJECTION";
		String decision = command.passed() ? "APPROVED_WITH_EXCEPTION" : "REJECTED";
		String replayRunId = command.passed() ? latestFailedReplayRun(candidateId) : null;
		attestationService.attest(candidateId,
				new ManualReplayAttestationService.AttestationCommand(attestationType, decision,
						required(command.summary(), "summary"), command.passed() ? command.summary() : null,
						replayRunId, Map.of()),
				operator);
		return get(candidateId);
	}

	public Map<String, Object> markReadyForPublish(String candidateId, String reviewedBy) {
		return markReadyForPublish(candidateId, OperatorContext.system("semantic-ready-for-publish"));
	}

	public Map<String, Object> markReadyForPublish(String candidateId, OperatorContext operator) {
		authorization.require(operator, "approve Candidate for publication");
		Map<String, Object> current = candidate(candidateId);
		if (replayed(candidateId, "READY_FOR_PUBLISH", operator, Map.of())) {
			return get(candidateId);
		}
		if ("READY_FOR_PUBLISH".equals(text(current.get("status")))) {
			return get(candidateId);
		}
		String status = text(current.get("status"));
		boolean automatedPassed = "REPLAY_PASSED".equals(status);
		boolean exceptionAllowed = "REPLAY_FAILED".equals(status)
				&& attestationService.latestAllowsRelease(candidateId);
		if (!automatedPassed && !exceptionAllowed) {
			throw new IllegalStateException(
					"Release requires automated Replay PASS or an explicit ALLOW Release Decision");
		}
		LearningAssetTrustPolicy.assertPromotionAllowed(
				"PROJECT_ALIAS_PROPOSAL".equals(text(current.get("candidate_type")))
						? LearningAssetTrustPolicy.AssetClass.PROJECT_ALIAS
						: LearningAssetTrustPolicy.AssetClass.SEMANTIC_CATALOG_CHANGE,
				LearningAssetTrustPolicy.PromotionMode.REPLAY_GATED);
		String reviewer = required(operator.operator(), "operator");
		String changeSetId = text(current.get("semantic_change_set_id"));
		if (StringUtils.hasText(changeSetId)) {
			if (!automatedPassed) {
				throw new IllegalStateException(
						"SemanticChangeSet release requires automated Replay PASS; legacy replay exceptions cannot auto-activate semantics");
			}
			Map<String, Object> replaySummary = versionedJson.readMap(text(current.get("replay_summary_json")),
					JsonPayloadRegistry.REPLAY_SUMMARY);
			int evaluatedCases = integer(replaySummary.get("queryCases")) + integer(replaySummary.get("goldenCases"));
			int passedCases = integer(replaySummary.get("passed"));
			String replayRunId = text(replaySummary.get("replayExecutionId"));
			var semanticRelease = releaseOrchestrator.releaseAfterReplay(changeSetId,
					new SemanticEvolutionReleaseOrchestrator.ReplayDecision(true, replayRunId, evaluatedCases,
							passedCases, evaluatedCases >= 10, replaySummary),
					reviewer, StringUtils.hasText(operator.requestId()) ? operator.requestId()
							: "semantic-release:" + candidateId);
			var compatibility = stateMachine().state(candidateId);
			if (compatibility.status() == CandidateStatus.REPLAY_PASSED) {
				compatibility = stateMachine().transition(candidateId, CandidateStatus.REPLAY_PASSED,
						compatibility.revision(), CandidateStatus.READY_FOR_PUBLISH, Mutation.ready(reviewer));
			}
			if (semanticRelease.status() == Status.ACTIVE && compatibility.status() == CandidateStatus.READY_FOR_PUBLISH) {
				stateMachine().transition(candidateId, CandidateStatus.READY_FOR_PUBLISH, compatibility.revision(),
						CandidateStatus.PUBLISHED, Mutation.published());
			}
			appendAudit(candidateId, "SEMANTIC_CHANGE_SET_RELEASED", status, semanticRelease.status().name(), operator,
					current, semanticRelease.semanticVersionId(), text(current.get("patch_hash")), Map.of(), semanticRelease);
			Map<String, Object> result = new LinkedHashMap<>(get(candidateId));
			result.put("semanticChangeSetId", changeSetId);
			result.put("semanticRelease", semanticRelease);
			return result;
		}
		stateMachine().transition(candidateId, CandidateStatus.valueOf(status), number(current.get("revision")),
				CandidateStatus.READY_FOR_PUBLISH, Mutation.ready(reviewer));
		Map<String, Object> release = attestationService.latestDecision(candidateId).orElse(Map.of());
		appendAudit(candidateId, "READY_FOR_PUBLISH", status, "READY_FOR_PUBLISH", operator, current,
				number(current.get("target_draft_version_id")), text(current.get("patch_hash")), Map.of(),
				Map.of("automatedReplayPassed", automatedPassed, "manualException", exceptionAllowed, "releaseDecision",
						release));
		return get(candidateId);
	}

	/**
	 * Operational compensation endpoint retained for a failed after-commit listener.
	 * Normal publication never requires this call.
	 */
	public Map<String, Object> acknowledgePublished(String candidateId, OperatorContext operator) {
		authorization.require(operator, "retry Semantic Evolution publication");
		Map<String, Object> current = candidate(candidateId);
		Long draftVersionId = number(current.get("target_draft_version_id"));
		Map<String, Object> draft = version(draftVersionId);
		if (!"PUBLISHED".equals(text(draft.get("status")))) {
			throw new IllegalStateException(
					"Target project version has not been published; publication compensation is not allowed");
		}
		publicationListener.retry(number(current.get("project_id")), draftVersionId);
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> markStale(String candidateId, String reason) {
		return markStale(candidateId, reason, OperatorContext.system("semantic-candidate-stale"));
	}

	@Transactional
	public Map<String, Object> markStale(String candidateId, String reason, OperatorContext operator) {
		Map<String, Object> current = lock(candidateId);
		if (replayed(candidateId, "CANDIDATE_STALE", operator, Map.of("reason", reason))) {
			return get(candidateId);
		}
		if (!ACTIVE_STATUSES.contains(text(current.get("status")))) {
			return get(candidateId);
		}
		stateMachine().transition(candidateId, CandidateStatus.valueOf(text(current.get("status"))),
				number(current.get("revision")), CandidateStatus.STALE,
				Mutation.stale(trim(required(reason, "reason"), 8000)));
		appendAudit(candidateId, "CANDIDATE_STALE", text(current.get("status")), "STALE", operator, current,
				number(current.get("target_draft_version_id")), text(current.get("patch_hash")),
				Map.of("reason", reason), Map.of("reason", reason));
		return get(candidateId);
	}

	public SemanticPatchValidator.ValidationReport preflight(String candidateId, SemanticPatch patch) {
		Map<String, Object> current = candidate(candidateId);
		if (policyCandidate(current)) {
			throw new IllegalArgumentException("Use the MultiSourcePolicyPatch preflight endpoint for this candidate");
		}
		String patchJson = text(current.get("patch_json"));
		if (patch == null && proposalOnly(patchJson)) {
			return proposalOnlyValidationReport();
		}
		return patchValidator.validateCandidate(candidateId, patch == null ? parsePatch(patchJson) : patch);
	}

	public MultiSourcePolicyPatchService.ValidationReport preflightPolicy(String candidateId,
			MultiSourcePolicyPatch patch) {
		Map<String, Object> current = candidate(candidateId);
		if (!policyCandidate(current)) {
			throw new IllegalArgumentException("Candidate does not use the MultiSourcePolicyPatch DSL");
		}
		return policyPatchService.validateCandidate(candidateId,
				patch == null ? parsePolicyPatch(text(current.get("patch_json"))) : patch);
	}

	private void assertSourceStillCurrent(Map<String, Object> candidate) {
		Map<String, Object> source = version(number(candidate.get("source_version_id")));
		if (!Objects.equals(number(source.get("project_id")), number(candidate.get("project_id")))) {
			throw new IllegalStateException("Semantic candidate source version belongs to another project");
		}
		if (!Objects.equals(text(source.get("catalog_hash")), text(candidate.get("source_catalog_hash")))) {
			throw new IllegalStateException("Semantic candidate is stale because its source Catalog Hash changed");
		}
		if (!"PUBLISHED".equals(text(source.get("status")))) {
			throw new IllegalStateException("Semantic evolution candidates must originate from a PUBLISHED version");
		}
	}

	private Map<String, Object> candidate(String candidateId) {
		List<Map<String, Object>> values = jdbc
			.queryForList("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Semantic evolution candidate not found: " + candidateId);
		}
		return values.get(0);
	}

	private Map<String, Object> lock(String candidateId) {
		List<Map<String, Object>> values = jdbc
			.queryForList("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE", candidateId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Semantic evolution candidate not found: " + candidateId);
		}
		return values.get(0);
	}

	private Map<String, Object> version(Long versionId) {
		if (versionId == null) {
			throw new IllegalStateException("Project version is required");
		}
		List<Map<String, Object>> values = jdbc.queryForList("SELECT * FROM qw_project_version WHERE id = ?",
				versionId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Project version not found: " + versionId);
		}
		return values.get(0);
	}

	private String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private String trim(String value, int max) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.length() <= max ? normalized : normalized.substring(0, max);
	}

	private int bounded(int limit) {
		return Math.max(1, Math.min(limit, 500));
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private int integer(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private Map<String, Object> candidateEvidence(Map<String, Object> candidate) {
		Object raw = candidate.get("evidence_summary");
		if (raw == null || !StringUtils.hasText(raw.toString())) {
			return Map.of();
		}
		try {
			return JsonUtil.getObjectMapper().readValue(raw.toString(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
		}
		catch (Exception invalid) {
			throw new IllegalStateException("Semantic evolution candidate has invalid evidence_summary", invalid);
		}
	}

	private boolean proposalOnly(String patchJson) {
		if (!StringUtils.hasText(patchJson)) {
			return false;
		}
		try {
			return JsonUtil.getObjectMapper().readTree(patchJson).path("proposalOnly").asBoolean(false);
		}
		catch (Exception invalid) {
			return false;
		}
	}

	private SemanticPatchValidator.ValidationReport proposalOnlyValidationReport() {
		SemanticPatchValidator.Violation error = new SemanticPatchValidator.Violation(
				SemanticPatchValidator.Severity.ERROR, "PROPOSAL_ONLY", "patch",
				"Proposal-only semantic candidate requires an explicit reviewed Semantic Patch before draft or replay");
		return new SemanticPatchValidator.ValidationReport(false, List.of(error), List.of(), 0, 0);
	}

	private SemanticPatch parsePatch(String patchJson) {
		return versionedJson.read(patchJson, JsonPayloadRegistry.SEMANTIC_PATCH, SemanticPatch.class);
	}

	private MultiSourcePolicyPatch parsePolicyPatch(String patchJson) {
		return versionedJson.read(patchJson, JsonPayloadRegistry.MULTI_SOURCE_POLICY_PATCH,
				MultiSourcePolicyPatch.class);
	}

	private void requireValid(String candidateId, SemanticPatch patch) {
		patchValidator.requireValid(candidateId, patch);
	}

	private void requirePolicyValid(String candidateId, MultiSourcePolicyPatch patch) {
		policyPatchService.requireValid(candidateId, patch);
	}

	private void requireCandidatePatchValid(String candidateId, Map<String, Object> candidate) {
		String patchJson = text(candidate.get("patch_json"));
		if (policyCandidate(candidate)) {
			requirePolicyValid(candidateId, parsePolicyPatch(patchJson));
		}
		else if (proposalOnly(patchJson)) {
			throw new IllegalStateException(
					"Proposal-only semantic candidate requires an explicit reviewed Semantic Patch before draft or replay");
		}
		else {
			requireValid(candidateId, parsePatch(patchJson));
		}
	}

	private boolean policyCandidate(Map<String, Object> candidate) {
		return List.of("DATASOURCE_AUTHORITY_INCORRECT", "MULTI_SOURCE_POLICY_INCORRECT")
			.contains(text(candidate.get("candidate_type")).toUpperCase(Locale.ROOT))
				|| text(candidate.get("asset_type")).toUpperCase(Locale.ROOT).startsWith("POLICY_");
	}

	private void assertNotTrueAmbiguity(Map<String, Object> candidate) {
		if ("TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")).toUpperCase(Locale.ROOT))) {
			throw new IllegalStateException(TRUE_AMBIGUITY_MESSAGE);
		}
	}

	private String latestFailedReplayRun(String candidateId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id FROM qw_evaluation_job
				WHERE candidate_id = ? AND job_type = 'SEMANTIC_REPLAY'
				  AND (status = 'FAILED' OR result_json LIKE '%\"allPassed\":false%')
				ORDER BY create_time DESC LIMIT 1
				""", candidateId);
		if (rows.isEmpty()) {
			throw new IllegalStateException("A failed automated Replay Run is required for a manual exception");
		}
		return text(rows.get(0).get("id"));
	}

	private SemanticEvolutionStateMachine stateMachine() {
		return stateMachine;
	}

	private boolean replayed(String candidateId, String eventType, OperatorContext operator, Object request) {
		return auditService.inspect(candidateId, eventType, operator, request).replayed();
	}

	private void appendAudit(String candidateId, String eventType, String fromStatus, String toStatus,
			OperatorContext operator, Map<String, Object> candidate, Long targetVersionId, String patchHash,
			Object request, Object payload) {
		auditService.append(candidateId, eventType, fromStatus, toStatus, operator,
				number(candidate.get("source_version_id")), targetVersionId, patchHash, null, request, payload);
	}

	private OperatorContext withKey(OperatorContext operator, String idempotencyKey) {
		return new OperatorContext(operator.operator(), operator.source(), operator.requestId(),
				idempotencyKey);
	}

	public record ReviewCommand(boolean approved, String reviewedBy, String comment) {
	}

	public record DraftCommand(String versionNumber) {
	}

	public record ReplayCommand(boolean passed, String summary) {
	}

}
