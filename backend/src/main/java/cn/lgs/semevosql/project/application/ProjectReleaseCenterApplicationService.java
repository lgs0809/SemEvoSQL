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
package cn.lgs.semevosql.project.application;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/**
 * Read-only release center assembled from version, replay, evolution and
 * controlled-release facts.
 */
@Service
@RequiredArgsConstructor
public class ProjectReleaseCenterApplicationService {

	private final SemanticProjectRepository repository;

	private final ProjectScopeService projectScope;

	private final JdbcTemplate jdbc;

	public ReleaseCenterView getReleaseCenter(Long projectId, OperatorContext operator) {
		projectScope.requireProject(projectId, operator);
		SemanticProject project = repository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
		Map<Long, List<VersionActivity>> activities = loadActivities(projectId);
		Map<Long, List<CandidateChange>> changes = loadCandidateChanges(projectId);
		Map<Long, ReplaySummary> replay = loadReplaySummaries(projectId);
		long enabledGoldenCaseCount = enabledGoldenCaseCount(projectId);
		Map<Long, GoldenReplaySummary> goldenReplay = loadGoldenReplaySummaries(projectId, enabledGoldenCaseCount);
		Map<Long, String> governanceDeciders = loadGovernanceDeciders(projectId);

		List<ReleaseVersion> versions = repository.findVersions(projectId)
			.stream()
			.sorted(Comparator.comparing(SemanticProjectVersion::getVersionNo).reversed())
			.map(version -> version(version, project.getActiveVersionId(),
					activities.getOrDefault(version.getId(), List.of()),
					changes.getOrDefault(version.getId(), List.of()), replay.get(version.getId()),
					goldenReplay.getOrDefault(version.getId(), GoldenReplaySummary.empty(enabledGoldenCaseCount)),
					governanceDeciders.get(version.getId())))
			.toList();
		return new ReleaseCenterView(projectId, project.getActiveVersionId(), versions,
				loadControlledReleases(projectId));
	}

	private ReleaseVersion version(SemanticProjectVersion version, Long activeVersionId,
			List<VersionActivity> activities, List<CandidateChange> changes, ReplaySummary replay,
			GoldenReplaySummary goldenReplay, String governanceDecidedBy) {
		VersionActivity published = latest(activities, "PUBLISHED");
		VersionActivity activated = latest(activities, "ACTIVATED");
		return new ReleaseVersion(version.getId(), version.getVersionNumber(), version.getParentVersionId(),
				version.getStatus().name(), version.getCatalogHash(), version.getReleaseReport(),
				version.getPublishedTime(), published == null ? null : published.operatorName(),
				activeVersionId != null && activeVersionId.equals(version.getId()),
				activated == null ? null : activated.createTime(), activated == null ? null : activated.operatorName(),
				governanceDecidedBy, changes, replay == null ? ReplaySummary.empty() : replay, goldenReplay);
	}

	private Map<Long, List<VersionActivity>> loadActivities(Long projectId) {
		Map<Long, List<VersionActivity>> result = new HashMap<>();
		jdbc.query("""
				SELECT project_version_id, activity_type, operator_name, create_time
				FROM qw_project_version_activity
				WHERE project_id = ?
				ORDER BY create_time DESC, id DESC
				""",
				(RowCallbackHandler) rs -> result
					.computeIfAbsent(rs.getLong("project_version_id"), ignored -> new ArrayList<>())
					.add(new VersionActivity(rs.getString("activity_type"), rs.getString("operator_name"),
						timestamp(rs.getTimestamp("create_time")))),
				projectId);
		return result;
	}

	private Map<Long, List<CandidateChange>> loadCandidateChanges(Long projectId) {
		Map<Long, List<CandidateChange>> result = new HashMap<>();
		jdbc.query("""
				SELECT id, target_draft_version_id, patch_json, status
				FROM qw_semantic_evolution_candidate
				WHERE project_id = ? AND target_draft_version_id IS NOT NULL
				ORDER BY create_time ASC
				""", rs -> {
			Long versionId = rs.getLong("target_draft_version_id");
			String candidateId = rs.getString("id");
			String status = rs.getString("status");
			for (CandidateChange change : parseChanges(candidateId, status, rs.getString("patch_json"))) {
				result.computeIfAbsent(versionId, ignored -> new ArrayList<>()).add(change);
			}
		}, projectId);
		return result;
	}

	private List<CandidateChange> parseChanges(String candidateId, String candidateStatus, String patchJson) {
		List<CandidateChange> result = new ArrayList<>();
		try {
			JsonNode operations = JsonUtil.getObjectMapper().readTree(patchJson).path("operations");
			if (!operations.isArray()) {
				return result;
			}
			for (JsonNode operation : operations) {
				String operationName = operation.path("operation").asText("");
				String assetType = operation.path("assetType").asText("");
				String assetKey = operation.path("assetKey").asText("");
				JsonNode values = operation.path("values");
				String businessName = firstText(values, "businessName", "name", "displayName", "metricCode",
						"dimensionCode", "ruleCode", "relationshipCode");
				result.add(new CandidateChange(changeKind(operationName), operationName, assetType, assetKey,
						businessName == null ? assetKey : businessName, candidateId, candidateStatus));
			}
		}
		catch (Exception ignored) {
			result.add(new CandidateChange("MODIFIED", "UNKNOWN", "UNKNOWN", candidateId, candidateId, candidateId,
					candidateStatus));
		}
		return result;
	}

	private Map<Long, ReplaySummary> loadReplaySummaries(Long projectId) {
		Map<Long, long[]> counts = new HashMap<>();
		jdbc.query("""
				SELECT r.target_version_id, r.status, COUNT(*) AS cnt
				FROM qw_semantic_replay_result r
				JOIN qw_semantic_evolution_candidate c ON c.id = r.candidate_id
				WHERE c.project_id = ?
				GROUP BY r.target_version_id, r.status
				""", rs -> {
			long[] value = counts.computeIfAbsent(rs.getLong("target_version_id"), ignored -> new long[4]);
			long count = rs.getLong("cnt");
			value[0] += count;
			switch (rs.getString("status")) {
				case "PASSED" -> value[1] += count;
				case "FAILED" -> value[2] += count;
				default -> value[3] += count;
			}
		}, projectId);
		Map<Long, ReplaySummary> result = new HashMap<>();
		counts.forEach(
				(versionId, value) -> result.put(versionId, new ReplaySummary(value[0], value[1], value[2], value[3])));
		return result;
	}

	private long enabledGoldenCaseCount(Long projectId) {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM qw_golden_case WHERE project_id = ? AND enabled = TRUE",
				Long.class, projectId);
		return count == null ? 0 : count;
	}

	private Map<Long, GoldenReplaySummary> loadGoldenReplaySummaries(Long projectId, long registeredCaseCount) {
		Map<Long, GoldenReplaySummary> result = new HashMap<>();
		jdbc.query("""
				SELECT DISTINCT ON (project_version_id)
				       project_version_id, status, result_json, finished_time, update_time
				FROM qw_evaluation_job
				WHERE project_id = ? AND job_type = 'REPLAY' AND project_version_id IS NOT NULL
				ORDER BY project_version_id, create_time DESC, id DESC
				""", rs -> {
			Long versionId = rs.getLong("project_version_id");
			JsonNode replayResult = null;
			String resultJson = rs.getString("result_json");
			if (resultJson != null && !resultJson.isBlank()) {
				try {
					replayResult = JsonUtil.getObjectMapper().readTree(resultJson);
				}
				catch (Exception ignored) {
					replayResult = null;
				}
			}
			long total = replayResult == null ? 0 : replayResult.path("total").asLong(0);
			long passed = replayResult == null ? 0 : replayResult.path("passed").asLong(0);
			long failed = replayResult == null ? 0 : replayResult.path("failed").asLong(0);
			Boolean safetyPassed = replayResult == null || !replayResult.has("safetyPassed") ? null
					: replayResult.path("safetyPassed").asBoolean();
			Timestamp finished = rs.getTimestamp("finished_time");
			result.put(versionId,
					new GoldenReplaySummary(registeredCaseCount, rs.getString("status"), total, passed, failed,
							safetyPassed, timestamp(finished == null ? rs.getTimestamp("update_time") : finished)));
		}, projectId);
		return result;
	}

	private Map<Long, String> loadGovernanceDeciders(Long projectId) {
		Map<Long, String> result = new HashMap<>();
		jdbc.query("""
				SELECT c.target_draft_version_id, d.decided_by, d.create_time
				FROM qw_release_decision d
				JOIN qw_semantic_evolution_candidate c ON c.id = d.candidate_id
				WHERE c.project_id = ? AND c.target_draft_version_id IS NOT NULL AND d.decision = 'ALLOW'
				ORDER BY d.create_time DESC
				""", (RowCallbackHandler) rs -> result.putIfAbsent(rs.getLong("target_draft_version_id"),
				rs.getString("decided_by")), projectId);
		return result;
	}

	private List<ControlledRelease> loadControlledReleases(Long projectId) {
		return jdbc.query("""
				SELECT id, baseline_version_id, candidate_version_id, release_type, status, traffic_percent,
				       sample_count, failure_count, rollback_reason, create_time, update_time
				FROM qw_release WHERE project_id = ? ORDER BY create_time DESC
				""",
				(rs, rowNum) -> new ControlledRelease(rs.getString("id"), rs.getLong("baseline_version_id"),
						rs.getLong("candidate_version_id"), rs.getString("release_type"), rs.getString("status"),
						rs.getInt("traffic_percent"), rs.getLong("sample_count"), rs.getLong("failure_count"),
						rs.getString("rollback_reason"), timestamp(rs.getTimestamp("create_time")),
						timestamp(rs.getTimestamp("update_time"))),
				projectId);
	}

	private VersionActivity latest(List<VersionActivity> activities, String type) {
		return activities.stream().filter(item -> type.equals(item.activityType())).findFirst().orElse(null);
	}

	private String firstText(JsonNode node, String... fields) {
		for (String field : fields) {
			String value = node.path(field).asText("").trim();
			if (!value.isEmpty()) {
				return value;
			}
		}
		return null;
	}

	private String changeKind(String operation) {
		String normalized = operation.toUpperCase();
		if (normalized.startsWith("ADD")) {
			return "ADDED";
		}
		if (normalized.startsWith("DELETE") || normalized.startsWith("REMOVE")) {
			return "REMOVED";
		}
		return "MODIFIED";
	}

	private LocalDateTime timestamp(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	public record ReleaseCenterView(Long projectId, Long activeVersionId, List<ReleaseVersion> versions,
			List<ControlledRelease> controlledReleases) {
	}

	public record ReleaseVersion(Long id, String versionNumber, Long parentVersionId, String status, String catalogHash,
			String structuredReleaseReport, LocalDateTime publishedTime, String publishedBy, boolean active,
			LocalDateTime activatedTime, String activatedBy, String governanceDecidedBy, List<CandidateChange> changes,
			ReplaySummary replay, GoldenReplaySummary goldenReplay) {
	}

	public record CandidateChange(String kind, String operation, String assetType, String assetKey, String businessName,
			String candidateId, String candidateStatus) {
	}

	public record ReplaySummary(long total, long passed, long failed, long needsAttention) {
		static ReplaySummary empty() {
			return new ReplaySummary(0, 0, 0, 0);
		}
	}

	public record GoldenReplaySummary(long registeredCaseCount, String latestJobStatus, long total, long passed,
			long failed, Boolean safetyPassed, LocalDateTime observedAt) {
		static GoldenReplaySummary empty(long registeredCaseCount) {
			return new GoldenReplaySummary(registeredCaseCount, null, 0, 0, 0, null, null);
		}
	}

	public record ControlledRelease(String id, long baselineVersionId, long candidateVersionId, String releaseType,
			String status, int trafficPercent, long sampleCount, long failureCount, String rollbackReason,
			LocalDateTime createTime, LocalDateTime updateTime) {
	}

	private record VersionActivity(String activityType, String operatorName, LocalDateTime createTime) {
	}

}
