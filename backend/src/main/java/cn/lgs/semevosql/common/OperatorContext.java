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

        private final LocalSecurityProperties security;

		public Resolver() {
			this(new OperatorContextProperties(), new LocalSecurityProperties());
		}

		public Resolver(OperatorContextProperties properties) {
            this(properties, new LocalSecurityProperties());
		}

        @Autowired
        public Resolver(OperatorContextProperties properties, LocalSecurityProperties security) {
            this.properties = properties; this.security = security;
        }

        public boolean accountsEnabled() { return security.isEnabled(); }

        public boolean administrator(OperatorContext operator) {
            return !security.isEnabled() || security.account(operator.operator()).isAdministrator();
        }

		/**
		 * Single-user deployments retain the local operator; enabled account deployments use only the
         * authenticated Principal. Caller-supplied user headers never determine identity.
		 */
		public OperatorContext resolve(HttpHeaders headers, Principal principal, String operation) {
			String requestId = header(headers, "X-Request-ID", UUID.randomUUID().toString());
			String idempotencyKey = header(headers, "Idempotency-Key", operation + ":" + requestId);
            if (security.isEnabled()) {
                if (principal == null) throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
                security.account(principal.getName());
                return new OperatorContext(principal.getName(), "AUTHENTICATED", requestId, idempotencyKey);
            }
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
