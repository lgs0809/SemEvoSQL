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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.learning.*;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository;
import cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary;
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
import com.alibaba.cloud.ai.graph.OverAllState;
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
import static cn.lgs.semevosql.constant.Constant.*;
import static cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.*;

/** Synthetic evidence fixtures in disposable PostgreSQL; answers use the production transaction boundary. */
@Testcontainers
class RequirementCorrectionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc; static TransactionTemplate tx;
    QueryRunService runs; RuntimeClarificationService service; RequirementCorrectionService corrections;
    ConversationTurnRepository turns; String current,target,thread,episode,caseId;
    final OperatorContext owner=new OperatorContext("owner","TEST","request","answer");
    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (1,'correction-test','Synthetic correction','synthetic','ACTIVE','owner')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (1,1,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
    }
    @BeforeEach void setup() {
        current=UUID.randomUUID().toString();target=UUID.randomUUID().toString();thread=UUID.randomUUID().toString();
        episode=UUID.randomUUID().toString();caseId=UUID.randomUUID().toString();
        runs=new QueryRunService(new QueryRunRepository(jdbc),"worker");turns=new ConversationTurnRepository(jdbc);
        var principals=new RuntimePrincipalResolver(jdbc);
        var caseRepo=new QueryCaseRepository(jdbc,new QueryCaseAssetReferenceRepository(jdbc));
        var quarantine=new QueryCaseQuarantineService(jdbc,caseRepo,new QueryCaseLineageService(jdbc,caseRepo),
            new QueryCaseGovernanceProperties(),new LocalOperatorService());
        corrections=new RequirementCorrectionService(runs,turns,principals,quarantine,
            new QueryPatternTemplateService(jdbc,new cn.lgs.semevosql.common.json.CanonicalJson()));
        service=new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),null,runs,null,
            mock(ThreadExecutionGuardService.class),mock(SemEvoSQLMetrics.class),principals,null,null,null,
            new LocalOperatorService(),corrections);
        jdbc.update("""
            INSERT INTO qw_query_run(run_id,run_type,project_id,project_version_id,thread_id,episode_id,attempt_id,status,
                idempotency_key,request_payload) VALUES (?,'INTERACTIVE_QUERY',1,1,?,?,?,'SUCCEEDED',?,'{"principalId":"owner","query":"一月支付金额"}')
            """,target,thread,episode,"attempt-"+target,target);
        jdbc.update("""
            INSERT INTO qw_query_run(run_id,run_type,project_id,project_version_id,thread_id,attempt_id,status,
                idempotency_key,owner_instance,lease_expire_time,request_payload)
            VALUES (?,'INTERACTIVE_QUERY',1,1,?,?,'RUNNING',?,'worker',CURRENT_TIMESTAMP+interval '5 minutes','{"principalId":"owner","query":"刚才说错了，要下单金额"}')
            """,current,thread,"attempt-"+current,current);
        jdbc.update("""
            INSERT INTO qw_conversation_turn(id,run_id,thread_id,turn_sequence,user_question,planner_output,status,revision)
            VALUES (?,?,?,1,'一月支付金额','plan','COMPLETED',4)
            """,UUID.randomUUID().toString(),target,thread);
        jdbc.update("""
            INSERT INTO qw_query_example(id,project_id,project_version_id,catalog_hash,episode_id,attempt_id,run_id,
                normalized_question,sql_text,sql_hash,fingerprint,status,typed_ir_json,quality_proof_json)
            VALUES (?,1,1,?,?,?,?,'synthetic','SELECT 1',?,?,'APPROVED','{}','{}')
            """,caseId,"a".repeat(64),episode,"attempt-"+target,target,"b".repeat(64),caseId);
        jdbc.update("""
            INSERT INTO qw_conversation_context_compaction(thread_id,covered_through_sequence,summary_json,source_digest,summary_version,revision,valid,create_time,update_time)
            VALUES (?,1,'{}','digest',1,1,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,thread);
        jdbc.update("INSERT INTO qw_result_artifact(artifact_id,run_id,artifact_type,schema_json,data_json,row_count,content_hash,status) VALUES (?,?,'DIRECT_RESULT','[]','[]',0,?,'READY')",UUID.randomUUID().toString(),target,"c".repeat(64));
        jdbc.update("INSERT INTO qw_run_event(run_id,sequence,event_type,payload,idempotency_key) VALUES (?,1,'POST_EXECUTION_REVIEW','{\"review\":{\"decision\":\"PASS\"}}','fixture-review')",target);
        jdbc.update("UPDATE qw_query_run SET last_event_sequence=1 WHERE run_id=?",target);
        assertTrue(new QueryCaseRequestEvidence(jdbc).eligible(target));

    }
    RuntimeClarification ask() { return tx.execute(s -> service.createRequirementCorrection(current,"刚才说错了，要下单金额",target,4,"查询一月下单金额")); }
    RuntimeClarification answer(RuntimeClarification q,String option,String key) {
        return tx.execute(s -> service.answer(current,q.clarificationId(),new RuntimeClarificationService.AnswerCommand(
            q.revision(),key,option,null,SemanticBindingScope.QUERY,"untrusted-client-name"),owner));
    }
    int count(String type,String run) {return jdbc.queryForObject("SELECT count(*) FROM qw_run_event WHERE run_id=? AND event_type=?",Integer.class,run,type);}
    @Test void confirmedAnswerAtomicallyRevokesCaseAndContextAndNativeResumeUsesExactConfirmedQuery() {
        var q=ask();assertEquals("WAITING_HUMAN",runs.get(current).status().name());
        var answered=answer(q,"CONFIRM_CORRECTION","same-answer");
        assertEquals(answered,answer(q,"CONFIRM_CORRECTION","same-answer"));
        assertEquals(1,count("REQUEST_REQUIREMENT_CORRECTED",target));
        assertEquals(1,count("REQUEST_REQUIREMENT_CORRECTED",current));
        assertEquals("QUARANTINED",jdbc.queryForObject("SELECT status FROM qw_query_example WHERE id=?",String.class,caseId));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_query_case_event WHERE query_example_id=?",Integer.class,caseId));
        assertEquals("CORRECTED",turns.findByRun(target).orElseThrow().status());
        assertFalse(jdbc.queryForObject("SELECT valid FROM qw_conversation_context_compaction WHERE thread_id=?",Boolean.class,thread));
        assertTrue(turns.completedHistory(thread,6).isEmpty());
        assertFalse(new QueryCaseRequestEvidence(jdbc).eligible(target));
        var boundary=new NativeClarificationBoundary(service,new RuntimeClarificationRepository(jdbc),mock(RunExecutionFenceService.class));
        var update=boundary.resume(new OverAllState(Map.of(RUN_ID,current,QUESTION_ID,q.clarificationId(),BASE_QUERY,"刚才说错了")));
        assertEquals(QUERY_ENHANCE_RESOLVE_NODE,update.get(RETURN_NODE));
        assertEquals("查询一月下单金额",((RequestQueryEnhancement)update.get(REQUEST_ENHANCEMENT_OUTPUT)).canonicalQuery());
        assertEquals("SUCCEEDED",runs.get(target).status().name()); // Actual execution history is never rewritten.
    }
    @Test void independentQueryDoesNotReclassifyHistoryAndCannotSaveDurableSemanticBinding() {
        var q=ask();
        assertThrows(IllegalArgumentException.class,()->tx.execute(s -> service.answer(current,q.clarificationId(),
            new RuntimeClarificationService.AnswerCommand(q.revision(),"bad-scope","INDEPENDENT_QUERY",null,SemanticBindingScope.PROJECT,"owner"),owner)));
        var answer=answer(q,"INDEPENDENT_QUERY","new-query");
        assertEquals("查询一月下单金额",service.confirmedRequirementQuery(answer).orElseThrow());
        assertEquals(0,count("REQUEST_REQUIREMENT_CORRECTED",target));
        assertEquals("COMPLETED",turns.findByRun(target).orElseThrow().status());
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM qw_query_example WHERE id=?",String.class,caseId));
    }
    @Test void staleTargetRollsBackAnswerAndKeepsQuestionPending() {
        var q=ask();jdbc.update("UPDATE qw_conversation_turn SET revision=revision+1 WHERE run_id=?",target);
        assertThrows(IllegalStateException.class,()->answer(q,"CONFIRM_CORRECTION","stale"));
        assertEquals("PENDING",service.getPending(current).status().name());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,q.clarificationId()));
        assertEquals(0,count("REQUEST_REQUIREMENT_CORRECTED",target));
    }
    @Test void crossOwnerAndCrossConversationCannotCreateOrAnswerCorrection() {
        jdbc.update("UPDATE qw_query_run SET thread_id='other-thread' WHERE run_id=?",target);
        assertThrows(SecurityException.class,this::ask);
        jdbc.update("UPDATE qw_query_run SET thread_id=? WHERE run_id=?",thread,target);
        var q=ask();var other=new OperatorContext("other-owner","TEST","request","answer");
        assertThrows(SecurityException.class,()->tx.execute(s -> service.answer(current,q.clarificationId(),
            new RuntimeClarificationService.AnswerCommand(q.revision(),"unauthorized","CONFIRM_CORRECTION",null,SemanticBindingScope.QUERY,"owner"),other)));
        assertEquals("PENDING",service.getPending(current).status().name());
    }
    @Test void concurrentIdenticalAnswersCommitOnceAndChangedPayloadCannotReuseTheKey() throws Exception {
        var q=ask();var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<RuntimeClarification> work=()->{start.await();return answer(q,"CONFIRM_CORRECTION","concurrent");};
            var a=executor.submit(work);var b=executor.submit(work);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS).revision(),b.get(10,TimeUnit.SECONDS).revision());
            assertEquals(1,count("REQUEST_REQUIREMENT_CORRECTED",target));
            assertThrows(IllegalArgumentException.class,()->answer(q,"INDEPENDENT_QUERY","concurrent"));
        } finally {executor.shutdownNow();}
    }
    @Test void differentAnswersRaceOnOneRevisionWithoutPartialCorrection() throws Exception {
        var q=ask();var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(String option:List.of("CONFIRM_CORRECTION","INDEPENDENT_QUERY")) results.add(executor.submit(()->{
                start.await();try{answer(q,option,option);return true;}catch(cn.lgs.semevosql.common.OptimisticLockingFailureException | IllegalStateException ex){return false;}
            }));
            start.countDown();int succeeded=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,q.clarificationId()));
            boolean corrected=count("REQUEST_REQUIREMENT_CORRECTED",target)==1;
            assertEquals(corrected,"CORRECTED".equals(turns.findByRun(target).orElseThrow().status()));
            assertEquals(corrected,"QUARANTINED".equals(jdbc.queryForObject("SELECT status FROM qw_query_example WHERE id=?",String.class,caseId)));
        } finally {executor.shutdownNow();}
    }
}
