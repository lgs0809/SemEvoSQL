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
package cn.lgs.semevosql.service.llm;

import cn.lgs.semevosql.util.ChatResponseUtil;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface LlmService {

	Flux<ChatResponse> call(String system, String user);

	default Flux<ChatResponse> call(String system, String user, LlmInvocationOptions options) {
		return call(system, user);
	}

	/**
	 * Executes a call inside the remaining caller budget. Implementations may still use a blocking provider under the
	 * Flux; the timeout prevents the Graph from accepting a late response, while its durable fence protects writes.
	 */
	default Flux<ChatResponse> callWithin(String system, String user, Duration budget) {
		Flux<ChatResponse> source = call(system, user);
		return budget == null ? source : source.timeout(budget);
	}

	default Flux<ChatResponse> callWithin(String system, String user, LlmInvocationOptions options,
			Duration budget) {
		Flux<ChatResponse> source = call(system, user, options);
		return budget == null ? source : source.timeout(budget);
	}

	default boolean supportsInvocationOptions(LlmInvocationOptions options) {
		return options == null || options.empty();
	}

	Flux<ChatResponse> callSystem(String system);

	Flux<ChatResponse> callUser(String user);

	default Flux<ChatResponse> callUserWithin(String user, Duration budget) {
		Flux<ChatResponse> source = callUser(user);
		return budget == null ? source : source.timeout(budget);
	}

	default Flux<ChatResponse> callSystemWithin(String system, Duration budget) {
		Flux<ChatResponse> source = callSystem(system);
		return budget == null ? source : source.timeout(budget);
	}

	default Flux<String> toStringFlux(Flux<ChatResponse> responseFlux) {
		return responseFlux.map(ChatResponseUtil::getText);
	}

}
