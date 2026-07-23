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
 * Resolves durable records to project access and private query ownership. Single-user deployments
 * retain their existing local scope; authenticated deployments enforce server-configured membership.
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
        if (!localOperator.canAccess(operator, projectId)) denied();
	}

    public void requireConversationOwner(Long projectId, String conversationId, OperatorContext operator) {
        requireProject(projectId, operator);
        if (!localOperator.authenticatedAccountsEnabled()) return;
        var owners = jdbc.queryForList("SELECT created_by FROM qw_project_conversation WHERE conversation_id=? AND project_id=? AND status<>'DELETED'",
                String.class, conversationId, projectId);
        if (owners.size() != 1 || !localOperator.owns(operator, owners.get(0))) denied();
    }

    public void requireRunOwner(String runId, OperatorContext operator) {
        var records = jdbc.queryForList("SELECT project_id, thread_id, request_payload FROM qw_query_run WHERE run_id=?", runId);
        if (records.size() != 1) denied();
        var row = records.get(0);
        requireProject(((Number) row.get("project_id")).longValue(), operator);
        if (!localOperator.authenticatedAccountsEnabled()) return;
        String owner = "";
        try {
            var payload = cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(java.util.Objects.toString(row.get("request_payload"), "{}"));
            for (String key : java.util.List.of("principalId", "userId", "createdBy")) {
                owner = payload.path(key).asText("");
                if (!owner.isBlank()) break;
            }
        } catch (Exception invalid) { denied(); }
        if (owner.isBlank()) {
            var owners = jdbc.queryForList("SELECT created_by FROM qw_project_conversation WHERE conversation_id=? AND project_id=? AND status<>'DELETED'",
                    String.class, row.get("thread_id"), row.get("project_id"));
            if (owners.size() == 1) owner = owners.get(0);
        }
        if (!localOperator.owns(operator, owner)) denied();
    }

    private void denied() {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此项目或查询");
    }

    public void requirePreferenceOwner(Long preferenceId, OperatorContext operator) {
        var rows = jdbc.queryForList("SELECT project_id, user_id FROM qw_user_semantic_preference WHERE id=?", preferenceId);
        if (rows.size() != 1) denied();
        var row = rows.get(0);
        requireProject(((Number) row.get("project_id")).longValue(), operator);
        if (!localOperator.owns(operator, java.util.Objects.toString(row.get("user_id"), ""))) denied();
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
