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

import cn.lgs.semevosql.evolution.domain.SemanticVersionLevel;
import cn.lgs.semevosql.evolution.domain.SemanticVersionNumber;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns Active Semantic Version pointer changes and Semantic Version numbering.
 *
 * <p>Activation and rollback never edit an existing Semantic Version snapshot and rollback never
 * creates a new version. A caller that activates a newly evolved version must provide the owning
 * READY SemanticChangeSet; explicit rollback uses the historical target directly.
 */
@Service
@RequiredArgsConstructor
public class SemanticVersionApplicationService {

    private final JdbcTemplate jdbc;

    public SemanticVersionNumber next(Long projectId, SemanticVersionLevel level) {
        if (level == null || level == SemanticVersionLevel.INITIAL) {
            throw new IllegalArgumentException("PATCH, MINOR or MAJOR increment level is required");
        }
        VersionRow active = activeVersion(projectId, false);
        return active.number().next(level);
    }

    @Transactional
    public ActivationResult activate(Long projectId, Long targetVersionId, String changeSetId, String actor,
            String requestId, String reason) {
        ProjectLock project = lockProject(projectId);
        VersionRow target = version(projectId, targetVersionId, true);
        requireActivatable(target);
        if (!StringUtils.hasText(changeSetId)) {
            throw new IllegalArgumentException("SemanticChangeSet is required when activating a newly evolved version");
        }
        assertReadyChangeSet(projectId, changeSetId, targetVersionId);
        if (Objects.equals(project.activeVersionId(), targetVersionId)) {
            jdbc.update("""
                    UPDATE qw_semantic_change_set
                    SET status = 'ACTIVE', completed_time = COALESCE(completed_time, CURRENT_TIMESTAMP),
                        revision = revision + 1, update_time = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'ACTIVATING'
                    """, changeSetId);
            return new ActivationResult(project.activeVersionId(), targetVersionId, "ACTIVATE", false);
        }
        switchPointer(projectId, project.activeVersionId(), targetVersionId, changeSetId, "ACTIVATE", actor, requestId,
                reason);
        jdbc.update("""
                UPDATE qw_semantic_change_set
                SET status = 'ACTIVE', completed_time = CURRENT_TIMESTAMP, revision = revision + 1,
                    update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVATING'
                """, changeSetId);
        return new ActivationResult(project.activeVersionId(), targetVersionId, "ACTIVATE", true);
    }

    @Transactional
    public ActivationResult rollback(Long projectId, Long targetVersionId, String actor, String requestId,
            String reason) {
        ProjectLock project = lockProject(projectId);
        VersionRow target = version(projectId, targetVersionId, true);
        requireActivatable(target);
        if (Objects.equals(project.activeVersionId(), targetVersionId)) {
            return new ActivationResult(project.activeVersionId(), targetVersionId, "ROLLBACK", false);
        }
        switchPointer(projectId, project.activeVersionId(), targetVersionId, null, "ROLLBACK", actor, requestId,
                reason);
        return new ActivationResult(project.activeVersionId(), targetVersionId, "ROLLBACK", true);
    }

    public ActiveVersion active(Long projectId) {
        VersionRow active = activeVersion(projectId, false);
        return new ActiveVersion(active.id(), active.number(), active.semanticStateHash(), active.corpusRevisionId(),
                active.activatedTime());
    }

    private void switchPointer(Long projectId, Long fromVersionId, Long toVersionId, String changeSetId,
            String eventType, String actor, String requestId, String reason) {
        requireText(actor, "actor");
        if (fromVersionId != null) {
            jdbc.update("UPDATE qw_project_version SET deactivated_time = CURRENT_TIMESTAMP WHERE id = ?", fromVersionId);
        }
        jdbc.update("""
                UPDATE qw_project_version
                SET activated_time = CURRENT_TIMESTAMP, deactivated_time = NULL
                WHERE id = ?
                """, toVersionId);
        int updated = jdbc.update("""
                UPDATE qw_project
                SET active_version_id = ?, status = 'READY', update_time = CURRENT_TIMESTAMP, revision = revision + 1
                WHERE id = ? AND active_version_id IS NOT DISTINCT FROM ?
                """, toVersionId, projectId, fromVersionId);
        if (updated != 1) {
            throw new IllegalStateException("Active Semantic Version changed concurrently for project " + projectId);
        }
        jdbc.update("""
                INSERT INTO qw_semantic_activation_event
                (id, project_id, from_version_id, to_version_id, change_set_id, event_type, reason, actor, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), projectId, fromVersionId, toVersionId, changeSetId, eventType,
                truncate(reason, 1024), actor, requestId);
    }

    private void assertReadyChangeSet(Long projectId, String changeSetId, Long targetVersionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_semantic_change_set
                WHERE id = ? AND project_id = ? AND materialized_version_id = ? AND status = 'ACTIVATING'
                """, Integer.class, changeSetId, projectId, targetVersionId);
        if (count == null || count != 1) {
            throw new IllegalStateException(
                    "Activation requires an ACTIVATING SemanticChangeSet bound to the materialized Semantic Version");
        }
    }

    private void requireActivatable(VersionRow version) {
        if (!"PUBLISHED".equals(version.status())) {
            throw new IllegalStateException("Only immutable PUBLISHED Semantic Versions can become active");
        }
        if (!StringUtils.hasText(version.semanticStateHash())) {
            throw new IllegalStateException("Semantic Version has no semanticStateHash");
        }
        if (!retrievalReady(version.id(), version.semanticStateHash())) {
            throw new IllegalStateException("Retrieval Index Generation is not READY for Semantic Version " + version.id());
        }
    }

    private boolean retrievalReady(Long semanticVersionId, String semanticStateHash) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_retrieval_index_generation
                WHERE semantic_version_id = ? AND semantic_state_hash = ? AND status = 'READY'
                """, Integer.class, semanticVersionId, semanticStateHash);
        if (count != null && count > 0) {
            return true;
        }
        // Compatibility for pre-SemEvoSQL published versions: their legacy version-bound retrieval
        // documents remain authoritative until a generation row is created by the migration/rebuild path.
        Integer legacy = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_semantic_retrieval_document
                WHERE project_version_id = ? AND catalog_hash = ?
                """, Integer.class, semanticVersionId, semanticStateHash);
        return legacy != null && legacy > 0;
    }

    private ProjectLock lockProject(Long projectId) {
        return jdbc.query("SELECT id, active_version_id FROM qw_project WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new ProjectLock(rs.getLong("id"), nullableLong(rs, "active_version_id")), projectId)
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
    }

    private VersionRow activeVersion(Long projectId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT v.id, v.version_number, v.semantic_major, v.semantic_minor, v.semantic_patch, v.status,
                       COALESCE(v.semantic_state_hash, v.catalog_hash) AS semantic_state_hash, v.corpus_revision_id,
                       v.activated_time
                FROM qw_project p JOIN qw_project_version v ON v.id = p.active_version_id
                WHERE p.id = ?
                """ + suffix, this::mapVersion, projectId).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Project has no Active Semantic Version: " + projectId));
    }

    private VersionRow version(Long projectId, Long versionId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT id, version_number, semantic_major, semantic_minor, semantic_patch, status,
                       COALESCE(semantic_state_hash, catalog_hash) AS semantic_state_hash, corpus_revision_id,
                       activated_time
                FROM qw_project_version WHERE project_id = ? AND id = ?
                """ + suffix, this::mapVersion, projectId, versionId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Semantic Version not found: " + versionId));
    }

    private VersionRow mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VersionRow(rs.getLong("id"),
                new SemanticVersionNumber(rs.getInt("semantic_major"), rs.getInt("semantic_minor"),
                        rs.getInt("semantic_patch")),
                rs.getString("status"), rs.getString("semantic_state_hash"), nullableLong(rs, "corpus_revision_id"),
                local(rs.getTimestamp("activated_time")));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime local(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record ProjectLock(Long projectId, Long activeVersionId) {
    }

    private record VersionRow(Long id, SemanticVersionNumber number, String status, String semanticStateHash,
            Long corpusRevisionId, LocalDateTime activatedTime) {
    }

    public record ActiveVersion(Long semanticVersionId, SemanticVersionNumber version, String semanticStateHash,
            Long corpusRevisionId, LocalDateTime activatedTime) {
    }

    public record ActivationResult(Long previousSemanticVersionId, Long activeSemanticVersionId, String eventType,
            boolean changed) {
    }
}
