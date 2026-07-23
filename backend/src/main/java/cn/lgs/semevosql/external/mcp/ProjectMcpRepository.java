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
package cn.lgs.semevosql.external.mcp;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectMcpRepository {

    private final JdbcTemplate jdbc;

    public Optional<ProjectMcpDeployment> findDeploymentByProject(Long projectId) {
        return jdbc.query("SELECT * FROM qw_project_mcp_deployment WHERE project_id = ?", this::mapDeployment, projectId)
            .stream().findFirst();
    }

    public Optional<ProjectMcpDeployment> findDeployment(String deploymentId) {
        return jdbc.query("SELECT * FROM qw_project_mcp_deployment WHERE deployment_id = ?", this::mapDeployment,
                deploymentId).stream().findFirst();
    }

    public List<ProjectMcpDeployment> runningDeployments() {
        return jdbc.query("SELECT * FROM qw_project_mcp_deployment WHERE status = 'RUNNING'", this::mapDeployment);
    }

    public void insertDeployment(ProjectMcpDeployment deployment) {
        jdbc.update("""
                INSERT INTO qw_project_mcp_deployment
                (deployment_id, project_id, principal_id, status, endpoint, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                deployment.status().name(), deployment.endpoint(), deployment.createdBy());
    }

    public void updateStatus(String deploymentId, ProjectMcpDeployment.Status status) {
        jdbc.update("""
                UPDATE qw_project_mcp_deployment
                SET status = ?, update_time = CURRENT_TIMESTAMP
                WHERE deployment_id = ?
                """, status.name(), deploymentId);
    }

    public void markUsed(String deploymentId) {
        jdbc.update("UPDATE qw_project_mcp_deployment SET last_used_time = CURRENT_TIMESTAMP WHERE deployment_id = ?",
                deploymentId);
    }

    public void markRecovered(String deploymentId) {
        jdbc.update("""
                UPDATE qw_project_mcp_deployment
                SET last_recovered_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
                WHERE deployment_id = ?
                """, deploymentId);
    }

    public void insertCredential(String credentialId, String deploymentId, String prefix, String secretHash,
            LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO qw_project_mcp_credential
                (credential_id, deployment_id, token_prefix, secret_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, credentialId, deploymentId, prefix, secretHash, expiresAt);
    }

    public void revokeCredentials(String deploymentId) {
        jdbc.update("""
                UPDATE qw_project_mcp_credential
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE deployment_id = ? AND revoked_at IS NULL
                """, deploymentId);
    }

    public Optional<CredentialRow> findCredentialByPrefix(String prefix) {
        return jdbc.query("""
                SELECT c.credential_id, c.deployment_id, c.token_prefix, c.secret_hash, c.expires_at, c.revoked_at,
                       d.project_id, d.principal_id, d.status, d.endpoint, d.created_by, d.create_time,
                       d.update_time, d.last_used_time, d.last_recovered_time
                FROM qw_project_mcp_credential c
                JOIN qw_project_mcp_deployment d ON d.deployment_id = c.deployment_id
                WHERE c.token_prefix = ?
                """, (rs, rowNum) -> new CredentialRow(rs.getString("credential_id"), rs.getString("token_prefix"),
                rs.getString("secret_hash"), local(rs.getTimestamp("expires_at")), local(rs.getTimestamp("revoked_at")),
                mapDeployment(rs, rowNum)), prefix).stream().findFirst();
    }

    public Optional<ExternalQueryHandle> findHandle(String queryId) {
        return jdbc.query("SELECT * FROM qw_external_query_handle WHERE query_id = ?", this::mapHandle, queryId)
            .stream().findFirst();
    }

    public int reserveHandle(String queryId, String deploymentId, Long projectId, String requestId,
            String idempotencyKey, String requestFingerprint, String originalQuestion) {
        return jdbc.update("""
                INSERT INTO qw_external_query_handle
                (query_id, deployment_id, project_id, state, request_fingerprint, original_question, request_id,
                 idempotency_key)
                VALUES (?, ?, ?, 'RESERVED', ?, ?, ?, ?)
                ON CONFLICT (deployment_id, idempotency_key) DO NOTHING
                """, queryId, deploymentId, projectId, requestFingerprint, originalQuestion, requestId, idempotencyKey);
    }

    public void submitHandle(String queryId, String conversationId, String runId, String episodeId,
            Long semanticVersionId) {
        int updated = jdbc.update("""
                UPDATE qw_external_query_handle
                SET conversation_id = ?, run_id = ?, episode_id = ?, semantic_version_id = ?, state = 'SUBMITTED',
                    last_error = NULL, update_time = CURRENT_TIMESTAMP
                WHERE query_id = ? AND state = 'RESERVED'
                """, conversationId, runId, episodeId, semanticVersionId, queryId);
        if (updated != 1) {
            throw new IllegalStateException("External query handle is not RESERVED: " + queryId);
        }
    }

    public int insertSubmittedHandle(String queryId, String deploymentId, Long projectId, String runId,
            String requestFingerprint, String originalQuestion) {
        return insertSubmittedHandle(queryId, deploymentId, projectId, null, runId, queryId, requestFingerprint,
                requestFingerprint, null, null, originalQuestion);
    }

    public int insertSubmittedHandle(String queryId, String deploymentId, Long projectId, String conversationId,
            String runId, String requestId, String idempotencyKey, String requestFingerprint, String episodeId,
            Long semanticVersionId, String originalQuestion) {
        return jdbc.update("""
                INSERT INTO qw_external_query_handle
                (query_id, deployment_id, project_id, conversation_id, run_id, state, request_fingerprint,
                 original_question, episode_id, request_id, idempotency_key, semantic_version_id)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (deployment_id, idempotency_key) DO NOTHING
                """, queryId, deploymentId, projectId, conversationId, runId, requestFingerprint, originalQuestion,
                episodeId, requestId, idempotencyKey, semanticVersionId);
    }

    public Optional<ExternalQueryHandle> findHandleByIdempotency(String deploymentId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM qw_external_query_handle WHERE deployment_id = ? AND idempotency_key = ?
                """, this::mapHandle, deploymentId, idempotencyKey).stream().findFirst();
    }

    public void failHandle(String queryId, String error) {
        String detail = limit(error, 1024);
        jdbc.update("""
                UPDATE qw_external_query_handle
                SET state = 'FAILED', last_error = ?, update_time = CURRENT_TIMESTAMP
                WHERE query_id = ?
                """, detail, queryId);
    }

    public void audit(String deploymentId, Long projectId, String principalId, String action, String outcome,
            String detail) {
        jdbc.update("""
                INSERT INTO qw_project_mcp_audit(deployment_id, project_id, principal_id, action, outcome, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, deploymentId, projectId, principalId, action, outcome, limit(detail, 1024));
    }

    public McpOperationalStats operationalStats(String deploymentId) {
        LocalDateTime credentialExpiresAt = jdbc.query("""
                SELECT expires_at
                FROM qw_project_mcp_credential
                WHERE deployment_id = ? AND revoked_at IS NULL
                ORDER BY create_time DESC
                LIMIT 1
                """, (rs, rowNum) -> local(rs.getTimestamp("expires_at")), deploymentId)
            .stream().findFirst().orElse(null);
        long[] queryCounts = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (
                         WHERE h.state = 'FAILED'
                            OR (h.state = 'SUBMITTED' AND r.status = 'FAILED')
                       ) AS failed,
                       COUNT(*) FILTER (
                         WHERE h.state = 'SUBMITTED'
                           AND r.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
                       ) AS pending
                FROM qw_external_query_handle h
                LEFT JOIN qw_query_run r ON r.run_id = h.run_id
                WHERE h.deployment_id = ?
                """, (rs, rowNum) -> new long[] { rs.getLong("total"), rs.getLong("failed"), rs.getLong("pending") },
                deploymentId);
        Long auditEvents = jdbc.queryForObject("SELECT COUNT(*) FROM qw_project_mcp_audit WHERE deployment_id = ?",
                Long.class, deploymentId);
        return new McpOperationalStats(credentialExpiresAt, queryCounts == null ? 0 : queryCounts[0],
                queryCounts == null ? 0 : queryCounts[1], queryCounts == null ? 0 : queryCounts[2],
                auditEvents == null ? 0 : auditEvents);
    }

    public List<McpAuditRow> recentAudit(String deploymentId, int limit) {
        return jdbc.query("""
                SELECT action, outcome, detail, create_time
                FROM qw_project_mcp_audit
                WHERE deployment_id = ?
                ORDER BY create_time DESC
                LIMIT ?
                """, (rs, rowNum) -> new McpAuditRow(rs.getString("action"), rs.getString("outcome"),
                rs.getString("detail"), local(rs.getTimestamp("create_time"))), deploymentId, Math.max(1, limit));
    }

    private ProjectMcpDeployment mapDeployment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProjectMcpDeployment(rs.getString("deployment_id"), rs.getLong("project_id"),
                rs.getString("principal_id"), ProjectMcpDeployment.Status.valueOf(rs.getString("status")),
                rs.getString("endpoint"), rs.getString("created_by"), local(rs.getTimestamp("create_time")),
                local(rs.getTimestamp("update_time")), local(rs.getTimestamp("last_used_time")),
                local(rs.getTimestamp("last_recovered_time")));
    }

    private ExternalQueryHandle mapHandle(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExternalQueryHandle(rs.getString("query_id"), rs.getString("deployment_id"), rs.getLong("project_id"),
                rs.getString("conversation_id"), rs.getString("run_id"), rs.getString("episode_id"),
                rs.getString("request_id"), rs.getString("idempotency_key"), nullableLong(rs, "semantic_version_id"),
                rs.getString("request_fingerprint"), rs.getString("state"), rs.getString("last_error"));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String limit(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), limit));
    }

    public record CredentialRow(String credentialId, String tokenPrefix, String secretHash, LocalDateTime expiresAt,
            LocalDateTime revokedAt, ProjectMcpDeployment deployment) {
    }

    public record ExternalQueryHandle(String queryId, String deploymentId, Long projectId, String conversationId,
            String runId, String episodeId, String requestId, String idempotencyKey, Long semanticVersionId,
            String requestFingerprint, String state, String lastError) {
    }

    public record McpOperationalStats(LocalDateTime credentialExpiresAt, long totalQueries, long failedQueries,
            long pendingQueries, long auditEvents) {
    }

    public record McpAuditRow(String action, String outcome, String detail, LocalDateTime createTime) {
    }
}
