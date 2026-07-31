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
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Immutable manual evidence and explicit release decisions layered over automated Replay
 * facts.
 */
@Service
public class ManualReplayAttestationService {

	private final JdbcTemplate jdbc;

	private final SemanticEvolutionAuditService auditService;

	private final LocalOperatorService authorization;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	public ManualReplayAttestationService(JdbcTemplate jdbc, SemanticEvolutionAuditService auditService,
			LocalOperatorService authorization) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.authorization = authorization;
	}

	@Transactional
	public AttestationResult attest(String candidateId, AttestationCommand command, OperatorContext operator) {
		if (!StringUtils.hasText(candidateId) || command == null || operator == null) {
			throw new IllegalArgumentException("candidateId, command and operator are required");
		}
		authorization.require(operator, "create manual Replay attestation");
		Map<String, Object> candidate = one("""
				SELECT * FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE
				""", candidateId);
		String candidateStatus = text(candidate.get("status"));
		if (!List.of("REPLAY_FAILED", "REPLAY_PASSED").contains(candidateStatus)) {
			throw new IllegalStateException("Manual attestation requires a completed automated Replay");
		}
		String automatedResult = "REPLAY_PASSED".equals(candidateStatus) ? "PASSED" : "FAILED";
		String attestationType = required(command.attestationType(), "attestationType").toUpperCase(Locale.ROOT);
		String decision = required(command.decision(), "decision").toUpperCase(Locale.ROOT);
		String reason = required(command.reason(), "reason");
		validateCommand(candidateId, automatedResult, attestationType, decision, command);
		String requestHash = canonicalJson.hash(commandPayload(command));
		List<Map<String, Object>> existing = jdbc.queryForList("""
				SELECT * FROM qw_manual_attestation WHERE candidate_id = ? AND idempotency_key = ?
				""", candidateId, operator.idempotencyKey());
		if (!existing.isEmpty()) {
			Map<String, Object> prior = existing.get(0);
			if (!Objects.equals(requestHash, text(prior.get("request_hash")))) {
				throw new IllegalArgumentException("Idempotency-Key is already bound to another attestation request");
			}
			return result(prior, latestDecision(candidateId).orElseThrow());
		}
		String attestationId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO qw_manual_attestation
				(id, candidate_id, attestation_type, decision, actor, actor_source, reason,
				 risk_acceptance, related_replay_run_id, alternative_proof_json, idempotency_key,
				 request_hash, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", attestationId, candidateId, attestationType, decision, operator.operator(), operator.source(),
				reason, nullIfBlank(command.riskAcceptance()), nullIfBlank(command.relatedReplayRunId()),
				json(command.alternativeProof()), operator.idempotencyKey(), requestHash);
		ReleasePolicyDecision release = releaseDecision(automatedResult, decision, command);
		String releaseDecisionId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO qw_release_decision
				(id, candidate_id, automated_result, manual_attestation_id, decision, policy_code,
				 reason, decided_by, related_replay_run_id, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", releaseDecisionId, candidateId, automatedResult, attestationId, release.decision(),
				release.policyCode(), release.reason(), operator.operator(), nullIfBlank(command.relatedReplayRunId()));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("attestationId", attestationId);
		payload.put("attestationType", attestationType);
		payload.put("decision", decision);
		payload.put("automatedResult", automatedResult);
		payload.put("releaseDecision", release.decision());
		payload.put("policyCode", release.policyCode());
		payload.put("riskAcceptance", command.riskAcceptance());
		payload.put("relatedReplayRunId", command.relatedReplayRunId());
		auditService.append(candidateId, "MANUAL_ATTESTATION_RECORDED", candidateStatus, candidateStatus, operator,
				number(candidate.get("source_version_id")), number(candidate.get("target_draft_version_id")),
				text(candidate.get("patch_hash")), command.relatedReplayRunId(), command, payload);
		return result(one("SELECT * FROM qw_manual_attestation WHERE id = ?", attestationId),
				one("SELECT * FROM qw_release_decision WHERE id = ?", releaseDecisionId));
	}

	public List<Map<String, Object>> attestations(String candidateId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_manual_attestation WHERE candidate_id = ? ORDER BY create_time, id
				""", candidateId);
	}

	public List<Map<String, Object>> releaseDecisions(String candidateId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_release_decision WHERE candidate_id = ? ORDER BY create_time, id
				""", candidateId);
	}

	public boolean latestAllowsRelease(String candidateId) {
		return latestDecision(candidateId).map(row -> "ALLOW".equals(text(row.get("decision")))).orElse(false);
	}

	public Optional<Map<String, Object>> latestDecision(String candidateId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT * FROM qw_release_decision WHERE candidate_id = ? ORDER BY create_time DESC, id DESC LIMIT 1
				""", candidateId);
		return rows.stream().findFirst();
	}

	private void validateCommand(String candidateId, String automatedResult, String type, String decision,
			AttestationCommand command) {
		if (!List.of("EXCEPTION", "ALTERNATIVE_PROOF", "REJECTION").contains(type)) {
			throw new IllegalArgumentException("Unsupported attestationType " + type);
		}
		if (!List.of("APPROVED_WITH_EXCEPTION", "APPROVED_AS_ALTERNATIVE_PROOF", "REJECTED").contains(decision)) {
			throw new IllegalArgumentException("Unsupported attestation decision " + decision);
		}
		if (!"FAILED".equals(automatedResult) && !"REJECTED".equals(decision)) {
			throw new IllegalStateException("A passing automated Replay does not require a manual release exception");
		}
		if ("APPROVED_WITH_EXCEPTION".equals(decision)) {
			required(command.riskAcceptance(), "riskAcceptance");
			requireRelatedFailedReplay(candidateId, command.relatedReplayRunId());
		}
		if ("APPROVED_AS_ALTERNATIVE_PROOF".equals(decision)) {
			if (command.alternativeProof() == null || command.alternativeProof().isEmpty()) {
				throw new IllegalArgumentException("alternativeProof is required");
			}
			requireRelatedFailedReplay(candidateId, command.relatedReplayRunId());
		}
	}

	private void requireRelatedFailedReplay(String candidateId, String replayRunId) {
		String requiredReplayRunId = required(replayRunId, "relatedReplayRunId");
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT * FROM qw_evaluation_job
				WHERE id = ? AND candidate_id = ? AND job_type = 'SEMANTIC_REPLAY'
				  AND status IN ('SUCCEEDED','FAILED')
				""", requiredReplayRunId, candidateId);
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("relatedReplayRunId is not a completed Replay for this candidate");
		}
		Map<String, Object> replay = rows.get(0);
		String result = text(replay.get("result_json"));
		String error = text(replay.get("error_message"));
		if (!"FAILED".equals(text(replay.get("status"))) && !replayResultFailed(result)
				&& !StringUtils.hasText(error)) {
			throw new IllegalArgumentException("relatedReplayRunId does not represent an automated failure");
		}
	}

	private ReleasePolicyDecision releaseDecision(String automatedResult, String decision, AttestationCommand command) {
		if ("PASSED".equals(automatedResult)) {
			return new ReleasePolicyDecision("ALLOW", "AUTOMATED_REPLAY_PASSED", "Automated Replay passed");
		}
		if ("APPROVED_WITH_EXCEPTION".equals(decision)) {
			return new ReleasePolicyDecision("ALLOW", "MANUAL_RISK_EXCEPTION",
					"Automated Replay failed; explicit risk exception accepted: " + command.reason());
		}
		if ("APPROVED_AS_ALTERNATIVE_PROOF".equals(decision)) {
			return new ReleasePolicyDecision("ALLOW", "ALTERNATIVE_PROOF_ACCEPTED",
					"Automated Replay failed; substitute evidence accepted: " + command.reason());
		}
		return new ReleasePolicyDecision("DENY", "MANUAL_REJECTION", command.reason());
	}

	private AttestationResult result(Map<String, Object> attestation, Map<String, Object> releaseDecision) {
		return new AttestationResult(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attestation)),
				java.util.Collections.unmodifiableMap(new LinkedHashMap<>(releaseDecision)));
	}

	private Map<String, Object> commandPayload(AttestationCommand command) {
		Map<String, Object> value = new TreeMap<>();
		value.put("alternativeProof", command.alternativeProof() == null ? Map.of() : command.alternativeProof());
		value.put("attestationType", command.attestationType());
		value.put("decision", command.decision());
		value.put("reason", command.reason());
		value.put("relatedReplayRunId", command.relatedReplayRunId());
		value.put("riskAcceptance", command.riskAcceptance());
		return value;
	}

	private boolean replayResultFailed(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		try {
			return JsonUtil.getObjectMapper().readTree(value).path("allPassed").isBoolean()
					&& !JsonUtil.getObjectMapper().readTree(value).path("allPassed").asBoolean();
		}
		catch (Exception ex) {
			return false;
		}
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode manual attestation", ex);
		}
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Semantic candidate or governance fact was not found");
		}
		return rows.get(0);
	}

	private String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private String nullIfBlank(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	public record AttestationCommand(String attestationType, String decision, String reason, String riskAcceptance,
			String relatedReplayRunId, Map<String, Object> alternativeProof) {
	}

	public record AttestationResult(Map<String, Object> manualAttestation, Map<String, Object> releaseDecision) {
	}

	private record ReleasePolicyDecision(String decision, String policyCode, String reason) {
	}

}
