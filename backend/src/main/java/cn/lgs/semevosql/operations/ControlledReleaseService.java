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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.CanaryRequest;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.ReleaseRequest;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.ShadowResult;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Owns the controlled release / shadow / canary / rollback lifecycle. */
@Component
@RequiredArgsConstructor
class ControlledReleaseService {

	private static final List<Integer> CANARY_STEPS = List.of(1, 5, 20, 50, 100);

	private final JdbcTemplate jdbc;

	private final SemanticCatalogCache catalogCache;

	private final LocalOperatorService authorization;

	@Transactional
	Map<String, Object> create(Long projectId, ReleaseRequest request, OperatorContext operator) {
		authorization.require(operator, "create controlled release");
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO qw_release
				(id, project_id, baseline_version_id, candidate_version_id, release_type, status, traffic_percent,
				 policy_version, sample_count, failure_count, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, 'SHADOW', 0, ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, projectId, request.baselineVersionId(), request.candidateVersionId(), request.releaseType(),
				request.policyVersion());
		catalogCache.warm(projectId, request.candidateVersionId());
		return get(id);
	}

	List<Map<String, Object>> list(Long projectId) {
		return jdbc.queryForList("SELECT * FROM qw_release WHERE project_id = ? ORDER BY create_time DESC", projectId);
	}

	@Transactional
	Map<String, Object> recordShadow(String releaseId, ShadowResult request, OperatorContext operator) {
		authorization.require(operator, "record release shadow result");
		jdbc.update("""
				UPDATE qw_release SET sample_count = sample_count + 1,
				failure_count = failure_count + ?, metrics_json = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?
				""", request.passed() ? 0 : 1, json(request.metrics()), releaseId);
		return get(releaseId);
	}

	Long assignVersion(String releaseId, String requestId) {
		Map<String, Object> release = get(releaseId);
		int traffic = ((Number) release.get("traffic_percent")).intValue();
		int bucket = Math.floorMod(requestId.hashCode(), 100);
		return number(bucket < traffic ? release.get("candidate_version_id") : release.get("baseline_version_id"));
	}

	@Transactional
	Map<String, Object> advanceCanary(String releaseId, CanaryRequest request, OperatorContext operator) {
		authorization.require(operator, "advance release canary");
		Map<String, Object> current = get(releaseId);
		int currentTraffic = ((Number) current.get("traffic_percent")).intValue();
		int next = request.trafficPercent();
		int expectedIndex = currentTraffic == 0 ? 0 : CANARY_STEPS.indexOf(currentTraffic) + 1;
		if (expectedIndex < 0 || expectedIndex >= CANARY_STEPS.size() || CANARY_STEPS.get(expectedIndex) != next) {
			throw new IllegalArgumentException("Canary traffic must progress through 1, 5, 20, 50 and 100");
		}
		if (request.failureRate() > 0.05 || request.p95LatencyMs() > 15000 || request.safetyViolations() > 0) {
			return rollback(releaseId, "automatic threshold rollback", operator);
		}
		jdbc.update("""
				UPDATE qw_release SET status = ?, traffic_percent = ?, metrics_json = ?,
				update_time = CURRENT_TIMESTAMP WHERE id = ?
				""", next == 100 ? "PRODUCTION" : "CANARY", next, json(request), releaseId);
		return get(releaseId);
	}

	@Transactional
	Map<String, Object> rollback(String releaseId, String reason, OperatorContext operator) {
		authorization.require(operator, "rollback controlled release");
		Map<String, Object> release = get(releaseId);
		Long projectId = number(release.get("project_id"));
		Long baseline = number(release.get("baseline_version_id"));
		Long candidate = number(release.get("candidate_version_id"));
		jdbc.update("UPDATE qw_project_version SET status = 'ARCHIVED' WHERE id = ?", candidate);
		jdbc.update("UPDATE qw_project_version SET status = 'PUBLISHED' WHERE id = ?", baseline);
		jdbc.update("UPDATE qw_project SET active_version_id = ?, status = 'READY' WHERE id = ?", baseline, projectId);
		jdbc.update("""
				UPDATE qw_release SET status = 'ROLLED_BACK', rollback_reason = ?, traffic_percent = 0,
				update_time = CURRENT_TIMESTAMP WHERE id = ?
				""", truncate(reason, 1000), releaseId);
		catalogCache.invalidate(candidate);
		catalogCache.warm(projectId, baseline);
		return get(releaseId);
	}

	private Map<String, Object> get(String id) {
		List<Map<String, Object>> values = jdbc.queryForList("SELECT * FROM qw_release WHERE id = ?", id);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Release not found: " + id);
		}
		return values.get(0);
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode release payload", ex);
		}
	}

	private String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}

}
