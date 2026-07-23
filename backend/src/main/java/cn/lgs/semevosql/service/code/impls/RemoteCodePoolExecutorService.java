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
package cn.lgs.semevosql.service.code.impls;

import cn.lgs.semevosql.properties.CodeExecutorProperties;
import cn.lgs.semevosql.service.code.CodePoolExecutorService;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/** Delegates generated-code execution to the isolated internal execution worker. */
public class RemoteCodePoolExecutorService implements CodePoolExecutorService {

	public static final String EXECUTION_PATH = "/internal/code-execution/tasks";

	private final WebClient webClient;

	private final String credential;

	private final Duration timeout;

	public RemoteCodePoolExecutorService(CodeExecutorProperties properties) {
		if (!StringUtils.hasText(properties.getRemoteUrl())) {
			throw new IllegalStateException("Remote code executor URL must be configured");
		}
		if (!StringUtils.hasText(properties.getInternalToken())) {
			throw new IllegalStateException("Remote code executor credential must be configured");
		}
		this.webClient = WebClient.builder().baseUrl(properties.getRemoteUrl().trim()).build();
		this.credential = properties.getInternalToken().trim();
		this.timeout = Duration.ofMillis(Math.max(1000L, properties.getContainerTimeout() * 1000L));
	}

	@Override
	public TaskResponse runTask(TaskRequest request) {
		if (request == null) {
			return TaskResponse.exception("Execution request must not be null");
		}
		try {
			TaskResponse response = webClient.post()
				.uri(EXECUTION_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(request)
				.retrieve()
				.bodyToMono(TaskResponse.class)
				.block(timeout);
			return response == null ? TaskResponse.exception("Execution worker returned an empty response") : response;
		}
		catch (Exception ex) {
			String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
			return TaskResponse.exception("Execution worker request failed: " + message);
		}
	}

}
