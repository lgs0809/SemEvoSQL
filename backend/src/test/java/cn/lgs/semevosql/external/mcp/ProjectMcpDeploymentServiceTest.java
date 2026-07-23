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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectMcpDeploymentServiceTest {

	private final ProjectMcpRepository repository = mock(ProjectMcpRepository.class);

	private final ProjectScopeService projectScope = mock(ProjectScopeService.class);

	private final ProjectRuntimeGate runtimeGate = mock(ProjectRuntimeGate.class);

	private final ProjectMcpProperties properties = mock(ProjectMcpProperties.class);

	private final ProjectMcpDeploymentService service = new ProjectMcpDeploymentService(repository, projectScope,
			runtimeGate, properties);

	@Test
	void recoversRunningDeploymentWithoutBrowserMembershipState() {
		ProjectMcpDeployment deployment = deployment("deployment-1", 12L, "integration:deployment-1");
		when(repository.runningDeployments()).thenReturn(List.of(deployment));

		service.recoverActiveDeployments();

		verify(runtimeGate).requireReadyByProject(12L);
		verify(repository).updateStatus("deployment-1", ProjectMcpDeployment.Status.RUNNING);
		verify(repository).markRecovered("deployment-1");
		verify(repository).audit("deployment-1", 12L, "integration:deployment-1", "RECOVER", "SUCCEEDED", null);
	}

	@Test
	void disablesDeploymentWhenProjectIsNoLongerReady() {
		ProjectMcpDeployment deployment = deployment("deployment-2", 13L, "integration:deployment-2");
		when(repository.runningDeployments()).thenReturn(List.of(deployment));
		doThrow(new IllegalStateException("project is not ready")).when(runtimeGate).requireReadyByProject(13L);

		service.recoverActiveDeployments();

		verify(repository).updateStatus("deployment-2", ProjectMcpDeployment.Status.DISABLED);
		verify(repository).audit("deployment-2", 13L, "integration:deployment-2", "RECOVER", "DISABLED",
				"IllegalStateException");
	}

	private ProjectMcpDeployment deployment(String deploymentId, Long projectId, String principalId) {
		return new ProjectMcpDeployment(deploymentId, projectId, principalId, ProjectMcpDeployment.Status.RUNNING,
				"http://127.0.0.1:28065/mcp", "tester", null, null, null, null);
	}
}
