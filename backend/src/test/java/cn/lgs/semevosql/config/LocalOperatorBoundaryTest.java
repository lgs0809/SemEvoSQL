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

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.OperatorContextProperties;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Contract test for the intentionally single-user HTTP boundary. */
class LocalOperatorBoundaryTest {

	@Test
	void configuredLocalOperatorIsAlwaysUsedInsteadOfExternalPrincipal() {
		OperatorContextProperties properties = new OperatorContextProperties();
		properties.setDefaultOperator("local-owner");
		OperatorContext.Resolver resolver = new OperatorContext.Resolver(properties);
		Principal unrelatedExternalIdentity = () -> "external-user";

		OperatorContext resolved = resolver.resolve(HttpHeaders.EMPTY, unrelatedExternalIdentity, "test-operation");

		assertThat(resolved.operator()).isEqualTo("local-owner");
		assertThat(resolved.source()).isEqualTo("SELF_HOSTED_SINGLE_USER");
	}

	@Test
	void idempotencyAndRequestHeadersRemainPartOfTheGovernedMutationEnvelope() {
		OperatorContext.Resolver resolver = new OperatorContext.Resolver(new OperatorContextProperties());
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Request-ID", "request-1");
		headers.add("Idempotency-Key", "mutation-1");

		OperatorContext resolved = resolver.resolve(headers, null, "test-operation");

		assertThat(resolved.requestId()).isEqualTo("request-1");
		assertThat(resolved.idempotencyKey()).isEqualTo("mutation-1");
	}
}
