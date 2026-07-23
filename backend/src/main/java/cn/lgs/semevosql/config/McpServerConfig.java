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
package cn.lgs.semevosql.config;

import cn.lgs.semevosql.annotation.McpServerTool;
import cn.lgs.semevosql.external.mcp.ExternalProjectMcpTools;
import cn.lgs.semevosql.external.mcp.ProjectMcpDeploymentService;
import cn.lgs.semevosql.external.mcp.ProjectMcpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import java.util.Arrays;
import java.util.Map;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(name = "semevosql.mcp.enabled", havingValue = "true")
public class McpServerConfig {

	@Bean
	@McpServerTool
	public ToolCallbackProvider projectMcpTools(ExternalProjectMcpTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build();
	}

	@Bean
	public java.util.List<McpServerFeatures.AsyncToolSpecification> projectMcpToolSpecifications(
			ToolCallbackProvider projectMcpTools) {
		return McpToolUtils.toAsyncToolSpecifications(Arrays.asList(projectMcpTools.getToolCallbacks()));
	}

	@Bean
	public WebFluxStreamableServerTransportProvider semEvoSQLMcpTransport(ObjectMapper objectMapper,
			ProjectMcpProperties properties) {
		return WebFluxStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(objectMapper))
			.messageEndpoint(properties.getEndpointPath())
			.contextExtractor(request -> {
				String authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
				return StringUtils.hasText(authorization)
						? McpTransportContext.create(Map.of(ExternalProjectMcpTools.authorizationContextKey(), authorization))
						: McpTransportContext.EMPTY;
			})
			.build();
	}

	@Bean
	public ApplicationRunner projectMcpDeploymentRecovery(ProjectMcpDeploymentService deploymentService) {
		return arguments -> deploymentService.recoverActiveDeployments();
	}

}
