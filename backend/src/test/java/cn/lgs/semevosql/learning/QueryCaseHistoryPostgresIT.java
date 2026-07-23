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
package cn.lgs.semevosql.learning;

import static cn.lgs.semevosql.constant.Constant.*;
import static cn.lgs.semevosql.learning.QueryCaseHistoryService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Synthetic successful/failed source records in disposable PostgreSQL; tests mechanics, not model relevance. */
@Testcontainers
class QueryCaseHistoryPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    static final AtomicLong PROJECTS = new AtomicLong();
    static final String HASH = "d".repeat(64);
    long project;
    QueryRunService runs;
    QueryCaseRetrievalIndexService index;
    QueryCaseRecallSnapshotRepository repository;
    QueryCaseHistoryService history;
    QueryRun consumer;

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl() + "&stringtype=unspecified", PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void setup() {
        project = PROJECTS.incrementAndGet();
        var proxy = new ProxyFactory(new QueryRunService(new QueryRunRepository(jdbc), "history-test"));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        runs = (QueryRunService) proxy.getProxy();
        index = spy(new QueryCaseRetrievalIndexService(jdbc, Optional.empty(), Optional.empty(), Optional.empty(), Runnable::run));
        repository = new QueryCaseRecallSnapshotRepository(jdbc, new QueryCaseRepository(jdbc, new QueryCaseAssetReferenceRepository(jdbc)));
        history = service();
        String id = UUID.randomUUID().toString();
        consumer = runs.create(new QueryRunService.CreateRunCommand(QueryRun.RunType.INTERACTIVE_QUERY,
            project, 1L, id, id, id, "{}"));
        consumer = runs.bindExecution(consumer.runId(), "episode-"+id, "attempt-"+id, id);
    }

    QueryCaseHistoryService service() {
        return new QueryCaseHistoryService(repository, index, runs, new RunExecutionFenceService(runs),
            new ConversationContextDependencyFingerprintService(jdbc, new CanonicalJson()), transactions);
    }

    @AfterEach void close() { history.close(); }

    String fixture(String question) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key,episode_id,attempt_id,project_id,project_version_id) VALUES (?,'INTERACTIVE_QUERY','SUCCEEDED',?,?,?,?,1)", id,id,id,id,project);
        jdbc.update("INSERT INTO qw_attempt(id,episode_id,attempt_no,status) VALUES (?,?,1,'SUCCEEDED')",id,id);
        jdbc.update("INSERT INTO qw_sql_trace(id,attempt_id,idempotency_key,sql_text,status) VALUES (?,?,?,'SELECT 1','SUCCEEDED')",id,id,id);
        jdbc.update("INSERT INTO qw_result_artifact(artifact_id,run_id,artifact_type,schema_json,data_json,row_count,content_hash,status) VALUES (?,?,'MERGED_RESULT','[]','[]',0,?,'READY')",id,id,HASH);
        jdbc.update("INSERT INTO qw_run_event(run_id,sequence,event_type,payload,idempotency_key) VALUES (?,1,'POST_EXECUTION_REVIEW','{\"review\":{\"decision\":\"PASS\"}}',?)",id,id);
        jdbc.update("""
            INSERT INTO qw_query_example(id,project_id,project_version_id,catalog_hash,episode_id,attempt_id,run_id,
              original_question,normalized_question,typed_ir_json,quality_proof_json,sql_text,sql_hash,fingerprint,status)
            VALUES (?,?,1,?,?,?,?,?,?,'{"models":[{"modelCode":"orders","physicalTable":"orders"}]}','{}','SELECT 1',?,?,'APPROVED')
            """,id,project,HASH,id,id,id,question,question,HASH,id.replace("-","").repeat(2));
        jdbc.update("INSERT INTO qw_query_example_asset_ref(id,query_example_id,asset_type,asset_key,asset_fingerprint,catalog_hash) VALUES (?,?,'MODEL','orders',?,?)",id,id,HASH,HASH);
        index.indexApprovedCase(id,question); return id;
    }

    OverAllState state(String question) {
        return new OverAllState(Map.of(RUN_ID,consumer.runId(),ATTEMPT_ID,consumer.attemptId(),PROJECT_ID,project,
            PROJECT_VERSION_ID,1L,CATALOG_HASH,HASH,PRINCIPAL_ID,"alice",INPUT_KEY,question,ROOT_CANONICAL_QUERY,question));
    }

    HistoryInput input(String root, String query, String task, String rootId, int revision) {
        return new HistoryInput(consumer.runId(),consumer.attemptId(),project,1L,HASH,"alice",root,query,task,rootId,revision);
    }

    String root(String question) { return history.recallRequest(state(question)).get(REQUEST_HISTORY_RECALL).toString(); }

    @Test void singleRequestUsesOneFrozenRecallAcrossRestartAndSqlRepair() {
        String caseId=fixture("alpha"); String snapshotId=root("alpha");
        var input=input("alpha","alpha","",snapshotId,0);
        assertEquals(List.of(caseId),history.planningContext(input).hints().sourceExampleIds());
        assertEquals(List.of(caseId),history.existingContext(input).hints().sourceExampleIds());
        assertEquals(List.of(caseId),history.existingContext(input).hints().sourceExampleIds());
        history.close(); history=service();
        assertEquals(snapshotId,root("alpha"));
        assertEquals(List.of(caseId),history.planningContext(input).hints().sourceExampleIds());
        verify(index,times(1)).search(project,1L,HASH,null,"alice","alpha");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_query_case_recall_snapshot WHERE run_id=?",Integer.class,consumer.runId()));
    }

    @Test void eachTodoRecallsIndependentlyAndMergesRootOneWithTaskTwo() {
        String a=fixture("root"),b=fixture("daily"),c=fixture("daily"); String rootId=root("root");
        var first=history.planningContext(input("root","daily","task-1",rootId,0));
        assertEquals(Set.of(a,b,c),new HashSet<>(first.hints().sourceExampleIds()));
        assertEquals(2,first.snapshotReferences().size());
        history.planningContext(input("root","daily","task-2",rootId,0));
        history.planningContext(input("root","daily","task-1",rootId,0));
        verify(index,times(1)).search(project,1L,HASH,null,"alice","root");
        verify(index,times(2)).search(project,1L,HASH,null,"alice","daily");
    }

    @Test void restartRepairsAMissingAuditReceiptWithoutRepeatingRetrieval() {
        fixture("alpha"); String snapshotId=root("alpha");
        var frozen=repository.findById(consumer.runId(),snapshotId).orElseThrow();
        // Disposable test database: reproduce the committed snapshot / unpublished receipt crash window.
        jdbc.update("DELETE FROM qw_run_event WHERE run_id=? AND event_type='CASE_RECALL_COMPLETED'",consumer.runId());
        history.close(); history=service();
        assertEquals(snapshotId,root("alpha"));
        assertEquals(frozen,repository.findById(consumer.runId(),snapshotId).orElseThrow());
        verify(index,times(1)).search(project,1L,HASH,null,"alice","alpha");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_run_event WHERE run_id=? AND event_type='CASE_RECALL_COMPLETED'",Integer.class,consumer.runId()));
    }

    @Test void duplicateRootAndTaskCaseKeepsBothSourcesWithoutRefillingTheQuota() {
        String id=fixture("alpha"); String rootId=root("alpha");
        var context=history.planningContext(input("alpha","alpha","task-1",rootId,0));
        assertEquals(List.of(id),context.hints().sourceExampleIds());
        var cases=QueryCaseRequestEvidence.read(context.prompt()).path("cases");
        assertEquals(1,cases.size()); assertEquals(2,cases.get(0).path("matchSources").size());
        verify(index,times(2)).search(project,1L,HASH,null,"alice","alpha");
    }

    @Test void retractionAndPermissionChangeInvalidateOldSnapshotWithoutSearchingAgain() {
        String id=fixture("alpha"); String rootId=root("alpha"); var input=input("alpha","alpha","",rootId,0);
        jdbc.update("INSERT INTO qw_query_case_binding_dependency(query_example_id,asset_type,asset_key,binding_scope,binding_source,principal_id) VALUES (?,'MODEL','orders','USER','USER','bob')",id);
        assertTrue(history.planningContext(input).hints().sourceExampleIds().isEmpty());
        assertThrows(IllegalArgumentException.class,()->history.details(input,rootId,id));
        verify(index,times(1)).search(project,1L,HASH,null,"alice","alpha");
    }

    @Test void changedCaseSqlCannotSilentlyReplaceTheFrozenRevision() {
        String id=fixture("alpha"); String rootId=root("alpha"); var input=input("alpha","alpha","",rootId,0);
        assertTrue(history.details(input,rootId,id).toString().contains("SELECT 1"));
        jdbc.update("UPDATE qw_query_example SET sql_text='SELECT 2' WHERE id=?",id);
        index.indexApprovedCase(id,null);
        assertTrue(history.planningContext(input).hints().sourceExampleIds().isEmpty());
        assertTrue(repository.findById(consumer.runId(),rootId).orElseThrow().toString().contains("SELECT 1"));
        assertFalse(repository.findById(consumer.runId(),rootId).orElseThrow().toString().contains("SELECT 2"));
    }

    @Test void explicitRetrievalRepairCreatesANewTaskSnapshotButLeavesRootFrozen() {
        fixture("alpha"); String rootId=root("alpha");
        var one=history.planningContext(input("alpha","alpha","task-1",rootId,0));
        var two=history.planningContext(input("alpha","alpha","task-1",rootId,1));
        assertNotEquals(one.snapshotReferences(),two.snapshotReferences());
        assertEquals(rootId,two.snapshotReferences().get("request"));
        verify(index,times(3)).search(project,1L,HASH,null,"alice","alpha");
    }

    @Test void frozenFailedSqlKeepsErrorAndAttemptLabelsAndBudgetFallsBackToReferences() {
        String id=fixture("alpha");String failed=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_attempt(id,episode_id,attempt_no,status,error_type) VALUES (?,?,0,'FAILED','SYNTAX')",failed,id);
        jdbc.update("INSERT INTO qw_sql_trace(id,attempt_id,idempotency_key,sql_text,status,error_type) VALUES (?,?,?,?,'FAILED','SYNTAX')",failed,failed,failed,"BAD SQL "+"x".repeat(20_000));
        String rootId=root("alpha"); var input=input("alpha","alpha","",rootId,0);
        var detail=history.details(input,rootId,id);
        assertTrue(detail.toString().contains("BAD SQL")); assertTrue(detail.toString().contains("SYNTAX"));
        var context=history.planningContext(input); var payload=QueryCaseRequestEvidence.read(context.prompt());
        assertFalse(payload.path("cases").get(0).path("detailsLoaded").asBoolean());
        assertFalse(context.prompt().contains("BAD SQL"));
        assertTrue(context.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=8192);
        assertTrue(payload.path("cases").get(0).has("detailReference"));
    }

    @Test void cancelledConsumerCannotPublishARecallSnapshot() {
        fixture("alpha"); runs.cancel(consumer.runId(),"cancel-test");
        assertThrows(LateRunResultDroppedException.class,()->root("alpha"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_query_case_recall_snapshot WHERE run_id=?",Integer.class,consumer.runId()));
    }

    @Test void sqlRepairWithNoSnapshotDoesNotInventANewRetrievalStage() {
        fixture("alpha");
        assertTrue(history.existingContext(input("alpha","alpha","","",0)).hints().sourceExampleIds().isEmpty());
        verify(index,never()).search(any(),any(),any(),any(),any(),any());
    }

    @Test void wholeMultiTaskCaseRemainsARequestWithOrderedTaskAndAttemptReferences() {
        String id=fixture("alpha");
        for(int n=1;n<=2;n++) jdbc.update("""
            INSERT INTO qw_query_task(run_id,task_id,ordinal_no,question,status,dependencies_json,
                semantic_plan_json,result_summary_json,review_json)
            VALUES (?,?,?,?,'DONE',?::jsonb,'{"executable":true,"models":[]}','{}','{"decision":"PASS"}')
            """,id,"task-"+n,n,"historical answer "+n,n==1?"[]":"[\"task-1\"]");
        jdbc.update("UPDATE qw_query_example SET intent_type='MULTI_TASK_REQUEST' WHERE id=?",id);
        jdbc.update("INSERT INTO qw_run_event(run_id,sequence,event_type,payload,idempotency_key) VALUES (?,2,'REQUEST_SYNTHESIS','both answers',?)",id,id+":synthesis");
        index.indexApprovedCase(id,null);
        String rootId=root("alpha"); var input=input("alpha","alpha","",rootId,0);
        var detail=history.details(input,rootId,id);
        assertEquals(2,detail.path("requestEvidence").path("tasks").size());
        assertEquals("task-1",detail.path("requestEvidence").path("tasks").get(0).path("task_id").asText());
        assertEquals(1,detail.path("requestEvidence").path("attempts").size());
        assertEquals(2,QueryCaseRequestEvidence.read(history.planningContext(input).prompt()).path("cases").get(0).path("taskOutline").size());
    }

    @Test void quarantineImmediatelyRemovesPreviouslyRecalledDetails() {
        String id=fixture("alpha"); String rootId=root("alpha");
        jdbc.update("UPDATE qw_query_example SET status='QUARANTINED' WHERE id=?",id);
        assertThrows(IllegalArgumentException.class,()->history.details(input("alpha","alpha","",rootId,0),rootId,id));
        assertTrue(history.planningContext(input("alpha","alpha","",rootId,0)).hints().sourceExampleIds().isEmpty());
    }

    @Test void smallContextBudgetKeepsReferencesForAllCasesWithoutLeakingPartialSql() {
        fixture("root");fixture("daily");fixture("daily");String rootId=root("root");
        org.springframework.test.util.ReflectionTestUtils.setField(history,"maxTokens",1024);
        var result=history.planningContext(input("root","daily","task-1",rootId,0));
        assertEquals(3,result.hints().sourceExampleIds().size());
        assertTrue(result.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1024);
        assertFalse(result.prompt().contains("SELECT 1"));
    }

    @Test void concurrentSameRootPublishesOneSnapshotAndOneCompletionEvent() throws Exception {
        fixture("alpha"); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->root("alpha"));var b=pool.submit(()->root("alpha"));
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_run_event WHERE run_id=? AND event_type='CASE_RECALL_COMPLETED'",Integer.class,consumer.runId()));
        } finally { pool.shutdownNow(); }
    }
}
