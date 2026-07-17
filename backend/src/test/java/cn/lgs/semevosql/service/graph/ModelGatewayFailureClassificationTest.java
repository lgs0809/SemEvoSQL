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
package cn.lgs.semevosql.service.graph;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.service.llm.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

class ModelGatewayFailureClassificationTest {
    @Test void unfinishedProviderStreamUsesDurableModelRecovery() {
        var failure = new org.springframework.ai.retry.TransientAiException("Model stream ended without a finish reason");
        assertThat(GraphFailureClassifier.recoverableModelFailure(failure)).isTrue();
        assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    }
    @Test void actualGatewayTimeoutRemainsRecoverableAndDistinctFromRunDeadline() {
        var llm=mock(LlmService.class);
        when(llm.call(anyString(),anyString())).thenReturn(Flux.<ChatResponse>never());
        when(llm.toStringFlux(any())).thenAnswer(call->((Flux<ChatResponse>)call.getArgument(0)).map(r->""));
        // This test exercises per-attempt timeout classification and retry, not exhaustion
        // of the total deadline. Leave room for scheduler/GC pauses during database ITs.
        var gateway=new SemEvoSQLModelGateway(llm,null,1,20,30,5000,1,1,5,100);
        Throwable failure=catchThrowable(()->gateway.complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","query"));
        assertThat(failure).isNotNull();
        assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("MODEL_PROVIDER_TIMEOUT");
        assertThat(GraphFailureClassifier.recoverableModelFailure(failure)).isTrue();
        verify(llm,times(2)).call(anyString(),anyString());
        assertThat(GraphFailureClassifier.recoverableModelFailure(new RunDeadlineExceededException("deadline"))).isFalse();
    }
    @Test void successfulHttpHeaderWithBrokenBodyIsRecoverable() {
        var response=org.springframework.web.reactive.function.client.WebClientResponseException.create(
            200,"OK",org.springframework.http.HttpHeaders.EMPTY,new byte[0],java.nio.charset.StandardCharsets.UTF_8);
        response.initCause(io.netty.handler.timeout.ReadTimeoutException.INSTANCE);
        assertThat(GraphFailureClassifier.recoverableModelFailure(response)).isTrue();
        assertThat(GraphFailureClassifier.errorCode(response)).isEqualTo("MODEL_PROVIDER_TIMEOUT");
        var malformed=org.springframework.web.reactive.function.client.WebClientResponseException.create(
            200,"OK",org.springframework.http.HttpHeaders.EMPTY,new byte[0],java.nio.charset.StandardCharsets.UTF_8);
        malformed.initCause(new IllegalArgumentException("malformed response"));
        assertThat(GraphFailureClassifier.recoverableModelFailure(malformed)).isFalse();
    }
    @Test void gatewayRetryDiscardsPartialResponseInsteadOfConcatenatingIt() {
        var response=org.springframework.web.reactive.function.client.WebClientResponseException.create(
            200,"OK",org.springframework.http.HttpHeaders.EMPTY,new byte[0],java.nio.charset.StandardCharsets.UTF_8);
        response.initCause(io.netty.handler.timeout.ReadTimeoutException.INSTANCE);
        var partial=new ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(
            new org.springframework.ai.chat.messages.AssistantMessage("incomplete"))));
        var complete=new ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(
            new org.springframework.ai.chat.messages.AssistantMessage("valid"))));
        var llm=mock(LlmService.class);
        when(llm.call(anyString(),anyString())).thenReturn(Flux.concat(Flux.just(partial),Flux.error(response)),Flux.just(complete));
        when(llm.toStringFlux(any())).thenAnswer(call->((Flux<ChatResponse>)call.getArgument(0)).map(r->r.getResult().getOutput().getText()));
        var gateway=new SemEvoSQLModelGateway(llm,null,1,20,1000,2000,1,1,5,100);
        var result=gateway.complete(ModelCallPurpose.QUERY_DECOMPOSITION,"system","query");
        assertThat(result.response()).isEqualTo("valid");assertThat(result.attempts()).isEqualTo(2);
    }
    @Test void authenticationRejectionIsNotTurnedIntoNetworkRetry() {
        var response=org.springframework.web.reactive.function.client.WebClientResponseException.create(
            401,"Unauthorized",org.springframework.http.HttpHeaders.EMPTY,new byte[0],java.nio.charset.StandardCharsets.UTF_8);
        response.initCause(io.netty.handler.timeout.ReadTimeoutException.INSTANCE);
        assertThat(GraphFailureClassifier.recoverableModelFailure(response)).isFalse();
        assertThat(GraphFailureClassifier.errorCode(response)).isEqualTo("MODEL_PROVIDER_REQUEST_REJECTED");
    }
    @Test void realLoopbackHttp200BodyTimeoutKeepsItsRecoveryClassification() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();server.setExecutor(pool);
        server.createContext("/slow",exchange->{
            try {
                exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write("data: first\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));exchange.getResponseBody().flush();
                Thread.sleep(300);
            } catch(Exception ignored) { } finally {exchange.close();}
        });server.start();
        try {
            var client=org.springframework.web.reactive.function.client.WebClient.builder().clientConnector(
                new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                    reactor.netty.http.client.HttpClient.create().responseTimeout(java.time.Duration.ofMillis(100)))).build();
            Throwable failure=catchThrowable(()->client.get().uri("http://127.0.0.1:"+server.getAddress().getPort()+"/slow")
                .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(3)));
            assertThat(failure).isNotNull();
            assertThat(GraphFailureClassifier.errorCode(failure)).isEqualTo("MODEL_PROVIDER_TIMEOUT");
            assertThat(GraphFailureClassifier.recoverableModelFailure(failure)).isTrue();
        } finally {server.stop(0);pool.shutdownNow();}
    }

    @Test void backgroundTimeoutCannotOpenTheInteractiveCircuitOrOccupyItsOnlyPermit() throws Exception {
        var llm=mock(LlmService.class);var started=new java.util.concurrent.CountDownLatch(1);
        var good=new ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("answer"))));
        when(llm.call(anyString(),anyString())).thenAnswer(call->"background".equals(call.getArgument(1))
            ?Flux.<ChatResponse>never().doOnSubscribe(ignored->started.countDown()):Flux.just(good));
        when(llm.toStringFlux(any())).thenAnswer(call->((Flux<ChatResponse>)call.getArgument(0)).map(r->r.getResult().getOutput().getText()));
        var gateway=new SemEvoSQLModelGateway(llm,null,1,10,300,1000,0,0,1,10000);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var failed=executor.submit(()->catchThrowable(()->gateway.complete(ModelCallPurpose.PERSONAL_DEFINITION_STRUCTURE,"system","background")));
            assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.complete(ModelCallPurpose.SEMANTIC_PLANNING,"system","foreground").response()).isEqualTo("answer");
            assertThat(GraphFailureClassifier.recoverableModelFailure(failed.get(3,java.util.concurrent.TimeUnit.SECONDS))).isTrue();
            assertThat(gateway.complete(ModelCallPurpose.SEMANTIC_PLANNING,"system","after-failure").response()).isEqualTo("answer");
            assertThat(catchThrowable(()->gateway.complete(ModelCallPurpose.PERSONAL_DEFINITION_STRUCTURE,"system","background")))
                .isInstanceOf(SemEvoSQLModelGateway.ModelCircuitOpenException.class);
        } finally {executor.shutdownNow();}
    }

}
