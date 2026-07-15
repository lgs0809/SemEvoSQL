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

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.properties.ModelClientProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP transport and Spring AI stream, without a remote model or credentials. */
class ModelStreamCancellationTest {

    @Test
    void cancellingBeforeResponseHeadersClosesTheRequestAndStopsConnectionRetries() throws Exception {
        assertCancellation(port -> client(port).prompt().user("synthetic cancellation fixture").stream().chatResponse());
    }

    @Test
    void cancellingDirectModelStopsConnectionRetries() throws Exception {
        assertCancellation(port -> model(port).stream(new org.springframework.ai.chat.prompt.Prompt("synthetic cancellation fixture")));
    }

    @Test
    void cancellingRawHttpStopsConnectionRetries() throws Exception {
        assertCancellation(port -> new DynamicModelFactory(properties()).configureConnectionRetry(
            org.springframework.web.reactive.function.client.WebClient.builder().clientConnector(
                new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                    reactor.netty.http.client.HttpClient.create().responseTimeout(Duration.ofMillis(500)))), "synthetic")
            .build().post().uri("http://127.0.0.1:"+port+"/v1/chat/completions").bodyValue("synthetic")
            .retrieve().bodyToFlux(String.class));
    }

    private void assertCancellation(java.util.function.IntFunction<reactor.core.publisher.Flux<?>> stream) throws Exception {
        var received = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0)
            .doOnConnection(connection -> connection.onDispose(disconnected::countDown))
            .handle((request, response) -> {
                requests.incrementAndGet(); received.countDown();
                return request.receive().then(Mono.never());
            }).bindNow();
        var subscription = stream.apply(server.port()).subscribe(ignored -> {}, ignored -> {});
        try {
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).as("HTTP request cancelled with subscriber").isTrue();
            // Longer than the fixture transport timeout and all configured retry backoffs.
            Mono.delay(Duration.ofMillis(900)).block();
            assertThat(requests).hasValue(1);
        }
        finally { subscription.dispose(); server.disposeNow(); }
    }

    @Test
    void transientConnectionFailureCanStillRetryAndReturnARealStream() {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            if (requests.incrementAndGet() == 1) return request.receive().then(Mono.never());
            return response.header("Content-Type", "text/event-stream").sendString(Mono.just("""
                data: {"id":"fixture","object":"chat.completion.chunk","model":"gpt-5.6-luna","choices":[{"index":0,"delta":{"role":"assistant","content":"recovered"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","model":"gpt-5.6-luna","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

                data: [DONE]

                """));
        }).bindNow();
        try {
            String result = client(server.port()).prompt().user("synthetic transient failure")
                .stream().content().collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
            assertThat(result).isEqualTo("recovered");
            assertThat(requests).hasValue(2);
        }
        finally { server.disposeNow(); }
    }

    @Test
    void cancellingAfterResponseChunksClosesBodyWithoutCancellingOtherCalls() throws Exception {
        var received = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var requests = new AtomicInteger();
        String chunk = "data: {\"id\":\"fixture\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}\n\n";
        var server = HttpServer.create().host("127.0.0.1").port(0)
            .doOnConnection(connection -> connection.onDispose(disconnected::countDown))
            .handle((request, response) -> {
                if (requests.incrementAndGet() == 1) return response.header("Content-Type", "text/event-stream")
                    .sendString(reactor.core.publisher.Flux.concat(Mono.just(chunk + chunk), Mono.never()));
                String finish = "data: {\"id\":\"fixture\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                return response.header("Content-Type", "text/event-stream").sendString(Mono.just(chunk + finish));
            }).bindNow();
        var shared = client(server.port());
        var subscription = shared.prompt().user("first synthetic call").stream().chatResponse()
            .subscribe(ignored -> received.countDown(), ignored -> {});
        try {
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
            String result = shared.prompt().user("second synthetic call").stream().content()
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(3));
            assertThat(result).isEqualTo("ok");
            assertThat(requests).hasValue(2);
        }
        finally { subscription.dispose(); server.disposeNow(); }
    }

    private ChatClient client(int port) {
        return ChatClient.builder(model(port)).build();
    }

    private ModelClientProperties properties() {
        var properties = new ModelClientProperties();
        properties.setRequestTimeout(Duration.ofMillis(500));
        properties.setConnectionInitialBackoff(Duration.ofMillis(10));
        properties.setConnectionMaxBackoff(Duration.ofMillis(20));
        properties.setConnectionRetryJitter(0);
        return properties;
    }

    private org.springframework.ai.chat.model.ChatModel model(int port) {
        var config = new ModelConfigDTO();
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setModelName("gpt-5.6-luna");
        config.setApiKey("synthetic-local-only");
        config.setRequestTimeoutSeconds(null);
        return new DynamicModelFactory(properties()).createChatModel(config);
    }
}
