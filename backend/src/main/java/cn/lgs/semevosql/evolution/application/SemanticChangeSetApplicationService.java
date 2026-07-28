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
import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.domain.EvolutionRootCause;
import cn.lgs.semevosql.evolution.domain.SemanticVersionLevel;
import cn.lgs.semevosql.evolution.domain.SemanticVersionPolicy;
import cn.lgs.semevosql.evolution.domain.SemanticVersionPolicy.Trigger;
import cn.lgs.semevosql.util.JsonUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Mutable semantic-evolution workspace. Published Semantic Versions never act as editing workspaces;
 * every proposed catalog mutation is first represented here and can only be materialized after
 * validation/replay/index barriers have completed.
 */
@Service
@RequiredArgsConstructor
public class SemanticChangeSetApplicationService {

    private final JdbcTemplate jdbc;

    @Transactional
    public ChangeSet create(CreateCommand command) {
        require(command.projectId(), "projectId");
        require(command.baseSemanticVersionId(), "baseSemanticVersionId");
        requireText(command.idempotencyKey(), "idempotencyKey");
        requireText(command.createdBy(), "createdBy");
        EvolutionRootCause rootCause = Objects.requireNonNull(command.rootCause(), "rootCause is required");
        if (!rootCause.allowsSemanticEvolution()) {
            throw new IllegalArgumentException("Only SEMANTIC_LAYER root cause can create a SemanticChangeSet; actual="
                    + rootCause);
        }
        SemanticVersionPolicy.Decision decision = SemanticVersionPolicy.decide(
                Objects.requireNonNull(command.trigger(), "trigger is required"), command.semanticDiffDetected());
        if (!decision.createVersion()) {
            throw new IllegalArgumentException("This event does not create a Semantic Version: " + decision.reason());
        }
        if (decision.level() == SemanticVersionLevel.INITIAL) {
            throw new IllegalArgumentException("Initialization does not use SemanticChangeSet");
        }
        assertBaseVersion(command.projectId(), command.baseSemanticVersionId());

        List<ChangeSet> existing = jdbc.query("""
                SELECT * FROM qw_semantic_change_set WHERE project_id = ? AND idempotency_key = ?
                """, this::map, command.projectId(), command.idempotencyKey());
        if (!existing.isEmpty()) {
            ChangeSet value = existing.get(0);
            if (!Objects.equals(value.baseSemanticVersionId(), command.baseSemanticVersionId())
                    || value.targetVersionLevel() != decision.level()
                    || value.rootCause() != rootCause) {
                throw new IllegalStateException("SemanticChangeSet idempotency key is bound to a different proposal");
            }
            return value;
        }

        String changeSetId = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO qw_semantic_change_set
                (id, project_id, base_semantic_version_id, target_version_level, origin_type, origin_ref, root_cause,
                 status, risk_level, idempotency_key, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                ON CONFLICT (project_id, idempotency_key) DO NOTHING
                """, changeSetId, command.projectId(), command.baseSemanticVersionId(), decision.level().name(),
                requiredOrigin(command.originType()), command.originRef(), rootCause.name(),
                defaultText(command.riskLevel(), "MEDIUM"), command.idempotencyKey(), command.createdBy());
        if (inserted == 0) {
            return getByIdempotency(command.projectId(), command.idempotencyKey());
        }
        return get(changeSetId);
    }

    @Transactional
    public ChangeItem putItem(String changeSetId, ChangeItemCommand command) {
        ChangeSet changeSet = lock(changeSetId);
        requireMutable(changeSet);
        requireText(command.assetType(), "assetType");
        requireText(command.assetKey(), "assetKey");
        String operation = defaultText(command.operation(), "UPDATE").toUpperCase();
        if (!List.of("ADD", "UPDATE", "DELETE").contains(operation)) {
            throw new IllegalArgumentException("Unsupported SemanticChangeSet operation: " + operation);
        }
        Operation patchOperation = normalizePatchOperation(command.patch());
        if (!Objects.equals(command.assetType(), patchOperation.assetType())
                || !Objects.equals(command.assetKey(), patchOperation.assetKey())) {
            throw new IllegalArgumentException("SemanticChangeItem asset identity must match its SemanticPatch.Operation");
        }
        String patchOperationType = changeOperation(patchOperation);
        if (!Objects.equals(operation, patchOperationType)) {
            throw new IllegalArgumentException("SemanticChangeItem operation does not match SemanticPatch.Operation: item="
                    + operation + ", patch=" + patchOperationType);
        }
        jdbc.update("""
                INSERT INTO qw_semantic_change_item
                (change_set_id, asset_type, asset_key, operation, before_hash, after_hash, patch_json, evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB))
                ON CONFLICT (change_set_id, asset_type, asset_key) DO UPDATE SET
                    operation = EXCLUDED.operation,
                    before_hash = EXCLUDED.before_hash,
                    after_hash = EXCLUDED.after_hash,
                    patch_json = EXCLUDED.patch_json,
                    evidence_json = EXCLUDED.evidence_json
                """, changeSetId, command.assetType(), command.assetKey(), operation, command.beforeHash(),
                command.afterHash(), json(patchOperation), json(command.evidence()));
        jdbc.update("""
                UPDATE qw_semantic_change_set
                SET affected_asset_count = (SELECT COUNT(*) FROM qw_semantic_change_item WHERE change_set_id = ?),
                    revision = revision + 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, changeSetId, changeSetId);
        return jdbc.query("""
                SELECT * FROM qw_semantic_change_item
                WHERE change_set_id = ? AND asset_type = ? AND asset_key = ?
                """, this::mapItem, changeSetId, command.assetType(), command.assetKey()).stream().findFirst()
            .orElseThrow();
    }

    @Transactional
    public List<ChangeItem> replaceDraftItems(String changeSetId, List<ChangeItemCommand> commands) {
        ChangeSet changeSet = lock(changeSetId);
        requireMutable(changeSet);
        if (changeSet.status() != Status.DRAFT) {
            throw new IllegalStateException("SemanticChangeSet items can only be replaced while DRAFT: " + changeSetId);
        }
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("SemanticChangeSet requires at least one semantic operation");
        }
        jdbc.update("DELETE FROM qw_semantic_change_item WHERE change_set_id = ?", changeSetId);
        for (ChangeItemCommand command : commands) {
            putItem(changeSetId, command);
        }
        return items(changeSetId);
    }

    @Transactional
    public ChangeSet raiseDraftRisk(String changeSetId, String requestedRiskLevel) {
        ChangeSet changeSet = lock(changeSetId);
        requireMutable(changeSet);
        if (changeSet.status() != Status.DRAFT) {
            throw new IllegalStateException("SemanticChangeSet risk can only change while DRAFT: " + changeSetId);
        }
        String requested = normalizeRisk(requestedRiskLevel);
        String current = normalizeRisk(changeSet.riskLevel());
        if (riskRank(requested) <= riskRank(current)) {
            return changeSet;
        }
        jdbc.update("""
                UPDATE qw_semantic_change_set
                SET risk_level = ?, revision = revision + 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'DRAFT'
                """, requested, changeSetId);
        return get(changeSetId);
    }

    @Transactional
    public ChangeSet transition(String changeSetId, Status expected, Status target, TransitionMetadata metadata) {
        ChangeSet current = lock(changeSetId);
        if (current.status() == target) {
            return current;
        }
        if (current.status() != expected) {
            throw new IllegalStateException("SemanticChangeSet transition conflict: expected=" + expected + ", actual="
                    + current.status());
        }
        assertTransition(expected, target);
        TransitionMetadata safe = metadata == null ? TransitionMetadata.empty() : metadata;
        int updated = jdbc.update("""
                UPDATE qw_semantic_change_set
                SET status = ?, semantic_diff_hash = COALESCE(?, semantic_diff_hash),
                    replay_run_id = COALESCE(?, replay_run_id),
                    replay_summary_json = COALESCE(CAST(? AS JSONB), replay_summary_json),
                    validation_summary_json = COALESCE(CAST(? AS JSONB), validation_summary_json),
                    materialized_version_id = COALESCE(?, materialized_version_id),
                    completed_time = CASE WHEN ? IN ('ACTIVE', 'REJECTED', 'FAILED', 'STALE') THEN CURRENT_TIMESTAMP
                                          ELSE completed_time END,
                    revision = revision + 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = ?
                """, target.name(), safe.semanticDiffHash(), safe.replayRunId(), nullableJson(safe.replaySummary()),
                nullableJson(safe.validationSummary()), safe.materializedVersionId(), target.name(), changeSetId,
                current.revision(), expected.name());
        if (updated != 1) {
            throw new IllegalStateException("SemanticChangeSet changed concurrently: " + changeSetId);
        }
        return get(changeSetId);
    }

    /**
     * Persist the durable outcome of an asynchronous replay without advancing the release
     * state. A replay that needs review remains REPLAYING so it can be retried after a new
     * approved replay, but its latest run and summary must never be invisible to operators.
     */
    @Transactional
    public ChangeSet recordReplayOutcome(String changeSetId, String replayRunId, Object replaySummary) {
        ChangeSet current = lock(changeSetId);
        if (current.status() != Status.REPLAYING) {
            return current;
        }
        String summaryJson = nullableJson(replaySummary);
        int updated = jdbc.update("""
                UPDATE qw_semantic_change_set
                SET replay_run_id = COALESCE(?, replay_run_id),
                    replay_summary_json = COALESCE(CAST(? AS JSONB), replay_summary_json),
                    revision = revision + 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'REPLAYING'
                """, replayRunId, summaryJson, changeSetId);
        if (updated != 1) {
            throw new IllegalStateException("SemanticChangeSet replay outcome changed concurrently: " + changeSetId);
        }
        return get(changeSetId);
    }

    @Transactional
    public ChangeSet rebase(String changeSetId, Long newBaseSemanticVersionId) {
        ChangeSet current = lock(changeSetId);
        require(newBaseSemanticVersionId, "newBaseSemanticVersionId");
        if (current.materializedVersionId() != null) {
            throw new IllegalStateException("A materialized SemanticChangeSet cannot be rebased");
        }
        if (!List.of(Status.DRAFT, Status.VALIDATING, Status.STALE).contains(current.status())) {
            throw new IllegalStateException("SemanticChangeSet can only rebase before replay/materialization; actual="
                    + current.status());
        }
        assertBaseVersion(current.projectId(), newBaseSemanticVersionId);
        if (Objects.equals(current.baseSemanticVersionId(), newBaseSemanticVersionId)
                && current.status() == Status.DRAFT) {
            return current;
        }
        int updated = jdbc.update("""
                UPDATE qw_semantic_change_set
                SET base_semantic_version_id = ?, status = 'DRAFT', semantic_diff_hash = NULL,
                    replay_run_id = NULL, replay_summary_json = NULL, validation_summary_json = NULL,
                    completed_time = NULL, revision = revision + 1, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND materialized_version_id IS NULL
                """, newBaseSemanticVersionId, changeSetId, current.revision());
        if (updated != 1) {
            throw new IllegalStateException("SemanticChangeSet changed concurrently while rebasing: " + changeSetId);
        }
        return get(changeSetId);
    }

    public ChangeSet get(String changeSetId) {
        return jdbc.query("SELECT * FROM qw_semantic_change_set WHERE id = ?", this::map, changeSetId).stream()
            .findFirst().orElseThrow(() -> new IllegalArgumentException("SemanticChangeSet not found: " + changeSetId));
    }

    public List<ChangeSet> list(Long projectId, Status status, int limit) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        if (status == null) {
            return jdbc.query("""
                    SELECT * FROM qw_semantic_change_set
                    WHERE project_id = ?
                    ORDER BY create_time DESC, id DESC LIMIT ?
                    """, this::map, projectId, boundedLimit);
        }
        return jdbc.query("""
                SELECT * FROM qw_semantic_change_set
                WHERE project_id = ? AND status = ?
                ORDER BY create_time DESC, id DESC LIMIT ?
                """, this::map, projectId, status.name(), boundedLimit);
    }

    public List<ChangeItem> items(String changeSetId) {
        get(changeSetId);
        return jdbc.query("SELECT * FROM qw_semantic_change_item WHERE change_set_id = ? ORDER BY id", this::mapItem,
                changeSetId);
    }

    public SemanticPatch semanticPatch(String changeSetId) {
        ChangeSet changeSet = get(changeSetId);
        String sourceHash = jdbc.query("""
                SELECT COALESCE(semantic_state_hash, catalog_hash)
                FROM qw_project_version WHERE id = ? AND project_id = ? AND status = 'PUBLISHED'
                """, (rs, rowNum) -> rs.getString(1), changeSet.baseSemanticVersionId(), changeSet.projectId()).stream()
            .findFirst().orElseThrow(() -> new IllegalStateException("SemanticChangeSet base version is unavailable"));
        List<Operation> operations = items(changeSetId).stream().map(item -> readPatchOperation(item.patchJson())).toList();
        if (operations.isEmpty()) {
            throw new IllegalStateException("SemanticChangeSet has no semantic operations: " + changeSetId);
        }
        return new SemanticPatch(1, changeSet.baseSemanticVersionId(), sourceHash, operations);
    }

    private ChangeSet getByIdempotency(Long projectId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM qw_semantic_change_set WHERE project_id = ? AND idempotency_key = ?", this::map,
                projectId, idempotencyKey).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Idempotent SemanticChangeSet insert produced no row"));
    }

    private ChangeSet lock(String changeSetId) {
        return jdbc.query("SELECT * FROM qw_semantic_change_set WHERE id = ? FOR UPDATE", this::map, changeSetId).stream()
            .findFirst().orElseThrow(() -> new IllegalArgumentException("SemanticChangeSet not found: " + changeSetId));
    }

    private void assertBaseVersion(Long projectId, Long versionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_project_version
                WHERE id = ? AND project_id = ? AND status = 'PUBLISHED' AND catalog_hash IS NOT NULL
                """, Integer.class, versionId, projectId);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("SemanticChangeSet base must be a published Semantic Version");
        }
    }

    private void requireMutable(ChangeSet changeSet) {
        if (List.of(Status.ACTIVE, Status.REJECTED, Status.FAILED, Status.STALE).contains(changeSet.status())) {
            throw new IllegalStateException("Terminal SemanticChangeSet is immutable: " + changeSet.changeSetId());
        }
        if (changeSet.materializedVersionId() != null) {
            throw new IllegalStateException("Materialized SemanticChangeSet items are immutable: " + changeSet.changeSetId());
        }
    }

    private void assertTransition(Status source, Status target) {
        boolean allowed = switch (source) {
            case DRAFT -> target == Status.VALIDATING || target == Status.REJECTED || target == Status.FAILED
                    || target == Status.STALE;
            case VALIDATING -> target == Status.REPLAYING || target == Status.REJECTED || target == Status.FAILED
                    || target == Status.STALE;
            case REPLAYING -> target == Status.INDEXING || target == Status.REJECTED || target == Status.FAILED
                    || target == Status.STALE;
            case INDEXING -> target == Status.READY || target == Status.FAILED || target == Status.STALE;
            case READY -> target == Status.ACTIVATING || target == Status.STALE || target == Status.FAILED;
            case ACTIVATING -> target == Status.ACTIVE || target == Status.STALE || target == Status.FAILED;
            case ACTIVE, REJECTED, FAILED, STALE -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Illegal SemanticChangeSet transition: " + source + " -> " + target);
        }
    }

    private ChangeSet map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChangeSet(rs.getString("id"), rs.getLong("project_id"), rs.getLong("base_semantic_version_id"),
                SemanticVersionLevel.valueOf(rs.getString("target_version_level")), rs.getString("origin_type"),
                rs.getString("origin_ref"), EvolutionRootCause.valueOf(rs.getString("root_cause")),
                Status.valueOf(rs.getString("status")), rs.getString("risk_level"), rs.getString("semantic_diff_hash"),
                rs.getString("replay_run_id"), rs.getString("replay_summary_json"), rs.getString("validation_summary_json"),
                rs.getInt("affected_asset_count"), nullableLong(rs, "materialized_version_id"),
                rs.getString("idempotency_key"), rs.getLong("revision"),
                rs.getString("created_by"), local(rs.getTimestamp("create_time")), local(rs.getTimestamp("update_time")),
                local(rs.getTimestamp("completed_time")));
    }

    private ChangeItem mapItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChangeItem(rs.getLong("id"), rs.getString("change_set_id"), rs.getString("asset_type"),
                rs.getString("asset_key"), rs.getString("operation"), rs.getString("before_hash"),
                rs.getString("after_hash"), rs.getString("patch_json"), rs.getString("evidence_json"));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime local(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String requiredOrigin(String value) {
        String origin = defaultText(value, "EPISODE").toUpperCase();
        if (!List.of("EPISODE", "CORPUS", "MANUAL", "BASELINE_PROMOTION").contains(origin)) {
            throw new IllegalArgumentException("Unsupported SemanticChangeSet origin: " + origin);
        }
        return origin;
    }

    private Operation normalizePatchOperation(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("SemanticChangeItem patch operation is required");
        }
        try {
            return value instanceof Operation operation ? operation
                    : JsonUtil.getObjectMapper().convertValue(value, Operation.class);
        }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("SemanticChangeItem patch must be one SemanticPatch.Operation", ex);
        }
    }

    private Operation readPatchOperation(String value) {
        try {
            return JsonUtil.getObjectMapper().readValue(value, Operation.class);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted SemanticChangeItem patch operation", ex);
        }
    }

    private String changeOperation(Operation operation) {
        String name = operation.operation().name();
        if (name.startsWith("ADD_")) {
            return "ADD";
        }
        if (name.startsWith("DELETE_")) {
            return "DELETE";
        }
        return "UPDATE";
    }

    private String json(Object value) {
        try {
            return JsonUtil.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize SemanticChangeSet JSON", ex);
        }
    }

    private String nullableJson(Object value) {
        return value == null ? null : json(value);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeRisk(String value) {
        String risk = defaultText(value, "MEDIUM").toUpperCase();
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(risk)) {
            throw new IllegalArgumentException("Unsupported SemanticChangeSet risk level: " + value);
        }
        return risk;
    }

    private int riskRank(String value) {
        return switch (normalizeRisk(value)) {
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> throw new IllegalStateException("Unreachable semantic risk level");
        };
    }

    private void require(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public enum Status {
        DRAFT, VALIDATING, REPLAYING, INDEXING, READY, ACTIVATING, ACTIVE, REJECTED, FAILED, STALE
    }

    public record CreateCommand(Long projectId, Long baseSemanticVersionId, Trigger trigger, boolean semanticDiffDetected,
            String originType, String originRef, EvolutionRootCause rootCause, String riskLevel, String idempotencyKey,
            String createdBy) {
    }

    public record ChangeItemCommand(String assetType, String assetKey, String operation, String beforeHash,
            String afterHash, Object patch, Object evidence) {
    }

    public record TransitionMetadata(String semanticDiffHash, String replayRunId, Object replaySummary,
            Object validationSummary, Long materializedVersionId) {
        public static TransitionMetadata empty() {
            return new TransitionMetadata(null, null, null, null, null);
        }
    }

    public record ChangeSet(String changeSetId, Long projectId, Long baseSemanticVersionId,
            SemanticVersionLevel targetVersionLevel, String originType, String originRef, EvolutionRootCause rootCause,
            Status status, String riskLevel, String semanticDiffHash, String replayRunId, String replaySummaryJson,
            String validationSummaryJson, int affectedAssetCount, Long materializedVersionId, String idempotencyKey,
            long revision, String createdBy, LocalDateTime createTime, LocalDateTime updateTime,
            LocalDateTime completedTime) {
    }

    public record ChangeItem(long id, String changeSetId, String assetType, String assetKey, String operation,
            String beforeHash, String afterHash, String patchJson, String evidenceJson) {
    }
}
