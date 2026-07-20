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
package cn.lgs.semevosql.service.llm.impls;

import cn.lgs.semevosql.service.aimodelconfig.AiModelRegistry;
import cn.lgs.semevosql.service.llm.LlmInvocationOptions;
import cn.lgs.semevosql.service.llm.LlmService;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class StreamLlmService implements LlmService {

	private final AiModelRegistry registry;

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		return registry.getChatClient().prompt().system(system).user(user).stream().chatResponse();
	}

	@Override
	public Flux<ChatResponse> call(String system, String user, LlmInvocationOptions invocationOptions) {
		if (invocationOptions == null || invocationOptions.empty()) {
			return call(system, user);
		}
		var options = OpenAiChatOptions.builder();
		if (invocationOptions.modelOverride() != null && !invocationOptions.modelOverride().isBlank()) {
			options.model(invocationOptions.modelOverride());
		}
		if (invocationOptions.reasoningEffort() != null && !invocationOptions.reasoningEffort().isBlank()) {
			options.reasoningEffort(invocationOptions.reasoningEffort());
		}
		return registry.getChatClient()
			.prompt()
			.system(system)
			.user(user)
			.options(options.build())
			.stream()
			.chatResponse();
	}

	@Override
	public boolean supportsInvocationOptions(LlmInvocationOptions options) {
		return true;
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		return registry.getChatClient().prompt().system(system).stream().chatResponse();
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		return registry.getChatClient().prompt().user(user).stream().chatResponse();
	}

}
