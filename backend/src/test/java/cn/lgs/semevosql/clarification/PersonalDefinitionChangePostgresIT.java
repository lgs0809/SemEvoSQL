/*
 * Copyright 2026 the original author or authors.
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

import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.learning.QueryCaseQuarantineService;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real migration/transactions; all query receipt and provider fixtures here are synthetic, never live acceptance. */
@Testcontainers
class PersonalDefinitionChangePostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;static TransactionTemplate tx;static long nextProject=10700;
    long project;PersonalSemanticDefinitionStore store;UserSemanticPreferenceService preferences;
    PersonalDefinitionChangeService service;SemanticDocumentExtractionClient model;QueryCaseQuarantineService quarantine;
    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void fixture() {
        project=++nextProject;
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,'Definition change fixture','test','ACTIVE','alice')",project,"change-"+project);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','COMPLETED',1,0,0)",project,project);
        store=new PersonalSemanticDefinitionStore(jdbc);preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class),store);
        model=mock(SemanticDocumentExtractionClient.class);quarantine=mock(QueryCaseQuarantineService.class);
        service=new PersonalDefinitionChangeService(jdbc,store,preferences,model);service.quarantine(quarantine);
    }
    PersonalSemanticDefinitionStore.Definition define(String owner,String phrase,String text,PersonalSemanticDefinitionStore.Sharing scope) {
        var snapshot=JsonNodeFactory.instance.objectNode().put("completeDefinitionRecorded",true).put("confirmedText",text);
        return tx.execute(ignored->store.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,owner,phrase,text,
            "TEXT_DEFINITION",UserSemanticPreferenceService.normalizePhrase(phrase),phrase,"TEXT_CONFIRMATION",UUID.randomUUID().toString(),
            project,snapshot,null,scope,store.currentRevision(project,owner,phrase))));
    }
    String use(PersonalSemanticDefinitionStore.Definition d,String owner) {
        String run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,project_id,project_version_id,request_payload) VALUES(?,'INTERACTIVE_QUERY',?,?,'SUCCEEDED',?,?,?,?)",
            run,run,run,run,project,project,PersonalSemanticDefinitionStore.json(Map.of("principalId",owner)));
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','fixture','QUERY',repeat('0',64),1,'FAILED')",
            UUID.randomUUID().toString(),run,run);
        tx.executeWithoutResult(ignored->jdbc.update("INSERT INTO qw_user_semantic_preference_usage(preference_id,definition_revision,run_id,event_type,valid,idempotency_key) VALUES(?,?,?,'COUNTED',TRUE,?)",d.preferenceId(),d.revision(),run,run));
        return run;
    }
    PersonalDefinitionChangeService.Proposal proposal(PersonalSemanticDefinitionStore.Definition d,String newText,List<String> affected) {
        int auth=jdbc.queryForObject("SELECT max(authorization_revision) FROM qw_user_semantic_authorization WHERE preference_id=? AND definition_revision=?",Integer.class,d.preferenceId(),d.revision());
        return new PersonalDefinitionChangeService.Proposal(d.preferenceId(),d.revision(),auth,d.contentHash(),d.phrase(),d.text(),d.sharing(),
            newText,service.history(d),affected,new ModelCallResult("synthetic-change",ModelCallPurpose.SEMANTIC_PLANNING,"{}",1,1));
    }
    RuntimeClarification freeze(PersonalDefinitionChangeService.Proposal p) {
        String id=UUID.randomUUID().toString();String run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_runtime_clarification(clarification_id,run_id,question,options_json,status,asset_type) VALUES(?,?,'Synthetic confirmation','[]','PENDING',?)",id,run,PersonalDefinitionChangeService.TYPE);
        tx.executeWithoutResult(ignored->service.freeze(id,project,"alice",p));
        return RuntimeClarification.builder().clarificationId(id).runId(run).assetType(PersonalDefinitionChangeService.TYPE).build();
    }
    void submit(RuntimeClarification question,String option,SemanticBindingScope scope) {
        tx.executeWithoutResult(ignored->service.submit(question,new RuntimeClarificationService.AnswerCommand(0,"answer-"+question.clarificationId(),option,null,scope,"alice"),project,"alice"));
    }
    ProjectDefinitionContributions.Totals totals(PersonalSemanticDefinitionStore.Definition d) {
        long candidate=jdbc.queryForObject("SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=? AND definition_revision=?",Long.class,d.preferenceId(),d.revision());
        var operator=new OperatorContextProperties();operator.setDefaultOperator("alice");
        return new ProjectDefinitionContributions(jdbc,new LocalSecurityProperties(),operator).totals(candidate,1,project);
    }

    @Test void scopeOnlyUsesSameImmutableMeaningAndPreservesAuthorizedHistoryAcrossFuturePrivateUses() {
        var d=define("alice","余额","余额是订单金额的一半，按创建时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        use(d,"alice");use(d,"alice");
        var sharedQuestion=freeze(proposal(d,null,List.of()));
        submit(sharedQuestion,"CONFIRM_VALID_HISTORY",SemanticBindingScope.PROJECT);
        var shared=store.current(d.preferenceId());assertEquals(1,shared.revision());assertEquals(d.contentHash(),shared.contentHash());
        assertEquals(2,totals(shared).validUses());assertEquals(1,totals(shared).validUsers());
        var privateQuestion=freeze(proposal(shared,null,List.of()));submit(privateQuestion,"CONFIRM_FUTURE",SemanticBindingScope.USER);
        var privateMeaning=store.current(d.preferenceId());assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,privateMeaning.sharing());
        use(privateMeaning,"alice");assertEquals(2,totals(privateMeaning).validUses());
        long candidate=jdbc.queryForObject("SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=?",Long.class,d.preferenceId());
        assertTrue(new ProjectDefinitionCandidateRepository(jdbc).visible(project,candidate).isPresent());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_definition_revision WHERE preference_id=?",Integer.class,d.preferenceId()));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=? AND valid",Integer.class,d.preferenceId()));
    }

    @Test void futureOnlySharingExcludesExistingPrivateUsesUntilAnExplicitHistoricalConsent() {
        var d=define("alice","利润","利润是收入扣除成本，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        use(d,"alice");submit(freeze(proposal(d,null,List.of())),"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);
        var current=store.current(d.preferenceId());assertEquals(0,totals(current).validUses());
        use(current,"alice");assertEquals(1,totals(current).validUses());
        submit(freeze(proposal(current,null,List.of())),"CONFIRM_VALID_HISTORY",SemanticBindingScope.PROJECT);
        assertEquals(2,totals(store.current(d.preferenceId())).validUses());
    }

    @Test void completionUsesTheSubmittedImmutableDefinitionEvenAfterASeparateLaterChange() {
        var d=define("alice","归集额","归集额是收入合计，按创建时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var q=freeze(proposal(d,null,List.of()));submit(q,"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);
        jdbc.update("UPDATE qw_runtime_clarification SET status='ANSWERED' WHERE clarification_id=?",q.clarificationId());
        String completed=service.completion(q.runId(),project,"alice").orElseThrow();
        assertTrue(completed.contains(d.text()));assertFalse(completed.contains("Run"));assertFalse(completed.contains("计算修订"));
        define("alice",d.phrase(),"归集额是收入扣除退款，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        assertEquals(completed,service.completion(q.runId(),project,"alice").orElseThrow());
        assertTrue(service.completion(q.runId(),project,"bob").isEmpty());assertTrue(service.completion(q.runId(),project+1,"alice").isEmpty());
    }

    @Test void changedFormulaCreatesOwnCountsAndLeavesOldSharedMeaningAndOtherUserUntouched() {
        var d=define("alice","周转金额","周转金额是订单金额的一半，单位元。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        use(d,"alice");var other=define("bob","周转金额","周转金额是订单金额的一半，单位元。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);use(other,"bob");
        submit(freeze(proposal(d,"周转金额是订单金额的四成，单位元。",List.of())),"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);
        var current=store.current(d.preferenceId());assertEquals(2,current.revision());assertEquals(0,totals(current).validUses());assertEquals(1,totals(d).validUses());
        assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,store.require(d.preferenceId(),1).sharing());
        assertEquals(1,store.current(other.preferenceId()).revision());
        submit(freeze(proposal(current,"周转金额是订单金额的三成，单位元。",List.of())),"CONFIRM_FUTURE",SemanticBindingScope.USER);
        assertEquals(3,store.current(d.preferenceId()).revision());assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,store.current(d.preferenceId()).sharing());
    }

    @Test void selectiveWithdrawalKeepsResultsValidWhileConfirmedCorrectionInvalidatesOnlyTheNamedUse() {
        var d=define("alice","支用金额","支用金额是订单金额合计，单位元。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        String first=use(d,"alice"),second=use(d,"alice");
        submit(freeze(proposal(d,null,List.of(first))),"CONFIRM_WITHDRAW_TARGETS",SemanticBindingScope.PROJECT);
        assertEquals(1,totals(store.current(d.preferenceId())).validUses());
        assertTrue(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,first));verifyNoInteractions(quarantine);
        submit(freeze(proposal(store.current(d.preferenceId()),"支用金额是订单金额扣除退款，单位元。",List.of(second))),"CONFIRM_INVALIDATE_TARGETS",SemanticBindingScope.USER);
        assertFalse(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,second));
        assertTrue(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,first));
        verify(quarantine).quarantineCorrectedSemanticUse(eq(second),eq("alice"),anyString());
    }

    @Test void olderQueryConsentAndCorrectionsRemainTargetableWithoutMigratingUsesToANewFormula() {
        var old=define("alice","归集数","归集数是订单金额的一半，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        String oldPrivate=use(old,"alice");
        submit(freeze(proposal(old,null,List.of())),"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);
        old=store.current(old.preferenceId());String oldShared=use(old,"alice");
        var current=define("alice",old.phrase(),"归集数是订单金额的四成，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        String latest=use(current,"alice");
        assertEquals(3,service.history(current).size());
        assertEquals(Set.of(oldPrivate,oldShared,latest),service.history(current).stream()
            .map(PersonalDefinitionChangeService.HistoryUse::runId).collect(java.util.stream.Collectors.toSet()));
        submit(freeze(proposal(current,null,List.of())),"CONFIRM_VALID_HISTORY",SemanticBindingScope.PROJECT);
        current=store.current(current.preferenceId());assertEquals(1,totals(current).validUses());assertEquals(1,totals(old).validUses());
        assertFalse(service.history(current).stream().filter(u->u.runId().equals(oldPrivate)).findFirst().orElseThrow().shared());
        submit(freeze(proposal(current,null,List.of(oldShared))),"CONFIRM_WITHDRAW_TARGETS",SemanticBindingScope.PROJECT);
        current=store.current(current.preferenceId());assertEquals(0,totals(old).validUses());assertEquals(1,totals(current).validUses());
        assertTrue(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,oldShared));
        verifyNoInteractions(quarantine);
        submit(freeze(proposal(current,null,List.of(oldPrivate))),"CONFIRM_INVALIDATE_TARGETS",SemanticBindingScope.PROJECT);
        assertFalse(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,oldPrivate));
        assertTrue(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE run_id=?",Boolean.class,latest));
        assertEquals(2,store.current(current.preferenceId()).revision());assertEquals(1,totals(current).validUses());
        verify(quarantine).quarantineCorrectedSemanticUse(eq(oldPrivate),eq("alice"),anyString());
    }

    @Test void cancellationAndRollbackDoNotApplyAndStaleDefinitionOrAuthorizationCannotBeOverwritten() {
        var d=define("alice","业绩","业绩是收入合计，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        use(d,"alice");var p=proposal(d,null,List.of());var q=freeze(p);
        submit(q,"CANCEL",SemanticBindingScope.QUERY);
        assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,store.current(d.preferenceId()).sharing());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_definition_change_receipt WHERE clarification_id=?",Integer.class,q.clarificationId()));
        tx.executeWithoutResult(status->{service.submit(q,new RuntimeClarificationService.AnswerCommand(0,"rollback","CONFIRM_VALID_HISTORY",null,SemanticBindingScope.PROJECT,"alice"),project,"alice");status.setRollbackOnly();});
        assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,store.current(d.preferenceId()).sharing());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_sharing_use_decision WHERE clarification_id=?",Integer.class,q.clarificationId()));
        tx.executeWithoutResult(ignored->store.authorize(d.preferenceId(),d.revision(),PersonalSemanticDefinitionStore.Sharing.ALLOWED,"alice","different-confirmation"));
        assertThrows(ResponseStatusException.class,()->submit(q,"CONFIRM_FUTURE",SemanticBindingScope.USER));
        assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,store.current(d.preferenceId()).sharing());
        var oldQuestion=freeze(proposal(store.current(d.preferenceId()),null,List.of()));
        define("alice",d.phrase(),"业绩是收入扣除退款，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        assertThrows(ResponseStatusException.class,()->submit(oldQuestion,"CONFIRM_VALID_HISTORY",SemanticBindingScope.PROJECT));
    }

    @Test void onlyOwnedActualUsesAreListedAndConcurrentConfirmationsHaveOneWinner() throws Exception {
        var d=define("alice","结算额","结算额是收入合计，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        use(d,"forged-other-owner");assertTrue(service.history(d).isEmpty());
        var p=proposal(d,null,List.of());var one=freeze(p);var two=freeze(p);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> a=()->{start.await();try{submit(one,"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);return true;}catch(ResponseStatusException stale){return false;}};
            Callable<Boolean> b=()->{start.await();try{submit(two,"CONFIRM_FUTURE",SemanticBindingScope.PROJECT);return true;}catch(ResponseStatusException stale){return false;}};
            var first=pool.submit(a);var second=pool.submit(b);start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_personal_definition_change_receipt WHERE clarification_id IN (?,?)",Integer.class,one.clarificationId(),two.clarificationId()));
        }finally{pool.shutdownNow();}
    }

    @Test void modelOnlyProposesAnExactOwnedReferenceAndCannotWriteSharing() throws Exception {
        var d=define("alice","自定义量","自定义量是所有订单金额的一半，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        String message="允许分享我的自定义量，计算方法不变。";
        var response=JsonNodeFactory.instance.objectNode().put("targetDefinitionId",d.preferenceId()).put("sourceRevision",1)
            .put("sourceContentHash",d.contentHash()).putNull("definitionText").put("intentExcerpt",message);
        response.putArray("affectedRunIds");
        when(model.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),nullable(java.time.Duration.class)))
            .thenReturn(new ModelCallResult("synthetic-proposal",ModelCallPurpose.SEMANTIC_PLANNING,JsonUtil.getObjectMapper().writeValueAsString(response),1,1));
        var proposed=service.propose(project,"alice",message,message,null);
        assertTrue(proposed.scopeOnly());assertEquals(d.text(),proposed.oldText());assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,store.current(d.preferenceId()).sharing());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_source WHERE preference_id=?",Integer.class,d.preferenceId()));
        response.put("targetDefinitionId",d.preferenceId()+10000);
        when(model.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),nullable(java.time.Duration.class)))
            .thenReturn(new ModelCallResult("forged-proposal",ModelCallPurpose.SEMANTIC_PLANNING,response.toString(),1,1));
        assertThrows(cn.lgs.semevosql.exception.ModelOutputInvalidException.class,()->service.propose(project,"alice",message,message,null));
    }

    private record PendingManagement(String run,RuntimeClarification question,RuntimeClarificationService runtime) {}
    PendingManagement pendingManagement(PersonalDefinitionChangeService.Proposal proposed) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,project_id,project_version_id,request_payload) VALUES(?,'INTERACTIVE_QUERY',?,?,'RUNNING',?,?,?,?)",
            id,id,id,id,project,project,PersonalSemanticDefinitionStore.json(Map.of("principalId","alice")));
        var runs=new cn.lgs.semevosql.run.QueryRunService(new cn.lgs.semevosql.run.QueryRunRepository(jdbc),"fixture-worker");
        var runtime=new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),null,runs,null,
            mock(cn.lgs.semevosql.run.ThreadExecutionGuardService.class),
            mock(cn.lgs.semevosql.observability.SemEvoSQLMetrics.class),new RuntimePrincipalResolver(jdbc),null,
            preferences,null,new LocalOperatorService());
        runtime.definitionChanges(service);
        var question=tx.execute(ignored->runtime.createDefinitionChangeClarification(id,proposed.phrase(),proposed));
        return new PendingManagement(id,question,runtime);
    }

    @Test void rejectedStaleAnswerReplacesOnlyPendingQuestionAndNativeResumeConsumesNewAnswer() {
        var d=define("alice","复核额","复核额是收入合计，按创建时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var pending=pendingManagement(proposal(d,null,List.of()));
        var owner=new OperatorContext("alice","AUTHENTICATED","fixture","fixture");
        tx.executeWithoutResult(ignored->store.authorize(d.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.ALLOWED,"alice","other-confirmation"));
        var oldAnswer=new RuntimeClarificationService.AnswerCommand(0,"old-answer","CONFIRM_FUTURE",null,SemanticBindingScope.USER,"alice");
        assertThrows(ResponseStatusException.class,()->tx.execute(ignored->pending.runtime().answer(pending.run(),pending.question().clarificationId(),oldAnswer,owner)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,pending.question().clarificationId()));
        var fresh=tx.execute(ignored->pending.runtime().refreshDefinitionChange(pending.run(),pending.question().clarificationId(),0,owner)).orElseThrow();
        assertNotEquals(pending.question().clarificationId(),fresh.clarificationId());
        assertTrue(fresh.question().contains("旧答案没有保存"));assertTrue(fresh.question().contains("允许分享为项目建议"));
        assertEquals(fresh.clarificationId(),jdbc.queryForObject("SELECT resolved_value FROM qw_runtime_clarification WHERE clarification_id=? AND status='SUPERSEDED'",String.class,pending.question().clarificationId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_definition_change_receipt WHERE clarification_id IN (?,?)",Integer.class,pending.question().clarificationId(),fresh.clarificationId()));
        assertTrue(tx.execute(ignored->pending.runtime().refreshDefinitionChange(pending.run(),pending.question().clarificationId(),0,owner)).isEmpty());
        var boundary=new cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary(pending.runtime(),new RuntimeClarificationRepository(jdbc),
            new cn.lgs.semevosql.run.RunExecutionFenceService(mock(cn.lgs.semevosql.run.QueryRunService.class)));
        var graphState=new com.alibaba.cloud.ai.graph.OverAllState(Map.of(cn.lgs.semevosql.constant.Constant.RUN_ID,pending.run(),
            cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.QUESTION_ID,pending.question().clarificationId(),
            cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.BASE_QUERY,d.phrase()));
        assertThrows(IllegalStateException.class,()->boundary.resume(graphState));
        tx.execute(ignored->pending.runtime().answer(pending.run(),fresh.clarificationId(),
            new RuntimeClarificationService.AnswerCommand(fresh.revision(),"fresh-answer","CONFIRM_FUTURE",null,SemanticBindingScope.USER,"alice"),owner));
        var resumed=boundary.resume(graphState);
        assertEquals("",resumed.get(cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.QUESTION_ID));
        assertEquals(Map.of(fresh.clarificationId(),1L),resumed.get(cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.APPLIED_ANSWERS));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_personal_definition_change_receipt WHERE clarification_id=?",Integer.class,fresh.clarificationId()));
        assertEquals(1,store.current(d.preferenceId()).revision());assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,store.current(d.preferenceId()).sharing());
    }

    @Test void staleCompleteFormulaShowsNewBaseAndBudgetExhaustionCannotDiscardPendingQuestion() {
        var d=define("alice","拨付额","拨付额是收入合计的一半，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var pending=pendingManagement(proposal(d,"拨付额是收入合计的四成，单位元。",List.of()));
        define("alice",d.phrase(),"拨付额是收入合计的三成，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var changed=tx.execute(ignored->service.refreshIfChanged(pending.question().clarificationId(),project,"alice")).orElseThrow();
        assertEquals(2,changed.revision());assertEquals("拨付额是收入合计的三成，单位元。",changed.oldText());
        assertEquals("拨付额是收入合计的四成，单位元。",changed.newText());assertEquals(pending.question().clarificationId(),changed.previousQuestionId());
        for(int i=0;i<2;i++)jdbc.update("INSERT INTO qw_runtime_clarification(clarification_id,run_id,question,options_json,status) VALUES(?,?,'Synthetic budget slot','[]','ANSWERED')",UUID.randomUUID().toString(),pending.run());
        var owner=new OperatorContext("alice","AUTHENTICATED","fixture","fixture");
        assertThrows(IllegalStateException.class,()->tx.execute(ignored->pending.runtime().refreshDefinitionChange(pending.run(),pending.question().clarificationId(),0,owner)));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qw_runtime_clarification WHERE clarification_id=?",String.class,pending.question().clarificationId()));
        assertEquals(2,store.current(d.preferenceId()).revision());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_definition_change_receipt WHERE clarification_id=?",Integer.class,pending.question().clarificationId()));
    }
}
