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
package cn.lgs.semevosql.external.mcp;

import cn.lgs.semevosql.external.mcp.McpIntegrationAuthenticator.McpAuthenticationException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class ProjectMcpAuthenticationWebFilter implements WebFilter {

    private final ProjectMcpProperties properties;

    private final McpIntegrationAuthenticator authenticator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!matches(exchange)) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return Mono.fromCallable(() -> authenticator.authenticateAuthorization(authorization))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(ignored -> chain.filter(exchange))
            .onErrorResume(McpAuthenticationException.class, ex -> unauthorized(exchange, ex.getMessage()));
    }

    private boolean matches(ServerWebExchange exchange) {
        String configured = properties.getEndpointPath();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String normalized = StringUtils.hasText(configured) && configured.startsWith("/") ? configured : "/" + configured;
        return path.equals(normalized) || path.startsWith(normalized + "/");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        byte[] bytes = ("{\"error\":\"unauthorized\",\"message\":\"" + sanitize(message) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "Invalid MCP credential";
        }
        return message.replace("\"", "'");
    }
}
