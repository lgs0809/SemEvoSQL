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

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Validates server-resolved identity while retaining the optional single-user deployment mode. */
@Service
public class LocalOperatorService {
    private final LocalSecurityProperties security;

    public LocalOperatorService() { this(new LocalSecurityProperties()); }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalOperatorService(LocalSecurityProperties security) { this.security = security; }

	public OperatorContext require(OperatorContext operator, String operation) {
		if (operator == null || !StringUtils.hasText(operator.operator())) {
			throw new IllegalStateException("Local operator is required for " + operation);
		}
        if (security.isEnabled() && !"SYSTEM".equals(operator.source())) {
            if (!"AUTHENTICATED".equals(operator.source())) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "需要可信用户身份");
            security.account(operator.operator());
        }
		return operator;
	}

    public boolean canAccess(OperatorContext operator, Long projectId) {
        require(operator, "access Project");
        return "SYSTEM".equals(operator.source()) || security.canAccess(operator.operator(), projectId);
    }

    public boolean owns(OperatorContext operator, String owner) {
        require(operator, "access private query");
        return !security.isEnabled() || "SYSTEM".equals(operator.source()) || operator.operator().equals(owner);
    }

    public boolean authenticatedAccountsEnabled() { return security.isEnabled(); }

}
