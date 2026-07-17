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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary;
import cn.lgs.semevosql.util.JsonUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestEnhancementProvenanceTest {
    @Test void eventUsesFrozenRunRevisionForOnlyTheReferencedTurn() throws Exception {
        var runs=mock(QueryRunService.class);
        var node=new QueryEnhanceResolutionNode(mock(RuntimeClarificationService.class),runs);
        var first=new TurnView(1,"一月金额","一月金额",ConversationTurnSummary.fallback("一月金额",""),1D,20,"source-a",4L);
        var second=new TurnView(2,"二月金额","二月金额",ConversationTurnSummary.fallback("二月金额",""),1D,20,"source-b",9L);
        var envelope=new ConversationContextEnvelope(3,null,List.of(first,second),List.of(),null);
        node.apply(new OverAllState(Map.of(RUN_ID,"current",TRACE_THREAD_ID,"thread-a",ORIGINAL_REQUEST,"那三月呢",INPUT_KEY,"那三月呢",
            CONVERSATION_CONTEXT_ENVELOPE,envelope,REQUEST_ENHANCEMENT_OUTPUT,
            new RequestQueryEnhancement("READY","三月金额",List.of("三月金额"),List.of(2L),"",List.of()))));
        var payload=ArgumentCaptor.forClass(String.class);
        verify(runs).appendEvent(eq("current"),isNull(),eq("REQUEST_ENHANCEMENT_COMPLETED"),anyString(),payload.capture(),anyString(),anyString());
        var sources=JsonUtil.getObjectMapper().readTree(payload.getValue()).path("contextSources");
        assertThat(sources.size()).isEqualTo(1);
        assertThat(sources.get(0).path("sourceRunId").asText()).isEqualTo("source-b");
        assertThat(sources.get(0).path("sourceRevision").asLong()).isEqualTo(9L);
        assertThat(sources.get(0).path("identityStatus").asText()).isEqualTo("FROZEN");
    }
}
