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

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class LegacyApiCompatibilityWebFilterTest {

    private final LegacyApiCompatibilityWebFilter filter = new LegacyApiCompatibilityWebFilter();

    @Test
    void rewritesLegacyApiBeforeHandlerMappingAndPreservesQuery() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/queryweaver/projects/12/semantic-readiness?detail=true").build());
        AtomicReference<ServerWebExchange> observed = new AtomicReference<>();

        filter.filter(exchange, value -> {
            observed.set(value);
            return Mono.empty();
        }).block();

        assertThat(observed.get().getRequest().getURI().getRawPath())
            .isEqualTo("/api/semevosql/projects/12/semantic-readiness");
        assertThat(observed.get().getRequest().getQueryParams().getFirst("detail")).isEqualTo("true");
        assertThat(observed.get().getResponse().getHeaders().getFirst("Deprecation")).isEqualTo("true");
    }

    @Test
    void leavesPrimaryApiUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/semevosql/projects/12").build());
        AtomicReference<ServerWebExchange> observed = new AtomicReference<>();

        filter.filter(exchange, value -> {
            observed.set(value);
            return Mono.empty();
        }).block();

        assertThat(observed.get().getRequest().getURI().getRawPath()).isEqualTo("/api/semevosql/projects/12");
        assertThat(observed.get().getResponse().getHeaders().containsKey("Deprecation")).isFalse();
    }
}
