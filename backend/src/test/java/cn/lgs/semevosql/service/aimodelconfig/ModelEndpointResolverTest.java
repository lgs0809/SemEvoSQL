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
package cn.lgs.semevosql.service.aimodelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelEndpointResolverTest {

	@Test
	void preservesBaseContextPath() {
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve(
				"https://dashscope.example/compatible-mode", null, "/v1/chat/completions");

		assertThat(endpoint.baseUrl()).isEqualTo("https://dashscope.example");
		assertThat(endpoint.path()).isEqualTo("/compatible-mode/v1/chat/completions");
	}

	@Test
	void removesOverlappingVersionSegments() {
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve("https://api.example/v1/", null,
				"/v1/embeddings");

		assertThat(endpoint.baseUrl()).isEqualTo("https://api.example");
		assertThat(endpoint.path()).isEqualTo("/v1/embeddings");
	}

	@Test
	void removesLongestOverlapAcrossContextAndEndpoint() {
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve("https://api.example/compatible/v1",
				"v1/rerank", "/v1/rerank");

		assertThat(endpoint.path()).isEqualTo("/compatible/v1/rerank");
	}

	@Test
	void rejectsUnsafeOrAmbiguousEndpointConfiguration() {
		assertThatThrownBy(() -> ModelEndpointResolver.resolve("file:///tmp/model", null, "/v1/embeddings"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("http or https");
		assertThatThrownBy(() -> ModelEndpointResolver.resolve("https://user:pass@example.test", null,
				"/v1/embeddings"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("user information");
		assertThatThrownBy(() -> ModelEndpointResolver.resolve("https://example.test", "https://other.test/rerank",
				"/v1/rerank"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be a path");
	}

}
