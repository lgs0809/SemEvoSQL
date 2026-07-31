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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.evolution.LowRiskSemanticEvolutionCandidateEvent;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.evolution.application.LegacyEvolutionChangeSetBridge;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a governed Semantic Evolution proposal for a project-wide language alias. */
@Service
public class ProjectSemanticAliasProposalService {

	private final JdbcTemplate jdbc;

	private final SemanticProjectRepository projectRepository;

	private final SemanticBindingTargetValidator targetValidator;

	private final LegacyEvolutionChangeSetBridge changeSetBridge;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	private ApplicationEventPublisher eventPublisher;

	@Autowired
	public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	public ProjectSemanticAliasProposalService(JdbcTemplate jdbc, SemanticProjectRepository projectRepository,
			SemanticBindingTargetValidator targetValidator, LegacyEvolutionChangeSetBridge changeSetBridge) {
		this.jdbc = jdbc;
		this.projectRepository = projectRepository;
		this.targetValidator = targetValidator;
		this.changeSetBridge = changeSetBridge;
	}

	@Transactional
	public ProposalResult propose(Long projectId, Long sourceVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel, String principal, String evidenceSource) {
		var sourceVersion = projectRepository.findVersion(sourceVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + sourceVersionId));
		if (!projectId.equals(sourceVersion.getProjectId())
				|| sourceVersion.getStatus() != ProjectVersionStatus.PUBLISHED) {
			throw new IllegalStateException("Project alias proposals must be pinned to a PUBLISHED source version");
		}
		String catalogHash = sourceVersion.getCatalogHash();
		if (catalogHash == null || catalogHash.isBlank()) {
			throw new IllegalStateException("Published source version has no catalog hash");
		}
		targetValidator.requireAsset(projectId, sourceVersionId, assetType, assetKey);
		String phrase = required(rawPhrase, "rawPhrase");
		String normalizedPhrase = ProjectSemanticAliasService.normalizePhrase(phrase);
		String label = required(businessLabel, "businessLabel");
		String targetAssetType = required(assetType, "assetType");
		String targetAssetKey = required(assetKey, "assetKey");
		Map<String, Object> evidence = Map.of("source",
				evidenceSource == null ? "PROJECT_ALIAS_REQUEST" : evidenceSource, "principal",
				principal == null ? "" : principal, "phrase", phrase, "targetAssetType", targetAssetType,
				"targetAssetKey", targetAssetKey, "businessLabel", label);
		PendingProposal existing = findPending(sourceVersionId, catalogHash, normalizedPhrase);
		if (existing != null) {
			requireCompatible(existing, targetAssetType, targetAssetKey);
			var bridged = changeSetBridge.linkCandidate(projectId, sourceVersionId, existing.id(),
					"PROJECT_ALIAS_PROPOSAL", "PROJECT_ALIAS", normalizedPhrase, "LOW", existing.patchJson(), evidence,
					null, principal);
			publishLowRiskCandidate(existing.id());
			return new ProposalResult(existing.id(), bridged.semanticChangeSetId(), sourceVersionId, normalizedPhrase,
					"CANDIDATE");
		}
		Operation operation = new Operation(
				OperationType.ADD_PROJECT_ALIAS, "PROJECT_ALIAS", normalizedPhrase, null, Map.of("phrase", phrase,
						"targetAssetType", targetAssetType, "targetAssetKey", targetAssetKey, "businessLabel", label),
				List.of());
		SemanticPatch patch = new SemanticPatch(1, sourceVersionId, catalogHash, List.of(operation));
		String candidateId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO qw_semantic_evolution_candidate
				(id, project_id, source_version_id, source_catalog_hash, candidate_type, asset_type, asset_key,
				 status, confidence, risk_level, patch_json, evidence_summary, mapping_classification,
				 distinct_conversation_count, distinct_user_count, distinct_root_evidence_count,
				 distinct_time_window_count, create_time, update_time)
				VALUES (?, ?, ?, ?, 'PROJECT_ALIAS_PROPOSAL', 'PROJECT_ALIAS', ?, 'CANDIDATE', 1.0, 'LOW',
				        CAST(? AS JSONB), ?, 'USER_CONFIRMED', 1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (source_version_id, source_catalog_hash, candidate_type, asset_type, asset_key, status)
				DO NOTHING
				""", candidateId, projectId, sourceVersionId, catalogHash, normalizedPhrase, json(patch),
				json(evidence));
		PendingProposal persisted = findPending(sourceVersionId, catalogHash, normalizedPhrase);
		if (persisted == null) {
			throw new IllegalStateException("Project alias proposal was not persisted");
		}
		requireCompatible(persisted, targetAssetType, targetAssetKey);
		var bridged = changeSetBridge.linkCandidate(projectId, sourceVersionId, persisted.id(),
				"PROJECT_ALIAS_PROPOSAL", "PROJECT_ALIAS", normalizedPhrase, "LOW", json(patch), evidence, null,
				principal);
		publishLowRiskCandidate(persisted.id());
		return new ProposalResult(persisted.id(), bridged.semanticChangeSetId(), sourceVersionId, normalizedPhrase,
				"CANDIDATE");
	}

	private void publishLowRiskCandidate(String candidateId) {
		if (eventPublisher != null && candidateId != null && !candidateId.isBlank()) {
			eventPublisher.publishEvent(new LowRiskSemanticEvolutionCandidateEvent(candidateId));
		}
	}

	private PendingProposal findPending(Long sourceVersionId, String catalogHash, String normalizedPhrase) {
		return jdbc
			.query("""
					SELECT id, patch_json::text AS patch_json,
					       patch_json #>> '{operations,0,values,targetAssetType}' AS target_asset_type,
					       patch_json #>> '{operations,0,values,targetAssetKey}' AS target_asset_key
					FROM qw_semantic_evolution_candidate
					WHERE source_version_id = ? AND source_catalog_hash = ? AND candidate_type = 'PROJECT_ALIAS_PROPOSAL'
					  AND asset_type = 'PROJECT_ALIAS' AND asset_key = ? AND status = 'CANDIDATE'
					""",
					(rs, rowNum) -> new PendingProposal(rs.getString("id"), rs.getString("patch_json"),
							rs.getString("target_asset_type"), rs.getString("target_asset_key")),
					sourceVersionId, catalogHash, normalizedPhrase)
			.stream()
			.findFirst()
			.orElse(null);
	}

	private void requireCompatible(PendingProposal existing, String targetAssetType, String targetAssetKey) {
		if (!targetAssetType.equals(existing.targetAssetType()) || !targetAssetKey.equals(existing.targetAssetKey())) {
			throw new IllegalStateException(
					"A pending project alias proposal already exists for this phrase with a different semantic target");
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to serialize project alias proposal", ex);
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private record PendingProposal(String id, String patchJson, String targetAssetType, String targetAssetKey) {
	}

	public record ProposalResult(String candidateId, String semanticChangeSetId, Long sourceVersionId,
			String normalizedPhrase, String status) {
	}

}
