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
package cn.lgs.semevosql.evolution.application;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.application.CorpusRevisionApplicationService.CorpusRevision;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeItem;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator.ReleaseResult;
import cn.lgs.semevosql.evolution.application.SemanticVersionApplicationService.ActivationResult;
import cn.lgs.semevosql.project.application.ProjectHealthApplicationService;
import cn.lgs.semevosql.project.application.ProjectHealthApplicationService.ProjectHealthView;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Read/write facade for the SemEvoSQL governance model exposed to the product UI. */
@Service
@RequiredArgsConstructor
public class SemanticGovernanceApplicationService {

    private final JdbcTemplate jdbc;

    private final SemanticProjectRepository projectRepository;

    private final CorpusRevisionApplicationService corpusRevisionService;

    private final SemanticChangeSetApplicationService changeSetService;

    private final SemanticEvolutionReleaseOrchestrator releaseOrchestrator;

    private final SemanticVersionApplicationService versionService;

    private final ProjectHealthApplicationService projectHealthService;

    public VersionTimelineView timeline(Long projectId) {
        SemanticProject project = projectRepository.findProject(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
        List<SemanticProjectVersion> versions = projectRepository.findVersions(projectId);
        List<Map<String, Object>> activationEvents = jdbc.queryForList("""
                SELECT id, from_version_id, to_version_id, change_set_id, event_type, reason, actor, request_id,
                       create_time
                FROM qw_semantic_activation_event
                WHERE project_id = ? ORDER BY create_time DESC
                LIMIT 500
                """, projectId);
        return new VersionTimelineView(projectId, project.getActiveVersionId(), versions, activationEvents);
    }

    public List<CorpusRevision> corpusRevisions(Long projectId) {
        requireProject(projectId);
        return corpusRevisionService.list(projectId);
    }

    public List<ChangeSet> changeSets(Long projectId, Status status, int limit) {
        requireProject(projectId);
        return changeSetService.list(projectId, status, limit);
    }

    public ChangeSetView changeSet(String changeSetId) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        List<ChangeItem> items = changeSetService.items(changeSetId);
        List<Map<String, Object>> replayResults = jdbc.queryForList("""
                SELECT replay_execution_id, case_id, replay_level, replay_mode, dataset_version, status,
                       baseline_json, candidate_json, proof_json, error_message, create_time
                FROM qw_semantic_replay_result
                WHERE change_set_id = ?
                ORDER BY create_time, case_id, replay_level
                """, changeSetId);
        return new ChangeSetView(changeSet, items, replayResults);
    }

    public EpisodeDiagnosisView episodeDiagnosis(String episodeId) {
        Map<String, Object> episode = one("SELECT * FROM qw_episode WHERE id = ?", episodeId,
                "Episode not found: " + episodeId);
        List<Map<String, Object>> turns = jdbc.queryForList("""
                SELECT * FROM qw_episode_turn WHERE episode_id = ? ORDER BY turn_no, create_time
                """, episodeId);
        List<Map<String, Object>> attempts = jdbc.queryForList("""
                SELECT * FROM qw_attempt WHERE episode_id = ? ORDER BY attempt_no, create_time
                """, episodeId);
        List<Map<String, Object>> signals = jdbc.queryForList("""
                SELECT * FROM qw_episode_signal WHERE episode_id = ? ORDER BY create_time
                """, episodeId);
        List<Map<String, Object>> queryCases = jdbc.queryForList("""
                SELECT id, project_version_id, catalog_hash, attempt_id, run_id, normalized_question, intent_type,
                       status, rebind_status, quality_summary, reviewed_time, create_time
                FROM qw_query_example WHERE episode_id = ? ORDER BY create_time
                """, episodeId);
        List<Map<String, Object>> changeSets = jdbc.queryForList("""
                SELECT * FROM qw_semantic_change_set
                WHERE origin_type = 'EPISODE' AND origin_ref = ? ORDER BY create_time
                """, episodeId);
        return new EpisodeDiagnosisView(episode, turns, attempts, signals, queryCases, changeSets);
    }

    public ProjectSemanticReadinessView readiness(Long projectId, OperatorContext operator) {
        ProjectHealthView health = projectHealthService.getHealth(projectId, operator);
        SemanticVersionApplicationService.ActiveVersion active = versionService.active(projectId);
        int inProgress = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_semantic_change_set
                WHERE project_id = ? AND origin_type = 'CORPUS'
                  AND status IN ('DRAFT', 'VALIDATING', 'REPLAYING', 'INDEXING', 'READY', 'ACTIVATING')
                """, Integer.class, projectId);
        Map<String, Object> latestCorpusRevision = jdbc.queryForList("""
                SELECT id, revision_no, source_type, source_ref, content_hash, semantic_diff_detected,
                       semantic_change_set_id, create_time
                FROM qw_corpus_revision WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1
                """, projectId).stream().findFirst().orElse(Map.of());
        List<Map<String, Object>> updateChangeSets = jdbc.queryForList("""
                SELECT id, target_version_level, status, risk_level, semantic_diff_hash, replay_run_id,
                       materialized_version_id, create_time, update_time
                FROM qw_semantic_change_set
                WHERE project_id = ? AND origin_type = 'CORPUS'
                  AND status IN ('DRAFT', 'VALIDATING', 'REPLAYING', 'INDEXING', 'READY', 'ACTIVATING')
                ORDER BY create_time DESC
                """, projectId);
        return new ProjectSemanticReadinessView(projectId, health.queryReady(), active, inProgress > 0, inProgress,
                latestCorpusRevision, updateChangeSets);
    }

    public ReleaseResult promoteMajor(String changeSetId, String actor, String requestId, String reason) {
        return releaseOrchestrator.promoteMajor(changeSetId, actor, requestId,
                StringUtils.hasText(reason) ? reason.trim() : "manual business baseline promotion");
    }

    public ActivationResult rollback(Long projectId, Long versionId, String actor, String requestId, String reason) {
        return versionService.rollback(projectId, versionId, actor, requestId,
                StringUtils.hasText(reason) ? reason.trim() : "manual semantic version rollback");
    }

    private void requireProject(Long projectId) {
        if (projectRepository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("Semantic project not found: " + projectId);
        }
    }

    private Map<String, Object> one(String sql, Object argument, String error) {
        return jdbc.queryForList(sql, argument).stream().findFirst().orElseThrow(() -> new IllegalArgumentException(error));
    }

    public record VersionTimelineView(Long projectId, Long activeVersionId, List<SemanticProjectVersion> versions,
            List<Map<String, Object>> activationEvents) {
    }

    public record ChangeSetView(ChangeSet changeSet, List<ChangeItem> items,
            List<Map<String, Object>> replayResults) {
    }

    public record EpisodeDiagnosisView(Map<String, Object> episode, List<Map<String, Object>> turns,
            List<Map<String, Object>> attempts, List<Map<String, Object>> signals,
            List<Map<String, Object>> queryCases, List<Map<String, Object>> changeSets) {
    }

    public record ProjectSemanticReadinessView(Long projectId, boolean queryReady,
            SemanticVersionApplicationService.ActiveVersion activeVersion, boolean knowledgeUpdateInProgress,
            int knowledgeUpdateCount, Map<String, Object> latestCorpusRevision,
            List<Map<String, Object>> knowledgeUpdates) {
    }
}
