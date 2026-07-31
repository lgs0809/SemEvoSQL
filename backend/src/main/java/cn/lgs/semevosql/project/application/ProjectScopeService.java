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

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves durable records to their owning project in self-hosted single-user mode.
 *
 * <p>This is a scope-integrity service, not an ACL. Governance state machines and MCP credentials remain the
 * authorization boundaries for their respective workflows.</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectScopeService {

	private final JdbcTemplate jdbc;

	private final LocalOperatorService localOperator;

	public void requireProject(Long projectId, OperatorContext operator) {
		if (projectId == null || projectId <= 0) {
			throw new IllegalArgumentException("projectId is required");
		}
		localOperator.require(operator, "access local Project");
	}

	public Optional<Long> projectForRun(String runId) {
		return oneLong("SELECT project_id FROM qw_query_run WHERE run_id = ?", runId);
	}

	public Optional<Long> projectForGap(Long gapId) {
		return oneLong("SELECT project_id FROM qw_semantic_gap WHERE id = ?", gapId);
	}

	public Optional<Long> projectForEvolutionCandidate(String candidateId) {
		return oneLong("SELECT project_id FROM qw_semantic_evolution_candidate WHERE id = ?", candidateId);
	}

	public Optional<Long> projectForSemanticChangeSet(String changeSetId) {
		return oneLong("SELECT project_id FROM qw_semantic_change_set WHERE id = ?", changeSetId);
	}

	public Optional<Long> projectForOptimizationCandidate(String candidateId) {
		return oneLong("SELECT project_id FROM qw_runtime_optimization_candidate WHERE id = ?", candidateId);
	}

	public Optional<Long> projectForEvaluationJob(String jobId) {
		return oneLong("SELECT project_id FROM qw_evaluation_job WHERE id = ?", jobId);
	}

	public Optional<Long> projectForRelease(String releaseId) {
		return oneLong("SELECT project_id FROM qw_release WHERE id = ?", releaseId);
	}

	public Optional<Long> projectForEpisode(String episodeId) {
		return oneLong("SELECT project_id FROM qw_episode WHERE id = ?", episodeId);
	}

	public Optional<Long> projectForAttempt(String attemptId) {
		return oneLong("""
				SELECT e.project_id
				FROM qw_attempt a
				JOIN qw_episode e ON e.id = a.episode_id
				WHERE a.id = ?
				""", attemptId);
	}

	public Optional<Long> projectForTrajectoryPattern(String patternId) {
		return oneLong("SELECT project_id FROM qw_query_pattern WHERE id = ?", patternId);
	}

	private Optional<Long> oneLong(String sql, Object value) {
		return jdbc.query(sql, (rs, rowNum) -> {
			long projectId = rs.getLong(1);
			return rs.wasNull() ? null : projectId;
		}, value).stream().filter(projectId -> projectId != null).findFirst();
	}
}
