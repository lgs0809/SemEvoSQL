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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectMcpDeploymentService {

    private final ProjectMcpRepository repository;

    private final ProjectScopeService projectScope;

    private final ProjectRuntimeGate runtimeGate;

    private final ProjectMcpProperties properties;

    private final SecureRandom random = new SecureRandom();

    @Transactional
    public DeploymentCredential deploy(Long projectId, OperatorContext operator) {
        projectScope.requireProject(projectId, operator);
        runtimeGate.requireReadyByProject(projectId);
        ProjectMcpDeployment existing = repository.findDeploymentByProject(projectId).orElse(null);
        if (existing != null && existing.status() != ProjectMcpDeployment.Status.REVOKED) {
            throw new IllegalStateException("Project MCP deployment already exists");
        }

        ProjectMcpDeployment deployment;
        if (existing == null) {
            String deploymentId = UUID.randomUUID().toString();
            String principalId = "integration:" + deploymentId;
            deployment = new ProjectMcpDeployment(deploymentId, projectId, principalId,
                    ProjectMcpDeployment.Status.RUNNING, properties.endpoint(), operator.operator(), null, null, null, null);
            repository.insertDeployment(deployment);
        }
        else {
            repository.updateStatus(existing.deploymentId(), ProjectMcpDeployment.Status.RUNNING);
            deployment = repository.findDeployment(existing.deploymentId()).orElseThrow();
        }

        String token = issueCredential(deployment.deploymentId());
        repository.audit(deployment.deploymentId(), projectId, deployment.principalId(), "DEPLOY", "SUCCEEDED", null);
        return credentialResponse(repository.findDeployment(deployment.deploymentId()).orElseThrow(), token);
    }

    public ProjectMcpDeployment get(Long projectId, OperatorContext operator) {
        projectScope.requireProject(projectId, operator);
        return repository.findDeploymentByProject(projectId).orElse(null);
    }

    @Transactional
    public ProjectMcpDeployment enable(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        if (deployment.status() == ProjectMcpDeployment.Status.REVOKED) {
            throw new IllegalStateException("Revoked deployment must be deployed again to issue a new credential");
        }
        runtimeGate.requireReadyByProject(projectId);
        repository.updateStatus(deployment.deploymentId(), ProjectMcpDeployment.Status.RUNNING);
        repository.audit(deployment.deploymentId(), projectId, deployment.principalId(), "ENABLE", "SUCCEEDED", null);
        return repository.findDeployment(deployment.deploymentId()).orElseThrow();
    }

    @Transactional
    public ProjectMcpDeployment disable(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        repository.updateStatus(deployment.deploymentId(), ProjectMcpDeployment.Status.DISABLED);
        repository.audit(deployment.deploymentId(), projectId, deployment.principalId(), "DISABLE", "SUCCEEDED", null);
        return repository.findDeployment(deployment.deploymentId()).orElseThrow();
    }

    @Transactional
    public DeploymentCredential rotate(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        if (deployment.status() == ProjectMcpDeployment.Status.REVOKED) {
            throw new IllegalStateException("Project MCP deployment is revoked");
        }
        repository.revokeCredentials(deployment.deploymentId());
        String token = issueCredential(deployment.deploymentId());
        repository.audit(deployment.deploymentId(), projectId, deployment.principalId(), "ROTATE_CREDENTIAL",
                "SUCCEEDED", null);
        return credentialResponse(repository.findDeployment(deployment.deploymentId()).orElseThrow(), token);
    }

    @Transactional
    public void revoke(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        repository.revokeCredentials(deployment.deploymentId());
        repository.updateStatus(deployment.deploymentId(), ProjectMcpDeployment.Status.REVOKED);
        repository.audit(deployment.deploymentId(), projectId, deployment.principalId(), "REVOKE", "SUCCEEDED", null);
    }

    public TestResult test(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        runtimeGate.requireReadyByProject(projectId);
        boolean running = deployment.status() == ProjectMcpDeployment.Status.RUNNING;
        return new TestResult(running, running, true, deployment.endpoint());
    }

    public OperationsView operations(Long projectId, OperatorContext operator) {
        ProjectMcpDeployment deployment = requireManaged(projectId, operator);
        ProjectMcpRepository.McpOperationalStats stats = repository.operationalStats(deployment.deploymentId());
        return new OperationsView(stats.credentialExpiresAt(), stats.totalQueries(), stats.failedQueries(),
                stats.pendingQueries(), stats.auditEvents(), repository.recentAudit(deployment.deploymentId(), 10));
    }

    @Transactional
    public void recoverActiveDeployments() {
        for (ProjectMcpDeployment deployment : repository.runningDeployments()) {
            try {
                runtimeGate.requireReadyByProject(deployment.projectId());
                repository.updateStatus(deployment.deploymentId(), ProjectMcpDeployment.Status.RUNNING);
                repository.markRecovered(deployment.deploymentId());
                repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(), "RECOVER",
                        "SUCCEEDED", null);
            }
            catch (RuntimeException ex) {
                repository.updateStatus(deployment.deploymentId(), ProjectMcpDeployment.Status.DISABLED);
                repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(), "RECOVER",
                        "DISABLED", ex.getClass().getSimpleName());
            }
        }
    }

    private ProjectMcpDeployment requireManaged(Long projectId, OperatorContext operator) {
        projectScope.requireProject(projectId, operator);
        return repository.findDeploymentByProject(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project MCP deployment does not exist"));
    }

    private String issueCredential(String deploymentId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        byte[] prefixBytes = new byte[9];
        random.nextBytes(prefixBytes);
        String prefix = Base64.getUrlEncoder().withoutPadding().encodeToString(prefixBytes);
        String token = "qwmcp_" + prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        LocalDateTime expiresAt = properties.getCredentialTtlDays() <= 0 ? null
                : LocalDateTime.now().plusDays(properties.getCredentialTtlDays());
        repository.insertCredential(UUID.randomUUID().toString(), deploymentId, prefix,
                McpIntegrationAuthenticator.sha256(token), expiresAt);
        return token;
    }

    private DeploymentCredential credentialResponse(ProjectMcpDeployment deployment, String token) {
        return new DeploymentCredential(deployment, token,
                new ConnectionConfig(deployment.endpoint(), "Authorization", "Bearer " + token,
                        "Remote Streamable HTTP Episode mode. Use query to create, continue or branch a durable Episode and query_status to read durable progress/results. New Episodes bind the project's current Active Semantic Version; existing Episodes stay pinned across semantic upgrades, so MCP does not require redeployment."));
    }

    public record DeploymentCredential(ProjectMcpDeployment deployment, String credential, ConnectionConfig config) {
    }

    public record ConnectionConfig(String endpoint, String header, String headerValue, String usage) {
    }

    public record TestResult(boolean ok, boolean running, boolean bindingCurrent, String endpoint) {
    }

    public record OperationsView(LocalDateTime credentialExpiresAt, long totalQueries, long failedQueries,
            long pendingQueries, long auditEvents, List<ProjectMcpRepository.McpAuditRow> recentAudit) {
    }
}
