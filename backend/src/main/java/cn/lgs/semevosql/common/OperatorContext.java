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

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Server-resolved local operator and request envelope for governed mutations. */
public record OperatorContext(String operator, String source, String requestId, String idempotencyKey) {

	public OperatorContext {
		if (!StringUtils.hasText(operator) || !StringUtils.hasText(source)
				|| !StringUtils.hasText(requestId) || !StringUtils.hasText(idempotencyKey)) {
			throw new IllegalArgumentException("Operator identity, source, requestId and idempotencyKey are required");
		}
	}

	public static OperatorContext system(String operation) {
		String requestId = UUID.randomUUID().toString();
		return new OperatorContext("semevosql-system", "SYSTEM", requestId, operation + ":" + requestId);
	}

	@Component
	public static class Resolver {

		private final OperatorContextProperties properties;

		public Resolver() {
			this(new OperatorContextProperties());
		}

		@Autowired
		public Resolver(OperatorContextProperties properties) {
			this.properties = properties;
		}

		/**
		 * Resolve every HTTP request to the configured local operator. The Principal parameter is retained only
		 * for controller signature compatibility; SemEvoSQL intentionally has no browser account boundary.
		 */
		public OperatorContext resolve(HttpHeaders headers, Principal principal, String operation) {
			String requestId = header(headers, "X-Request-ID", UUID.randomUUID().toString());
			String idempotencyKey = header(headers, "Idempotency-Key", operation + ":" + requestId);
			String operator = required(properties.getDefaultOperator(), "semevosql.operator.default-operator");
			return new OperatorContext(operator, "SELF_HOSTED_SINGLE_USER", requestId, idempotencyKey);
		}

		private String header(HttpHeaders headers, String name, String fallback) {
			String value = headers == null ? null : headers.getFirst(name);
			return StringUtils.hasText(value) ? value.trim() : Objects.requireNonNull(fallback);
		}

		private String required(String value, String field) {
			if (!StringUtils.hasText(value)) {
				throw new IllegalStateException(field + " is required");
			}
			return value.trim();
		}
	}
}
