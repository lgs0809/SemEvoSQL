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
package cn.lgs.semevosql.service.graph.checkpoint;

import static org.junit.jupiter.api.Assertions.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.task.QueryTask;
import cn.lgs.semevosql.task.RequestExecutionContext;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Native 1.1.0.0 framework and a real PostgreSQL server; model calls are not part of this fixture. */
@Testcontainers
class NativeCheckpointPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;
    @BeforeAll static void migrate(){ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();jdbc=new JdbcTemplate(ds);}
    DatabasePostgresSaver saver() throws Exception{return new DatabasePostgresSaver(ds,new DurableGraphStateSerializer());}
    RunnableConfig config(){return RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();}
    Checkpoint checkpoint(Map<String,Object> state){return Checkpoint.builder().id(UUID.randomUUID().toString()).nodeId("prepare").nextNodeId("confirm").state(state).build();}
    @Test void nativeCheckpointRoundTripsDtoCollectionsTimesEnumsAndDecimalWithoutMemory() throws Exception {
        var plan=SemanticBlueprint.builder().projectId(12L).canonicalQuery("一月华东已支付金额").offset(2_000_000_000L).executable(true).build();
        var identity=new cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding("amount",3,"orders","paid_amount",
            "支付金额",List.of("收款金额"),null,null);
        var dictionary=new cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding("order_state",2,"orders","state",
            "订单状态",List.of("交易状态"),"order_state_dictionary",4);
        plan.setMetrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("bound_amount").modelCode("orders")
            .businessName("支付金额").expression("SUM(amount)").aggregation("EXPRESSION").unit("元")
            .definitionBinding(identity).numericRange(new cn.lgs.semevosql.semantic.domain.NumericValueRange(
                java.math.BigDecimal.ZERO,new java.math.BigDecimal("100.00"),true,false)).build()));
        plan.setDimensions(List.of(SemanticBlueprint.DimensionSelection.builder().dimensionCode("bound_state")
            .modelCode("orders").columnName("status").definitionBinding(dictionary).build()));
        var task=new QueryTask("task-a",0,"一月金额",List.of(),QueryTask.TaskStatus.DONE);
        var context=new RequestExecutionContext("request-a","一月金额",List.of(task));
        context.acceptClarification("task-a","按什么口径？","已支付，不扣退款");
        context.acceptReviewedTask(new RequestExecutionContext.TaskExecutionResult("task-a",plan,Map.of("resultRef","artifact-a"),
            PostExecutionReview.deterministicPass(List.of()),List.of(new RequestExecutionContext.AcceptedEvidence("task-a","SQL_RESULT","金额270元"))));
        Map<String,Object> state=new LinkedHashMap<>();state.put("plan",plan);state.put("request",context);
        state.put("date",LocalDate.of(2026,1,1));state.put("time",Instant.parse("2026-01-01T00:00:00Z"));
        state.put("amount",new BigDecimal("270.00"));state.put("ids",Set.of("a","b"));state.put("long",1L);
        state.put("enhancement",new cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement("READY","二月支付金额",
            List.of("二月已支付订单总金额"),List.of(1L),"",List.of()));
        state.put("sourceTurn",new cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView(
            3L,"二月金额","二月金额",cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.fallback("二月金额",""),1D,20,"source-run",7L));
        state.put("doc",org.springframework.ai.document.Document.builder().id("doc-a").text("合成目录").metadata(Map.of("model","orders")).build());
        var config=config();saver().put(config,checkpoint(state));
        var restored=saver().get(config).orElseThrow().getState();
        assertEquals(plan,restored.get("plan"));assertEquals(state.get("date"),restored.get("date"));assertEquals(state.get("time"),restored.get("time"));
        assertEquals(new BigDecimal("270.00"),restored.get("amount"));assertInstanceOf(Long.class,restored.get("long"));assertEquals(Set.of("a","b"),restored.get("ids"));
        var request=assertInstanceOf(RequestExecutionContext.class,restored.get("request"));assertEquals(context.completedTasks(),request.completedTasks());
        assertEquals(context.clarifications(),request.clarifications());assertEquals(context.acceptedEvidence(),request.acceptedEvidence());
        assertEquals("合成目录",assertInstanceOf(org.springframework.ai.document.Document.class,restored.get("doc")).getText());
        assertEquals(state.get("enhancement"),restored.get("enhancement"));
        assertEquals(state.get("sourceTurn"),restored.get("sourceTurn"));
    }
    @Test void alreadyReadSaverSeesNewCheckpointFromAnotherInstance() throws Exception {
        var a=saver();var b=saver();var config=config();a.put(config,checkpoint(Map.of("version",1)));
        assertEquals(1,a.get(config).orElseThrow().getState().get("version"));
        b.put(config,checkpoint(Map.of("version",2)));
        assertEquals(2,a.get(config).orElseThrow().getState().get("version"));
    }
    @Test void planReviewAndExecutionRepairsShareTwoAttemptsInRealPostgresCheckpoints() throws Exception {
        var policy=new cn.lgs.semevosql.review.QueryRepairPolicy();
        var config=config();
        var state=new LinkedHashMap<String,Object>();
        state.put(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET,
            new cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget(2,0,1,3,2,5));
        state.put(cn.lgs.semevosql.constant.Constant.PLAN_REPAIR_COUNT,50);
        state.put(cn.lgs.semevosql.constant.Constant.HUMAN_FEEDBACK_DATA,Map.of("feedback",false,"feedback_content","改为下单时间"));
        state.putAll(new cn.lgs.semevosql.workflow.node.HumanFeedbackNode(policy).apply(new OverAllState(state)));
        saver().put(config,checkpoint(state));
        var restarted=new LinkedHashMap<>(saver().get(config).orElseThrow().getState());
        restarted.put(cn.lgs.semevosql.constant.Constant.PLANNER_NODE_OUTPUT,"{}");
        restarted.putAll(new cn.lgs.semevosql.workflow.node.PlanExecutorNode(policy).apply(new OverAllState(restarted)));
        saver().put(config,checkpoint(restarted));
        var afterSecond=saver().get(config).orElseThrow().getState();
        assertEquals(new cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget(2,2,1,3,2,7),
            afterSecond.get(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET));
        assertThrows(IllegalStateException.class,()->new cn.lgs.semevosql.workflow.node.HumanFeedbackNode(policy)
            .apply(new OverAllState(afterSecond)));
        var approval=new LinkedHashMap<>(afterSecond);
        approval.put(cn.lgs.semevosql.constant.Constant.HUMAN_FEEDBACK_DATA,Map.of("feedback",true));
        assertEquals(cn.lgs.semevosql.constant.Constant.SEMANTIC_EXECUTION_NODE,
            new cn.lgs.semevosql.workflow.node.HumanFeedbackNode(policy).apply(new OverAllState(approval)).get("human_next_node"));
    }
    @Test void databaseOutageDoesNotReturnCachedSuccess() throws Exception {
        AtomicBoolean unavailable=new AtomicBoolean();
        var failing=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()){
            @Override public Connection getConnection() throws SQLException {if(unavailable.get())throw new SQLException("injected database unavailable");return super.getConnection();}
        };
        var saver=new DatabasePostgresSaver(failing,new DurableGraphStateSerializer());var config=config();
        saver.put(config,checkpoint(Map.of("state","persisted")));assertTrue(saver.get(config).isPresent());
        unavailable.set(true);assertThrows(RuntimeException.class,()->saver.get(config));
        assertThrows(Exception.class,()->saver.put(config,checkpoint(Map.of("state","must not persist"))));
        unavailable.set(false);assertEquals("persisted",saver.get(config).orElseThrow().getState().get("state"));
    }
    @Test void rejectsRuntimeObjectsSecretsAndIncompatibleSchema() throws Exception {
        var serializer=new DurableGraphStateSerializer();
        for(Object bad:List.of(new Object(),new CompletableFuture<>(),new cn.lgs.semevosql.entity.ModelConfig()))
            assertThrows(IOException.class,()->serializer.dataToBytes(Map.of("bad",bad)));
        assertThrows(IOException.class,()->serializer.dataToBytes(Map.of("nested",Map.of("apiKey","not-a-real-secret"))));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){
            byte[] bad="{\"schemaVersion\":999,\"state\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bad.length);out.write(bad);}
        assertThrows(IOException.class,()->serializer.dataFromBytes(bytes.toByteArray()));
    }
    CompiledGraph graph(DatabasePostgresSaver saver,AtomicInteger before,AtomicInteger after) throws Exception {
        var serializer=new DurableGraphStateSerializer();
        var graph=new StateGraph("native-restart-fixture",()->Map.of("prepared",KeyStrategy.REPLACE,"answer",KeyStrategy.REPLACE,"done",KeyStrategy.REPLACE,"plan",KeyStrategy.REPLACE),serializer)
            .addNode("prepare",node_async(s->{before.incrementAndGet();return Map.of("prepared",true);}))
            .addNode("confirm",node_async(s->{after.incrementAndGet();return Map.of("done",s.value("answer").orElse("missing"));}))
            .addEdge(StateGraph.START,"prepare").addEdge("prepare","confirm").addEdge("confirm",StateGraph.END);
        return graph.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).interruptBefore("confirm").build());
    }
    @Test void nativeInterruptResumesInNewCompiledGraphWithoutRepeatingCompletedNode() throws Exception {
        var before=new AtomicInteger();var after=new AtomicInteger();var config=config();
        var first=graph(saver(),before,after);first.invoke(Map.of("answer",""),config);
        assertEquals(1,before.get());assertEquals(0,after.get());assertEquals("confirm",first.getState(config).next());
        var restarted=graph(saver(),before,after);
        var updated=restarted.updateState(config,Map.of("answer","已支付金额"));
        restarted.invoke((Map<String,Object>) null,RunnableConfig.builder(updated).addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,Map.of("answer","已支付金额")).build());
        assertEquals(1,before.get());assertEquals(1,after.get());
        assertEquals("已支付金额",saver().get(config).orElseThrow().getState().get("done"));
    }

    @Test void requestedPersonalResultSurvivesRealNativeApprovalAndNewWorkflowInstance() throws Exception {
        var contract=new cn.lgs.semevosql.semantic.domain.SemanticResultContract(Set.of(),List.of(
            new cn.lgs.semevosql.semantic.domain.SemanticResultContract.PersonalMeasure("p_7_2","资源测算金额",7L,2,"sha256:source")),List.of(
            new cn.lgs.semevosql.semantic.domain.SemanticResultContract.QueryMeasure(
                "q_1c67f9d2c51e480998917bf54a831fbb_1","仅本次测算","1c67f9d2-c51e-4809-9891-7bf54a831fbb",1L,
                "仅本次按原订单月份计算，完整分子分母及单位保持。","sha256:query-source")));
        var relationship=SemanticBlueprint.RelationshipSelection.builder().relationshipCode("order_refunds")
            .sourceModelCode("orders").targetModelCode("refunds")
            .cardinality(cn.lgs.semevosql.semantic.domain.RelationshipCardinality.ONE_TO_MANY)
            .joinType("LEFT").joinCondition("orders.order_id=refunds.order_id").build();
        var plan=SemanticBlueprint.builder().projectId(12L).resultContract(contract)
            .relationships(List.of(relationship)).compilerMode("CONSTRAINED_GENERATION").build();
        var prepared=new AtomicInteger();var approved=new AtomicInteger();var config=config();
        var first=graph(saver(),prepared,approved);
        first.invoke(Map.of("plan",plan,"answer",""),config);
        assertEquals("confirm",first.getState(config).next());assertEquals(1,prepared.get());
        var restarted=graph(saver(),prepared,approved);
        assertEquals(plan,restarted.getState(config).state().value("plan").orElseThrow());
        var updated=restarted.updateState(config,Map.of("answer","批准"));
        restarted.invoke((Map<String,Object>)null,RunnableConfig.builder(updated)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,Map.of("answer","批准")).build());
        assertEquals(1,prepared.get());assertEquals(1,approved.get());
        var restored=(SemanticBlueprint)saver().get(config).orElseThrow().getState().get("plan");
        assertEquals(contract,restored.getResultContract());assertEquals(List.of(relationship),restored.getRelationships());
        assertEquals("批准",saver().get(config).orElseThrow().getState().get("done"));
    }

    @Test void applicationStreamingGeneratorPersistsOnlyItsCompletedData() throws Exception {
        var config=config();
        var graph=new StateGraph("stream-checkpoint",()->Map.of("output",KeyStrategy.REPLACE),new DurableGraphStateSerializer())
            .addNode("stream",node_async(state->Map.of("output",cn.lgs.semevosql.util.FluxUtil.createStreamingGeneratorWithMessages(
                cn.lgs.semevosql.workflow.node.PlannerNode.class,state,text->Map.of("output",text),
                reactor.core.publisher.Flux.just(cn.lgs.semevosql.util.ChatResponseUtil.createPureResponse("真实框架流"))))))
            .addEdge(StateGraph.START,"stream").addEdge("stream",StateGraph.END)
            .compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver()).build()).build());
        graph.stream(Map.of(),config).collectList().block(Duration.ofSeconds(10));
        assertEquals("真实框架流",saver().get(config).orElseThrow().getState().get("output"));
    }
}
