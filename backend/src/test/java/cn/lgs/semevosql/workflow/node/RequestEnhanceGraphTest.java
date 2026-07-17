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
import static cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.learning.QueryCaseHistoryService;
import cn.lgs.semevosql.config.SemEvoSQLConfiguration;
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
import cn.lgs.semevosql.properties.CodeExecutorProperties;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer;
import cn.lgs.semevosql.service.graph.checkpoint.*;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.task.*;
import cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis;
import cn.lgs.semevosql.util.*;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

/** Production graph edges and real streaming protocol, deterministic provider fixture. */
class RequestEnhanceGraphTest {
    static final String READY = """
        {"status":"READY","canonical_query":"查询2026年2月已支付订单支付金额，按支付时间，不扣退款","expanded_queries":["2026年2月已支付金额"],"context_turns":[1],"question":"","options":[]}
        """;
    static final String AMBIGUOUS = """
        {"status":"NEEDS_CONTEXT","canonical_query":"","expanded_queries":[],"context_turns":[1,2],"question":"您要继续查询支付金额还是订单数？","options":["查询2026年2月支付金额","查询2026年2月订单数"]}
        """;
    static final String CONTEXT = "<turn sequence=\"1\">一月支付金额</turn>\n<turn sequence=\"2\">一月订单数</turn>";

    @Test void normalGraphEnhancesBeforeDetectionAndAnalysisWhilePreservingOriginal() throws Exception {
        var f = fixture(READY, RequestAnalysis.simpleDataQuery());
        f.graph.stream(input(), f.config).collectList().block(Duration.ofSeconds(10));
        var state = f.graph.getState(f.config).state();
        String full = "查询2026年2月已支付订单支付金额，按支付时间，不扣退款";
        verify(f.analysis).analyze(full);
        assertThat(state.value(ORIGINAL_REQUEST)).contains("那二月呢");
        assertThat(state.value(ROOT_CANONICAL_QUERY)).contains(full);
        assertThat(state.value(ACTIVE_QUERY)).contains(full);
        var order = inOrder(f.llm, f.boundary, f.history, f.analysis);
        order.verify(f.llm).callUserWithin(anyString(), nullable(Duration.class));
        order.verify(f.boundary).detect(argThat(s -> full.equals(s.value(INPUT_KEY).orElse(null))));
        order.verify(f.history).recallRequest(argThat(s -> full.equals(s.value(ROOT_CANONICAL_QUERY).orElse(null))));
        order.verify(f.history).requestContext(any());
        order.verify(f.analysis).analyze(full);
    }

    @Test void todoQuestionWinsWithoutReplacingOriginalOrRootQuestion() throws Exception {
        var one = new QueryTask("task-1",0,"二月支付金额合计",List.of(),QueryTask.TaskStatus.ACTIVE);
        var two = new QueryTask("task-2",1,"二月支付金额每日趋势",List.of(),QueryTask.TaskStatus.PENDING);
        var f = fixture(READY,new RequestAnalysis(QueryDecompositionService.RequestType.DATA_QUERY,true,List.of(one,two)));
        when(f.tasks.activateFirst("run-a")).thenReturn(one);
        f.graph.stream(input(),f.config).collectList().block(Duration.ofSeconds(10));
        var state=f.graph.getState(f.config).state();
        assertThat(state.value(ACTIVE_QUERY)).contains(one.question());
        assertThat(StateUtil.getCanonicalQuery(state)).isEqualTo(one.question());
        assertThat(state.value(ROOT_CANONICAL_QUERY)).contains("查询2026年2月已支付订单支付金额，按支付时间，不扣退款");
        assertThat(state.value(ORIGINAL_REQUEST)).contains("那二月呢");
        verify(f.tasks).initialize(eq("run-a"),anyList());
        verify(f.runs).allocateTaskDeadline("run-a","attempt-a",2);
        assertThat(state.value(QueryCaseHistoryService.REQUEST_HISTORY_RECALL)).contains("root-snapshot");
    }

    @Test void requestAnalysisConsumesAuthorizedRootStructureBeforeTodoActivation() throws Exception {
        var f=fixture(READY,RequestAnalysis.simpleDataQuery());
        String historical="{\"cases\":[{\"taskOutline\":[{\"question\":\"historical total\"}]}]}";
        when(f.history.requestContext(any())).thenReturn(historical);
        when(f.analysis.analyze(anyString(),eq(historical))).thenReturn(RequestAnalysis.simpleDataQuery());
        f.graph.stream(input(),f.config).collectList().block(Duration.ofSeconds(10));
        verify(f.analysis).analyze("查询2026年2月已支付订单支付金额，按支付时间，不扣退款",historical);
        verify(f.analysis,never()).analyze(anyString());
    }

    @Test void namedDefinitionHistoryManagementDoesNotRequireCurrentConversationHistory() throws Exception {
        String request="撤回我的费用口径最早一次查询的共享认可，查询结果仍然有效，以后的范围保持不变。";
        String response=JsonUtil.getObjectMapper().writeValueAsString(Map.of(
            "status","READY","canonical_query",request,"expanded_queries",List.of(request),
            "context_turns",List.of(),"question","","options",List.of()));
        var f=fixture(response,new RequestAnalysis(QueryDecompositionService.RequestType.SEMANTIC_UPDATE,false,List.of()));
        var input=new HashMap<String,Object>(input());
        input.put(INPUT_KEY,request);input.put(ORIGINAL_REQUEST,request);input.put(MULTI_TURN_CONTEXT,"(无)");
        f.graph.stream(input,f.config).collectList().block(Duration.ofSeconds(10));
        var state=f.graph.getState(f.config).state();
        assertThat(f.graph.getState(f.config).next()).isEqualTo(SEMANTIC_PLAN_NODE);
        assertThat(state.value(ROOT_CANONICAL_QUERY)).contains(request);
        assertThat(state.value(ORIGINAL_REQUEST)).contains(request);
        verify(f.analysis).analyze(request);
        verifyNoInteractions(f.clarifications,f.tasks);
        verify(f.runs).allocateTaskDeadline("run-a","attempt-a",1);
    }

    @ParameterizedTest @ValueSource(strings={SQL_GENERATION_ONLY,APPROVED_PLAN_RECOVERY})
    void governedInternalPathsSkipChatEnhancementAndDecomposition(String flag) throws Exception {
        var f=fixture("must not be called",RequestAnalysis.simpleDataQuery());
        var input=new HashMap<String,Object>(input());input.put(flag,true);
        f.graph.stream(input,f.config).collectList().block(Duration.ofSeconds(10));
        verifyNoInteractions(f.llm,f.analysis);
        assertThat(f.graph.getState(f.config).state().value(ACTIVE_QUERY)).contains("那二月呢");
    }

    @Test void ambiguousReferenceStopsBeforeAnalysisAndCarriesBudgetIntoNativeWait() throws Exception {
        var f=fixture(AMBIGUOUS,RequestAnalysis.simpleDataQuery());
        f.graph.stream(input(),f.config).collectList().block(Duration.ofSeconds(10));
        var state=f.graph.getState(f.config).state();
        assertThat(f.graph.getState(f.config).next()).isEqualTo(RESUME_NODE);
        verifyNoInteractions(f.analysis);
        verify(f.boundary,never()).detect(any());
        assertThat(state.value(RETURN_NODE)).contains(QUERY_ENHANCE_NODE);
        assertThat(state.value(ROOT_CANONICAL_QUERY)).isEmpty();
        var budget=(QueryRepairPolicy.RepairBudget)state.value(QUERY_REPAIR_BUDGET).orElseThrow();
        assertThat(budget.clarificationsUsed()).isZero();
        assertThat(state.value(REQUEST_ENHANCEMENT_OUTPUT).orElseThrow()).isInstanceOf(RequestQueryEnhancement.class);
    }

    @Test void exhaustionDoesNotOpenAnotherHumanWait() throws Exception {
        var f=fixture(AMBIGUOUS,RequestAnalysis.simpleDataQuery());
        var input=new HashMap<String,Object>(input());
        input.put(QUERY_REPAIR_BUDGET,new QueryRepairPolicy.RepairBudget(1,0,0,0,2,3));
        when(f.clarifications.createContextClarification(anyString(),anyString(),anyString(),anyList()))
            .thenThrow(new IllegalStateException("CLARIFICATION_BUDGET_EXHAUSTED"));
        assertThatThrownBy(()->f.graph.stream(input,f.config).collectList().block(Duration.ofSeconds(10)))
            .hasStackTraceContaining("CLARIFICATION_BUDGET_EXHAUSTED");
        verifyNoInteractions(f.analysis);
    }

    @ParameterizedTest @ValueSource(strings={"{}","not-json",
        "{\"status\":\"READY\",\"canonical_query\":\"那二月呢\",\"expanded_queries\":[],\"context_turns\":[],\"question\":\"\",\"options\":[]}",
        "{\"status\":\"NEEDS_CONTEXT\",\"canonical_query\":\"猜测指标\",\"expanded_queries\":[],\"context_turns\":[],\"question\":\"指什么\",\"options\":[]}"})
    void malformedOrContradictoryResultCannotContinue(String response) throws Exception {
        var f=fixture(response,RequestAnalysis.simpleDataQuery());
        assertThatThrownBy(()->f.graph.stream(input(),f.config).collectList().block(Duration.ofSeconds(10)))
            .hasStackTraceContaining("ModelOutputInvalidException");
        verifyNoInteractions(f.analysis,f.clarifications);
    }

    @Test void contextReferencesMustActuallyBeVisibleAndUnique() {
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(READY.replace("[1]","[999]"),CONTEXT))
            .isInstanceOf(cn.lgs.semevosql.exception.ModelOutputInvalidException.class);
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(READY.replace("[1]","[1,1]"),CONTEXT))
            .isInstanceOf(cn.lgs.semevosql.exception.ModelOutputInvalidException.class);
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(READY.replace("[1]","[1.5]"),CONTEXT))
            .isInstanceOf(cn.lgs.semevosql.exception.ModelOutputInvalidException.class);
    }

    @Test void independentQuestionHasExplicitReadyResultAndNoHistoricalSource() {
        var result=QueryEnhanceNode.parseRequestResult(READY.replace("[1]","[]"),"(无)");
        assertThat(result.ready()).isTrue();assertThat(result.contextTurns()).isEmpty();
    }

    @Test void correctionRequiresExactlyOneKnownTargetAndCannotSkipConfirmation() {
        var correction=READY.replace("READY","CONFIRM_CORRECTION");
        assertThat(QueryEnhanceNode.parseRequestResult(correction,CONTEXT).correction()).isTrue();
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(correction.replace("[1]","[]"),CONTEXT))
            .isInstanceOf(cn.lgs.semevosql.exception.ModelOutputInvalidException.class);
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(correction.replace("[1]","[1,2]"),CONTEXT))
            .isInstanceOf(cn.lgs.semevosql.exception.ModelOutputInvalidException.class);
        assertThatThrownBy(()->QueryEnhanceNode.parseRequestResult(correction,CONTEXT).queryOutput())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void correctionWaitsOnNativeBoundaryThenResumesWithoutReinterpretingConfirmedQuery() throws Exception {
        var f=fixture(READY.replace("READY","CONFIRM_CORRECTION"),RequestAnalysis.simpleDataQuery());
        var input=new HashMap<String,Object>(input());
        var turn=new cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView(1,"一月支付金额","一月支付金额",
            cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.fallback("一月支付金额",""),1D,20,"old-run",4L);
        input.put(CONVERSATION_CONTEXT_ENVELOPE,new cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope(3,null,List.of(turn),List.of(),null));
        when(f.clarifications.createRequirementCorrection(anyString(),anyString(),eq("old-run"),eq(4L),anyString()))
            .thenReturn(RuntimeClarification.builder().clarificationId("correction-question").runId("run-a").build());
        f.graph.stream(input,f.config).collectList().block(Duration.ofSeconds(10));
        assertThat(f.graph.getState(f.config).next()).isEqualTo(RESUME_NODE);
        verifyNoInteractions(f.analysis);
        when(f.boundary.resume(any())).thenReturn(Map.of(QUESTION_ID,"",RETURN_NODE,QUERY_ENHANCE_RESOLVE_NODE,
            APPLIED_ANSWERS,Map.of("correction-question",1L),REQUEST_ENHANCEMENT_OUTPUT,
            new RequestQueryEnhancement("READY","用户确认的完整问题",List.of("用户确认的完整问题"),List.of(),"",List.of())));
        f.graph.stream((Map<String,Object>)null,RunnableConfig.builder(f.config)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,Map.of("accepted",true)).build()).collectList().block(Duration.ofSeconds(10));
        verify(f.analysis).analyze("用户确认的完整问题");
        verify(f.llm,times(1)).callUserWithin(anyString(),nullable(Duration.class));
        assertThat(f.graph.getState(f.config).state().value(ROOT_CANONICAL_QUERY)).contains("用户确认的完整问题");
    }

    @Test void correctionWithoutLegacySourceIdentityAsksForContextRatherThanInventingTarget() throws Exception {
        var f=fixture(READY.replace("READY","CONFIRM_CORRECTION"),RequestAnalysis.simpleDataQuery());
        f.graph.stream(input(),f.config).collectList().block(Duration.ofSeconds(10));
        assertThat(f.graph.getState(f.config).next()).isEqualTo(RESUME_NODE);
        verify(f.clarifications).createContextClarification(eq("run-a"),anyString(),contains("需要纠正"),eq(List.of()));
        verify(f.clarifications,never()).createRequirementCorrection(anyString(),anyString(),anyString(),anyLong(),anyString());
    }

    Map<String,Object> input(){return Map.of(INPUT_KEY,"那二月呢",ORIGINAL_REQUEST,"那二月呢",MULTI_TURN_CONTEXT,CONTEXT,
        RUN_ID,"run-a",ATTEMPT_ID,"attempt-a",TRACE_THREAD_ID,"thread-a");}

    @SuppressWarnings({"unchecked","rawtypes"})
    Fixture fixture(String response,RequestAnalysis result) throws Exception {
        var llm=mock(LlmService.class);
        when(llm.callUserWithin(anyString(),nullable(Duration.class)))
            .thenReturn(Flux.just(ChatResponseUtil.createPureResponse(response)));
        var analysis=mock(QueryDecompositionService.class);when(analysis.analyze(anyString())).thenReturn(result);
        var tasks=mock(QueryTaskRepository.class);var runs=mock(QueryRunService.class);
        var history=mock(QueryCaseHistoryService.class);
        when(history.recallRequest(any())).thenReturn(Map.of(QueryCaseHistoryService.REQUEST_HISTORY_RECALL,"root-snapshot"));
        when(history.requestContext(any())).thenReturn("");
        var fence=mock(RunExecutionFenceService.class);
        when(fence.assertActive(any(OverAllState.class))).thenReturn(new RunExecutionFenceService.ExecutionToken("run-a","attempt-a",null));
        var clarifications=mock(RuntimeClarificationService.class);
        when(clarifications.createContextClarification(anyString(),anyString(),anyString(),anyList()))
            .thenReturn(RuntimeClarification.builder().clarificationId("context-question").runId("run-a").build());
        var boundary=mock(NativeClarificationBoundary.class);when(boundary.detect(any())).thenReturn(Map.of(QUESTION_ID,""));
        var context=mock(ApplicationContext.class);
        var renderer=mock(ConversationContextPromptRenderer.class);
        when(renderer.render(any(),any())).thenReturn(CONTEXT);
        Map<Class<?>,NodeAction> actual=Map.of(
            QueryEnhanceNode.class,new QueryEnhanceNode(llm,renderer),
            QueryEnhanceResolutionNode.class,new QueryEnhanceResolutionNode(clarifications,runs),
            RequestHistoryRecallNode.class,new RequestHistoryRecallNode(history),
            RequestAnalysisNode.class,new RequestAnalysisNode(analysis,tasks,runs,fence,history));
        when(context.getBean(any(Class.class))).thenAnswer(call->{
            Class<?> type=call.getArgument(0);
            if(com.alibaba.cloud.ai.graph.action.EdgeAction.class.isAssignableFrom(type))
                return (com.alibaba.cloud.ai.graph.action.EdgeAction) state->StateGraph.END;
            return actual.getOrDefault(type,state->Map.of());
        });
        var graph=new SemEvoSQLConfiguration().nl2sqlGraph(new NodeBeanUtil(context,fence),new CodeExecutorProperties(),boundary)
            .compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .interruptBefore(SEMANTIC_PLAN_NODE,RESUME_NODE).build());
        return new Fixture(graph,RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build(),llm,analysis,tasks,clarifications,boundary,history,runs);
    }
    record Fixture(CompiledGraph graph,RunnableConfig config,LlmService llm,QueryDecompositionService analysis,
        QueryTaskRepository tasks,RuntimeClarificationService clarifications,NativeClarificationBoundary boundary,
        QueryCaseHistoryService history,QueryRunService runs) { }
}
