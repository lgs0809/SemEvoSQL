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
import cn.lgs.semevosql.external.mcp.ProjectMcpDeploymentService.DeploymentCredential;
import cn.lgs.semevosql.external.mcp.ProjectMcpDeploymentService.OperationsView;
import cn.lgs.semevosql.external.mcp.ProjectMcpDeploymentService.TestResult;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/mcp-deployment")
@RequiredArgsConstructor
public class ProjectMcpDeploymentController {

    private final ProjectMcpDeploymentService service;

    private final OperatorContext.Resolver operatorResolver;

    @GetMapping
    public ProjectMcpDeployment get(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.get(projectId, operatorResolver.resolve(headers, principal, "mcp-deployment-get:" + projectId));
    }

    @PostMapping("/deploy")
    public DeploymentCredential deploy(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.deploy(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-deploy:" + projectId));
    }

    @PostMapping("/enable")
    public ProjectMcpDeployment enable(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.enable(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-enable:" + projectId));
    }

    @PostMapping("/disable")
    public ProjectMcpDeployment disable(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.disable(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-disable:" + projectId));
    }

    @PostMapping("/rotate-credential")
    public DeploymentCredential rotate(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.rotate(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-rotate:" + projectId));
    }

    @PostMapping("/test")
    public TestResult test(@PathVariable Long projectId, @RequestHeader HttpHeaders headers, Principal principal) {
        return service.test(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-test:" + projectId));
    }

    @GetMapping("/operations")
    public OperationsView operations(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        return service.operations(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-operations:" + projectId));
    }

    @DeleteMapping
    public void revoke(@PathVariable Long projectId, @RequestHeader HttpHeaders headers, Principal principal) {
        service.revoke(projectId,
                operatorResolver.resolve(headers, principal, "mcp-deployment-revoke:" + projectId));
    }
}
