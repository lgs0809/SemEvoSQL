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
package cn.lgs.semevosql.correction;

import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.conversation.ProjectConversationService;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryCorrectionProvenanceTest {
    final QueryRunService runs=mock(QueryRunService.class);
    final RuntimePrincipalResolver principals=mock(RuntimePrincipalResolver.class);
    final ProjectConversationService conversations=mock(ProjectConversationService.class);
    final UserSemanticPreferenceService preferences=mock(UserSemanticPreferenceService.class);
    final QueryPatternTemplateService patterns=mock(QueryPatternTemplateService.class);
    final QueryCorrectionService service=new QueryCorrectionService(runs,mock(SemanticCatalogLookupService.class),principals,
        preferences,mock(ProjectSemanticAliasWorkflowService.class),patterns,mock(SemanticCorrectionProposalService.class),
        conversations,new LocalOperatorService());
    final OperatorContext actor=new OperatorContext("fixture-user","SYNTHETIC_TEST","request","confirm-binding");
    final QueryCorrectionService.BindingCorrectionCommand command=new QueryCorrectionService.BindingCorrectionCommand(
        "金额","METRIC","paid_amount","支付金额",SemanticBindingScope.QUERY,"confirm-binding");
    QueryRun original(long revision) {
        return QueryRun.builder().runId("original").projectId(1L).projectVersionId(2L).threadId("conversation")
            .status(QueryRun.RunStatus.SUCCEEDED).attemptId("original-attempt").revision(revision)
            .requestPayload("{\"query\":\"查询金额\"}").build();
    }
    void configure() {
        when(runs.get("original")).thenReturn(original(7),original(10));
        when(principals.resolve(any())).thenReturn(actor.operator());
        var rerun=QueryRun.builder().runId("corrected-rerun").projectId(1L).threadId("conversation").status(QueryRun.RunStatus.QUEUED).build();
        when(conversations.rerunWithBinding(eq(1L),eq("conversation"),eq("original"),eq("金额"),eq("METRIC"),
            eq("paid_amount"),eq("支付金额"),eq("confirm-binding"),anyString(),eq(actor.operator())))
            .thenReturn(new ProjectConversationService.SendMessageResult(null,rerun));
    }
    @Test void confirmedCorrectionTagsBothRequestsAndKeepsStableReplayIdentity() throws Exception {
        configure();
        for(int i=0;i<2;i++) assertEquals("corrected-rerun",service.correctBinding(1L,"conversation","original",command,actor).rerunId());
        var payloads=ArgumentCaptor.forClass(String.class);
        verify(runs,times(4)).appendEvent(anyString(),eq("REQUEST_REQUIREMENT_CORRECTED"),eq("query-diagnosis"),
            payloads.capture(),anyString(),eq("confirm-binding:requirement"));
        assertEquals(1,new HashSet<>(payloads.getAllValues()).size());
        var event=JsonUtil.getObjectMapper().readTree(payloads.getValue());
        assertEquals("original",event.path("targetRunId").asText());
        assertEquals("original-attempt",event.path("targetAttemptId").asText());
        assertEquals("corrected-rerun",event.path("rerunId").asText());
        assertEquals(actor.operator(),event.path("confirmedBy").asText());
        assertEquals(64,event.path("targetRequestHash").asText().length());
        verify(runs,times(2)).appendEvent(eq("original"),eq("REQUEST_REQUIREMENT_CORRECTED"),anyString(),anyString(),anyString(),anyString());
        verify(runs,times(2)).appendEvent(eq("corrected-rerun"),eq("REQUEST_REQUIREMENT_CORRECTED"),anyString(),anyString(),anyString(),anyString());
    }
    @Test void otherConversationAndOwnerCannotReclassifyTheTarget() {
        configure();
        assertThrows(IllegalStateException.class,()->service.correctBinding(1L,"other","original",command,actor));
        var other=new OperatorContext("other-user","SYNTHETIC_TEST","request","confirm-binding");
        assertThrows(SecurityException.class,()->service.correctBinding(1L,"conversation","original",command,other));
        verifyNoInteractions(preferences,patterns,conversations);
        verify(runs,never()).appendEvent(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
}
