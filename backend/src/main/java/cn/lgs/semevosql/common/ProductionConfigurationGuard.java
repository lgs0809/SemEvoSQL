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
package cn.lgs.semevosql.common;

import cn.lgs.semevosql.enums.CodePoolExecutorEnum;
import cn.lgs.semevosql.properties.CodeExecutorProperties;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Rejects development-only or unsafe defaults when the prod profile is active. */
@Component
@Profile("prod")
public class ProductionConfigurationGuard implements ApplicationRunner {

	private final CodeExecutorProperties codeExecutorProperties;

	private final Environment environment;

	public ProductionConfigurationGuard(CodeExecutorProperties codeExecutorProperties, Environment environment) {
		this.codeExecutorProperties = codeExecutorProperties;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		CodePoolExecutorEnum executor = codeExecutorProperties.getCodePoolExecutor();
		String runtimeRole = environment.getProperty("semevosql.runtime-role", "application").trim();
		if (executor == CodePoolExecutorEnum.LOCAL) {
			throw invalid("local code execution is forbidden; use the isolated execution worker");
		}
		if (executor == CodePoolExecutorEnum.DOCKER && !"execution-worker".equals(runtimeRole)) {
			throw invalid("the Docker executor may run only in the execution-worker process");
		}
		if ("execution-worker".equals(runtimeRole) && executor != CodePoolExecutorEnum.DOCKER) {
			throw invalid("the execution-worker process must use the Docker executor");
		}
		if (executor == CodePoolExecutorEnum.DOCKER) {
			String imageName = requireText(codeExecutorProperties.getImageName(), "execution runner image");
			if (!"semevosql/python-runner:1.0.0".equals(imageName)) {
				throw invalid("execution runner image must be the release-pinned semevosql/python-runner:1.0.0 image");
			}
			if (!"none".equalsIgnoreCase(codeExecutorProperties.getNetworkMode())) {
				throw invalid("execution containers must use network mode none");
			}
			if (!Boolean.TRUE.equals(codeExecutorProperties.getReadOnlyRootFilesystem())) {
				throw invalid("execution containers must use a read-only root filesystem");
			}
			String runAsUser = requireText(codeExecutorProperties.getRunAsUser(), "execution container user");
			if (runAsUser.startsWith("0:") || "0".equals(runAsUser)) {
				throw invalid("execution containers must not run as root");
			}
		}
		if (executor == CodePoolExecutorEnum.REMOTE) {
			requireText(codeExecutorProperties.getRemoteUrl(), "remote code executor URL");
		}
		if (executor == CodePoolExecutorEnum.REMOTE || "execution-worker".equals(runtimeRole)) {
			String credential = requireText(codeExecutorProperties.getInternalToken(),
					"execution broker internal credential");
			if (credential.length() < 32) {
				throw invalid("execution broker internal credential must contain at least 32 characters");
			}
		}
		boolean projectMcpEnabled = Boolean.parseBoolean(environment.getProperty("semevosql.mcp.enabled", "false"));
		boolean springMcpEnabled = Boolean.parseBoolean(environment.getProperty("spring.ai.mcp.server.enabled", "false"));
		if (projectMcpEnabled != springMcpEnabled) {
			throw invalid("SemEvoSQL and Spring AI MCP server enablement must match");
		}
		if (projectMcpEnabled) {
			if (!"STREAMABLE".equalsIgnoreCase(environment.getProperty("spring.ai.mcp.server.protocol", ""))) {
				throw invalid("production Project MCP must use Remote Streamable HTTP");
			}
			if (Boolean.parseBoolean(environment.getProperty("spring.ai.mcp.server.tool-callback-converter", "true"))) {
				throw invalid("automatic ToolCallback conversion must be disabled for the production MCP surface");
			}
			if (Boolean.parseBoolean(environment.getProperty("spring.ai.mcp.server.annotation-scanner.enabled", "true"))) {
				throw invalid("MCP annotation scanning must be disabled for the production MCP surface");
			}
			if (!"/mcp".equals(environment.getProperty("semevosql.mcp.external.endpoint-path", "/mcp"))) {
				throw invalid("production Project MCP endpoint must remain /mcp");
			}
		}
		required("semevosql.secrets.encryption-key");
		if (!Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "true"))) {
			throw invalid("spring.flyway.enabled must be true");
		}
		String sqlInitMode = environment.getProperty("spring.sql.init.mode", "never");
		if (!"never".equalsIgnoreCase(sqlInitMode)) {
			throw invalid("spring.sql.init.mode must be never; Flyway owns production schema changes");
		}
		String datasourceUrl = required("spring.datasource.url").toLowerCase(Locale.ROOT);
		String datasourceUser = required("spring.datasource.username");
		required("spring.datasource.password");
		if ("root".equalsIgnoreCase(datasourceUser.trim())) {
			throw invalid("the metadata datasource must not use the root account");
		}
		if (datasourceUrl.contains("allowmultiqueries=true")) {
			throw invalid("allowMultiQueries=true is forbidden for the metadata datasource");
		}
		if (datasourceUrl.contains("usessl=false") || datasourceUrl.contains("sslmode=disable")) {
			throw invalid("database transport encryption must not be disabled");
		}
	}

	private String requireText(String value, String description) {
		if (!StringUtils.hasText(value)) {
			throw invalid(description + " is required");
		}
		return value.trim();
	}

	private String required(String property) {
		String value = environment.getProperty(property);
		if (!StringUtils.hasText(value)) {
			throw invalid(property + " is required");
		}
		return value.trim();
	}

	private IllegalStateException invalid(String message) {
		return new IllegalStateException("Unsafe SemEvoSQL production configuration: " + message);
	}

}
