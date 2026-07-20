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
package cn.lgs.semevosql.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;

class GroundedRequestSynthesisServiceTest {
    @Test void twoAcceptedResultsAreReadableWithoutExposingInternalJson() throws Exception {
        var tasks=mock(QueryTaskRepository.class);
        when(tasks.list("run")).thenReturn(List.of(new QueryTask("one",0,"一月总计",List.of(),QueryTask.TaskStatus.DONE),
            new QueryTask("two",1,"一月每日趋势",List.of(),QueryTask.TaskStatus.DONE)));
        var plan=SemanticBlueprint.builder().metrics(List.of(SemanticBlueprint.MetricSelection.builder()
            .metricCode("paid_amount").businessName("支付金额").build())).build();
        when(tasks.plan(anyString(),anyString())).thenReturn(plan);
        when(tasks.resultSummaryJson("run","one")).thenReturn(payload("{\"column\":[\"paid_amount\"],\"data\":[{\"paid_amount\":\"270.00\"}]}"));
        when(tasks.resultSummaryJson("run","two")).thenReturn(payload("{\"column\":[\"day\",\"paid_amount\"],\"data\":[{\"day\":\"2026-01-10\",\"paid_amount\":\"150.00\"},{\"day\":\"2026-01-11\",\"paid_amount\":\"80.00\"}]}"));
        String answer=new GroundedRequestSynthesisService(tasks).synthesize("run","两份结果");
        assertThat(answer).contains("支付金额：270.00","2026-01-10；支付金额：150.00","2026-01-11；支付金额：80.00")
            .doesNotContain("resultPayload","resultSet","{", "\"");
    }

    @Test void partialSuccessCannotBePresentedAsACompletedRequest() {
        var tasks=mock(QueryTaskRepository.class);
        when(tasks.list("run")).thenReturn(List.of(new QueryTask("one",0,"金额",List.of(),QueryTask.TaskStatus.DONE),
            new QueryTask("two",1,"趋势",List.of(),QueryTask.TaskStatus.ACTIVE)));
        assertThatThrownBy(()->new GroundedRequestSynthesisService(tasks).synthesize("run","两份结果"))
            .isInstanceOf(IllegalStateException.class);
    }

    private String payload(String table) throws Exception {
        return JsonUtil.getObjectMapper().writeValueAsString(Map.of("report","","resultPayload","{\"resultSet\":"+table+"}"));
    }
}
