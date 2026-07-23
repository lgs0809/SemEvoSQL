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

import cn.lgs.semevosql.clarification.ProjectSemanticAliasService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatchApplicationService;
import cn.lgs.semevosql.evolution.domain.SemanticVersionCause;
import cn.lgs.semevosql.evolution.domain.SemanticVersionLevel;
import cn.lgs.semevosql.evolution.domain.SemanticVersionNumber;
import cn.lgs.semevosql.evolution.domain.SemanticVersionPolicy;
import cn.lgs.semevosql.evolution.domain.SemanticVersionPolicy.Trigger;
import cn.lgs.semevosql.project.application.ProjectDatasourceBindingService;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogCloner;
import cn.lgs.semevosql.project.domain.ProjectVersionReleaseGate;
import cn.lgs.semevosql.semantic.application.ProjectDocumentCloningService;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Materializes one validated SemanticChangeSet into one immutable Semantic Version snapshot.
 *
 * <p>The temporary DRAFT row exists only inside this transaction so existing catalog clone code can
 * be reused safely. It is never exposed as a durable editing workspace: the transaction either
 * commits a fully release-gated PUBLISHED snapshot or rolls the row and all cloned state back.
 */
@Service
@RequiredArgsConstructor
public class SemanticVersionMaterializationService {

    private final JdbcTemplate jdbc;

    private final ProjectVersionCatalogCloner catalogCloner;

    private final ProjectDatasourceBindingService datasourceBindingService;

    private final ProjectDocumentCloningService projectDocumentCloningService;

    private final ProjectSemanticAliasService projectSemanticAliasService;

    private final SemanticPatchApplicationService patchApplicationService;

    private final ProjectVersionReleaseGate releaseGate;

    @Transactional
    public MaterializationResult materialize(String changeSetId, SemanticPatch patch) {
        Map<String, Object> changeSet = one("SELECT * FROM qw_semantic_change_set WHERE id = ? FOR UPDATE", changeSetId);
        String status = text(changeSet.get("status"));
        if (!"REPLAYING".equals(status)) {
            throw new IllegalStateException("Semantic Version materialization requires REPLAYING ChangeSet; actual=" + status);
        }
        Long existingTarget = number(changeSet.get("materialized_version_id"));
        if (existingTarget != null) {
            return existingResult(changeSetId, existingTarget, true);
        }
        Long projectId = number(changeSet.get("project_id"));
        Long baseVersionId = number(changeSet.get("base_semantic_version_id"));
        SemanticVersionLevel level = SemanticVersionLevel.valueOf(text(changeSet.get("target_version_level")));
        if (patch == null) {
            throw new IllegalArgumentException("SemanticPatch is required");
        }

        // Serializes Semantic Version number allocation and rejects stale ChangeSets before any clone.
        Long activeVersionId = jdbc.query("SELECT active_version_id FROM qw_project WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> nullableLong(rs, "active_version_id"), projectId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
        if (!Objects.equals(activeVersionId, baseVersionId)) {
            throw new StaleChangeSetException(changeSetId, baseVersionId, activeVersionId);
        }
        BaseVersion base = baseVersion(projectId, baseVersionId);
        if (!Objects.equals(patch.sourceVersionId(), baseVersionId)
                || !Objects.equals(patch.sourceCatalogHash(), base.semanticStateHash())) {
            throw new IllegalStateException("SemanticPatch source pin does not match the ChangeSet base Semantic Version");
        }

        SemanticVersionNumber next = base.number().next(level);
        int versionNo = nextVersionNo(projectId);
        Long corpusRevisionId = resolveCorpusRevision(changeSetId, projectId, base.corpusRevisionId(), level);
        Long targetVersionId = insertTemporarySnapshot(projectId, versionNo, next, level, baseVersionId,
                text(changeSet.get("origin_type")), corpusRevisionId);

        OperatorContext system = OperatorContext.system("semantic-version-materialize:" + changeSetId);
        catalogCloner.cloneCatalog(projectId, baseVersionId, targetVersionId);
        datasourceBindingService.cloneBindings(projectId, baseVersionId, targetVersionId, system);
        projectDocumentCloningService.cloneDocuments(projectId, baseVersionId, targetVersionId);
        projectSemanticAliasService.cloneAliases(projectId, baseVersionId, targetVersionId);
        SemanticPatchApplicationService.PatchMaterializationResult applied = patchApplicationService
            .materializePatch(projectId, targetVersionId, patch);

        ProjectVersionReleaseGate.ReleaseReport report = releaseGate.validate(projectId, baseVersionId, targetVersionId);
        if (!report.passed()) {
            String detail = report.scenarioPreflightFailures().isEmpty() ? ""
                    : ": " + String.join("; ", report.scenarioPreflightFailures());
            throw new IllegalStateException("Semantic release gate rejected materialized Semantic Version" + detail);
        }
        String semanticStateHash = required(report.catalogHash(), "catalogHash");
        int updated = jdbc.update("""
                UPDATE qw_project_version
                SET status = 'PUBLISHED', analysis_status = 'COMPLETED', analysis_error = NULL,
                    catalog_hash = ?, semantic_state_hash = ?, release_report = CAST(? AS JSONB),
                    validated_time = CURRENT_TIMESTAMP, published_time = CURRENT_TIMESTAMP,
                    revision = revision + 1
                WHERE id = ? AND project_id = ? AND status = 'DRAFT'
                """, semanticStateHash, semanticStateHash, json(report), targetVersionId, projectId);
        if (updated != 1) {
            throw new IllegalStateException("Temporary Semantic Version snapshot changed concurrently: " + targetVersionId);
        }
        return new MaterializationResult(changeSetId, projectId, baseVersionId, targetVersionId, next.toString(), level,
                semanticStateHash, corpusRevisionId, applied.operationCount(), applied.patchHash(), false);
    }

    private Long insertTemporarySnapshot(Long projectId, int versionNo, SemanticVersionNumber next,
            SemanticVersionLevel level, Long baseVersionId, String originType, Long corpusRevisionId) {
        return jdbc.queryForObject("""
                INSERT INTO qw_project_version
                (project_id, version_no, version_number, status, parent_version_id, creation_mode,
                 analysis_status, source, semantic_major, semantic_minor, semantic_patch, version_level,
                 version_cause, corpus_revision_id)
                VALUES (?, ?, ?, 'DRAFT', ?, 'CLONE', 'PENDING', 'SEMANTIC_CHANGE_SET', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, projectId, versionNo, next.toString(), baseVersionId, next.major(), next.minor(),
                next.patch(), level.name(), causeForOriginType(originType).name(), corpusRevisionId);
    }

    static SemanticVersionCause causeForOriginType(String originType) {
        Trigger trigger = switch (defaultOriginType(originType)) {
            case "EPISODE" -> Trigger.EPISODE_LEARNING;
            case "MANUAL" -> Trigger.MANUAL_SEMANTIC_FIX;
            case "CORPUS" -> Trigger.CORPUS_UPDATE;
            case "BASELINE_PROMOTION" -> Trigger.PROMOTE_BUSINESS_BASELINE;
            default -> throw new IllegalArgumentException("Unsupported SemanticChangeSet origin type: " + originType);
        };
        return SemanticVersionPolicy.decide(trigger, true).cause();
    }

    private static String defaultOriginType(String originType) {
        return StringUtils.hasText(originType) ? originType.trim().toUpperCase(java.util.Locale.ROOT) : "";
    }

    private BaseVersion baseVersion(Long projectId, Long versionId) {
        return jdbc.query("""
                SELECT id, semantic_major, semantic_minor, semantic_patch,
                       COALESCE(semantic_state_hash, catalog_hash) AS semantic_state_hash, corpus_revision_id
                FROM qw_project_version
                WHERE project_id = ? AND id = ? AND status = 'PUBLISHED'
                """, (rs, rowNum) -> new BaseVersion(rs.getLong("id"),
                new SemanticVersionNumber(rs.getInt("semantic_major"), rs.getInt("semantic_minor"),
                        rs.getInt("semantic_patch")), rs.getString("semantic_state_hash"),
                nullableLong(rs, "corpus_revision_id")), projectId, versionId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Published base Semantic Version not found: " + versionId));
    }

    private int nextVersionNo(Long projectId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM qw_project_version WHERE project_id = ?",
                Integer.class, projectId);
        return next == null ? 1 : next;
    }

    private Long resolveCorpusRevision(String changeSetId, Long projectId, Long baseCorpusRevisionId,
            SemanticVersionLevel level) {
        if (level == SemanticVersionLevel.MINOR) {
            Long linked = jdbc.query("""
                    SELECT id FROM qw_corpus_revision
                    WHERE project_id = ? AND semantic_change_set_id = ?
                    ORDER BY revision_no DESC LIMIT 1
                    """, (rs, rowNum) -> rs.getLong(1), projectId, changeSetId).stream().findFirst().orElse(null);
            if (linked == null) {
                throw new IllegalStateException("MINOR Semantic Version requires the Corpus Revision that caused its ChangeSet");
            }
            return linked;
        }
        if (level == SemanticVersionLevel.MAJOR) {
            return jdbc.query("""
                    SELECT id FROM qw_corpus_revision WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1
                    """, (rs, rowNum) -> rs.getLong(1), projectId).stream().findFirst().orElse(baseCorpusRevisionId);
        }
        return baseCorpusRevisionId;
    }

    private MaterializationResult existingResult(String changeSetId, Long targetVersionId, boolean alreadyMaterialized) {
        Map<String, Object> version = one("SELECT * FROM qw_project_version WHERE id = ?", targetVersionId);
        Map<String, Object> changeSet = one("SELECT * FROM qw_semantic_change_set WHERE id = ?", changeSetId);
        return new MaterializationResult(changeSetId, number(changeSet.get("project_id")),
                number(changeSet.get("base_semantic_version_id")), targetVersionId, text(version.get("version_number")),
                SemanticVersionLevel.valueOf(text(changeSet.get("target_version_level"))),
                text(version.get("semantic_state_hash")), number(version.get("corpus_revision_id")), 0, null,
                alreadyMaterialized);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> values = jdbc.queryForList(sql, args);
        if (values.size() != 1) {
            throw new IllegalArgumentException("Expected one Semantic Version materialization row");
        }
        return values.get(0);
    }

    private String json(Object value) {
        try {
            return JsonUtil.getObjectMapper().writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize Semantic Version release report", ex);
        }
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String text(Object value) {
        return Objects.toString(value, "");
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record BaseVersion(Long id, SemanticVersionNumber number, String semanticStateHash, Long corpusRevisionId) {
    }

    public record MaterializationResult(String changeSetId, Long projectId, Long baseSemanticVersionId,
            Long semanticVersionId, String semanticVersion, SemanticVersionLevel versionLevel, String semanticStateHash,
            Long corpusRevisionId, int operationCount, String patchHash, boolean alreadyMaterialized) {
    }

    public static final class StaleChangeSetException extends IllegalStateException {
        private final String changeSetId;
        private final Long baseSemanticVersionId;
        private final Long currentActiveSemanticVersionId;

        public StaleChangeSetException(String changeSetId, Long baseSemanticVersionId, Long currentActiveSemanticVersionId) {
            super("SemanticChangeSet " + changeSetId + " is stale: base=" + baseSemanticVersionId + ", active="
                    + currentActiveSemanticVersionId);
            this.changeSetId = changeSetId;
            this.baseSemanticVersionId = baseSemanticVersionId;
            this.currentActiveSemanticVersionId = currentActiveSemanticVersionId;
        }

        public String changeSetId() {
            return changeSetId;
        }

        public Long baseSemanticVersionId() {
            return baseSemanticVersionId;
        }

        public Long currentActiveSemanticVersionId() {
            return currentActiveSemanticVersionId;
        }
    }
}
