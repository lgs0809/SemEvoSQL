/*
 * Copyright 2026 the original author or authors.
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Small self-hosted account registry. Identity and project access come only from server configuration. */
@Data
@ConfigurationProperties(prefix = "semevosql.security")
public class LocalSecurityProperties {
    private boolean enabled;
    private Map<String, Account> accounts = new LinkedHashMap<>();

    @Data
    public static class Account {
        private String passwordHash;
        private boolean administrator;
        private List<Long> projectIds = List.of();
    }

    public Account account(String name) {
        var account = accounts.get(name);
        if (account == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "当前用户已无访问权限");
        return account;
    }

    public boolean canAccess(String name, Long project) {
        if (!enabled) return true;
        var account = account(name);
        // An explicitly scoped administrator cannot escape that scope by having the administrator role.
        // Existing workspace administrators with no project restriction retain their configured global role.
        return account.getProjectIds().contains(project) || (account.isAdministrator() && account.getProjectIds().isEmpty());
    }
}
