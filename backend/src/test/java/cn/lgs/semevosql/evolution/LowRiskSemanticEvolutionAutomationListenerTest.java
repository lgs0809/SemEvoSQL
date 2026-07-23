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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LowRiskSemanticEvolutionAutomationListenerTest {

	private final LowRiskSemanticEvolutionAutomationListener listener =
			new LowRiskSemanticEvolutionAutomationListener(null, null);

	@Test
	void explicitProjectAliasCanEnterAutomatedReplayFlow() {
		Map<String, Object> candidate = candidate("PROJECT_ALIAS_PROPOSAL", "LOW", "USER_CONFIRMED", 1.0, 1, 1);

		assertThat(listener.eligibleForAutomation(candidate)).isTrue();
	}

	@Test
	void repeatedStableAliasNeedsIndependentEvidence() {
		Map<String, Object> eligible = candidate("TERM_ALIAS_MISSING", "LOW", "STABLE_DOMINANT", 0.91, 3, 3);
		Map<String, Object> singleConversation = candidate("TERM_ALIAS_MISSING", "LOW", "STABLE_DOMINANT", 0.91, 1, 3);

		assertThat(listener.eligibleForAutomation(eligible)).isTrue();
		assertThat(listener.eligibleForAutomation(singleConversation)).isFalse();
	}

	@Test
	void ambiguityAndHighRiskAssetsNeverAutoAdvance() {
		assertThat(listener.eligibleForAutomation(candidate("ENUM_MAPPING_MISSING", "LOW", "TRUE_AMBIGUITY", 0.99, 4, 4)))
			.isFalse();
		assertThat(listener.eligibleForAutomation(candidate("METRIC_FORMULA_INCORRECT", "HIGH", "", 0.99, 4, 4)))
			.isFalse();
		assertThat(listener.eligibleForAutomation(candidate("PLANNING_POLICY_GAP", "HIGH", "", 0.99, 4, 4)))
			.isFalse();
	}

	@Test
	void duplicateEventsCanResumeFromDurableIntermediateStates() {
		for (String status : java.util.List.of("CANDIDATE", "APPROVED", "PATCH_APPLIED", "REPLAY_RUNNING",
				"REPLAY_PASSED", "READY_FOR_PUBLISH", "PUBLISHED")) {
			Map<String, Object> resumable = candidate("TERM_ALIAS_MISSING", "LOW", "STABLE_DOMINANT", 0.91, 3, 3);
			resumable.put("status", status);
			assertThat(listener.eligibleForRecovery(resumable)).as(status).isTrue();
		}
		Map<String, Object> failed = candidate("TERM_ALIAS_MISSING", "LOW", "STABLE_DOMINANT", 0.91, 3, 3);
		failed.put("status", "REPLAY_FAILED");
		assertThat(listener.eligibleForRecovery(failed)).isFalse();
	}

	@Test
	void candidateEventAdvancesEachDurableStepExactlyOnce() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticEvolutionService service = mock(SemanticEvolutionService.class);
		LowRiskSemanticEvolutionAutomationListener durableListener =
				new LowRiskSemanticEvolutionAutomationListener(jdbc, service);
		stubCandidateStates(jdbc, "CANDIDATE", "CANDIDATE", "APPROVED", "PATCH_APPLIED");

		durableListener.onCandidate(new LowRiskSemanticEvolutionCandidateEvent("candidate-1"));

		verify(service).review(eq("candidate-1"), any(SemanticEvolutionService.ReviewCommand.class),
				any(cn.lgs.semevosql.common.OperatorContext.class));
		verify(service).createDraft(eq("candidate-1"), any(SemanticEvolutionService.DraftCommand.class),
				any(cn.lgs.semevosql.common.OperatorContext.class));
		verify(service).startReplay(eq("candidate-1"), any(cn.lgs.semevosql.common.OperatorContext.class));
	}

	@Test
	void replayedCandidateEventResumesAfterCrashWithoutRepeatingReview() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticEvolutionService service = mock(SemanticEvolutionService.class);
		LowRiskSemanticEvolutionAutomationListener durableListener =
				new LowRiskSemanticEvolutionAutomationListener(jdbc, service);
		stubCandidateStates(jdbc, "APPROVED", "APPROVED", "PATCH_APPLIED");

		durableListener.onCandidate(new LowRiskSemanticEvolutionCandidateEvent("candidate-1"));

		verify(service, never()).review(anyString(), any(SemanticEvolutionService.ReviewCommand.class),
				any(cn.lgs.semevosql.common.OperatorContext.class));
		verify(service).createDraft(eq("candidate-1"), any(SemanticEvolutionService.DraftCommand.class),
				any(cn.lgs.semevosql.common.OperatorContext.class));
		verify(service).startReplay(eq("candidate-1"), any(cn.lgs.semevosql.common.OperatorContext.class));
	}

	@Test
	void duplicateEventDuringReplayIsAnIdempotentNoOp() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticEvolutionService service = mock(SemanticEvolutionService.class);
		LowRiskSemanticEvolutionAutomationListener durableListener =
				new LowRiskSemanticEvolutionAutomationListener(jdbc, service);
		stubCandidateStates(jdbc, "REPLAY_RUNNING", "REPLAY_RUNNING");

		durableListener.onCandidate(new LowRiskSemanticEvolutionCandidateEvent("candidate-1"));

		verifyNoInteractions(service);
	}

	@Test
	void duplicateReplayPassedPublicationEventIsIdempotentAfterPublication() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticEvolutionService service = mock(SemanticEvolutionService.class);
		LowRiskSemanticEvolutionAutomationListener durableListener =
				new LowRiskSemanticEvolutionAutomationListener(jdbc, service);
		stubCandidateStates(jdbc, "PUBLISHED");

		durableListener.onReplayPassed(new LowRiskSemanticEvolutionReplayPassedEvent("candidate-1"));

		verifyNoInteractions(service);
	}

	@Test
	void replayPassedEventRequestsPublicationThroughTheNormalReleaseGate() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticEvolutionService service = mock(SemanticEvolutionService.class);
		LowRiskSemanticEvolutionAutomationListener durableListener =
				new LowRiskSemanticEvolutionAutomationListener(jdbc, service);
		stubCandidateStates(jdbc, "REPLAY_PASSED");

		durableListener.onReplayPassed(new LowRiskSemanticEvolutionReplayPassedEvent("candidate-1"));

		verify(service).markReadyForPublish(eq("candidate-1"), any(cn.lgs.semevosql.common.OperatorContext.class));
	}

	@Test
	void onlyReplayPassedLowRiskCandidateCanRequestPublication() {
		Map<String, Object> passed = candidate("PROJECT_ALIAS_PROPOSAL", "LOW", "USER_CONFIRMED", 1.0, 1, 1);
		passed.put("status", "REPLAY_PASSED");
		Map<String, Object> failed = new LinkedHashMap<>(passed);
		failed.put("status", "REPLAY_FAILED");

		assertThat(listener.eligibleForPublication(passed)).isTrue();
		assertThat(listener.eligibleForPublication(failed)).isFalse();
	}

	private Map<String, Object> candidate(String type, String risk, String classification, double confidence,
			int conversations, int roots) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", "CANDIDATE");
		value.put("risk_level", risk);
		value.put("candidate_type", type);
		value.put("mapping_classification", classification);
		value.put("confidence", confidence);
		value.put("distinct_conversation_count", conversations);
		value.put("distinct_root_evidence_count", roots);
		value.put("semantic_change_set_id", "change-set-1");
		return value;
	}

	private void stubCandidateStates(JdbcTemplate jdbc, String... statuses) {
		AtomicInteger index = new AtomicInteger();
		List<String> values = List.of(statuses);
		when(jdbc.queryForList(anyString(), eq("candidate-1"))).thenAnswer(invocation -> {
			String status = values.get(Math.min(index.getAndIncrement(), values.size() - 1));
			Map<String, Object> state = candidate("TERM_ALIAS_MISSING", "LOW", "STABLE_DOMINANT", 0.91, 3, 3);
			state.put("status", status);
			return List.of(state);
		});
	}
}
