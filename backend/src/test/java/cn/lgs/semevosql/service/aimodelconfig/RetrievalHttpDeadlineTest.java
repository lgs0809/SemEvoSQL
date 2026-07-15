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
package cn.lgs.semevosql.service.aimodelconfig;

import static org.assertj.core.api.Assertions.*;
import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.properties.ModelClientProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Production clients and real sockets. Vectors/scores below are synthetic protocol fixtures, not quality evidence. */
class RetrievalHttpDeadlineTest {
    private Runnable invocation(int port,boolean embedding,Integer seconds) {
        var properties=new ModelClientProperties();properties.setRequestTimeout(Duration.ofSeconds(60));
        var config=new ModelConfigDTO();config.setBaseUrl("http://127.0.0.1:"+port);config.setRequestTimeoutSeconds(seconds);
        config.setModelName(embedding?"Qwen/Qwen3-VL-Embedding-2B":"Qwen/Qwen3-VL-Reranker-2B");config.setEmbeddingDimensions(2);
        var factory=new DynamicModelFactory(properties);
        if(embedding){var model=factory.createEmbeddingModel(config);return ()->assertThat(model.embed("paid amount")).hasSize(2);}
        var model=factory.createRerankModel(config);return ()->assertThat(model.rerank("paid amount",List.of("paid total"),1)).hasSize(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void independentProbeUsesConfiguredDeadlineAndMakesOneRealRequest(boolean embedding) {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request,response) -> {
            requests.incrementAndGet();
            return response.header("Content-Type", "application/json")
                .sendString(Mono.delay(Duration.ofMillis(5300)).map(ignored -> success(embedding)));
        }).bindNow();
        try {
            var config = new ModelConfigDTO();
            config.setBaseUrl("http://127.0.0.1:" + server.port());
            config.setModelName("synthetic-connectivity-fixture");
            config.setRequestTimeoutSeconds(8); config.setEmbeddingDimensions(2);
            var factory = new DynamicModelFactory(new ModelClientProperties());
            if (embedding) assertThat(factory.createEmbeddingProbe(config).embed("test")).hasSize(2);
            else assertThat(factory.createRerankProbe(config).rerank("test", List.of("test"), 1)).hasSize(1);
            assertThat(requests).hasValue(1);
        } finally { server.disposeNow(); }
    }

    private String success(boolean embedding) {
        return embedding?"{\"data\":[{\"index\":0,\"embedding\":[0.25,0.5]}]}":
            "{\"results\":[{\"index\":0,\"relevance_score\":0.7}]}";
    }
    private void deadline(boolean embedding,boolean streamBody,Integer seconds,long expected) throws Exception {
        var requests=new AtomicInteger();var chunks=new AtomicInteger();var disconnected=new CountDownLatch(1);
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            if(requests.incrementAndGet()>1)return response.header("Content-Type","application/json").sendString(Mono.just(success(embedding)));
            response.withConnection(connection->connection.channel().closeFuture().addListener(future->disconnected.countDown()));
            if(!streamBody)return request.receive().then(Mono.never());
            // Bytes arrive every 100 ms: an inactivity/read timeout alone would never end this response.
            return response.header("Content-Type","application/json").sendString(Flux.concat(Mono.just("{"),
                Flux.interval(Duration.ofMillis(100)).map(tick->{chunks.incrementAndGet();return " ";})));
        }).bindNow();
        try {
            var call=invocation(server.port(),embedding,seconds);
            long start=System.nanoTime();assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);
            long elapsed=(System.nanoTime()-start)/1_000_000;
            assertThat(elapsed).isBetween(expected-300,expected+1700);
            assertThat(disconnected.await(2,TimeUnit.SECONDS)).as("timed-out HTTP socket is actually closed").isTrue();
            Mono.delay(Duration.ofMillis(300)).block();assertThat(requests).hasValue(1);
            if(streamBody)assertThat(chunks.get()).isGreaterThan(5);
            call.run();assertThat(requests).hasValue(2);
            System.out.println("REAL_RETRIEVAL_DEADLINE kind="+(embedding?"EMBEDDING":"RERANK")+" bodyStreaming="+streamBody+
                " configuredSeconds="+seconds+" effectiveBudgetMs="+expected+" elapsedMs="+elapsed+
                " disconnected=true faultHttpRequests=1 subsequentSuccess=true");
        }finally {server.disposeNow();}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void capsConfiguredSixtySecondsAtFiveBeforeHeaders(boolean embedding) throws Exception {deadline(embedding,false,60,5000);}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void fiveSecondBudgetIncludesAnEndlesslyStreamingBody(boolean embedding) throws Exception {deadline(embedding,true,null,5000);}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void keepsSmallerConfiguredBudget(boolean embedding) throws Exception {deadline(embedding,false,1,1000);}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void serviceUnavailableDoesNotStartHiddenRetrievalRetries(boolean embedding) {
        var requests=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            requests.incrementAndGet();return response.status(503).sendString(Mono.just("synthetic unavailable"));
        }).bindNow();
        try {
            var call=invocation(server.port(),embedding,60);assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);
            Mono.delay(Duration.ofMillis(600)).block();assertThat(requests).hasValue(1);
        }finally {server.disposeNow();}
    }
}
