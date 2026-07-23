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
package cn.lgs.semevosql.episode.application;

import cn.lgs.semevosql.episode.domain.EpisodeRelationType;
import cn.lgs.semevosql.episode.domain.EpisodeTurnType;
import cn.lgs.semevosql.evolution.domain.EvolutionRootCause;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
 * Canonical Episode lifecycle entry point shared by Web/API/MCP runtime paths.
 *
 * <p>An Episode pins the project's Active Semantic Version exactly once at creation. Clarification,
 * correction, retry and durable resume mutate the same Episode and never silently rebase it.
 */
@Service
@RequiredArgsConstructor
public class EpisodeApplicationService {

    private final JdbcTemplate jdbc;

    @Transactional
    public EpisodeSnapshot start(StartCommand command) {
        require(command.projectId(), "projectId");
        requireText(command.requestId(), "requestId");
        requireText(command.agentId(), "agentId");
        requireText(command.originalQuestion(), "originalQuestion");
        String idempotencyKey = StringUtils.hasText(command.idempotencyKey()) ? command.idempotencyKey()
                : command.requestId();
        String requestFingerprint = StringUtils.hasText(command.requestFingerprint()) ? command.requestFingerprint()
                : fingerprint(command.projectId() + "\n" + command.originalQuestion());

        List<EpisodeSnapshot> existing = jdbc.query("""
                SELECT id, request_id, agent_id, project_id, conversation_id, parent_episode_id, relation_type,
                       base_semantic_version_id, accepted_semantic_state_hash, result_semantic_version_id,
                       original_question, normalized_question, status, outcome, idempotency_key, request_fingerprint,
                       accepted_attempt_id, create_time, update_time, completed_time
                FROM qw_episode
                WHERE project_id = ? AND idempotency_key = ?
                """, this::mapEpisode, command.projectId(), idempotencyKey);
        if (!existing.isEmpty()) {
            EpisodeSnapshot snapshot = existing.get(0);
            if (!Objects.equals(snapshot.requestFingerprint(), requestFingerprint)) {
                throw new IllegalStateException("Episode idempotency key was reused with a different request");
            }
            return snapshot;
        }

        VersionPin pin = activeVersion(command.projectId());
        assertExpectedVersion(command.expectedSemanticVersionId(), pin.semanticVersionId());
        validateParent(command.projectId(), command.parentEpisodeId(), command.relationType());

        String episodeId = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO qw_episode
                (id, request_id, agent_id, project_id, project_version_id, datasource_id, catalog_hash,
                 original_question, normalized_question, status, model_name, prompt_version, conversation_id,
                 parent_episode_id, relation_type, base_semantic_version_id, accepted_semantic_state_hash,
                 idempotency_key, request_fingerprint, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                ON CONFLICT (project_id, idempotency_key) DO NOTHING
                """, episodeId, command.requestId(), command.agentId(), command.projectId(), pin.semanticVersionId(),
                command.datasourceId(), pin.catalogHash(), limit(command.originalQuestion(), 8000),
                limit(command.normalizedQuestion(), 8000), command.modelName(), command.promptVersion(),
                command.conversationId(), command.parentEpisodeId(), enumName(command.relationType()),
                pin.semanticVersionId(), pin.semanticStateHash(), idempotencyKey, requestFingerprint);
        if (inserted == 0) {
            return jdbc.query("""
                    SELECT id, request_id, agent_id, project_id, conversation_id, parent_episode_id, relation_type,
                           base_semantic_version_id, accepted_semantic_state_hash, result_semantic_version_id,
                           original_question, normalized_question, status, outcome, idempotency_key,
                           request_fingerprint, accepted_attempt_id, create_time, update_time, completed_time
                    FROM qw_episode WHERE project_id = ? AND idempotency_key = ?
                    """, this::mapEpisode, command.projectId(), idempotencyKey).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Episode idempotent insert did not produce a row"));
        }
        appendTurn(episodeId, EpisodeTurnType.QUESTION, "USER", command.originalQuestion(), Map.of(),
                command.requestId());
        return get(episodeId);
    }

    @Transactional
    public EpisodeSnapshot createChild(String parentEpisodeId, EpisodeRelationType relationType, StartCommand command) {
        if (relationType == null) {
            throw new IllegalArgumentException("Child Episode relationType is required");
        }
        EpisodeSnapshot parent = get(parentEpisodeId);
        if (parent.completedTime() == null && !isTerminal(parent.status())) {
            throw new IllegalStateException("A child Episode can only be created after the parent business task completes");
        }
        if (!Objects.equals(parent.projectId(), command.projectId())) {
            throw new IllegalArgumentException("Child Episode must belong to the same project as its parent");
        }
        return start(command.withParent(parentEpisodeId, relationType));
    }

    @Transactional
    public EpisodeTurn appendTurn(String episodeId, EpisodeTurnType type, String role, String content,
            Map<String, Object> payload, String requestId) {
        requireText(episodeId, "episodeId");
        Objects.requireNonNull(type, "Episode turn type is required");
        requireText(role, "role");
        lockEpisode(episodeId);
        Integer turnNo = jdbc.queryForObject("SELECT COALESCE(MAX(turn_no), 0) + 1 FROM qw_episode_turn WHERE episode_id = ?",
                Integer.class, episodeId);
        String turnId = UUID.randomUUID().toString();
        String payloadJson = json(payload == null ? Map.of() : payload);
        jdbc.update("""
                INSERT INTO qw_episode_turn(id, episode_id, turn_no, turn_type, role, content, payload_json, request_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
                """, turnId, episodeId, turnNo == null ? 1 : turnNo, type.name(), role, limit(content, 16000),
                payloadJson, requestId);
        jdbc.update("UPDATE qw_episode SET update_time = CURRENT_TIMESTAMP WHERE id = ?", episodeId);
        return new EpisodeTurn(turnId, episodeId, turnNo == null ? 1 : turnNo, type, role, content,
                payload == null ? Map.of() : Map.copyOf(payload), requestId);
    }

    @Transactional
    public void recordSignal(String episodeId, String attemptId, String signalType, EvolutionRootCause rootCause,
            Double confidence, Map<String, Object> evidence) {
        get(episodeId);
        requireText(signalType, "signalType");
        jdbc.update("""
                INSERT INTO qw_episode_signal(id, episode_id, attempt_id, signal_type, root_cause, confidence,
                                              evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """, UUID.randomUUID().toString(), episodeId, attemptId, signalType,
                rootCause == null ? null : rootCause.name(), confidence,
                json(evidence == null ? Map.of() : evidence));
    }

    @Transactional
    public EpisodeSnapshot complete(String episodeId, String status, String outcome, String acceptedAttemptId,
            Long resultSemanticVersionId) {
        EpisodeSnapshot current = get(episodeId);
        if (current.completedTime() != null) {
            return current;
        }
        jdbc.update("""
                UPDATE qw_episode
                SET status = ?, outcome = ?, accepted_attempt_id = ?, result_semantic_version_id = ?,
                    completed_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
                WHERE id = ? AND completed_time IS NULL
                """, status, outcome, acceptedAttemptId, resultSemanticVersionId, episodeId);
        return get(episodeId);
    }

    public EpisodeSnapshot resume(String episodeId) {
        return get(episodeId);
    }

    public EpisodeSnapshot get(String episodeId) {
        return jdbc.query("""
                SELECT id, request_id, agent_id, project_id, conversation_id, parent_episode_id, relation_type,
                       base_semantic_version_id, accepted_semantic_state_hash, result_semantic_version_id,
                       original_question, normalized_question, status, outcome, idempotency_key, request_fingerprint,
                       accepted_attempt_id, create_time, update_time, completed_time
                FROM qw_episode WHERE id = ?
                """, this::mapEpisode, episodeId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
    }

    private VersionPin activeVersion(Long projectId) {
        return jdbc.query("""
                SELECT v.id AS semantic_version_id, v.catalog_hash,
                       COALESCE(v.semantic_state_hash, v.catalog_hash) AS semantic_state_hash
                FROM qw_project p
                JOIN qw_project_version v ON v.id = p.active_version_id
                WHERE p.id = ?
                """, (rs, rowNum) -> new VersionPin(rs.getLong("semantic_version_id"), rs.getString("catalog_hash"),
                rs.getString("semantic_state_hash")), projectId).stream().findFirst()
            .map(pin -> {
                if (!StringUtils.hasText(pin.catalogHash()) || !StringUtils.hasText(pin.semanticStateHash())) {
                    throw new IllegalStateException("Active Semantic Version is missing a validated semantic state hash");
                }
                return pin;
            })
            .orElseThrow(() -> new IllegalStateException("Project has no Active Semantic Version: " + projectId));
    }

    private void validateParent(Long projectId, String parentEpisodeId, EpisodeRelationType relationType) {
        if (!StringUtils.hasText(parentEpisodeId)) {
            if (relationType != null) {
                throw new IllegalArgumentException("relationType requires parentEpisodeId");
            }
            return;
        }
        if (relationType == null) {
            throw new IllegalArgumentException("parentEpisodeId requires relationType");
        }
        EpisodeSnapshot parent = get(parentEpisodeId);
        if (!Objects.equals(parent.projectId(), projectId)) {
            throw new IllegalArgumentException("Parent Episode belongs to another project");
        }
        if (parent.completedTime() == null && !isTerminal(parent.status())) {
            throw new IllegalStateException("Child Episode requires a completed parent Episode");
        }
    }

    private void assertExpectedVersion(Long expected, Long actual) {
        if (expected != null && !Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Active Semantic Version changed before Episode creation: expected=" + expected + ", actual=" + actual);
        }
    }

    private void lockEpisode(String episodeId) {
        if (jdbc.query("SELECT id FROM qw_episode WHERE id = ? FOR UPDATE", (rs, rowNum) -> rs.getString(1), episodeId)
            .isEmpty()) {
            throw new IllegalArgumentException("Episode not found: " + episodeId);
        }
    }

    private EpisodeSnapshot mapEpisode(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String relation = rs.getString("relation_type");
        Long resultVersion = nullableLong(rs, "result_semantic_version_id");
        return new EpisodeSnapshot(rs.getString("id"), rs.getString("request_id"), rs.getString("agent_id"),
                rs.getLong("project_id"), rs.getString("conversation_id"), rs.getString("parent_episode_id"),
                StringUtils.hasText(relation) ? EpisodeRelationType.valueOf(relation) : null,
                rs.getLong("base_semantic_version_id"), rs.getString("accepted_semantic_state_hash"), resultVersion,
                rs.getString("original_question"), rs.getString("normalized_question"), rs.getString("status"),
                rs.getString("outcome"), rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("accepted_attempt_id"), local(rs.getTimestamp("create_time")),
                local(rs.getTimestamp("update_time")), local(rs.getTimestamp("completed_time")));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime local(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                || "EXPIRED".equals(status) || "COMPLETED".equals(status);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String json(Object value) {
        try {
            return JsonUtil.getObjectMapper().writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize Episode payload", ex);
        }
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
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

    private record VersionPin(Long semanticVersionId, String catalogHash, String semanticStateHash) {
    }

    public record StartCommand(String requestId, String idempotencyKey, String requestFingerprint, String agentId,
            Long projectId, Long expectedSemanticVersionId, Integer datasourceId, String conversationId,
            String parentEpisodeId, EpisodeRelationType relationType, String originalQuestion, String normalizedQuestion,
            String modelName, String promptVersion) {

        public StartCommand withParent(String parentEpisodeId, EpisodeRelationType relationType) {
            return new StartCommand(requestId, idempotencyKey, requestFingerprint, agentId, projectId,
                    expectedSemanticVersionId, datasourceId, conversationId, parentEpisodeId, relationType,
                    originalQuestion, normalizedQuestion, modelName, promptVersion);
        }
    }

    public record EpisodeSnapshot(String episodeId, String requestId, String agentId, Long projectId,
            String conversationId, String parentEpisodeId, EpisodeRelationType relationType, Long semanticVersionId,
            String semanticStateHash, Long resultSemanticVersionId, String originalQuestion, String normalizedQuestion,
            String status, String outcome, String idempotencyKey, String requestFingerprint, String acceptedAttemptId,
            LocalDateTime createTime, LocalDateTime updateTime, LocalDateTime completedTime) {
    }

    public record EpisodeTurn(String turnId, String episodeId, int turnNo, EpisodeTurnType type, String role,
            String content, Map<String, Object> payload, String requestId) {
    }

}
