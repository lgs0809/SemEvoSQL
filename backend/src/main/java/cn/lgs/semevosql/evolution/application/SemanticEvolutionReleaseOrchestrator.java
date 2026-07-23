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

import cn.lgs.semevosql.evolution.SemanticPatch;
import cn.lgs.semevosql.evolution.SemanticPatchApplicationService;
import cn.lgs.semevosql.evolution.SemanticPatchValidator;
import cn.lgs.semevosql.evolution.SemanticPatchValidator.ValidationReport;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.TransitionMetadata;
import cn.lgs.semevosql.evolution.application.SemanticVersionMaterializationService.MaterializationResult;
import cn.lgs.semevosql.evolution.application.SemanticVersionMaterializationService.StaleChangeSetException;
import cn.lgs.semevosql.evolution.domain.SemanticVersionLevel;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.retrieval.RetrievalIndexGenerationService;
import cn.lgs.semevosql.semantic.retrieval.RetrievalIndexGenerationService.GenerationResult;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ChangeSet-driven Semantic Evolution release pipeline.
 *
 * <p>Each stage commits independently so long-running replay/index work never holds the mutable
 * ChangeSet row or a Semantic Version transaction open. PATCH/MINOR activate automatically after
 * all barriers pass; MAJOR stops at READY and requires explicit promotion.
 */
@Service
@RequiredArgsConstructor
public class SemanticEvolutionReleaseOrchestrator {

    private final SemanticChangeSetApplicationService changeSetService;

    private final SemanticVersionMaterializationService materializationService;

    private final SemanticPatchApplicationService patchApplicationService;

    private final SemanticPatchValidator patchValidator;

    private final RetrievalIndexGenerationService retrievalIndexGenerationService;

    private final SemanticVersionApplicationService versionService;

    private final ValidatedQueryExampleService queryExampleService;

    private final SemanticCatalogRepository catalogRepository;

    private final JdbcTemplate jdbc;

    public ChangeSet beginValidation(String changeSetId, String semanticDiffHash, Map<String, Object> summary) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        if (changeSet.affectedAssetCount() <= 0) {
            throw new IllegalStateException("SemanticChangeSet has no affected semantic assets");
        }
        if (!StringUtils.hasText(semanticDiffHash)) {
            throw new IllegalArgumentException("semanticDiffHash is required");
        }
        return changeSetService.transition(changeSetId, Status.DRAFT, Status.VALIDATING,
                new TransitionMetadata(semanticDiffHash, null, null, summary, null));
    }

    public ChangeSet approveValidation(String changeSetId, Map<String, Object> validationSummary) {
        return changeSetService.transition(changeSetId, Status.VALIDATING, Status.REPLAYING,
                new TransitionMetadata(null, null, null, validationSummary, null));
    }

    public ValidationDecision validateAndQueueReplay(String changeSetId) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        if (changeSet.status() == Status.REPLAYING) {
            return new ValidationDecision(changeSet, true, null);
        }
        if (changeSet.status() != Status.DRAFT && changeSet.status() != Status.VALIDATING) {
            throw new IllegalStateException(
                    "Semantic validation requires DRAFT or VALIDATING ChangeSet; actual=" + changeSet.status());
        }
        SemanticPatch patch = changeSetService.semanticPatch(changeSetId);
        String patchHash = patchApplicationService.patchHash(patch);
        ChangeSet validating = changeSet.status() == Status.DRAFT
                ? beginValidation(changeSetId, patchHash,
                        Map.of("operationCount", patch.operations().size(), "validator", "SemanticPatchValidator"))
                : changeSet;
        ValidationReport report = patchValidator.validatePatch(validating.projectId(), validating.baseSemanticVersionId(),
                patch);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("valid", report.valid());
        summary.put("operationCount", report.operationCount());
        summary.put("highRiskOperationCount", report.highRiskOperationCount());
        summary.put("errors", report.errors());
        summary.put("warnings", report.warnings());
        if (!report.valid()) {
            ChangeSet rejected = changeSetService.transition(changeSetId, Status.VALIDATING, Status.REJECTED,
                    new TransitionMetadata(null, null, null, Map.copyOf(summary), null));
            return new ValidationDecision(rejected, false, report);
        }
        ChangeSet replaying = approveValidation(changeSetId, Map.copyOf(summary));
        return new ValidationDecision(replaying, true, report);
    }

    public ReleaseResult releaseAfterReplay(String changeSetId, ReplayDecision replay, String actor, String requestId) {
        return releaseAfterReplay(changeSetId, changeSetService.semanticPatch(changeSetId), replay, actor, requestId);
    }

    public ReleaseResult releaseAfterReplay(String changeSetId, SemanticPatch patch, ReplayDecision replay,
            String actor, String requestId) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        if (changeSet.status() == Status.ACTIVE) {
            return existingRelease(changeSet, Status.ACTIVE, false);
        }
        if (changeSet.status() == Status.READY) {
            if (changeSet.targetVersionLevel() == SemanticVersionLevel.MAJOR) {
                return existingRelease(changeSet, Status.READY, false);
            }
            activate(changeSet, actor, requestId,
                    "automatic " + changeSet.targetVersionLevel() + " semantic evolution");
            return existingRelease(changeSetService.get(changeSetId), Status.ACTIVE, true);
        }
        if (changeSet.status() == Status.ACTIVATING) {
            activate(changeSet, actor, requestId,
                    "resume automatic " + changeSet.targetVersionLevel() + " semantic evolution");
            return existingRelease(changeSetService.get(changeSetId), Status.ACTIVE, true);
        }
        requireReplayApproval(changeSet, replay);

        MaterializationResult materialized;
        if (changeSet.status() == Status.INDEXING) {
            materialized = existingMaterialization(changeSet);
        }
        else {
            if (changeSet.status() != Status.REPLAYING) {
                throw new IllegalStateException(
                        "Semantic release requires REPLAYING/INDEXING/READY/ACTIVATING ChangeSet; actual="
                                + changeSet.status());
            }
            try {
                materialized = materializationService.materialize(changeSetId, patch);
            }
            catch (StaleChangeSetException ex) {
                changeSetService.transition(changeSetId, Status.REPLAYING, Status.STALE,
                        new TransitionMetadata(null, replay.replayRunId(), replay.summary(), null, null));
                throw ex;
            }

            changeSetService.transition(changeSetId, Status.REPLAYING, Status.INDEXING,
                    new TransitionMetadata(materialized.semanticStateHash(), replay.replayRunId(), replay.summary(), null,
                            materialized.semanticVersionId()));
        }

        GenerationResult generation;
        try {
            generation = retrievalIndexGenerationService.buildForChangeSet(changeSetId,
                    materialized.semanticVersionId(), materialized.semanticStateHash());
        }
        catch (RuntimeException ex) {
            changeSetService.transition(changeSetId, Status.INDEXING, Status.FAILED,
                    new TransitionMetadata(null, replay.replayRunId(), replay.summary(),
                            Map.of("indexError", truncate(ex.getMessage(), 1024)), materialized.semanticVersionId()));
            throw ex;
        }

        Map<String, Object> indexSummary = new LinkedHashMap<>();
        indexSummary.put("generationId", generation.generationId());
        indexSummary.put("affectedAssetCount", generation.affectedAssetCount());
        indexSummary.put("indexedAssetCount", generation.indexedAssetCount());
        indexSummary.put("reusedBaseArtifacts", generation.reusedBaseArtifacts());
        ChangeSet ready = changeSetService.transition(changeSetId, Status.INDEXING, Status.READY,
                new TransitionMetadata(null, replay.replayRunId(), replay.summary(), Map.copyOf(indexSummary),
                        materialized.semanticVersionId()));

        if (ready.targetVersionLevel() == SemanticVersionLevel.MAJOR) {
            return new ReleaseResult(changeSetId, materialized.semanticVersionId(), materialized.semanticVersion(),
                    Status.READY, false, generation.generationId());
        }
        activate(ready, actor, requestId, "automatic " + ready.targetVersionLevel() + " semantic evolution");
        return new ReleaseResult(changeSetId, materialized.semanticVersionId(), materialized.semanticVersion(),
                Status.ACTIVE, true, generation.generationId());
    }

    public ReleaseResult promoteMajor(String changeSetId, String actor, String requestId, String reason) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        if (changeSet.targetVersionLevel() != SemanticVersionLevel.MAJOR) {
            throw new IllegalArgumentException("Only a MAJOR SemanticChangeSet uses manual promotion");
        }
        if (changeSet.status() == Status.ACTIVE) {
            Long versionId = changeSet.materializedVersionId();
            return new ReleaseResult(changeSetId, versionId, versionNumber(versionId), Status.ACTIVE, false,
                    generationId(versionId));
        }
        if (changeSet.status() != Status.READY || changeSet.materializedVersionId() == null) {
            throw new IllegalStateException("MAJOR SemanticChangeSet must be READY before manual promotion");
        }
        activate(changeSet, actor, requestId, StringUtils.hasText(reason) ? reason : "manual business baseline promotion");
        return new ReleaseResult(changeSetId, changeSet.materializedVersionId(),
                versionNumber(changeSet.materializedVersionId()), Status.ACTIVE, true,
                generationId(changeSet.materializedVersionId()));
    }

    public ChangeSet rebaseStale(String changeSetId) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        Long activeVersionId = versionService.active(changeSet.projectId()).semanticVersionId();
        return changeSetService.rebase(changeSetId, activeVersionId);
    }

    public ReleaseResult resumeRelease(String changeSetId, String actor, String requestId) {
        ChangeSet changeSet = changeSetService.get(changeSetId);
        if (!List.of(Status.INDEXING, Status.READY, Status.ACTIVATING, Status.ACTIVE).contains(changeSet.status())) {
            throw new IllegalStateException("Semantic release resume requires INDEXING/READY/ACTIVATING/ACTIVE; actual="
                    + changeSet.status());
        }
        ReplayDecision replay = persistedReplayDecision(changeSet);
        return releaseAfterReplay(changeSetId, replay, actor, requestId);
    }

    private ReplayDecision persistedReplayDecision(ChangeSet changeSet) {
        if (changeSet.status() != Status.INDEXING) {
            return new ReplayDecision(true, defaultText(changeSet.replayRunId(), "resume:" + changeSet.changeSetId()),
                    1, 1, true, Map.of("resume", true));
        }
        Map<String, Object> summary = parseMap(changeSet.replaySummaryJson());
        int queryCases = integer(summary.get("queryCases"));
        int goldenCases = integer(summary.get("goldenCases"));
        int evaluated = queryCases + goldenCases;
        int independent = integer(summary.get("independentEvidenceCount"));
        boolean broadReplay = booleanValue(summary.get("broadReplay")) || evaluated >= 10;
        boolean passed = integer(summary.get("failed")) == 0 && integer(summary.get("reviewRequired")) == 0;
        String replayRunId = defaultText(changeSet.replayRunId(), Objects.toString(summary.get("replayExecutionId"), ""));
        return new ReplayDecision(passed, replayRunId, evaluated, independent, broadReplay, summary);
    }

    private ReleaseResult existingRelease(ChangeSet changeSet, Status status, boolean activated) {
        Long versionId = changeSet.materializedVersionId();
        if (versionId == null) {
            throw new IllegalStateException("SemanticChangeSet has no materialized Semantic Version: "
                    + changeSet.changeSetId());
        }
        return new ReleaseResult(changeSet.changeSetId(), versionId, versionNumber(versionId), status, activated,
                generationId(versionId));
    }

    private MaterializationResult existingMaterialization(ChangeSet changeSet) {
        Long versionId = changeSet.materializedVersionId();
        if (versionId == null) {
            throw new IllegalStateException("INDEXING SemanticChangeSet has no materialized Semantic Version");
        }
        return jdbc.query("""
                SELECT version_number, COALESCE(semantic_state_hash, catalog_hash) AS semantic_state_hash,
                       corpus_revision_id
                FROM qw_project_version WHERE id = ? AND project_id = ? AND status = 'PUBLISHED'
                """, (rs, rowNum) -> new MaterializationResult(changeSet.changeSetId(), changeSet.projectId(),
                changeSet.baseSemanticVersionId(), versionId, rs.getString("version_number"), changeSet.targetVersionLevel(),
                rs.getString("semantic_state_hash"), nullableLong(rs, "corpus_revision_id"), 0, null, true), versionId,
                changeSet.projectId()).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Materialized Semantic Version is unavailable: " + versionId));
    }

    private void activate(ChangeSet changeSet, String actor, String requestId, String reason) {
        if (!StringUtils.hasText(actor) || !StringUtils.hasText(requestId)) {
            throw new IllegalArgumentException("actor and requestId are required for Semantic Version activation");
        }
        if (changeSet.status() == Status.READY) {
            changeSet = changeSetService.transition(changeSet.changeSetId(), Status.READY, Status.ACTIVATING,
                    TransitionMetadata.empty());
        }
        else if (changeSet.status() != Status.ACTIVATING) {
            throw new IllegalStateException("Semantic Version activation requires READY or ACTIVATING ChangeSet");
        }
        try {
            versionService.activate(changeSet.projectId(), changeSet.materializedVersionId(), changeSet.changeSetId(),
                    actor, requestId, reason);
        }
        catch (RuntimeException ex) {
            ChangeSet current = changeSetService.get(changeSet.changeSetId());
            if (current.status() == Status.ACTIVATING) {
                changeSetService.transition(changeSet.changeSetId(), Status.ACTIVATING, Status.FAILED,
                        new TransitionMetadata(null, null, null,
                                Map.of("activationError", truncate(ex.getMessage(), 1024)),
                                changeSet.materializedVersionId()));
            }
            throw ex;
        }
        rebindLearningAssets(changeSet);
    }

    private void rebindLearningAssets(ChangeSet changeSet) {
        String semanticStateHash = jdbc.query("""
                SELECT COALESCE(semantic_state_hash, catalog_hash) FROM qw_project_version WHERE id = ?
                """, (rs, rowNum) -> rs.getString(1), changeSet.materializedVersionId()).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Activated Semantic Version disappeared"));
        try {
            queryExampleService.rebindApprovedExamples(changeSet.projectId(), changeSet.baseSemanticVersionId(),
                    changeSet.materializedVersionId(), semanticStateHash,
                    catalogRepository.loadCatalog(changeSet.projectId(), changeSet.materializedVersionId()));
            jdbc.update("""
                    UPDATE qw_semantic_evolution_candidate
                    SET rebind_status = 'SUCCEEDED', rebind_error = NULL, update_time = CURRENT_TIMESTAMP
                    WHERE semantic_change_set_id = ?
                    """, changeSet.changeSetId());
        }
        catch (RuntimeException ex) {
            jdbc.update("""
                    UPDATE qw_semantic_evolution_candidate
                    SET rebind_status = 'FAILED', rebind_error = ?, update_time = CURRENT_TIMESTAMP
                    WHERE semantic_change_set_id = ?
                    """, truncate(ex.getMessage(), 2048), changeSet.changeSetId());
        }
    }

    private void requireReplayApproval(ChangeSet changeSet, ReplayDecision replay) {
        if (replay == null || !replay.passed()) {
            throw new IllegalStateException("SemanticChangeSet replay must pass before materialization");
        }
        if (!StringUtils.hasText(replay.replayRunId())) {
            throw new IllegalArgumentException("replayRunId is required");
        }
        if (replay.independentEvidenceCount() <= 0) {
            throw new IllegalStateException("The causative signal cannot be the only proof for Semantic Evolution");
        }
        String risk = changeSet.riskLevel() == null ? "MEDIUM" : changeSet.riskLevel().toUpperCase();
        int minimumCases = switch (risk) {
            case "LOW" -> 1;
            case "HIGH" -> 10;
            case "CRITICAL" -> 20;
            default -> 3;
        };
        if (replay.evaluatedCaseCount() < minimumCases) {
            throw new IllegalStateException("Replay coverage is below the " + risk + " risk threshold: required="
                    + minimumCases + ", actual=" + replay.evaluatedCaseCount());
        }
        if (("HIGH".equals(risk) || "CRITICAL".equals(risk)) && !replay.broadReplay()) {
            throw new IllegalStateException("High-risk Semantic Evolution requires broad replay coverage");
        }
    }

    private String versionNumber(Long versionId) {
        if (versionId == null) {
            return null;
        }
        return jdbc.query("SELECT version_number FROM qw_project_version WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), versionId).stream().findFirst().orElse(null);
    }

    private String generationId(Long versionId) {
        if (versionId == null) {
            return null;
        }
        return jdbc.query("""
                SELECT id FROM qw_retrieval_index_generation
                WHERE semantic_version_id = ? AND status = 'READY'
                ORDER BY ready_time DESC NULLS LAST LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), versionId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> parseMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtil.getObjectMapper().readValue(value, Map.class);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted SemanticChangeSet replay summary", ex);
        }
    }

    private int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ValidationDecision(ChangeSet changeSet, boolean accepted, ValidationReport report) {
    }

    public record ReplayDecision(boolean passed, String replayRunId, int evaluatedCaseCount,
            int independentEvidenceCount, boolean broadReplay, Map<String, Object> summary) {
        public ReplayDecision {
            summary = summary == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        }
    }

    public record ReleaseResult(String changeSetId, Long semanticVersionId, String semanticVersion, Status status,
            boolean activated, String retrievalGenerationId) {
    }
}
