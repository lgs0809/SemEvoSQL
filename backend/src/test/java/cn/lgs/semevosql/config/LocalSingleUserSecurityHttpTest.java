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
package cn.lgs.semevosql.config;

import cn.lgs.semevosql.common.LocalHttpAccessPolicy;
import cn.lgs.semevosql.common.LocalSecurityConfiguration;
import cn.lgs.semevosql.common.LocalSecurityProperties;
import cn.lgs.semevosql.common.LocalSessionController;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.OperatorContextProperties;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.security.Principal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/** Exercises the default empty registry and preserves enabled-account fail-closed startup. */
class LocalSingleUserSecurityHttpTest {
    private final ReactiveWebApplicationContextRunner runner =
            new ReactiveWebApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultLoopbackModeStartsWithAnEmptyRegistryAndNoSyntheticLogin() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LocalSecurityProperties.class).getAccounts()).isEmpty();
            var users = context.getBean(MapReactiveUserDetailsService.class);
            assertThat(users.findByUsername("user").block()).isNull();
            assertThat(users.findByUsername("admin").block()).isNull();
            WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build()
                    .get().uri("/api/semevosql/auth/session").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.enabled").isEqualTo(false);
        });
    }

    @Test
    void disabledAccountsAndCallerCredentialsCannotChangeTheSingleUserOperator() {
        runner.withPropertyValues("semevosql.security.enabled=false",
                "semevosql.security.accounts.retained.password-hash=unused-plaintext")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MapReactiveUserDetailsService.class)
                            .findByUsername("retained").block()).isNull();
                    WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build()
                            .get().uri("/api/semevosql/operator-context")
                            .header("X-User-ID", "admin")
                            .headers(headers -> headers.setBasicAuth("retained", "unused-plaintext"))
                            .exchange().expectStatus().isOk().expectBody()
                            .jsonPath("$.operator").isEqualTo("loopback-owner")
                            .jsonPath("$.source").isEqualTo("SELF_HOSTED_SINGLE_USER");
                });
    }

    @Test
    void enabledAuthenticationWithNoAccountsStillRefusesStartup() {
        runner.withPropertyValues("semevosql.security.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("Local security requires configured accounts"));
    }

    @Test
    void enabledAuthenticationWithPlainTextPasswordsStillRefusesStartup() {
        runner.withPropertyValues("semevosql.security.enabled=true",
                "semevosql.security.accounts.admin.password-hash=plaintext",
                "semevosql.security.accounts.admin.administrator=true").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("Local accounts require stable names and encoded passwords"));
    }

    @Configuration
    @EnableWebFlux
    @Import({LocalSecurityConfiguration.class, LocalHttpAccessPolicy.class,
            LocalSessionController.class, OperatorEndpoint.class})
    static class Config {
        @Bean
        ProjectScopeService scope() { return mock(ProjectScopeService.class); }

        @Bean
        OperatorContext.Resolver operator(LocalSecurityProperties security) {
            var properties = new OperatorContextProperties();
            properties.setDefaultOperator("loopback-owner");
            return new OperatorContext.Resolver(properties, security);
        }
    }

    @RestController
    static class OperatorEndpoint {
        private final OperatorContext.Resolver operators;
        OperatorEndpoint(OperatorContext.Resolver operators) { this.operators = operators; }

        @GetMapping("/api/semevosql/operator-context")
        Map<String, Object> operator(@RequestHeader HttpHeaders headers, Principal principal) {
            var operator = operators.resolve(headers, principal, "security-bootstrap-test");
            return Map.of("operator", operator.operator(), "source", operator.source());
        }
    }
}
