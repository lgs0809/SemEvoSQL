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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer;
import cn.lgs.semevosql.service.graph.checkpoint.DurableGraphStateSerializer;
import cn.lgs.semevosql.util.ChatResponseUtil;
import com.alibaba.cloud.ai.graph.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/** Real Alibaba streaming graph; deterministic provider outputs exercise the failure boundary. */
class QueryEnhanceNodeTest {
    private static final String VALID = "{\"canonical_query\":\"查询2026年1月已支付订单金额\",\"expanded_queries\":[\"2026年1月已支付订单总金额\"]}";

    @ParameterizedTest
    @ValueSource(strings = {"", "not JSON", "{}", "null", "[]",
        "{\"canonical_query\":\"那上个月呢\",\"expanded_queries\":[]}",
        "{\"canonical_query\":7,\"expanded_queries\":[\"查询\"]}",
        "{\"canonical_query\":\"查询\",\"expanded_queries\":[null]}",
        "{\"canonical_query\":\"查询\",\"expanded_queries\":[\" \"]}",
        "{\"canonical_query\":\"查询\",\"expanded_queries\":[7]}",
        "{\"canonical_query\":\"查询\",\"canonical_query\":\"另一问题\",\"expanded_queries\":[\"查询\"]}",
        "{\"canonical_query\":\"查询\",\"expanded_queries\":[\"查询\"],\"extra\":true}",
        "{\"canonical_query\":\"查询\",\"expanded_queries\":[\"查询\"]} {}"})
    void invalidEnhancementStopsBeforeAnyDownstreamSql(String response) throws Exception {
        var fixture = fixture(response);
        assertThatThrownBy(() -> fixture.graph.stream(input()).collectList().block(Duration.ofSeconds(5)))
            .hasStackTraceContaining("ModelOutputInvalidException");
        assertThat(fixture.sqlCalls).hasValue(0);
        verify(fixture.llm, times(1)).callUserWithin(anyString(), nullable(Duration.class));
        verifyNoMoreInteractions(fixture.llm);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validEnhancementReachesDownstreamOnce(boolean fenced) throws Exception {
        var fixture = fixture(fenced ? "```json\n" + VALID + "\n```" : VALID);
        fixture.graph.stream(input()).collectList().block(Duration.ofSeconds(5));
        assertThat(fixture.sqlCalls).hasValue(1);
        assertThat(fixture.canonical.get()).isEqualTo("查询2026年1月已支付订单金额");
        verify(fixture.llm, times(1)).callUserWithin(anyString(), nullable(Duration.class));
        verifyNoMoreInteractions(fixture.llm);
    }

    @Test
    void expiredRunNeverDispatchesAnotherModelRequestOrSql() throws Exception {
        var fixture = fixture(VALID);
        assertThatThrownBy(() -> fixture.graph.stream(Map.of(INPUT_KEY, "那上个月呢", RUN_DEADLINE_EPOCH_MILLIS, 1L))
            .collectList().block(Duration.ofSeconds(5))).hasStackTraceContaining("RunDeadlineExceededException");
        assertThat(fixture.sqlCalls).hasValue(0);
        verifyNoInteractions(fixture.llm);
    }

    private Map<String,Object> input() {
        return Map.of(INPUT_KEY, "那上个月呢", MULTI_TURN_CONTEXT, "当前问题延续2026年2月已支付订单金额统计，只改变月份。",
            REQUEST_ANALYSIS, cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis.simpleDataQuery());
    }

    private Fixture fixture(String response) throws Exception {
        var llm = mock(LlmService.class);
        when(llm.callUserWithin(anyString(), nullable(Duration.class)))
            .thenReturn(response.isEmpty() ? Flux.empty() : Flux.just(ChatResponseUtil.createPureResponse(response)));
        var node = new QueryEnhanceNode(llm, mock(ConversationContextPromptRenderer.class));
        var sqlCalls = new AtomicInteger();
        var canonical = new AtomicReference<String>();
        var graph = new StateGraph("enhancement-boundary", () -> Map.of(QUERY_ENHANCE_NODE_OUTPUT, KeyStrategy.REPLACE),
                new DurableGraphStateSerializer())
            .addNode("enhance", node_async(node))
            .addNode("sql", node_async(state -> {
                var result = (QueryEnhanceOutputDTO) state.value(QUERY_ENHANCE_NODE_OUTPUT).orElseThrow();
                canonical.set(result.getCanonicalQuery());
                sqlCalls.incrementAndGet();
                return Map.of();
            }))
            .addEdge(StateGraph.START, "enhance").addEdge("enhance", "sql").addEdge("sql", StateGraph.END)
            .compile(CompileConfig.builder().build());
        return new Fixture(graph, llm, sqlCalls, canonical);
    }

    private record Fixture(CompiledGraph graph, LlmService llm, AtomicInteger sqlCalls, AtomicReference<String> canonical) { }
}
