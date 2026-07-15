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

import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/** Real WebFlux filter chain, password authentication, session cookies and CSRF validation. */
@SpringJUnitConfig(LocalSecurityHttpTest.Config.class)
class LocalSecurityHttpTest {
    @Autowired ApplicationContext context;
    @Autowired ProjectScopeService scope;
    WebTestClient web;
    @BeforeEach void setup() {
        reset(scope);
        doAnswer(call -> {
            if (!((OperatorContext) call.getArgument(1)).operator().equals("owner"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return null;
        }).when(scope).requireRunOwner(anyString(), any());
        web = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }
    @Test void anonymousAndSpoofedIdentityCannotReadPrivateRun() {
        web.get().uri("/api/semevosql/runs/run-one").header("X-User-ID", "owner")
                .exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/semevosql/runs/run-one").headers(h -> h.setBasicAuth("bob", "test-only"))
                .exchange().expectStatus().isForbidden();
        web.get().uri("/api/semevosql/runs/run-one").headers(h -> h.setBasicAuth("owner", "test-only"))
                .exchange().expectStatus().isOk();
    }
    @Test void memberCannotPublishEvenWithValidCsrfAndSpoofedHeaders() {
        var token = session("bob");
        web.post().uri("/api/semevosql/projects/2/versions/1/publish")
                .headers(h -> h.setBasicAuth("bob", "test-only"))
                .cookie("SESSION", token.cookie()).header(token.header(), token.token()).header("X-User-ID", "admin")
                .exchange().expectStatus().isForbidden();
    }
    @Test void administratorStillNeedsCsrfForMutations() {
        web.post().uri("/api/semevosql/projects/2/versions/1/publish")
                .headers(h -> h.setBasicAuth("admin", "test-only")).exchange().expectStatus().isForbidden();
        var token = session("admin");
        web.post().uri("/api/semevosql/projects/2/versions/1/publish")
                .headers(h -> h.setBasicAuth("admin", "test-only"))
                .cookie("SESSION", token.cookie()).header(token.header(), token.token())
                .exchange().expectStatus().isOk();
    }
    @Test void formLoginAndLogoutUseServerSessionWithoutCredentialsInResponse() {
        var token = session(null);
        var login = web.post().uri("/api/semevosql/auth/login")
                .cookie("SESSION", token.cookie()).header(token.header(), token.token())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("username=owner&password=test-only")
                .exchange().expectStatus().isNoContent().expectBody().returnResult();
        String cookie = Objects.requireNonNull(login.getResponseCookies().getFirst("SESSION")).getValue();
        assertThat(cookie).isNotEqualTo(token.cookie());
        var logged = web.get().uri("/api/semevosql/auth/session").cookie("SESSION", cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(logged).containsEntry("username", "owner").doesNotContainKeys("password", "passwordHash");
        web.post().uri("/api/semevosql/auth/logout").cookie("SESSION", cookie)
                .header(logged.get("csrfHeader").toString(), logged.get("csrfToken").toString())
                .exchange().expectStatus().isNoContent();
        web.get().uri("/api/semevosql/runs/run-one").cookie("SESSION", cookie)
                .exchange().expectStatus().isUnauthorized();
    }
    record Token(String cookie, String header, String token) {}
    Token session(String user) {
        var request = web.get().uri("/api/semevosql/auth/session");
        if (user != null) request.headers(h -> h.setBasicAuth(user, "test-only"));
        var result = request.exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        var body = Objects.requireNonNull(result.getResponseBody());
        return new Token(Objects.requireNonNull(result.getResponseCookies().getFirst("SESSION")).getValue(),
                body.get("csrfHeader").toString(), body.get("csrfToken").toString());
    }
    @Configuration @EnableWebFlux
    @Import({LocalSecurityConfiguration.class, LocalHttpAccessPolicy.class, LocalSessionController.class, Endpoints.class})
    static class Config {
        @Bean @Primary LocalSecurityProperties accounts() {
            var properties = new LocalSecurityProperties(); properties.setEnabled(true);
            var hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("test-only");
            for (String name : List.of("owner", "bob", "admin")) {
                var account = new LocalSecurityProperties.Account(); account.setPasswordHash(hash);
                account.setAdministrator(name.equals("admin")); account.setProjectIds(List.of(2L));
                properties.getAccounts().put(name, account);
            }
            return properties;
        }
        @Bean ProjectScopeService scope() { return mock(ProjectScopeService.class); }
    }
    @RestController static class Endpoints {
        @GetMapping("/api/semevosql/runs/{id}") String run() { return "private"; }
        @PostMapping("/api/semevosql/projects/{project}/versions/{version}/publish") String publish() { return "published"; }
    }
}
