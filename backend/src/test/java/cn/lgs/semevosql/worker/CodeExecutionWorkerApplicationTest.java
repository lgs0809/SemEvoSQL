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
package cn.lgs.semevosql.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.properties.CodeExecutorProperties;
import cn.lgs.semevosql.service.code.CodePoolExecutorService;
import cn.lgs.semevosql.service.code.CodePoolExecutorService.TaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodeExecutionWorkerApplicationTest {

	private static final String INTERNAL_CREDENTIAL = "x".repeat(40);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void rejectsRuntimePackageInstallationBeforeExecutorRuns() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		CodePoolExecutorService executor = request -> {
			executions.incrementAndGet();
			return TaskResponse.success("ok");
		};
		try (CodeExecutionWorkerApplication worker = new CodeExecutionWorkerApplication(properties(), executor,
				objectMapper)) {
			int port = worker.start(0);
			HttpResponse<String> response = send(port,
					new CodePoolExecutorService.TaskRequest("print('ok')", "[]", "requests==2.0.0"));

			assertThat(response.statusCode()).isEqualTo(400);
			assertThat(response.body()).contains("Runtime package installation is disabled");
			assertThat(executions).hasValue(0);
		}
	}

	@Test
	void acceptsPinnedDependencyFreeExecutionRequest() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		CodePoolExecutorService executor = request -> {
			executions.incrementAndGet();
			return TaskResponse.success("{\"ok\":true}");
		};
		try (CodeExecutionWorkerApplication worker = new CodeExecutionWorkerApplication(properties(), executor,
				objectMapper)) {
			int port = worker.start(0);
			HttpResponse<String> response = send(port,
					new CodePoolExecutorService.TaskRequest("print('ok')", "[]", ""));

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("{\\\"ok\\\":true}");
			assertThat(executions).hasValue(1);
		}
	}

	@Test
	void rejectsUnsafeWorkerImageBeforeServingTraffic() {
		CodeExecutorProperties properties = properties();
		properties.setImageName("example/python:latest");

		assertThatThrownBy(() -> new CodeExecutionWorkerApplication(properties, request -> TaskResponse.success("ok"),
				objectMapper)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("semevosql/python-runner:1.0.0");
	}

	private CodeExecutorProperties properties() {
		return CodeExecutionWorkerApplication.loadProperties(
				Map.of("SEMEVOSQL_EXECUTION_INTERNAL_TOKEN", INTERNAL_CREDENTIAL));
	}

	private HttpResponse<String> send(int port, CodePoolExecutorService.TaskRequest task) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/code-execution/tasks"))
			.timeout(Duration.ofSeconds(5))
			.header("Authorization", "Bearer " + INTERNAL_CREDENTIAL)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(task)))
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}
}
