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
package cn.lgs.semevosql.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ModelConfigDTOTest {

	@Test
	void builderPreservesConfiguredDefaults() {
		ModelConfigDTO model = ModelConfigDTO.builder()
			.provider("openai-compatible")
			.baseUrl("https://example.invalid/v1")
			.modelName("example-model")
			.modelType("CHAT")
			.build();

		assertEquals(0.0d, model.getTemperature());
		assertEquals(2000, model.getMaxTokens());
		assertEquals(60, model.getRequestTimeoutSeconds());
		assertFalse(model.getIsActive());
		assertEquals(Boolean.FALSE, model.getProxyEnabled());
	}

}
