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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.SemanticEvolutionService.DraftCommand;
import cn.lgs.semevosql.evolution.SemanticEvolutionService.ReviewCommand;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Risk-based automation for low-risk semantic evolution only.
 *
 * <p>The automation never mutates published Catalogs directly. It can review and validate a
 * low-risk candidate, start the normal automated Replay, and request publication only after
 * Replay PASS. Metric definitions, relationships, grains, authority policy and genuine
 * ambiguity remain human-governed.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowRiskSemanticEvolutionAutomationListener {

	private static final Set<String> AUTO_TYPES = Set.of("PROJECT_ALIAS_PROPOSAL", "TERM_ALIAS_MISSING",
			"ENUM_MAPPING_MISSING");

	private final JdbcTemplate jdbc;

	private final SemanticEvolutionService evolutionService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCandidate(LowRiskSemanticEvolutionCandidateEvent event) {
		if (event == null || !StringUtils.hasText(event.candidateId())) {
			return;
		}
		Map<String, Object> candidate = candidate(event.candidateId());
		if (!eligibleForRecovery(candidate)) {
			return;
		}
		try {
			advanceToReplay(event.candidateId());
		}
		catch (RuntimeException failure) {
			// Every step is durable and idempotent. A duplicate/replayed event resumes from the persisted status.
			log.warn("Low-risk semantic evolution automation stopped for candidate {} at durable status {}: {}",
					event.candidateId(), text(candidate(event.candidateId()).get("status")), failure.getMessage());
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onReplayPassed(LowRiskSemanticEvolutionReplayPassedEvent event) {
		if (event == null || !StringUtils.hasText(event.candidateId())) {
			return;
		}
		Map<String, Object> candidate = candidate(event.candidateId());
		if (!eligibleForPublication(candidate)) {
			return;
		}
		try {
			evolutionService.markReadyForPublish(event.candidateId(),
					OperatorContext.system("auto-low-risk-publish:" + event.candidateId()));
		}
		catch (RuntimeException failure) {
			// Publication remains replay-gated and recoverable; never bypass the existing release policy.
			log.warn("Low-risk semantic evolution publication stopped for candidate {}: {}", event.candidateId(),
					failure.getMessage());
		}
	}

	private void advanceToReplay(String candidateId) {
		for (int step = 0; step < 4; step++) {
			Map<String, Object> current = candidate(candidateId);
			if (!trustedLowRiskAsset(current)) {
				return;
			}
			switch (text(current.get("status"))) {
				case "CANDIDATE" -> {
					OperatorContext review = OperatorContext.system("auto-low-risk-review:" + candidateId);
					evolutionService.review(candidateId,
							new ReviewCommand(true, review.operator(),
									"Low-risk semantic change passed automatic trust gates"),
							review);
				}
				case "APPROVED" -> evolutionService.createDraft(candidateId, new DraftCommand("automatic-change-set"),
						OperatorContext.system("auto-low-risk-draft:" + candidateId));
				case "PATCH_APPLIED" -> {
					evolutionService.startReplay(candidateId,
							OperatorContext.system("auto-low-risk-replay:" + candidateId));
					return;
				}
				case "REPLAY_RUNNING", "REPLAY_PASSED", "READY_FOR_PUBLISH", "PUBLISHED" -> {
					return;
				}
				default -> {
					return;
				}
			}
		}
	}

	boolean eligibleForAutomation(Map<String, Object> candidate) {
		return candidate != null && "CANDIDATE".equals(text(candidate.get("status"))) && trustedLowRiskAsset(candidate);
	}

	boolean eligibleForRecovery(Map<String, Object> candidate) {
		if (!trustedLowRiskAsset(candidate)) {
			return false;
		}
		return Set.of("CANDIDATE", "APPROVED", "PATCH_APPLIED", "REPLAY_RUNNING", "REPLAY_PASSED",
				"READY_FOR_PUBLISH", "PUBLISHED").contains(text(candidate.get("status")));
	}

	private boolean trustedLowRiskAsset(Map<String, Object> candidate) {
		if (candidate == null || candidate.isEmpty() || !"LOW".equals(text(candidate.get("risk_level")))
				|| !AUTO_TYPES.contains(text(candidate.get("candidate_type")))) {
			return false;
		}
		String classification = text(candidate.get("mapping_classification"));
		if ("TRUE_AMBIGUITY".equals(classification)
				|| !StringUtils.hasText(text(candidate.get("semantic_change_set_id")))) {
			return false;
		}
		if ("PROJECT_ALIAS_PROPOSAL".equals(text(candidate.get("candidate_type")))) {
			return "USER_CONFIRMED".equals(classification);
		}
		return number(candidate.get("confidence")) >= 0.80d
				&& integer(candidate.get("distinct_conversation_count")) >= 2
				&& integer(candidate.get("distinct_root_evidence_count")) >= 2;
	}

	boolean eligibleForPublication(Map<String, Object> candidate) {
		return eligibleLowRiskType(candidate) && "REPLAY_PASSED".equals(text(candidate.get("status")))
				&& !"TRUE_AMBIGUITY".equals(text(candidate.get("mapping_classification")))
				&& StringUtils.hasText(text(candidate.get("semantic_change_set_id")));
	}

	private boolean eligibleLowRiskType(Map<String, Object> candidate) {
		return candidate != null && "LOW".equals(text(candidate.get("risk_level")))
				&& AUTO_TYPES.contains(text(candidate.get("candidate_type")));
	}

	private Map<String, Object> candidate(String candidateId) {
		return jdbc.queryForList("SELECT * FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId)
			.stream()
			.findFirst()
			.orElse(Map.of());
	}

	private static int integer(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static double number(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0d;
	}

	private static String text(Object value) {
		return Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
	}
}
