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

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * One-cycle compatibility adapter for the public QueryWeaver v1 API namespace.
 *
 * <p>Application code and OpenAPI only expose {@code /api/semevosql/**}. Legacy callers are
 * transparently routed to the same handlers before security and handler mapping run.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyApiCompatibilityWebFilter implements WebFilter {

    static final String LEGACY_PREFIX = "/api/queryweaver";

    static final String PRIMARY_PREFIX = "/api/semevosql";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!legacyPath(path)) {
            return chain.filter(exchange);
        }
        String rewritten = PRIMARY_PREFIX + path.substring(LEGACY_PREFIX.length());
        ServerWebExchange compatible = exchange.mutate()
            .request(exchange.getRequest().mutate().path(rewritten).build())
            .build();
        compatible.getResponse().getHeaders().set("Deprecation", "true");
        compatible.getResponse().getHeaders().add(HttpHeaders.LINK,
                "</api/semevosql>; rel=\"successor-version\"");
        return chain.filter(compatible);
    }

    private boolean legacyPath(String path) {
        return LEGACY_PREFIX.equals(path) || path.startsWith(LEGACY_PREFIX + "/");
    }
}
