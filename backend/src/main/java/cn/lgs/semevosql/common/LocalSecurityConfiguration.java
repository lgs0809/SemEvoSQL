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

import java.util.ArrayList;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.logout.HttpStatusReturningServerLogoutSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/** Spring Security owns authentication, session fixation protection, CSRF and logout. */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(LocalSecurityProperties.class)
public class LocalSecurityConfiguration {
    @Bean
    PasswordEncoder localPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    MapReactiveUserDetailsService localUsers(LocalSecurityProperties properties) {
        var users = new ArrayList<UserDetails>();
        if (properties.isEnabled()) {
            if (properties.getAccounts().isEmpty()) throw new IllegalStateException("Local security requires configured accounts");
            properties.getAccounts().forEach((name, account) -> {
                var hash = account.getPasswordHash();
                if (!name.matches("[A-Za-z0-9_-]{1,128}") || hash == null
                        || !(hash.startsWith("{bcrypt}") || hash.startsWith("{pbkdf2") || hash.startsWith("{argon2")))
                    throw new IllegalStateException("Local accounts require stable names and encoded passwords");
                users.add(User.withUsername(name).password(hash)
                        .roles(account.isAdministrator() ? "ADMIN" : "USER").build());
            });
        }
        // Spring's Map constructor supports the empty registry used by loopback single-user mode.
        return users.isEmpty() ? new MapReactiveUserDetailsService(Map.of())
                : new MapReactiveUserDetailsService(users);
    }

    @Bean
    SecurityWebFilterChain localSecurityChain(ServerHttpSecurity http, LocalSecurityProperties properties,
            LocalHttpAccessPolicy access) {
        // External MCP endpoints keep their existing credential boundary; this chain protects browser APIs.
        http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**"));
        if (!properties.isEnabled()) {
            return http.authorizeExchange(a -> a.anyExchange().permitAll())
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable).build();
        }
        return http.authorizeExchange(a -> a.pathMatchers("/api/semevosql/auth/session",
                        "/api/semevosql/auth/login").permitAll().anyExchange().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(Customizer.withDefaults())
                .formLogin(f -> f.loginPage("/api/semevosql/auth/login")
                        .authenticationSuccessHandler((filter, auth) -> {
                            filter.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                            return filter.getExchange().getResponse().setComplete();
                        })
                        .authenticationFailureHandler((filter, error) -> {
                            filter.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return filter.getExchange().getResponse().setComplete();
                        }))
                .logout(l -> l.logoutUrl("/api/semevosql/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningServerLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .addFilterAfter((exchange, chain) -> exchange.getPrincipal()
                        .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(principal -> (principal.isPresent()
                                ? access.authorize(exchange, principal.get().getName()) : Mono.<Void>empty())
                                .then(Mono.defer(() -> chain.filter(exchange)))), SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }
}
