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
package cn.lgs.semevosql.service.aimodelconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.service.llm.impls.BlockLlmService;
import cn.lgs.semevosql.service.llm.impls.StreamLlmService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Production HTTP/SDK/gateway against synthetic loopback responses, not a real model quality claim. */
class ModelTokenUsageHttpTest {
    private static final String FINISH = "data: {\"id\":\"same-provider-id\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    private SemEvoSQLModelGateway gateway(int port, boolean streaming, SimpleMeterRegistry meters, int retries) {
        var properties = new ModelClientProperties();
        properties.setRequestTimeout(Duration.ofSeconds(1));
        var config = new ModelConfigDTO();
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setModelName("gpt-5.6-luna");
        config.setApiKey("synthetic-local-only");
        var client = ChatClient.builder(new DynamicModelFactory(properties).createChatModel(config)).build();
        var registry = mock(AiModelRegistry.class);
        when(registry.getChatClient()).thenReturn(client);
        return new SemEvoSQLModelGateway(streaming ? new StreamLlmService(registry) : new BlockLlmService(registry),
            new SemEvoSQLMetrics(meters), 2, 100, 3000, 8000, 1, retries, 5, 100);
    }

    private String completion(String text, int prompt, int output) {
        return "{\"id\":\"same-provider-id\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
            + text + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + prompt
            + ",\"completion_tokens\":" + output + ",\"total_tokens\":" + (prompt + output) + "}}";
    }

    private String chunk(String text, int prompt, int output) {
        return "data: {\"id\":\"same-provider-id\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
            + text + "\"},\"finish_reason\":null}],\"usage\":{\"prompt_tokens\":" + prompt
            + ",\"completion_tokens\":" + output + ",\"total_tokens\":" + (prompt + output) + "}}\n\n";
    }

    private void assertMetrics(SimpleMeterRegistry meters, long prompt, long output, boolean success) {
        assertThat(meters.get("semevosql.model.prompt.tokens").summary().totalAmount()).isEqualTo(prompt);
        assertThat(meters.get("semevosql.model.completion.tokens").summary().totalAmount()).isEqualTo(output);
        assertThat(meters.get("semevosql.model.prompt.tokens").summary().count()).isEqualTo(1);
        assertThat(meters.get("semevosql.model.calls").tag("outcome", success ? "success" : "failure").counter().count()).isEqualTo(1);
    }

    @Test void emptyBlockingResponseStillCostsTokensBeforeSuccessfulRetry() {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) ->
            response.header("Content-Type", "application/json").sendString(Mono.just(
                requests.incrementAndGet() == 1 ? completion("", 11, 2) : completion("recovered", 17, 5)))).bindNow();
        var meters = new SimpleMeterRegistry();
        try {
            var result = gateway(server.port(), false, meters, 1).complete(ModelCallPurpose.OTHER, "fixture", "fixture");
            assertThat(result.response()).isEqualTo("recovered");
            assertThat(requests).hasValue(2);
            assertThat(result.httpAttempts()).isEqualTo(2);
            assertThat(result.promptTokens()).isEqualTo(28);
            assertThat(result.completionTokens()).isEqualTo(7);
            assertMetrics(meters, 28, 7, true);
        } finally { meters.close(); server.disposeNow(); }
    }

    @Test void exhaustedCallsKeepUsageFromEveryFailedAttempt() {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            requests.incrementAndGet();
            return response.header("Content-Type", "application/json").sendString(Mono.just(completion("", 11, 2)));
        }).bindNow();
        var meters = new SimpleMeterRegistry();
        try {
            assertThatThrownBy(() -> gateway(server.port(), false, meters, 1).complete(ModelCallPurpose.OTHER, "fixture", "fixture"));
            assertThat(requests).hasValue(2);
            assertMetrics(meters, 22, 4, false);
        } finally { meters.close(); server.disposeNow(); }
    }

    @Test void repeatedAndCumulativeUsageFramesAreCountedOnceWithinAnAttempt() {
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) ->
            response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                chunk("ok", 11, 2) + chunk("", 11, 2) + chunk("", 11, 4) + FINISH))).bindNow();
        var meters = new SimpleMeterRegistry();
        try {
            var result = gateway(server.port(), true, meters, 0).complete(ModelCallPurpose.OTHER, "fixture", "fixture");
            assertThat(result.response()).isEqualTo("ok");
            assertThat(result.httpAttempts()).isEqualTo(1);
            assertThat(result.promptTokens()).isEqualTo(11);
            assertThat(result.completionTokens()).isEqualTo(4);
            assertMetrics(meters, 11, 4, true);
        } finally { meters.close(); server.disposeNow(); }
    }

    @Test void partialStreamTimeoutRetainsUsageButDiscardsFailedTextBeforeRetry() {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            response.header("Content-Type", "text/event-stream");
            // Spring AI 1.1.0 uses buffer(2, 1); send a second frame so usage reaches the gateway before stalling.
            if (requests.incrementAndGet() == 1) return response.sendString(Flux.concat(
                Mono.just(chunk("discarded", 11, 2) + chunk("", 11, 2)), Mono.never()));
            return response.sendString(Mono.just(chunk("recovered", 17, 5) + FINISH));
        }).bindNow();
        var meters = new SimpleMeterRegistry();
        try {
            var result = gateway(server.port(), true, meters, 1).complete(ModelCallPurpose.OTHER, "fixture", "fixture");
            assertThat(requests).hasValue(2);
            assertThat(result.response()).isEqualTo("recovered");
            assertThat(result.promptTokens()).isEqualTo(28);
            assertThat(result.completionTokens()).isEqualTo(7);
            assertMetrics(meters, 28, 7, true);
        } finally { meters.close(); server.disposeNow(); }
    }

    @Test void parallelLogicalCallsDoNotShareUsageOrDeduplicateTheSameProviderId() throws Exception {
        var requests = new AtomicInteger();
        var bothEntered = new CompletableFuture<Void>();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            int number = requests.incrementAndGet();
            if (number == 2) bothEntered.complete(null);
            response.header("Content-Type", "application/json");
            return response.sendString(number <= 2
                ? Mono.fromFuture(bothEntered).thenReturn(completion("", 11, 2))
                : Mono.just(completion("recovered", 17, 5)));
        }).bindNow();
        var workers = Executors.newFixedThreadPool(2);
        var meters = new SimpleMeterRegistry();
        try {
            var gateway = gateway(server.port(), false, meters, 1);
            var first = workers.submit(() -> gateway.complete(ModelCallPurpose.OTHER, "fixture", "first"));
            var second = workers.submit(() -> gateway.complete(ModelCallPurpose.OTHER, "fixture", "second"));
            var one = first.get(8, TimeUnit.SECONDS);
            var two = second.get(8, TimeUnit.SECONDS);
            assertThat(one.callId()).isNotEqualTo(two.callId());
            assertThat(one.promptTokens()).isEqualTo(28);
            assertThat(two.promptTokens()).isEqualTo(28);
            assertThat(one.completionTokens()).isEqualTo(7);
            assertThat(two.completionTokens()).isEqualTo(7);
            assertThat(requests).hasValue(4);
            assertThat(meters.get("semevosql.model.prompt.tokens").summary().totalAmount()).isEqualTo(56);
            assertThat(meters.get("semevosql.model.completion.tokens").summary().totalAmount()).isEqualTo(14);
            assertThat(meters.get("semevosql.model.calls").counter().count()).isEqualTo(2);
            assertThat(meters.get("semevosql.model.http.requests").counter().count()).isEqualTo(4);
        } finally { workers.shutdownNow(); meters.close(); server.disposeNow(); }
    }

    @Test void missingUsageIsNotInventedFromTheFollowingAttempt() {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) ->
            response.header("Content-Type", "application/json").sendString(Mono.just(
                requests.incrementAndGet() == 1
                    ? "{\"id\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}"
                    : completion("recovered", 17, 5)))).bindNow();
        var meters = new SimpleMeterRegistry();
        try {
            var result = gateway(server.port(), false, meters, 1).complete(ModelCallPurpose.OTHER, "fixture", "fixture");
            assertThat(requests).hasValue(2);
            assertThat(result.promptTokens()).isEqualTo(17);
            assertThat(result.completionTokens()).isEqualTo(5);
            assertMetrics(meters, 17, 5, true);
        } finally { meters.close(); server.disposeNow(); }
    }
}
