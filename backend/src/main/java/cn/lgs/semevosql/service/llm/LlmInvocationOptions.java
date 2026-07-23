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

import org.springframework.util.StringUtils;

/** Provider-neutral per-call model options used by governed SemEvoSQL invocations. */
public record LlmInvocationOptions(String modelOverride, String reasoningEffort) {

	public static LlmInvocationOptions none() {
		return new LlmInvocationOptions(null, null);
	}

	public boolean requestsReasoning() {
		return StringUtils.hasText(reasoningEffort);
	}

	public boolean empty() {
		return !StringUtils.hasText(modelOverride) && !StringUtils.hasText(reasoningEffort);
	}

}
