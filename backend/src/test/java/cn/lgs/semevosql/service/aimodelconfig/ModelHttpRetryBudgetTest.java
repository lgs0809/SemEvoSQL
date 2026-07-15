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
import cn.lgs.semevosql.model.*;
import cn.lgs.semevosql.properties.*;
import cn.lgs.semevosql.service.llm.impls.*;
import cn.lgs.semevosql.service.llm.LlmInvocationOptions;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Real loopback HTTP and production SDK/factory/gateway; no remote model or model mock. */
class ModelHttpRetryBudgetTest {
    private ChatClient streamClient(int port) {
        var properties=new ModelClientProperties();properties.setRequestTimeout(Duration.ofMillis(150));
        properties.setConnectionInitialBackoff(Duration.ofMillis(5));properties.setConnectionMaxBackoff(Duration.ofMillis(10));properties.setConnectionRetryJitter(0);
        var config=new ModelConfigDTO();config.setBaseUrl("http://127.0.0.1:"+port);config.setModelName("gpt-5.6-luna");config.setApiKey("synthetic-local-only");config.setRequestTimeoutSeconds(null);
        return ChatClient.builder(new DynamicModelFactory(properties).createChatModel(config)).build();
    }

    @Test void ordinaryStreamCanRecoverOnTheFifthHttpRequest() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()<5)return request.receive().then(Mono.never());
            return response.header("Content-Type","text/event-stream").sendString(Mono.just(CHUNK));
        }).bindNow();
        try {
            String result=streamClient(server.port()).prompt().user("synthetic five-attempt fixture").stream().content()
                .collectList().map(parts->String.join("",parts)).block(Duration.ofSeconds(5));
            assertThat(result).isEqualTo("recovered");assertThat(requests).hasValue(5);
        } finally {server.disposeNow();}
    }

    @Test void ordinaryStreamStopsAfterFiveHeaderFailuresWithoutLateRequests() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return request.receive().then(Mono.never());
        }).bindNow();
        try {
            assertThatThrownBy(()->streamClient(server.port()).prompt().user("synthetic exhaustion fixture")
                .stream().content().collectList().block(Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
            Mono.delay(Duration.ofMillis(500)).block();assertThat(requests).hasValue(5);
        } finally {server.disposeNow();}
    }

    @Test void gatewayFifthRequestSucceedsWithoutMultiplyingTheStreamConnectionPolicy() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()<5)return request.receive().then(Mono.never());
            return response.header("Content-Type","text/event-stream").sendString(Mono.just(CHUNK));
        }).bindNow();
        try {
            var result=gateway(server.port(),true,4).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture");
            assertThat(result.response()).isEqualTo("recovered");assertThat(result.attempts()).isEqualTo(5);
            assertThat(result.httpAttempts()).isEqualTo(5);assertThat(requests).hasValue(5);
        } finally {server.disposeNow();}
    }

    @Test void gatewayCallerDeadlineMayStopBeforeAllFiveAttempts() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return request.receive().then(Mono.never());
        }).bindNow();
        try {
            assertThatThrownBy(()->gateway(server.port(),true,4).complete(ModelCallPurpose.QUERY_DECOMPOSITION,
                "system","fixture",LlmInvocationOptions.none(),Duration.ofMillis(200))).isInstanceOf(RuntimeException.class);
            Mono.delay(Duration.ofMillis(500)).block();assertThat(requests.get()).isBetween(1,4);
        } finally {server.disposeNow();}
    }

    @Test void ordinaryStreamDoesNotAppendARetriedAnswerToAnAlreadyEmittedPartialJson() {
        var requests=new AtomicInteger();
        String partial="data: {\"id\":\"fixture\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"{partial\"},\"finish_reason\":null}]}\n\n";
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return response.header("Content-Type","text/event-stream").sendString(Mono.just(partial));
        }).bindNow();
        try {
            assertThatThrownBy(()->streamClient(server.port()).prompt().user("synthetic partial response")
                .stream().content().collectList().block(Duration.ofSeconds(3))).hasStackTraceContaining("without a finish reason");
            assertThat(requests).hasValue(1);
        } finally {server.disposeNow();}
    }

    @Test void blockingSdkFifthRequestUsesOnlyTheGatewayHttpBudget() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()<5)return response.status(503).header("Content-Type","application/json")
                .sendString(Mono.just("{\"error\":{\"message\":\"synthetic unavailable\"}}"));
            return response.header("Content-Type","application/json").sendString(Mono.just(COMPLETION));
        }).bindNow();
        try {
            var result=gateway(server.port(),false,4).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture");
            assertThat(result.attempts()).isEqualTo(5);assertThat(result.httpAttempts()).isEqualTo(5);
            assertThat(requests).hasValue(5);assertThat(ModelTransportBudget.blocking()).isNull();
        } finally {server.disposeNow();}
    }
    private static final String CHUNK="""
        data: {"id":"fixture","model":"gpt-5.6-luna","choices":[{"index":0,"delta":{"role":"assistant","content":"recovered"},"finish_reason":null}]}

        data: {"id":"fixture","model":"gpt-5.6-luna","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

        data: [DONE]

        """;
    private static final String COMPLETION="""
        {"id":"fixture","model":"gpt-5.6-luna","choices":[{"index":0,"message":{"role":"assistant","content":"recovered"},"finish_reason":"stop"}]}
        """;
    private SemEvoSQLModelGateway gateway(int port,boolean streaming,int retries) {
        var properties=new ModelClientProperties();properties.setRequestTimeout(Duration.ofMillis(150));
        properties.setConnectionInitialBackoff(Duration.ofMillis(5));properties.setConnectionMaxBackoff(Duration.ofMillis(10));properties.setConnectionRetryJitter(0);
        var config=new ModelConfigDTO();config.setBaseUrl("http://127.0.0.1:"+port);config.setModelName("gpt-5.6-luna");config.setApiKey("synthetic-local-only");config.setRequestTimeoutSeconds(null);
        var client=ChatClient.builder(new DynamicModelFactory(properties).createChatModel(config)).build();
        var registry=mock(AiModelRegistry.class);when(registry.getChatClient()).thenReturn(client);
        return new SemEvoSQLModelGateway(streaming?new StreamLlmService(registry):new BlockLlmService(registry),null,2,100,5000,10000,1,retries,5,100);
    }
    @Test void headerFailuresConsumeOnlyGatewayBudgetAndNoLateRetriesRemain() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return request.receive().then(Mono.never());
        }).bindNow();
        try {
            var gateway=gateway(server.port(),true,1);
            assertThatThrownBy(()->gateway.complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture")).isInstanceOf(RuntimeException.class);
            Mono.delay(Duration.ofMillis(500)).block();assertThat(requests).hasValue(2);
            System.out.println("REAL_HTTP_BUDGET_EXHAUSTED configuredAttempts=2 actualHttpRequests="+requests.get()+" lateRequests=0");
        }finally {server.disposeNow();}
    }
    @Test void streamingRetryAndSdkCannotMultiplyActualHttpCount() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()==1)return request.receive().then(Mono.never());
            return response.header("Content-Type","text/event-stream").sendString(Mono.just(CHUNK));
        }).bindNow();
        try {
            var result=gateway(server.port(),true,1).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture");
            assertThat(result.response()).isEqualTo("recovered");assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.httpAttempts()).isEqualTo(2);assertThat(requests).hasValue(2);
            System.out.println("REAL_HTTP_RETRY_FIXED gatewayAttempts="+result.attempts()+" actualHttpRequests="+requests.get());
        }finally {server.disposeNow();}
    }
    @Test void prematureCleanEofRetriesWithinTheSameHttpBudgetAndDiscardsPartialText() {
        var requests = new AtomicInteger();
        String unfinished = "data: {\"id\":\"fixture\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"discarded\"},\"finish_reason\":null}]}\n\n";
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
            response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                requests.incrementAndGet() == 1 ? unfinished : CHUNK))).bindNow();
        try {
            var result = gateway(server.port(), true, 1).complete(ModelCallPurpose.QUERY_DECOMPOSITION, "system", "fixture");
            assertThat(result.response()).isEqualTo("recovered");
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.httpAttempts()).isEqualTo(2);
            assertThat(requests).hasValue(2);
        } finally { server.disposeNow(); }
    }
    @Test void repeatedPrematureEofExhaustsTwoHttpRequestsWithoutAcceptingAnyPartialAnswer() {
        var requests = new AtomicInteger();
        String unfinished = "data: {\"id\":\"fixture\",\"model\":\"gpt-5.6-luna\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"discarded\"},\"finish_reason\":null}]}\n\n";
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.incrementAndGet();
            return response.header("Content-Type", "text/event-stream").sendString(Mono.just(unfinished));
        }).bindNow();
        try {
            assertThatThrownBy(() -> gateway(server.port(), true, 1).complete(ModelCallPurpose.QUERY_DECOMPOSITION, "system", "fixture"))
                .hasStackTraceContaining("without a finish reason");
            assertThat(requests).hasValue(2);
        } finally { server.disposeNow(); }
    }
    @Test void blockingSdkAndApacheRetriesAlsoUseTheSameBudget() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()==1)return response.status(503).header("Content-Type","application/json")
                .sendString(Mono.just("{\"error\":{\"message\":\"synthetic unavailable\"}}"));
            return response.header("Content-Type","application/json").sendString(Mono.just(COMPLETION));
        }).bindNow();
        try {
            var result=gateway(server.port(),false,1).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture");
            assertThat(result.response()).isEqualTo("recovered");assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.httpAttempts()).isEqualTo(2);assertThat(requests).hasValue(2);
            assertThat(ModelTransportBudget.blocking()).isNull();
        }finally {server.disposeNow();}
    }
    @Test void unsupportedReasoningFallbackConsumesAnOrdinaryAttempt() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()==1)return response.status(400).header("Content-Type","application/json")
                .sendString(Mono.just("{\"error\":{\"message\":\"unsupported reasoning_effort\"}}"));
            return response.header("Content-Type","text/event-stream").sendString(Mono.just(CHUNK));
        }).bindNow();
        try {
            var result=gateway(server.port(),true,1).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture",new LlmInvocationOptions(null,"low"));
            assertThat(result.response()).isEqualTo("recovered");assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.httpAttempts()).isEqualTo(2);assertThat(requests).hasValue(2);
            assertThat(result.invocationProfile().reasoningApplied()).isFalse();
        }finally {server.disposeNow();}
    }
    @Test void authenticationFailureDoesNotConsumeExtraTransportAttempts() {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return response.status(401).header("Content-Type","application/json")
                .sendString(Mono.just("{\"error\":{\"message\":\"synthetic unauthorized\"}}"));
        }).bindNow();
        try {
            assertThatThrownBy(()->gateway(server.port(),true,3).complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture"));
            assertThat(requests).hasValue(1);
        }finally {server.disposeNow();}
    }
    @Test void reasoningDowngradePreservesTheExplicitModelOnBothActualHttpRequests() {
        var bodies=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->
            request.receive().aggregate().asString().flatMap(body->{
                bodies.add(body);
                if(bodies.size()==1)return response.status(400).header("Content-Type","application/json")
                    .sendString(Mono.just("{\"error\":{\"message\":\"unsupported reasoning_effort\"}}" )).then();
                return response.header("Content-Type","text/event-stream")
                    .sendString(Mono.just(CHUNK.replace("gpt-5.6-luna","gpt-5.6-terra"))).then();
            })).bindNow();
        try {
            var result=gateway(server.port(),true,1).complete(ModelCallPurpose.SEMANTIC_PLANNING,"system","fixture",
                new LlmInvocationOptions("gpt-5.6-terra","medium"));
            assertThat(result.httpAttempts()).isEqualTo(2);assertThat(bodies).hasSize(2);
            var first=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(bodies.get(0));
            var second=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(bodies.get(1));
            assertThat(first.path("model").asText()).isEqualTo("gpt-5.6-terra");
            assertThat(second.path("model").asText()).isEqualTo("gpt-5.6-terra");
            assertThat(first.path("reasoning_effort").asText()).isEqualTo("medium");
            assertThat(second.hasNonNull("reasoning_effort")).isFalse();
            assertThat(result.invocationProfile().modelOverride()).isEqualTo("gpt-5.6-terra");
            assertThat(result.invocationProfile().reasoningApplied()).isFalse();
        } catch(java.io.IOException error) {throw new AssertionError(error);}
        finally {server.disposeNow();}
    }
    @Test void unsupportedAdapterCannotSilentlyDiscardAnExplicitModel() {
        var service=mock(cn.lgs.semevosql.service.llm.LlmService.class);
        var gateway=new SemEvoSQLModelGateway(service);
        assertThatThrownBy(()->gateway.complete(ModelCallPurpose.SEMANTIC_PLANNING,"system","fixture",
            new LlmInvocationOptions("gpt-5.6-terra","medium")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("MODEL_OVERRIDE_UNSUPPORTED");
        verify(service,never()).call(anyString(),anyString());
        verify(service,never()).call(anyString(),anyString(),any(LlmInvocationOptions.class));
    }
    @Test void simultaneousCallsHaveSeparateBudgets() throws Exception {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()<=2)return request.receive().then(Mono.never());
            return response.header("Content-Type","text/event-stream").sendString(Mono.just(CHUNK));
        }).bindNow();
        var workers=Executors.newFixedThreadPool(2);
        try {
            var gateway=gateway(server.port(),true,1);
            var one=workers.submit(()->gateway.complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture one"));
            var two=workers.submit(()->gateway.complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","fixture two"));
            var first=one.get(8,TimeUnit.SECONDS);var second=two.get(8,TimeUnit.SECONDS);
            assertThat(first.callId()).isNotEqualTo(second.callId());
            assertThat(first.httpAttempts()).isEqualTo(2);assertThat(second.httpAttempts()).isEqualTo(2);assertThat(requests).hasValue(4);
        }finally {workers.shutdownNow();server.disposeNow();}
    }
}
