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

import static org.assertj.core.api.Assertions.assertThat;
import cn.lgs.semevosql.semantic.retrieval.RerankModel.RerankScore;
import cn.lgs.semevosql.util.JsonUtil;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Real local HTTP; response scores are synthetic protocol fixtures. */
class HttpRerankModelTest {
    @Test void sendsCommonRerankRequestAndParsesRelevanceScore() throws Exception {
        var path=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var method=new AtomicReference<String>();var payload=new AtomicReference<String>();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            path.set(request.uri());auth.set(request.requestHeaders().get("Authorization"));method.set(request.method().name());
            return request.receive().aggregate().asString().flatMap(body->{
                payload.set(body);return response.header("Content-Type","application/json").sendString(Mono.just(
                    "{\"results\":[{\"index\":1,\"relevance_score\":0.91},{\"index\":0,\"relevance_score\":0.42}]}")).then();
            });
        }).bindNow();
        try {
            var model=new HttpRerankModel(WebClient.builder(),"http://127.0.0.1:"+server.port()+"/","synthetic-local-only",null,
                "Qwen/Qwen3-VL-Reranker-2B",Duration.ofSeconds(5));
            assertThat(model.rerank("payment amount",List.of("gross amount","paid amount"),99))
                .containsExactly(new RerankScore(1,0.91d),new RerankScore(0,0.42d));
            assertThat(path.get()).isEqualTo("/v1/rerank");assertThat(method.get()).isEqualTo("POST");
            assertThat(auth.get()).isEqualTo("Bearer synthetic-local-only");
            var body=JsonUtil.getObjectMapper().readTree(payload.get());
            assertThat(body.path("model").asText()).isEqualTo("Qwen/Qwen3-VL-Reranker-2B");
            assertThat(body.path("query").asText()).isEqualTo("payment amount");
            assertThat(body.path("documents").size()).isEqualTo(2);assertThat(body.path("top_n").asInt()).isEqualTo(2);
        }finally {server.disposeNow();}
    }
    @Test void acceptsDataScoreShapeAndIgnoresInvalidIndexesWithoutAuthorization() {
        var path=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            path.set(request.uri());auth.set(request.requestHeaders().get("Authorization"));
            return response.header("Content-Type","application/json").sendString(Mono.just(
                "{\"data\":[{\"index\":0,\"score\":0.7},{\"index\":9,\"score\":1.0},{\"index\":1,\"score\":0.3}]}"));
        }).bindNow();
        try {
            var model=new HttpRerankModel(WebClient.builder(),"http://127.0.0.1:"+server.port(),"","rerank",
                "Qwen/Qwen3-VL-Reranker-2B",Duration.ofSeconds(5));
            assertThat(model.rerank("query",List.of("a","b"),2)).containsExactly(new RerankScore(0,0.7d),new RerankScore(1,0.3d));
            assertThat(path.get()).isEqualTo("/rerank");assertThat(auth.get()).isNull();
        }finally {server.disposeNow();}
    }
}
