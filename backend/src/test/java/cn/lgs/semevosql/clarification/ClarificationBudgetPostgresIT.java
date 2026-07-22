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
package cn.lgs.semevosql.clarification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Real database transactions and public clarification service; no model or forced success states. */
@Testcontainers
class ClarificationBudgetPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static TransactionTemplate tx;
    @BeforeAll static void setup(){
        ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    QueryRunService runs(){return new QueryRunService(new QueryRunRepository(jdbc),"budget-worker");}
    RuntimeClarificationService service(){return new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),
        null,runs(),null,null,mock(SemEvoSQLMetrics.class),null,null,null,null,null);}
    String run(){
        String id=UUID.randomUUID().toString();jdbc.update("""
            INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,owner_instance,lease_expire_time)
            VALUES (?,'INTERACTIVE_QUERY',?,?,'RUNNING',?,'budget-worker',CURRENT_TIMESTAMP+interval '5 minutes')
            """,id,id,"attempt-"+id,id);return id;
    }
    RuntimeClarification ask(String id,String question){return tx.execute(ignored->service().createContextClarification(id,
        "那个查询",question,List.of("查询一月支付金额","查询二月支付金额")));}
    @Test void lexicalBusinessOverlapDoesNotPreemptPlannerButDeclaredAccessPolicyStillInterrupts(){
        var catalog=cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(1L)
            .metrics(List.of(
                cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Metric.builder().metricCode("role_a").modelCode("a")
                    .businessName("甲范围金额").status(cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED).build(),
                cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Metric.builder().metricCode("role_b").modelCode("b")
                    .businessName("乙范围金额").status(cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED).build())).build();
        var lookup=mock(cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService.class);
        when(lookup.loadClarificationContext(any(),any(),anyString(),any(),any(),any())).thenReturn(catalog);
        var principal=mock(RuntimePrincipalResolver.class);when(principal.resolve(any())).thenReturn("policy-owner");
        var binding=mock(RuntimeSemanticBindingService.class);
        when(binding.resolve(any(),any(),any(),any())).thenThrow(new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED"));
        var saved=new RuntimeSemanticBindingService.ResolvedRuntimeBinding("乙范围金额","乙范围金额","METRIC","role_b",
            "Old private meaning", "b","USER",42L,"policy-owner",2,"old-source","old-dependency","The complete previous definition");
        when(binding.planningCandidates(any(),any(),any(),any(),any()))
            .thenReturn(new RuntimeSemanticBindingService.BindingContext(List.of(saved),cn.lgs.semevosql.learning.QueryCaseHints.empty(),List.of("orders_b")));
        var guarded=new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),lookup,runs(),null,null,
            mock(SemEvoSQLMetrics.class),principal,binding,null,null,null);
        String id=run();assertTrue(tx.execute(ignored->guarded.detect(id,1L,1L,"一月乙范围的金额" )).isEmpty());
        assertEquals(0,new RuntimeClarificationRepository(jdbc).questionCount(id));
        verify(lookup).loadClarificationContext(eq(1L),eq(1L),eq("一月乙范围的金额"),any(),eq(Set.of("b")),any());
        verify(binding,never()).resolve(any(),any(),any(),any());
        catalog.setColumns(List.of(cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Column.builder()
            .modelCode("b").columnName("private_value").status(cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED)
            .allowProjection(false).build()));
        var policy=tx.execute(ignored->guarded.detect(id,1L,1L,"查看金额明细" )).orElseThrow();
        assertEquals(cn.lgs.semevosql.semantic.domain.SemanticIssueType.PERMISSION_DENIED,policy.issueType());
        assertEquals("WAITING_HUMAN",jdbc.queryForObject("SELECT status FROM qw_query_run WHERE run_id=?",String.class,id));
        assertEquals(1,new RuntimeClarificationRepository(jdbc).questionCount(id));
    }
    void answer(String id,RuntimeClarification question){
        tx.execute(ignored->service().answer(id,question.clarificationId(),new RuntimeClarificationService.AnswerCommand(
            question.revision(),UUID.randomUUID().toString(),"CONTEXT_1",null,SemanticBindingScope.QUERY,"budget-owner")));
        tx.execute(ignored->runs().transition(id,QueryRun.RunStatus.RUNNING,"test-resume",null,null));
    }
    @Test void firstQuestionAndTwoAdditionalQuestionsShareDurableBudgetAcrossKindsAndRestart(){
        String id=run();var first=ask(id,"指哪个月份？");assertEquals(0,service().additionalQuestionsUsed(id));answer(id,first);
        var second=tx.execute(ignored->service().createPlanningClarification(id,"一月金额",
            new SemanticPlanningOutcome.ClarificationRequired("USER_QUESTION_AMBIGUOUS","采用哪个时间？",
                List.of(new SemanticPlanningOutcome.Option("PAID","支付时间",null,null)),"需要确认")));
        assertEquals(1,service().additionalQuestionsUsed(id));
        tx.execute(ignored->service().answer(id,second.clarificationId(),new RuntimeClarificationService.AnswerCommand(
            second.revision(),UUID.randomUUID().toString(),"PAID",null,SemanticBindingScope.QUERY,"budget-owner")));
        tx.execute(ignored->runs().transition(id,QueryRun.RunStatus.RUNNING,"test-resume",null,null));
        var third=ask(id,"最后确认查询对象？");assertEquals(2,service().additionalQuestionsUsed(id));answer(id,third);
        assertThrows(IllegalStateException.class,()->ask(id,"不能再追加第四个问题"));
        assertEquals(3,new RuntimeClarificationRepository(jdbc).questionCount(id));
        assertEquals(2,service().additionalQuestionsUsed(id));
    }
    @Test void concurrentLastSlotCreatesOneQuestionAndReusesItsIdentity() throws Exception {
        String id=run();answer(id,ask(id,"第一个问题"));answer(id,ask(id,"第二个问题"));
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<RuntimeClarification> work=()->{start.await();return ask(id,"第三个问题");};
            var a=executor.submit(work);var b=executor.submit(work);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS).clarificationId(),b.get(10,TimeUnit.SECONDS).clarificationId());
            assertEquals(3,new RuntimeClarificationRepository(jdbc).questionCount(id));
        } finally {executor.shutdownNow();}
    }
    @Test void contextAnswerCannotPersistUserOrProjectBinding(){
        String id=run();var question=ask(id,"查询什么？");
        for(var scope:List.of(SemanticBindingScope.USER,SemanticBindingScope.PROJECT))
            assertThrows(IllegalArgumentException.class,()->tx.execute(ignored->service().answer(id,question.clarificationId(),
                new RuntimeClarificationService.AnswerCommand(question.revision(),UUID.randomUUID().toString(),
                    "CONTEXT_1",null,scope,"budget-owner"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,question.clarificationId()));
    }

    @Test void plannerCandidatesAlwaysAllowUserMeaningWithoutLosingAssetBindings() {
        String id=run();
        var question=tx.execute(ignored->service().createPlanningClarification(id,"退款率",
            new SemanticPlanningOutcome.ClarificationRequired("USER_QUESTION_AMBIGUOUS","选择哪种业务口径？",
                List.of(new SemanticPlanningOutcome.Option("AMOUNT","按金额","METRIC","amount_rate"),
                    new SemanticPlanningOutcome.Option("OTHER","我补充",null,null),
                    new SemanticPlanningOutcome.Option("ORDERS","按订单","METRIC","order_rate")),"口径不同")));
        assertEquals("amount_rate,order_rate",question.assetKey());
        assertTrue(RuntimeClarificationService.isDurablePhraseBinding(question));
        var answered=tx.execute(ignored->service().answer(id,question.clarificationId(),
            new RuntimeClarificationService.AnswerCommand(question.revision(),"meaning-"+id,"OTHER",
                "成功退款订单数除以全部订单数，同一订单只计一次",SemanticBindingScope.QUERY,"budget-owner")));
        assertEquals("OTHER",answered.selectedOption());
        assertEquals("成功退款订单数除以全部订单数，同一订单只计一次",answered.resolvedValue());
    }

    @Test void persistedLegacyPlannerQuestionAcceptsCustomAnswerAndRetainsRevisionAndIdempotency() {
        String id=run();
        var question=tx.execute(ignored->service().createPlanningClarification(id,"退款率",
            new SemanticPlanningOutcome.ClarificationRequired("METRIC_AMBIGUOUS","选择哪种业务口径？",
                List.of(new SemanticPlanningOutcome.Option("AMOUNT","按金额","METRIC","amount_rate"),
                    new SemanticPlanningOutcome.Option("ORDERS","按订单","METRIC","order_rate")),"口径不同")));
        // This is an old persisted question, not a fabricated successful run or answer.
        jdbc.update("UPDATE qw_runtime_clarification SET options_json=options_json - (SELECT ordinality::int-1 "
            +"FROM jsonb_array_elements(options_json) WITH ORDINALITY o WHERE o.value->>'code'='OTHER') "
            +"WHERE clarification_id=? AND options_json @> '[{\"code\":\"OTHER\"}]'::jsonb",question.clarificationId());
        var restarted=new RuntimeClarificationRepository(jdbc).find(question.clarificationId()).orElseThrow();
        assertEquals(0,restarted.revision());
        assertEquals(1,restarted.options().stream().filter(o->o.code().equals("OTHER")).count());
        var command=new RuntimeClarificationService.AnswerCommand(0,"legacy-"+id,"OTHER",
            "仅本次：成功退款订单数除以全部订单数",SemanticBindingScope.QUERY,"budget-owner");
        var first=tx.execute(ignored->service().answer(id,question.clarificationId(),command));
        var again=tx.execute(ignored->service().answer(id,question.clarificationId(),command));
        assertEquals(first.revision(),again.revision());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,question.clarificationId()));
        assertEquals(2,jdbc.queryForObject("SELECT jsonb_array_length(options_json) FROM qw_runtime_clarification WHERE clarification_id=?",Integer.class,question.clarificationId()));
    }

    @Test void customChoiceDoesNotAllowUnknownOptionsBlankTextOrUnauthorizedScope() {
        String id=run();var question=ask(id,"查询什么？");
        for(var command:List.of(
            new RuntimeClarificationService.AnswerCommand(0,"unknown-"+id,"INVENTED","随意补充",SemanticBindingScope.QUERY,"budget-owner"),
            new RuntimeClarificationService.AnswerCommand(0,"blank-"+id,"OTHER"," ",SemanticBindingScope.QUERY,"budget-owner"),
            new RuntimeClarificationService.AnswerCommand(0,"scope-"+id,"OTHER","查询一月金额",SemanticBindingScope.PROJECT,"budget-owner")))
            assertThrows(IllegalArgumentException.class,()->tx.execute(ignored->service().answer(id,question.clarificationId(),command)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,question.clarificationId()));
        assertEquals("WAITING_HUMAN",jdbc.queryForObject("SELECT status FROM qw_query_run WHERE run_id=?",String.class,id));
    }
}
