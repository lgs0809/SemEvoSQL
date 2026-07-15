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
import static cn.lgs.semevosql.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer;
import cn.lgs.semevosql.service.graph.checkpoint.DurableGraphStateSerializer;
import cn.lgs.semevosql.service.llm.impls.StreamLlmService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.workflow.node.QueryEnhanceNode;
import com.alibaba.cloud.ai.graph.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Native graph, production Spring AI client and actual delayed loopback SSE; synthetic provider only. */
class RequestModelStreamHttpTest {
    private static final String READY="{\"status\":\"READY\",\"canonical_query\":\"known scope\",\"expanded_queries\":[\"known scope\"],\"context_turns\":[],\"question\":\"\",\"options\":[]}";

    @Test void nativeGraphCollectsAllDelayedSdkChunksBeforeAcceptingARequest() throws Exception {
        check(true);
    }

    @Test void prematureHttpBodyEndCannotReachDownstreamQueryExecution() throws Exception {
        check(false);
    }

    @Test void bufferedSlowConsumerPreservesEveryBurstChunkInOrder() throws Exception {
        var expected = new StringBuilder();
        var chunks = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            String token = "token-" + i + " ";
            expected.append(token);
            chunks.add(chunk(token, null));
        }
        chunks.add(chunk("", "stop"));
        chunks.add("data: [DONE]\n\n");
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.incrementAndGet();
            return request.receive().aggregate().then(response.header("Content-Type", "text/event-stream")
                .sendString(Flux.fromIterable(chunks)).then());
        }).bindNow();
        try {
            var config = new ModelConfigDTO();
            config.setBaseUrl("http://127.0.0.1:" + server.port());
            config.setModelName("gpt-5.6-luna");
            config.setApiKey("synthetic-only");
            var client = ChatClient.builder(new DynamicModelFactory(new ModelClientProperties())
                .createChatModel(config)).build();
            String actual = client.prompt().user("synthetic burst").stream().chatResponse()
                .buffer(2).concatMap(buffer -> Mono.delay(Duration.ofMillis(2))
                    .thenMany(Flux.fromIterable(buffer)))
                .map(response -> response.getResult().getOutput().getText())
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString).block(Duration.ofSeconds(5));
            assertThat(actual).isEqualTo(expected.toString());
            assertThat(requests).hasValue(1);
        } finally { server.disposeNow(); }
    }

    private void check(boolean complete) throws Exception {
        var httpRequests=new AtomicInteger();
        int split=40;
        var chunks=new ArrayList<String>();
        chunks.add(chunk(READY.substring(0,split),null));
        if(complete) {
            chunks.add(chunk(READY.substring(split),null));
            chunks.add(chunk("","stop"));
            chunks.add("data: [DONE]\n\n");
        }
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            httpRequests.incrementAndGet();
            return request.receive().aggregate().then(response.header("Content-Type","text/event-stream")
                .sendString(Flux.fromIterable(chunks).delayElements(Duration.ofMillis(30))).then());
        }).bindNow();
        try {
            var properties=new ModelClientProperties();properties.setRequestTimeout(Duration.ofSeconds(2));
            var config=new ModelConfigDTO();config.setBaseUrl("http://127.0.0.1:"+server.port());
            config.setModelName("gpt-5.6-luna");config.setApiKey("synthetic-only");
            var registry=mock(AiModelRegistry.class);
            when(registry.getChatClient()).thenReturn(ChatClient.builder(new DynamicModelFactory(properties).createChatModel(config)).build());
            var node=new QueryEnhanceNode(new StreamLlmService(registry),mock(ConversationContextPromptRenderer.class));
            var downstream=new AtomicInteger();
            var graph=new StateGraph("request-http-stream",()->Map.of(REQUEST_ENHANCEMENT_OUTPUT,KeyStrategy.REPLACE),new DurableGraphStateSerializer())
                .addNode("enhance",node_async(node))
                .addNode("downstream",node_async(state->{
                    var request=(RequestQueryEnhancement)state.value(REQUEST_ENHANCEMENT_OUTPUT).orElseThrow();
                    assertThat(request.ready()).isTrue();assertThat(request.canonicalQuery()).isEqualTo("known scope");
                    downstream.incrementAndGet();return Map.of();
                }))
                .addEdge(StateGraph.START,"enhance").addEdge("enhance","downstream").addEdge("downstream",StateGraph.END)
                .compile(CompileConfig.builder().build());
            var input=Map.<String,Object>of(INPUT_KEY,"synthetic protocol question",RUN_DEADLINE_EPOCH_MILLIS,System.currentTimeMillis()+5000);
            if(complete) {
                graph.stream(input).collectList().block(Duration.ofSeconds(8));
                assertThat(downstream).hasValue(1);
            } else {
                assertThatThrownBy(()->graph.stream(input).collectList().block(Duration.ofSeconds(8)))
                    .hasStackTraceContaining("TransientAiException")
                    .hasStackTraceContaining("without a finish reason");
                assertThat(downstream).hasValue(0);
            }
            assertThat(httpRequests).hasValue(1);
        } finally {server.disposeNow();}
    }

    private String chunk(String text,String reason) throws Exception {
        var choice=new LinkedHashMap<String,Object>();choice.put("index",0);
        choice.put("delta",Map.of("role","assistant","content",text));choice.put("finish_reason",reason);
        return "data: "+JsonUtil.getObjectMapper().writeValueAsString(Map.of("id","synthetic-stream",
            "model","gpt-5.6-luna","choices",List.of(choice)))+"\n\n";
    }
}
