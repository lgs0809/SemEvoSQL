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

import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Fail closed for administration APIs; member queries resolve their durable project and owner. */
@Service
public class LocalHttpAccessPolicy {
    private static final Pattern PROJECT = Pattern.compile("^/api/semevosql/(?:operations/)?projects/(\\d+)(?:/.*)?$");
    private static final Pattern RUN = Pattern.compile("^/api/semevosql/runs/([^/]+)(?:/.*)?$");
    private static final Pattern PREFERENCE = Pattern.compile("^/api/semevosql/semantic-preferences/(\\d+)/(?:continue-personal|dismiss-upgrade|promote-project)$");
    private final LocalSecurityProperties properties;
    private final ProjectScopeService scope;

    public LocalHttpAccessPolicy(LocalSecurityProperties properties, ProjectScopeService scope) {
        this.properties = properties; this.scope = scope;
    }

    public Mono<Void> authorize(ServerWebExchange exchange, String principal) {
        return Mono.fromRunnable(() -> {
            var path = exchange.getRequest().getPath().value();
            if (path.startsWith("/api/semevosql/auth/")) return;
            var account = properties.account(principal);
            var operator = new OperatorContext(principal, "AUTHENTICATED", "http-access", "http-access");
            var preference = PREFERENCE.matcher(path);
            if (preference.matches()) {
                scope.requirePreferenceOwner(Long.valueOf(preference.group(1)), operator);
                return;
            }
            // Feedback resolves the Episode to its owned Run in RuntimeMutationScopeService.
            if (HttpMethod.POST.equals(exchange.getRequest().getMethod())
                    && path.matches("^/api/semevosql/operations/episodes/[^/]+/feedback$")) return;
            var run = RUN.matcher(path);
            if (run.matches()) {
                scope.requireRunOwner(run.group(1), operator);
                return;
            }
            var project = PROJECT.matcher(path);
            if (project.matches()) {
                if (path.startsWith("/api/semevosql/operations/") && !account.isAdministrator()) denied();
                var projectId = Long.valueOf(project.group(1));
                scope.requireProject(projectId, operator);
                boolean conversation = path.contains("/conversations");
                if (conversation) {
                    var parts = path.split("/conversations/", 2);
                    if (parts.length == 2) scope.requireConversationOwner(projectId, parts[1].split("/")[0], operator);
                }
                boolean readOnly = HttpMethod.GET.equals(exchange.getRequest().getMethod())
                        || path.endsWith("/semantic-catalog/details");
                if (!readOnly && !conversation && !account.isAdministrator()) denied();
                return;
            }
            if (path.equals("/api/semevosql/operator-context")
                    || path.equals("/api/model-config/check-ready")
                    || (HttpMethod.GET.equals(exchange.getRequest().getMethod())
                        && (path.equals("/api/semevosql/projects") || path.equals("/api/semevosql/projects/health-summary")))) return;
            if (!account.isAdministrator()) denied();
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void denied() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "此操作需要管理员权限");
    }
}
