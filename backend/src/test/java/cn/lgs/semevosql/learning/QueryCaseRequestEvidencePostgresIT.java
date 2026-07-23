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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.FeedbackRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Synthetic records in disposable PostgreSQL; verifies real SQL/transactions, not model quality. */
@Testcontainers
class QueryCaseRequestEvidencePostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    QueryCaseRequestEvidence evidence;
    String run, episode, attempt;
    long sequence;

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified", PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (1,'case-test','Synthetic case test','synthetic','ACTIVE','test')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (1,1,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
    }

    @BeforeEach void setup() {
        run = UUID.randomUUID().toString(); episode = UUID.randomUUID().toString(); attempt = UUID.randomUUID().toString();
        evidence = new QueryCaseRequestEvidence(jdbc);
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key,episode_id,attempt_id) VALUES (?,'INTERACTIVE_QUERY','SUCCEEDED',?,?,?)",
            run, run, episode, attempt);
        jdbc.update("INSERT INTO qw_attempt(id,episode_id,attempt_no,status) VALUES (?,?,1,'SUCCEEDED')", attempt, episode);
        jdbc.update("""
            INSERT INTO qw_result_artifact(artifact_id,run_id,artifact_type,schema_json,data_json,row_count,content_hash,status)
            VALUES (?,?,'MERGED_RESULT','[]','[]',0,?,'READY')
            """, UUID.randomUUID().toString(), run, "a".repeat(64));
        event("POST_EXECUTION_REVIEW", "{\"review\":{\"decision\":\"PASS\"}}");
    }

    void event(String type, String payload) {
        jdbc.update("INSERT INTO qw_run_event(run_id,sequence,event_type,payload,idempotency_key) VALUES (?,?,?,?,?)",
            run, ++sequence, type, payload, UUID.randomUUID().toString());
    }

    void task(int n, String status) {
        jdbc.update("""
            INSERT INTO qw_query_task(run_id,task_id,ordinal_no,question,status,semantic_plan_json,result_summary_json,review_json)
            VALUES (?,?,?,'synthetic task',?,'{"executable":true,"models":[{"modelCode":"orders","physicalTable":"orders"}]}','{}','{"decision":"PASS"}')
            """, run, "task-" + n, n, status);
    }

    @Test
    void reviewedDirectResultRemainsEligibleButSourceFragmentsDoNot() {
        jdbc.update("UPDATE qw_result_artifact SET artifact_type='DIRECT_RESULT' WHERE run_id=?", run);
        assertTrue(evidence.eligible(run));
        jdbc.update("UPDATE qw_result_artifact SET artifact_type='SOURCE_RESULT' WHERE run_id=?", run);
        assertFalse(evidence.eligible(run));
        jdbc.update("UPDATE qw_result_artifact SET artifact_type='DIRECT_RESULT',status='FAILED' WHERE run_id=?", run);
        assertFalse(evidence.eligible(run));
    }

    @Test void aBrokenReviewReceiptCannotPromoteAnAbandonedReadyResult() {
        assertTrue(evidence.eligible(run));
        event("RESULT_ARTIFACT_ACCEPTED", "{\"artifactId\":\"missing-reviewed-result\"}");
        assertFalse(evidence.eligible(run));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM qw_result_artifact WHERE run_id=? AND status='READY'", Integer.class, run));
    }

    @Test void unfinishedOrUncertainExecutionCannotBeCapturedOrRecalled() {
        jdbc.update("INSERT INTO datasource(id,name,type,host,port,database_name,username,password) VALUES (450001,'synthetic','postgresql','localhost',5432,'test','test','test') ON CONFLICT(id) DO NOTHING");
        String source=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_source_sub_run(sub_run_id,run_id,project_id,project_version_id,datasource_id,status,source_plan_json,execution_key) VALUES (?,?,1,1,450001,'RUNNING','{}','synthetic')",source,run);
        assertFalse(evidence.eligible(run));
        jdbc.update("UPDATE qw_source_sub_run SET status='FAILED' WHERE sub_run_id=?",source);
        assertTrue(evidence.eligible(run));
        String sql=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES (?,?,?,'synthetic','synthetic','QUERY',?,450001,'UNCERTAIN')",sql,run,attempt,"a".repeat(64));
        assertFalse(evidence.eligible(run));
        jdbc.update("UPDATE qw_sql_execution_attempt SET status='FAILED' WHERE sql_attempt_id=?",sql);
        assertTrue(evidence.eligible(run));
    }

    @Test void singleRequestWithReasonableEmptyResultIsEligible() {
        assertTrue(evidence.eligible(run));
        assertFalse(evidence.eligible("unknown"));
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "WAITING_HUMAN", "FAILED", "CANCELLED", "EXPIRED"})
    void aSuccessfulSqlCannotPromoteAnUnsuccessfulRequest(String status) {
        jdbc.update("UPDATE qw_query_run SET status=? WHERE run_id=?", status, run);
        assertFalse(evidence.eligible(run));
    }

    @Test void allTodosAndSynthesisAreRequired() {
        task(1, "DONE"); task(2, "ACTIVE");
        assertFalse(evidence.eligible(run));
        jdbc.update("UPDATE qw_query_task SET status='DONE' WHERE run_id=?", run);
        assertFalse(evidence.eligible(run));
        event("REQUEST_SYNTHESIS", "Two results returned");
        assertTrue(evidence.eligible(run));
        jdbc.update("UPDATE qw_query_task SET review_json='{}' WHERE run_id=? AND task_id='task-1'", run);
        assertFalse(evidence.eligible(run));
    }

    @Test void missingTaskRowsCannotTurnAMultiTaskRequestIntoSingleTask() {
        event("REQUEST_ANALYSIS_COMPLETED", "{\"needsTodo\":true,\"tasks\":[{\"taskId\":\"task-1\"},{\"taskId\":\"task-2\"}]}");
        assertFalse(evidence.eligible(run));
        task(1, "DONE"); assertFalse(evidence.eligible(run));
        task(2, "DONE"); event("REQUEST_SYNTHESIS", "Both complete"); assertTrue(evidence.eligible(run));
    }

    @Test void latestFailedReviewOverridesEarlierPass() {
        event("POST_EXECUTION_REVIEW", "{\"review\":{\"decision\":\"REPAIR\"}}");
        assertFalse(evidence.eligible(run));
    }

    @Test void negativeFeedbackCannotBeHiddenByAnotherUsersPositiveFeedback() {
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,5,true)",
            UUID.randomUUID().toString(), episode, UUID.randomUUID().toString());
        assertTrue(evidence.eligible(run));
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,1,false)",
            UUID.randomUUID().toString(), episode, UUID.randomUUID().toString());
        assertFalse(evidence.eligible(run));
    }

    @ParameterizedTest @ValueSource(strings={"QUERY_BINDING_CORRECTED", "SEMANTIC_DEFINITION_CORRECTION_PROPOSED", "REQUEST_REQUIREMENT_CORRECTED"})
    void unresolvedCorrectionSuspendsReuse(String type) {
        event(type, "{}"); assertFalse(evidence.eligible(run));
    }

    @Test @SuppressWarnings("unchecked") void snapshotRetainsEveryTaskAndFailedAttemptWithoutResultRows() {
        task(1, "DONE"); task(2, "DONE"); event("REQUEST_SYNTHESIS", "both results");
        jdbc.update("INSERT INTO qw_attempt(id,episode_id,attempt_no,status,error_type) VALUES (?,?,0,'FAILED','SYNTAX')",
            UUID.randomUUID().toString(), episode);
        event("REQUEST_ENHANCEMENT_COMPLETED", "{\"originalQuery\":\"那三月呢\",\"enhancement\":{\"status\":\"READY\",\"canonicalQuery\":\"三月已支付金额\"}}");
        var proof = evidence.snapshot(run);
        assertEquals(2, ((List<?>)proof.get("tasks")).size());
        assertEquals(2, ((List<?>)proof.get("attempts")).size());
        assertEquals("FAILED", ((List<Map<String,Object>>)proof.get("attempts")).get(0).get("status"));
        assertEquals("三月已支付金额", proof.get("rootCanonicalQuery"));
        assertFalse(proof.toString().contains("data_json"));
        assertEquals(true, proof.get("eligibleAtCapture"));
    }

    @Test void captureStoresWholeRequestAndRepeatedCaptureUsesSameCase() {
        jdbc.update("""
            INSERT INTO qw_episode(id,request_id,agent_id,project_id,project_version_id,catalog_hash,original_question,normalized_question,status,base_semantic_version_id,accepted_semantic_state_hash,idempotency_key,request_fingerprint)
            VALUES (?,?, 'synthetic',1,1,?,'那三月呢','那三月呢','SUCCEEDED',1,?,?,?)
            """, episode, episode, "a".repeat(64), "a".repeat(64), episode, episode);
        jdbc.update("INSERT INTO qw_sql_trace(id,attempt_id,idempotency_key,sql_text,status) VALUES (?,?,?,'SELECT 1','SUCCEEDED')",
            UUID.randomUUID().toString(), attempt, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,5,true)",
            UUID.randomUUID().toString(), episode, UUID.randomUUID().toString());
        task(1, "DONE"); task(2, "DONE"); event("REQUEST_SYNTHESIS", "Both complete");
        event("REQUEST_ENHANCEMENT_COMPLETED", "{\"originalQuery\":\"那三月呢\",\"enhancement\":{\"status\":\"READY\",\"canonicalQuery\":\"三月总额及每日趋势\"}}");
        var refs = new QueryCaseAssetReferenceRepository(jdbc);
        var repository = new QueryCaseRepository(jdbc, refs);
        var context = mock(cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService.class);
        when(context.resolutions(run)).thenReturn(List.of());
        when(context.fingerprint(eq(run), anyString())).thenReturn("c".repeat(64));
        var plans = mock(cn.lgs.semevosql.run.SemanticPlanSnapshotService.class);
        when(plans.latest(run)).thenReturn(java.util.Optional.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.builder().executable(true).build()));
        var capture = new QueryCaseCaptureService(jdbc, repository, refs, new QueryCaseLineageService(jdbc, repository),
            mock(QueryCaseRetrievalIndexService.class), context, null, null, plans);
        var first = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        var second = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        assertEquals(first.id(), second.id());
        var row = repository.require(first.id());
        assertEquals("三月总额及每日趋势", row.get("normalized_question"));
        assertEquals("MULTI_TASK_REQUEST", row.get("intent_type"));
        var proof = QueryCaseRequestEvidence.read(row.get("quality_proof_json").toString()).path("payload").path("requestEvidence");
        assertEquals(2, proof.path("tasks").size());
        assertEquals(run, proof.path("request").path("run_id").asText());
        assertFalse(repository.singlePlanCompatible(first.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE run_id=?", Integer.class, run));
        jdbc.update("UPDATE qw_query_task SET status='FAILED' WHERE run_id=? AND task_id='task-2'", run);
        assertTrue(tx.execute(s0 -> capture.captureEligibleCandidate(episode)).isEmpty());
    }

    QueryCaseCaptureService completionFixture(String compilerMode) {
        jdbc.update("""
            INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch)
            VALUES (2,1,2,'2.0.0','PUBLISHED','COMPLETED',2,0,0) ON CONFLICT (id) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO qw_episode(id,request_id,agent_id,project_id,project_version_id,catalog_hash,original_question,
                normalized_question,status,base_semantic_version_id,accepted_semantic_state_hash,idempotency_key,request_fingerprint)
            VALUES (?,?,'synthetic',1,2,?,'三月总金额','三月总金额','SUCCEEDED',2,?,?,?)
            """, episode, episode, "a".repeat(64), "a".repeat(64), episode, episode);
        jdbc.update("""
            INSERT INTO qw_sql_trace(id,attempt_id,idempotency_key,sql_text,status,retry_count,guard_summary,cost_summary,explain_summary)
            VALUES (?,?,?,'SELECT 1','SUCCEEDED',3,'{"decision":"PASS"}','{"decision":"PASS"}',?::jsonb)
            """, UUID.randomUUID().toString(), attempt, UUID.randomUUID().toString(), "{\"compilerMode\":\""+compilerMode+"\"}");
        event("SEMANTIC_PLAN_SNAPSHOT", "{\"executable\":true,\"compilerMode\":\""+compilerMode
            +"\",\"models\":[{\"modelCode\":\"orders\",\"physicalTable\":\"orders\"}],\"sourceSubPlans\":[{\"datasourceId\":1}]}");
        var refs = new QueryCaseAssetReferenceRepository(jdbc);
        var repository = new QueryCaseRepository(jdbc, refs);
        var context = mock(cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService.class);
        when(context.resolutions(run)).thenReturn(List.of(Map.of("clarificationId", "first-definition")));
        when(context.fingerprint(eq(run), anyString())).thenReturn("c".repeat(64));
        var plans = new cn.lgs.semevosql.run.SemanticPlanSnapshotService(jdbc,
            mock(cn.lgs.semevosql.run.ExecutionSnapshotService.class));
        return new QueryCaseCaptureService(jdbc, repository, refs, new QueryCaseLineageService(jdbc, repository),
            new QueryCaseRetrievalIndexService(jdbc, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), Runnable::run),
            context, null, null, plans);
    }

    @ParameterizedTest @ValueSource(strings={"DETERMINISTIC", "SEMANTIC_SQL", "PATTERN_TEMPLATE"})
    void completionAdmitsValidatedRequestWithoutFeedbackDespiteInitialClarificationAndTechnicalRepair(String compilerMode) {
        var capture = completionFixture(compilerMode);
        // Constrained recovery persists DIRECT_RESULT rather than a multi-source MERGED_RESULT.
        if ("SEMANTIC_SQL".equals(compilerMode))
            jdbc.update("UPDATE qw_result_artifact SET artifact_type='DIRECT_RESULT' WHERE run_id=?", run);
        String clarification = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_runtime_clarification(clarification_id,run_id,question,options_json,status) VALUES (?,?,'首次明确时间范围','[]','ANSWERED')", clarification, run);
        jdbc.update("INSERT INTO qw_runtime_clarification_answer(clarification_id,idempotency_key,custom_answer,answered_by,clarification_revision) VALUES (?,?,'三月','synthetic',1)", clarification, clarification);
        jdbc.update("INSERT INTO qw_sql_trace(id,attempt_id,idempotency_key,sql_text,status,error_type) VALUES (?,?,?,'SELECT missing','FAILED','SYNTAX')",
            UUID.randomUUID().toString(), attempt, UUID.randomUUID().toString());
        var result = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        assertEquals("APPROVED", result.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qw_feedback WHERE episode_id=?", Integer.class, episode));
        var proof = QueryCaseRequestEvidence.read(result.attributes().get("quality_proof_json").toString()).path("payload");
        assertFalse(proof.path("userAdopted").asBoolean());
        assertEquals(2, proof.path("requestEvidence").path("sqlTraces").size());
        assertEquals(1, proof.path("requestEvidence").path("confirmations").size());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_case_question_index WHERE query_example_id=?", Integer.class, result.id()));
    }

    @Test void partialTaskIsNotCapturedAndCompleteMultiTaskRequestIsOneCase() {
        var capture = completionFixture("DETERMINISTIC");
        task(1, "DONE"); task(2, "ACTIVE");
        jdbc.update("""
            UPDATE qw_query_task SET semantic_plan_json=semantic_plan_json || '{"sourceSubPlans":[{"datasourceId":1}]}'::jsonb
            WHERE run_id=?
            """, run);
        assertTrue(tx.execute(s0 -> capture.captureEligibleCandidate(episode)).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE run_id=?", Integer.class, run));
        jdbc.update("UPDATE qw_query_task SET status='DONE' WHERE run_id=?", run);
        assertTrue(tx.execute(s0 -> capture.captureEligibleCandidate(episode)).isEmpty());
        event("REQUEST_SYNTHESIS", "Both results returned");
        var result = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        assertEquals("APPROVED", result.status());
        assertEquals("MULTI_TASK_REQUEST", result.intentType());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE run_id=?", Integer.class, run));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qw_feedback WHERE episode_id=?", Integer.class, episode));
    }

    @Test void positiveFeedbackCannotReplaceMissingGuardEvidence() {
        var capture = completionFixture("SEMANTIC_SQL");
        jdbc.update("UPDATE qw_sql_trace SET guard_summary='{}' WHERE attempt_id=?", attempt);
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,5,true)",
            UUID.randomUUID().toString(), episode, episode);
        assertEquals("CANDIDATE", tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow()).status());
    }

    @Test void completionRetriesDoNotReviveOrOverwriteQuarantinedEvidence() {
        var capture = completionFixture("SEMANTIC_SQL");
        var first = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        var service = production();
        tx.executeWithoutResult(s0 -> service.feedback(episode, new FeedbackRequest("synthetic", 1, false, "negative")));
        assertTrue(tx.execute(s0 -> capture.captureEligibleCandidate(episode)).isEmpty());
        tx.executeWithoutResult(s0 -> service.feedback(episode, new FeedbackRequest("synthetic", 5, true, "repeat positive")));
        var retried = tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow());
        assertEquals("QUARANTINED", retried.status());
        assertEquals(first.attributes().get("quality_proof_json"), retried.attributes().get("quality_proof_json"));
    }

    @Test void concurrentCompletionCapturesCreateOneApprovedCaseWithoutFeedback() throws Exception {
        var capture = completionFixture("SEMANTIC_SQL");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var ready = new CountDownLatch(4); var go = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) jobs.add(executor.submit(() -> {
                ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS));
                return tx.execute(s0 -> capture.captureEligibleCandidate(episode).orElseThrow().id());
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            var ids = new java.util.HashSet<String>();
            for (var job : jobs) ids.add(job.get(20, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE run_id=? AND status='APPROVED'", Integer.class, run));
        } finally { executor.shutdownNow(); }
    }

    @Test void assetReferenceDuplicateDoesNotAbortTransactionAndChangedEvidenceIsRejected() {
        String id = approvedCase(); var refs = new QueryCaseAssetReferenceRepository(jdbc);
        var value = new QueryCaseAssetReferenceRepository.ReferenceValue("MODEL", "orders", "b".repeat(64));
        tx.executeWithoutResult(s0 -> {
            refs.insertIfAbsent(id, "a".repeat(64), value);
            refs.insertIfAbsent(id, "a".repeat(64), value);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example_asset_ref WHERE query_example_id=?", Integer.class, id));
        });
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s0 -> refs.insertIfAbsent(id, "a".repeat(64),
            new QueryCaseAssetReferenceRepository.ReferenceValue("MODEL", "orders", "c".repeat(64)))));
        assertEquals("b".repeat(64), jdbc.queryForObject("SELECT asset_fingerprint FROM qw_query_example_asset_ref WHERE query_example_id=?", String.class, id));
    }

    SemEvoSQLProductionService production() {
        var service = mock(SemEvoSQLProductionService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "queryExampleService", mock(ValidatedQueryExampleService.class));
        ReflectionTestUtils.setField(service, "patternTemplateService", mock(QueryPatternTemplateService.class));
        return service;
    }

    String approvedCase() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO qw_query_example(id,project_id,project_version_id,catalog_hash,episode_id,attempt_id,run_id,
              normalized_question,sql_text,sql_hash,fingerprint,status,typed_ir_json,quality_proof_json)
            VALUES (?,1,1,?,?,?,?,'synthetic','SELECT 1',?,?,'APPROVED','{}','{}')
            """, id, "a".repeat(64), episode, attempt, run, "b".repeat(64), id);
        return id;
    }

    @Test void actualFeedbackTransactionQuarantinesAndRepeatedPositiveDoesNotRevive() {
        String id = approvedCase(); var service = production();
        tx.executeWithoutResult(s -> service.feedback(episode, new FeedbackRequest("synthetic", 1, false, "test negative")));
        assertEquals("QUARANTINED", jdbc.queryForObject("SELECT status FROM qw_query_example WHERE id=?", String.class, id));
        assertFalse(evidence.eligible(run));
        tx.executeWithoutResult(s -> service.feedback(episode, new FeedbackRequest("synthetic", 5, true, "test positive")));
        assertEquals("QUARANTINED", jdbc.queryForObject("SELECT status FROM qw_query_example WHERE id=?", String.class, id));
    }

    @Test void feedbackSerializesBehindCaptureAndCannotLeaveApprovedCase() throws Exception {
        var service = production();
        var held = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> capture = executor.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT run_id FROM qw_query_run WHERE run_id=? FOR UPDATE", run);
                assertTrue(evidence.eligible(run)); held.countDown();
                try { assertTrue(finish.await(10, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                approvedCase();
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> negative = executor.submit(() -> tx.executeWithoutResult(s ->
                service.feedback(episode, new FeedbackRequest("synthetic", 1, false, "test concurrent negative"))));
            assertThrows(TimeoutException.class, () -> negative.get(150, TimeUnit.MILLISECONDS));
            finish.countDown(); capture.get(10, TimeUnit.SECONDS); negative.get(10, TimeUnit.SECONDS);
            assertFalse(evidence.eligible(run));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE run_id=? AND status='APPROVED'", Integer.class, run));
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
}
