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

import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class McpIntegrationAuthenticator {

    private final ProjectMcpRepository repository;

    private final SemEvoSQLMetrics metrics;

    public Authentication authenticateAuthorization(String authorization) {
        try {
            if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
                throw new McpAuthenticationException("Missing MCP bearer credential");
            }
            Authentication authentication = authenticateToken(authorization.substring(7).trim());
            metrics.mcpRequest("authenticate", "success");
            return authentication;
        }
        catch (RuntimeException ex) {
            metrics.mcpRequest("authenticate", "failure");
            throw ex;
        }
    }

    public Authentication authenticateToken(String token) {
        if (!StringUtils.hasText(token) || !token.startsWith("qwmcp_")) {
            throw new McpAuthenticationException("Invalid MCP bearer credential");
        }
        int prefixStart = "qwmcp_".length();
        int prefixEnd = prefixStart + 12;
        if (token.length() <= prefixEnd || token.charAt(prefixEnd) != '_') {
            throw new McpAuthenticationException("Invalid MCP bearer credential");
        }
        String prefix = token.substring(prefixStart, prefixEnd);
        ProjectMcpRepository.CredentialRow row = repository.findCredentialByPrefix(prefix)
            .orElseThrow(() -> new McpAuthenticationException("Invalid MCP bearer credential"));
        if (row.revokedAt() != null || (row.expiresAt() != null && !row.expiresAt().isAfter(LocalDateTime.now()))) {
            throw new McpAuthenticationException("MCP bearer credential is expired or revoked");
        }
        if (row.deployment().status() != ProjectMcpDeployment.Status.RUNNING) {
            throw new McpAuthenticationException("Project MCP deployment is not running");
        }
        byte[] expected = row.secretHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256(token).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new McpAuthenticationException("Invalid MCP bearer credential");
        }
        repository.markUsed(row.deployment().deploymentId());
        return new Authentication(row.deployment());
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record Authentication(ProjectMcpDeployment deployment) {
    }

    public static class McpAuthenticationException extends SecurityException {

        public McpAuthenticationException(String message) {
            super(message);
        }
    }
}
