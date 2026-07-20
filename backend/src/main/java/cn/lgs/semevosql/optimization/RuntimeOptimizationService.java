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
package cn.lgs.semevosql.optimization;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Shadow-first runtime optimization governance. Enabled plans are only starting hints:
 * callers must still perform applicability, SQL guard, review and execution checks on
 * every run.
 */
@Service
@RequiredArgsConstructor
public class RuntimeOptimizationService {

	private static final long MINIMUM_SHADOW_SAMPLES = 5;

	private static final double MINIMUM_COST_REDUCTION = 0.30;

	private final JdbcTemplate jdbc;

	private final LocalOperatorService authorization;

	public List<Map<String, Object>> list(Long projectId, String status, int limit) {
		if (StringUtils.hasText(status)) {
			return jdbc.queryForList("""
					SELECT * FROM qw_runtime_optimization_candidate
					WHERE project_id = ? AND status = ? ORDER BY confidence DESC, create_time DESC LIMIT ?
					""", projectId, status.toUpperCase(Locale.ROOT), bounded(limit));
		}
		return jdbc.queryForList("""
				SELECT * FROM qw_runtime_optimization_candidate
				WHERE project_id = ? ORDER BY create_time DESC LIMIT ?
				""", projectId, bounded(limit));
	}

	public Map<String, Object> get(String candidateId) {
		Map<String, Object> result = new LinkedHashMap<>(candidate(candidateId));
		result.put("preferredPlans",
				jdbc.queryForList(
						"SELECT * FROM qw_preferred_execution_plan WHERE candidate_id = ? ORDER BY create_time DESC",
						candidateId));
		result.put("thresholds", Map.of("minimumShadowSamples", MINIMUM_SHADOW_SAMPLES, "minimumCostReduction",
				MINIMUM_COST_REDUCTION, "qualityRegressionAllowed", false, "safetyRegressionAllowed", false));
		return result;
	}

	@Transactional
	public Map<String, Object> recordShadow(String candidateId, ShadowCommand command) {
		Map<String, Object> current = lockCandidate(candidateId);
		String currentStatus = text(current.get("status"));
		if (!List.of("CANDIDATE", "SHADOW").contains(currentStatus)) {
			throw new IllegalStateException("Shadow metrics can only be recorded for CANDIDATE or SHADOW records");
		}
		Map<String, Object> metrics = command.metrics() == null ? Map.of() : Map.copyOf(command.metrics());
		if (metric(metrics, "sampleCount", "sample_count") < 0) {
			throw new IllegalArgumentException("sampleCount must be non-negative");
		}
		jdbc.update("""
				UPDATE qw_runtime_optimization_candidate SET status = 'SHADOW', shadow_metrics_json = ?,
				 update_time = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('CANDIDATE','SHADOW')
				""", json(metrics), candidateId);
		return evaluation(candidateId);
	}

	public Map<String, Object> evaluation(String candidateId) {
		Map<String, Object> current = candidate(candidateId);
		Map<String, Object> baseline = readJson(text(current.get("baseline_metrics_json")));
		Map<String, Object> shadow = readJson(text(current.get("shadow_metrics_json")));
		GateDecision gate = gate(baseline, shadow);
		Map<String, Object> result = new LinkedHashMap<>(get(candidateId));
		result.put("gatePassed", gate.passed());
		result.put("gateReasons", gate.reasons());
		result.put("costReduction", gate.costReduction());
		return result;
	}

	@Transactional
	public Map<String, Object> approve(String candidateId, ReviewCommand command) {
		return approve(candidateId, command, OperatorContext.system("runtime-optimization-approve"));
	}

	@Transactional
	public Map<String, Object> approve(String candidateId, ReviewCommand command, OperatorContext operator) {
		authorization.require(operator, "approve Runtime Optimization");
		Map<String, Object> current = lockCandidate(candidateId);
		if (!"SHADOW".equals(text(current.get("status")))) {
			throw new IllegalStateException("Runtime optimization candidate must complete SHADOW before approval");
		}
		GateDecision gate = gate(readJson(text(current.get("baseline_metrics_json"))),
				readJson(text(current.get("shadow_metrics_json"))));
		if (!gate.passed()) {
			throw new IllegalStateException(
					"Runtime optimization shadow gate failed: " + String.join("; ", gate.reasons()));
		}
		jdbc.update("""
				UPDATE qw_runtime_optimization_candidate SET status = 'APPROVED', reviewed_by = ?,
				 review_comment = ?, reviewed_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'SHADOW'
				""", operator.operator(), trim(command.comment(), 8000), candidateId);
		return evaluation(candidateId);
	}

	@Transactional
	public Map<String, Object> reject(String candidateId, ReviewCommand command) {
		return reject(candidateId, command, OperatorContext.system("runtime-optimization-reject"));
	}

	@Transactional
	public Map<String, Object> reject(String candidateId, ReviewCommand command, OperatorContext operator) {
		authorization.require(operator, "reject Runtime Optimization");
		Map<String, Object> current = lockCandidate(candidateId);
		if (!List.of("CANDIDATE", "SHADOW").contains(text(current.get("status")))) {
			throw new IllegalStateException("Only CANDIDATE or SHADOW runtime optimizations can be rejected");
		}
		jdbc.update("""
				UPDATE qw_runtime_optimization_candidate SET status = 'REJECTED', reviewed_by = ?,
				 review_comment = ?, reviewed_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status IN ('CANDIDATE','SHADOW')
				""", operator.operator(), required(command.comment(), "comment"), candidateId);
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> enable(String candidateId) {
		return enable(candidateId, OperatorContext.system("runtime-optimization-enable"));
	}

	@Transactional
	public Map<String, Object> enable(String candidateId, OperatorContext operator) {
		authorization.require(operator, "enable Runtime Optimization");
		Map<String, Object> current = lockCandidate(candidateId);
		if ("ENABLED".equals(text(current.get("status")))) {
			return get(candidateId);
		}
		if (!"APPROVED".equals(text(current.get("status")))) {
			throw new IllegalStateException("Only APPROVED runtime optimization candidates can be enabled");
		}
		String patternId = text(current.get("pattern_id"));
		String compatibilityHash = text(current.get("execution_compatibility_hash"));
		Map<String, Object> pattern = pattern(patternId);
		if (!Objects.equals(number(pattern.get("project_version_id")), number(current.get("project_version_id")))
				|| !Objects.equals(text(pattern.get("execution_compatibility_hash")), compatibilityHash)) {
			throw new IllegalStateException("Runtime optimization candidate no longer matches its Query Pattern");
		}
		jdbc.update("""
				UPDATE qw_preferred_execution_plan SET status = 'DISABLED', disabled_reason = 'superseded',
				 revision = revision + 1, update_time = CURRENT_TIMESTAMP
				WHERE pattern_id = ? AND execution_compatibility_hash = ? AND status = 'ENABLED'
				""", patternId, compatibilityHash);
		jdbc.update("""
				UPDATE qw_runtime_optimization_candidate SET status = 'DISABLED', update_time = CURRENT_TIMESTAMP
				WHERE pattern_id = ? AND execution_compatibility_hash = ? AND status = 'ENABLED' AND id <> ?
				""", patternId, compatibilityHash, candidateId);
		jdbc.update("""
				INSERT INTO qw_preferred_execution_plan
				(id, project_id, project_version_id, pattern_id, candidate_id, execution_compatibility_hash,
				 plan_json, applicability_json, status, enabled_time, revision, create_time, update_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", UUID.randomUUID().toString(), current.get("project_id"), current.get("project_version_id"),
				patternId, candidateId, compatibilityHash, current.get("proposal_json"),
				current.get("applicability_json"));
		jdbc.update("""
				UPDATE qw_runtime_optimization_candidate SET status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'APPROVED'
				""", candidateId);
		return get(candidateId);
	}

	@Transactional
	public Map<String, Object> disable(String candidateId, String reason, boolean degraded) {
		return disable(candidateId, reason, degraded, OperatorContext.system("runtime-optimization-disable"));
	}

	@Transactional
	public Map<String, Object> disable(String candidateId, String reason, boolean degraded, OperatorContext operator) {
		authorization.require(operator, "disable Runtime Optimization");
		Map<String, Object> current = lockCandidate(candidateId);
		if (!List.of("ENABLED", "APPROVED", "SHADOW").contains(text(current.get("status")))) {
			return get(candidateId);
		}
		String status = degraded ? "DEGRADED" : "DISABLED";
		String message = required(reason, "reason");
		jdbc.update(
				"""
						UPDATE qw_runtime_optimization_candidate SET status = ?, review_comment = ?, update_time = CURRENT_TIMESTAMP
						WHERE id = ?
						""",
				status, trim(message, 8000), candidateId);
		jdbc.update("""
				UPDATE qw_preferred_execution_plan SET status = ?, disabled_reason = ?, revision = revision + 1,
				 update_time = CURRENT_TIMESTAMP WHERE candidate_id = ? AND status = 'ENABLED'
				""", status, trim(message, 8000), candidateId);
		return get(candidateId);
	}

	public Optional<Map<String, Object>> findApplicablePlan(Long projectId, Long projectVersionId, String patternId,
			String compatibilityHash, Map<String, Object> runtimeFacts) {
		List<Map<String, Object>> plans = jdbc.queryForList("""
				SELECT * FROM qw_preferred_execution_plan
				WHERE project_id = ? AND project_version_id = ? AND pattern_id = ?
				 AND execution_compatibility_hash = ? AND status = 'ENABLED'
				ORDER BY enabled_time DESC LIMIT 1
				""", projectId, projectVersionId, patternId, compatibilityHash);
		if (plans.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> plan = plans.get(0);
		Map<String, Object> applicability = readJson(text(plan.get("applicability_json")));
		if (!applicable(applicability, projectId, projectVersionId, patternId, compatibilityHash, runtimeFacts)) {
			return Optional.empty();
		}
		Map<String, Object> result = new LinkedHashMap<>(plan);
		result.put("startHintOnly", true);
		result.put("guardStillRequired", true);
		result.put("reviewStillRequired", true);
		return Optional.of(result);
	}

	private GateDecision gate(Map<String, Object> baseline, Map<String, Object> shadow) {
		List<String> reasons = new java.util.ArrayList<>();
		long samples = Math.round(metric(shadow, "sampleCount", "sample_count"));
		if (samples < MINIMUM_SHADOW_SAMPLES) {
			reasons.add("shadow sample count must be at least " + MINIMUM_SHADOW_SAMPLES);
		}
		qualityNotLower(reasons, baseline, shadow, "correctness", "correctness_rate", "correctnessRate");
		qualityNotLower(reasons, baseline, shadow, "safety", "safety_rate", "safetyRate");
		qualityNotLower(reasons, baseline, shadow, "coverage", "coverage_rate", "coverageRate");
		qualityNotLower(reasons, baseline, shadow, "freshness", "freshness_rate", "freshnessRate");
		qualityNotLower(reasons, baseline, shadow, "stability", "stability_rate", "stabilityRate");
		double baselineCost = normalizedCost(baseline);
		double shadowCost = normalizedCost(shadow);
		double reduction = baselineCost <= 0 ? 0 : (baselineCost - shadowCost) / baselineCost;
		if (baselineCost <= 0) {
			reasons.add("baseline cost metrics are unavailable");
		}
		else if (reduction < MINIMUM_COST_REDUCTION) {
			reasons.add("aggregate cost reduction must be at least 30%");
		}
		return new GateDecision(reasons.isEmpty(), List.copyOf(reasons), reduction);
	}

	private void qualityNotLower(List<String> reasons, Map<String, Object> baseline, Map<String, Object> shadow,
			String label, String snake, String camel) {
		double baselineValue = metric(baseline, snake, camel);
		double shadowValue = metric(shadow, snake, camel);
		if (shadowValue + 0.000001 < baselineValue) {
			reasons.add(label + " must not decrease");
		}
	}

	private double normalizedCost(Map<String, Object> metrics) {
		double latency = metric(metrics, "avg_latency_ms", "avgLatencyMs", "latencyMs");
		double tokens = metric(metrics, "avg_token_count", "avgTokenCount", "tokenCount");
		double retries = metric(metrics, "avg_retry_count", "avgRetryCount", "retryCount");
		double clarifications = metric(metrics, "avg_clarification_count", "avgClarificationCount",
				"clarificationCount");
		return latency + tokens + retries * 1000 + clarifications * 1000;
	}

	private boolean applicable(Map<String, Object> applicability, Long projectId, Long projectVersionId,
			String patternId, String compatibilityHash, Map<String, Object> runtimeFacts) {
		if (!Objects.equals(text(applicability.get("patternId")), patternId)
				|| !Objects.equals(text(applicability.get("executionCompatibilityHash")), compatibilityHash)) {
			return false;
		}
		if (applicability.containsKey("projectId")
				&& !Objects.equals(number(applicability.get("projectId")), projectId)) {
			return false;
		}
		if (applicability.containsKey("projectVersionId")
				&& !Objects.equals(number(applicability.get("projectVersionId")), projectVersionId)) {
			return false;
		}
		if (runtimeFacts == null) {
			return true;
		}
		for (Map.Entry<String, Object> entry : applicability.entrySet()) {
			if (List.of("patternId", "executionCompatibilityHash", "catalogHash", "projectId", "projectVersionId")
				.contains(entry.getKey())) {
				continue;
			}
			if (runtimeFacts.containsKey(entry.getKey())
					&& !Objects.equals(runtimeFacts.get(entry.getKey()), entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	private Map<String, Object> candidate(String candidateId) {
		List<Map<String, Object>> values = jdbc
			.queryForList("SELECT * FROM qw_runtime_optimization_candidate WHERE id = ?", candidateId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Runtime optimization candidate not found: " + candidateId);
		}
		return values.get(0);
	}

	private Map<String, Object> lockCandidate(String candidateId) {
		List<Map<String, Object>> values = jdbc
			.queryForList("SELECT * FROM qw_runtime_optimization_candidate WHERE id = ? FOR UPDATE", candidateId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Runtime optimization candidate not found: " + candidateId);
		}
		return values.get(0);
	}

	private Map<String, Object> pattern(String patternId) {
		List<Map<String, Object>> values = jdbc.queryForList("SELECT * FROM qw_query_pattern WHERE id = ?", patternId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Query Pattern not found: " + patternId);
		}
		return values.get(0);
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid runtime optimization JSON", ex);
		}
	}

	private Map<String, Object> readJson(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid runtime optimization JSON", ex);
		}
	}

	private double metric(Map<String, Object> metrics, String... keys) {
		for (String key : keys) {
			Object value = metrics.get(key);
			if (value instanceof Number number) {
				return number.doubleValue();
			}
		}
		return 0;
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

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	public record ShadowCommand(Map<String, Object> metrics) {
	}

	public record ReviewCommand(String reviewedBy, String comment) {
	}

	private record GateDecision(boolean passed, List<String> reasons, double costReduction) {
	}

}
