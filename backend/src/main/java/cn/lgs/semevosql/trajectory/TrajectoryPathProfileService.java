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
package cn.lgs.semevosql.trajectory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Recomputes aggregate execution-path quality profiles and their Pareto dominance ranks. */
@Service
@RequiredArgsConstructor
public class TrajectoryPathProfileService {

	private final JdbcTemplate jdbc;

	public void recompute(String patternId) {
		Map<String, Object> pattern = one("SELECT * FROM qw_query_pattern WHERE id = ?", patternId);
		List<Map<String, Object>> aggregates = jdbc.queryForList("""
				SELECT path_signature, execution_compatibility_hash, COUNT(*) sample_count,
				 SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) success_count,
				 AVG(correctness_score) correctness_rate, AVG(safety_score) safety_rate,
				 AVG(coverage_score) coverage_rate, AVG(freshness_score) freshness_rate,
				 AVG(stability_score) stability_rate, AVG(COALESCE(latency_ms, 0)) avg_latency_ms,
				 AVG(COALESCE(token_count, 0)) avg_token_count, AVG(retry_count) avg_retry_count,
				 AVG(clarification_count) avg_clarification_count
				FROM qw_trajectory_path WHERE pattern_id = ?
				GROUP BY path_signature, execution_compatibility_hash
				""", patternId);
		for (Map<String, Object> aggregate : aggregates) {
			Optional<Map<String, Object>> existing = optional("""
					SELECT * FROM qw_query_path_profile
					WHERE pattern_id = ? AND execution_compatibility_hash = ? AND path_signature = ?
					""", patternId, aggregate.get("execution_compatibility_hash"), aggregate.get("path_signature"));
			if (existing.isPresent()) {
				jdbc.update("""
						UPDATE qw_query_path_profile SET sample_count = ?, success_count = ?, correctness_rate = ?,
						 safety_rate = ?, coverage_rate = ?, freshness_rate = ?, stability_rate = ?, avg_latency_ms = ?,
						 avg_token_count = ?, avg_retry_count = ?, avg_clarification_count = ?,
						 last_evaluated_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = ?
						""", aggregate.get("sample_count"), aggregate.get("success_count"),
						aggregate.get("correctness_rate"), aggregate.get("safety_rate"), aggregate.get("coverage_rate"),
						aggregate.get("freshness_rate"), aggregate.get("stability_rate"), aggregate.get("avg_latency_ms"),
						aggregate.get("avg_token_count"), aggregate.get("avg_retry_count"),
						aggregate.get("avg_clarification_count"), existing.orElseThrow().get("id"));
			}
			else {
				jdbc.update("""
						INSERT INTO qw_query_path_profile
						(id, project_id, project_version_id, pattern_id, execution_compatibility_hash, path_signature,
						 sample_count, success_count, correctness_rate, safety_rate, coverage_rate, freshness_rate,
						 stability_rate, avg_latency_ms, avg_token_count, avg_retry_count, avg_clarification_count,
						 dominated, pareto_rank, status, last_evaluated_time, create_time, update_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 0, 'OBSERVE_ONLY',
						 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						""", UUID.randomUUID().toString(), pattern.get("project_id"), pattern.get("project_version_id"),
						patternId, aggregate.get("execution_compatibility_hash"), aggregate.get("path_signature"),
						aggregate.get("sample_count"), aggregate.get("success_count"),
						aggregate.get("correctness_rate"), aggregate.get("safety_rate"), aggregate.get("coverage_rate"),
						aggregate.get("freshness_rate"), aggregate.get("stability_rate"), aggregate.get("avg_latency_ms"),
						aggregate.get("avg_token_count"), aggregate.get("avg_retry_count"),
						aggregate.get("avg_clarification_count"));
			}
		}
		markPareto(patternId);
	}

	private void markPareto(String patternId) {
		List<Map<String, Object>> profiles = jdbc
			.queryForList("SELECT * FROM qw_query_path_profile WHERE pattern_id = ?", patternId);
		for (Map<String, Object> candidate : profiles) {
			boolean dominated = profiles.stream().anyMatch(other -> other != candidate && dominates(other, candidate));
			int rank = dominated ? 1
					+ (int) profiles.stream().filter(other -> other != candidate && dominates(other, candidate)).count()
					: 0;
			jdbc.update("""
					UPDATE qw_query_path_profile SET dominated = ?, pareto_rank = ?,
					 last_evaluated_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = ?
					""", dominated, rank, candidate.get("id"));
		}
	}

	static boolean dominates(Map<String, Object> left, Map<String, Object> right) {
		boolean quality = decimal(left, "correctness_rate") >= decimal(right, "correctness_rate")
				&& decimal(left, "safety_rate") >= decimal(right, "safety_rate")
				&& decimal(left, "coverage_rate") >= decimal(right, "coverage_rate")
				&& decimal(left, "freshness_rate") >= decimal(right, "freshness_rate")
				&& decimal(left, "stability_rate") >= decimal(right, "stability_rate");
		boolean cost = decimal(left, "avg_latency_ms") <= decimal(right, "avg_latency_ms")
				&& decimal(left, "avg_token_count") <= decimal(right, "avg_token_count")
				&& decimal(left, "avg_retry_count") <= decimal(right, "avg_retry_count")
				&& decimal(left, "avg_clarification_count") <= decimal(right, "avg_clarification_count");
		boolean strict = decimal(left, "correctness_rate") > decimal(right, "correctness_rate")
				|| decimal(left, "safety_rate") > decimal(right, "safety_rate")
				|| decimal(left, "coverage_rate") > decimal(right, "coverage_rate")
				|| decimal(left, "freshness_rate") > decimal(right, "freshness_rate")
				|| decimal(left, "stability_rate") > decimal(right, "stability_rate")
				|| decimal(left, "avg_latency_ms") < decimal(right, "avg_latency_ms")
				|| decimal(left, "avg_token_count") < decimal(right, "avg_token_count")
				|| decimal(left, "avg_retry_count") < decimal(right, "avg_retry_count")
				|| decimal(left, "avg_clarification_count") < decimal(right, "avg_clarification_count");
		return quality && cost && strict;
	}

	private Optional<Map<String, Object>> optional(String sql, Object... args) {
		return jdbc.queryForList(sql, args).stream().findFirst();
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Trajectory pattern not found or not unique");
		}
		return rows.get(0);
	}

	private static double decimal(Map<String, Object> value, String key) {
		Object raw = value.get(key);
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		return raw == null ? 0d : Double.parseDouble(raw.toString());
	}
}
