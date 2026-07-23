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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Single authority for Semantic Evolution Candidate lifecycle transitions and CAS
 * revisions.
 */
@Service
public class SemanticEvolutionStateMachine {

	private static final Map<CandidateStatus, Set<CandidateStatus>> ALLOWED = allowedTransitions();

	private final JdbcTemplate jdbc;

	public SemanticEvolutionStateMachine(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public CandidateState transition(String candidateId, Set<CandidateStatus> expectedStatuses,
			CandidateStatus targetStatus, Mutation mutation) {
		CandidateState current = state(candidateId);
		if (current.status() == targetStatus) {
			return current;
		}
		if (!expectedStatuses.contains(current.status())) {
			throw new IllegalStateException(
					"Candidate status mismatch: expected=" + expectedStatuses + ", actual=" + current.status());
		}
		return transition(candidateId, current.status(), current.revision(), targetStatus,
				mutation == null ? Mutation.none() : mutation);
	}

	@Transactional
	public CandidateState transition(String candidateId, CandidateStatus expectedStatus, long expectedRevision,
			CandidateStatus targetStatus, Mutation mutation) {
		if (!ALLOWED.getOrDefault(expectedStatus, Set.of()).contains(targetStatus)) {
			throw new IllegalStateException(
					"Illegal Semantic Evolution transition " + expectedStatus + " -> " + targetStatus);
		}
		Mutation effective = mutation == null ? Mutation.none() : mutation;
		List<String> assignments = new ArrayList<>();
		List<Object> arguments = new ArrayList<>();
		assignments.add("status = ?");
		arguments.add(targetStatus.name());
		assignments.add("revision = revision + 1");
		assignments.add("update_time = CURRENT_TIMESTAMP");
		append(assignments, arguments, "reviewed_by", effective.reviewedBy());
		append(assignments, arguments, "review_comment", effective.reviewComment());
		append(assignments, arguments, "target_draft_version_id", effective.targetDraftVersionId());
		append(assignments, arguments, "patch_hash", effective.patchHash());
		append(assignments, arguments, "replay_summary_json", effective.replaySummaryJson());
		append(assignments, arguments, "rebind_status", effective.rebindStatus());
		append(assignments, arguments, "rebind_error", effective.rebindError());
		if (effective.reviewedNow()) {
			assignments.add("reviewed_time = CURRENT_TIMESTAMP");
		}
		if (effective.appliedNow()) {
			assignments.add("applied_time = CURRENT_TIMESTAMP");
		}
		if (effective.clearRebindError()) {
			assignments.add("rebind_error = NULL");
		}
		arguments.add(candidateId);
		arguments.add(expectedStatus.name());
		arguments.add(expectedRevision);
		int changed = jdbc.update("UPDATE qw_semantic_evolution_candidate SET " + String.join(", ", assignments)
				+ " WHERE id = ? AND status = ? AND revision = ?", arguments.toArray());
		if (changed != 1) {
			CandidateState actual = state(candidateId);
			if (actual.status() == targetStatus) {
				return actual;
			}
			throw new IllegalStateException("Candidate CAS transition failed: expected=" + expectedStatus + "@"
					+ expectedRevision + ", actual=" + actual.status() + "@" + actual.revision());
		}
		return state(candidateId);
	}

	public CandidateState state(String candidateId) {
		if (!StringUtils.hasText(candidateId)) {
			throw new IllegalArgumentException("candidateId is required");
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, status, revision, target_draft_version_id, patch_hash, replay_summary_json,
				       rebind_status, rebind_error
				FROM qw_semantic_evolution_candidate WHERE id = ?
				""", candidateId);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Semantic Evolution Candidate was not found: " + candidateId);
		}
		Map<String, Object> row = rows.get(0);
		return new CandidateState(Objects.toString(row.get("id")),
				CandidateStatus.valueOf(Objects.toString(row.get("status"))), number(row.get("revision")),
				nullableNumber(row.get("target_draft_version_id")), Objects.toString(row.get("patch_hash"), null),
				Objects.toString(row.get("replay_summary_json"), null),
				Objects.toString(row.get("rebind_status"), null), Objects.toString(row.get("rebind_error"), null));
	}

	public boolean canTransition(CandidateStatus from, CandidateStatus to) {
		return ALLOWED.getOrDefault(from, Set.of()).contains(to);
	}

	private void append(List<String> assignments, List<Object> arguments, String column, Object value) {
		if (value != null) {
			assignments.add(column + " = ?");
			arguments.add(value);
		}
	}

	private static Map<CandidateStatus, Set<CandidateStatus>> allowedTransitions() {
		Map<CandidateStatus, Set<CandidateStatus>> value = new EnumMap<>(CandidateStatus.class);
		allow(value, CandidateStatus.CANDIDATE, CandidateStatus.APPROVED, CandidateStatus.REJECTED,
				CandidateStatus.STALE);
		allow(value, CandidateStatus.APPROVED, CandidateStatus.DRAFT_CREATED, CandidateStatus.PATCH_APPLIED,
				CandidateStatus.STALE);
		allow(value, CandidateStatus.DRAFT_CREATED, CandidateStatus.PATCH_APPLIED, CandidateStatus.STALE);
		allow(value, CandidateStatus.PATCH_APPLIED, CandidateStatus.REPLAY_RUNNING, CandidateStatus.STALE);
		allow(value, CandidateStatus.REPLAY_RUNNING, CandidateStatus.REPLAY_PASSED, CandidateStatus.REPLAY_FAILED,
				CandidateStatus.PATCH_APPLIED, CandidateStatus.STALE);
		allow(value, CandidateStatus.REPLAY_FAILED, CandidateStatus.REPLAY_RUNNING, CandidateStatus.READY_FOR_PUBLISH,
				CandidateStatus.STALE);
		allow(value, CandidateStatus.REPLAY_PASSED, CandidateStatus.READY_FOR_PUBLISH, CandidateStatus.STALE);
		allow(value, CandidateStatus.READY_FOR_PUBLISH, CandidateStatus.PUBLISHED, CandidateStatus.STALE);
		return Map.copyOf(value);
	}

	private static void allow(Map<CandidateStatus, Set<CandidateStatus>> target, CandidateStatus source,
			CandidateStatus... destinations) {
		target.put(source, EnumSet.copyOf(List.of(destinations)));
	}

	private long number(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private Long nullableNumber(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	public enum CandidateStatus {

		CANDIDATE, APPROVED, REJECTED, DRAFT_CREATED, PATCH_APPLIED, REPLAY_RUNNING, REPLAY_PASSED, REPLAY_FAILED,
		READY_FOR_PUBLISH, PUBLISHED, STALE

	}

	public record CandidateState(String candidateId, CandidateStatus status, long revision, Long targetDraftVersionId,
			String patchHash, String replaySummaryJson, String rebindStatus, String rebindError) {
	}

	public record Mutation(String reviewedBy, String reviewComment, Long targetDraftVersionId, String patchHash,
			String replaySummaryJson, String rebindStatus, String rebindError, boolean reviewedNow, boolean appliedNow,
			boolean clearRebindError) {

		public static Mutation none() {
			return new Mutation(null, null, null, null, null, null, null, false, false, false);
		}

		public static Mutation review(String reviewedBy, String reviewComment) {
			return new Mutation(reviewedBy, reviewComment, null, null, null, null, null, true, false, false);
		}

		public static Mutation draft(Long targetDraftVersionId) {
			return new Mutation(null, null, targetDraftVersionId, null, null, null, null, false, false, false);
		}

		public static Mutation patchApplied(String patchHash) {
			return new Mutation(null, null, null, patchHash, null, null, null, false, true, false);
		}

		public static Mutation replayCompleted(String replaySummaryJson) {
			return new Mutation(null, null, null, null, replaySummaryJson, null, null, false, false, false);
		}

		public static Mutation ready(String reviewedBy) {
			return new Mutation(reviewedBy, null, null, null, null, null, null, true, false, false);
		}

		public static Mutation published() {
			return new Mutation(null, null, null, null, null, "RUNNING", null, false, false, true);
		}

		public static Mutation stale(String reason) {
			return new Mutation(null, reason, null, null, null, null, null, false, false, false);
		}
	}

}
