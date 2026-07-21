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

import cn.lgs.semevosql.semantic.domain.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Immutable meaning, migration and concurrency verified against real PostgreSQL. */
@Testcontainers
class PersonalDefinitionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;static TransactionTemplate tx;static PersonalSemanticDefinitionStore store;
    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("51").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(990,'personal-fixture','Synthetic','test','ACTIVE','legacy-owner')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(990,990,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
        jdbc.update("INSERT INTO qw_user_semantic_preference(project_id,user_id,normalized_phrase,display_phrase,asset_type,asset_key,business_label) VALUES(990,'legacy-owner','old','old','METRIC','old_metric','旧口径')");
        jdbc.update("INSERT INTO qw_user_semantic_preference_usage(preference_id,run_id,event_type,valid,idempotency_key) SELECT id,'old-run','COUNTED',TRUE,'old-use' FROM qw_user_semantic_preference");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();store=new PersonalSemanticDefinitionStore(jdbc);
    }
    PersonalSemanticDefinitionStore.Confirmation command(String owner,String phrase,String text,String source,Integer expected) {
        var snapshot=JsonNodeFactory.instance.objectNode().put("completeDefinitionRecorded",true).put("confirmedText",text);
        return new PersonalSemanticDefinitionStore.Confirmation(990L,owner,phrase,text,"TEXT_DEFINITION",phrase,phrase,"TEXT_CONFIRMATION",source,990L,snapshot,null,PersonalSemanticDefinitionStore.Sharing.PRIVATE,expected);
    }
    PersonalSemanticDefinitionStore.Definition confirm(PersonalSemanticDefinitionStore.Confirmation c){return tx.execute(ignored->store.confirm(c));}
    private PersonalDefinitionRetrievalRepository.Work indexClaim(PersonalDefinitionRetrievalRepository documents,long id,
            cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity identity) {
        for(int n=0;n<100;n++) {
            var job=tx.execute(ignored->documents.claim(identity,java.time.Duration.ofMinutes(5))).orElseThrow();
            if(job.preferenceId()==id)return job;
            tx.executeWithoutResult(ignored->documents.fail(job));
        }
        throw new AssertionError("Synthetic fixture work was not claimable");
    }
    @Test void confirmedTextIsFtsSearchableImmediatelyAndFailedConfirmationLeavesNoProjection() {
        var d=confirm(command("retrieval-owner","净回款","客户订单付款金额扣除退款，按付款日期统计。","retrieval-1",0));
        var repo=new PersonalDefinitionRetrievalRepository(jdbc);
        assertTrue(repo.lexical(990L,d.principal(),"请看扣除退款后的金额",20).stream().anyMatch(h->h.preferenceId()==d.preferenceId()));
        assertTrue(store.applicable(990L,d.principal(),"请看扣除退款后的金额").isEmpty());
        assertTrue(repo.lexical(990L,"another-owner","净回款",20).isEmpty());
        assertTrue(repo.lexical(991L,d.principal(),"净回款",20).isEmpty());
        assertTrue(repo.lexical(990L,d.principal(),"!!!",20).isEmpty());
        tx.executeWithoutResult(status->{store.confirm(command("rolled-back-owner","独有术语","独有术语的完整确认。","retrieval-rollback",0));status.setRollbackOnly();});
        assertTrue(repo.lexical(990L,"rolled-back-owner","独有术语",20).isEmpty());
    }
    @Test void vectorChannelDoesNotDependOnLexicalHitsAndFiltersOwnerBeforeTopK() {
        var own=confirm(command("vector-owner","Own receipt","Only the owner's confirmed amount.","private-vector-1",0));
        var foreign=confirm(command("vector-foreign","Foreign receipt","Unrelated private amount.","private-vector-foreign",0));
        var repo=new PersonalDefinitionRetrievalRepository(jdbc);
        var identity=new cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity("synthetic-vector","v1",3);
        var ownJob=indexClaim(repo,own.preferenceId(),identity);assertTrue(tx.execute(ignored->repo.complete(ownJob,identity,new float[]{1,.1f,0})).booleanValue());
        var foreignJob=indexClaim(repo,foreign.preferenceId(),identity);assertTrue(tx.execute(ignored->repo.complete(foreignJob,identity,new float[]{1,0,0})).booleanValue());
        assertTrue(repo.lexical(990L,own.principal(),"蜃景",20).isEmpty());
        var hits=repo.vector(990L,own.principal(),new float[]{1,0,0},identity,1);
        assertEquals(List.of(own.preferenceId()),hits.stream().map(PersonalDefinitionRetrievalRepository.Hit::preferenceId).toList());
        assertFalse(repo.hasVectors(991L,own.principal(),identity));assertFalse(repo.hasVectors(990L,"unrelated-owner",identity));
        assertTrue(repo.vector(990L,own.principal(),new float[]{1,0,0},new cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity("synthetic-vector","v2",3),20).isEmpty());
    }
    @Test void correctionInvalidatesVectorsAtomicallyAndRejectsLateWorkerAndArchivedRecall() {
        var d=confirm(command("index-race-owner","回款余额","回款扣除退款。","index-race-1",0));
        var repo=new PersonalDefinitionRetrievalRepository(jdbc);
        var identity=new cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity("synthetic-vector","race-v1",3);
        var stale=indexClaim(repo,d.preferenceId(),identity);
        var fresh=confirm(command(d.principal(),d.phrase(),"回款包含退款。","index-race-2",1));
        assertFalse(tx.execute(ignored->repo.complete(stale,identity,new float[]{1,0,0})).booleanValue());
        var hits=repo.lexical(990L,d.principal(),"退款",20);assertEquals(1,hits.size());assertEquals(2,hits.get(0).revision());
        assertTrue(hits.get(0).text().contains(fresh.text()));assertFalse(repo.hasVectors(990L,d.principal(),identity));
        var newer=indexClaim(repo,d.preferenceId(),identity);
        jdbc.update("UPDATE qw_personal_definition_document SET lease_until=CURRENT_TIMESTAMP-interval '1 second' WHERE preference_id=?",d.preferenceId());
        var replacement=indexClaim(repo,d.preferenceId(),identity);
        assertFalse(tx.execute(ignored->repo.complete(newer,identity,new float[]{1,0,0})).booleanValue());
        assertTrue(tx.execute(ignored->repo.complete(replacement,identity,new float[]{1,0,0})).booleanValue());
        new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class)).delete(990L,d.principal(),d.phrase());
        assertTrue(repo.lexical(990L,d.principal(),"退款",20).isEmpty());assertTrue(repo.vector(990L,d.principal(),new float[]{1,0,0},identity,20).isEmpty());
    }
    @Test void badEmbeddingRetainsDurableRetryAndFullTextWithNoInvalidVector() {
        var d=confirm(command("bad-embedding-owner","毛利额","收入减去已确认成本。","bad-vector-1",0));
        var repo=new PersonalDefinitionRetrievalRepository(jdbc);
        var identity=new cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity("synthetic-vector","bad-v1",3);
        var job=indexClaim(repo,d.preferenceId(),identity);
        assertThrows(IllegalArgumentException.class,()->repo.complete(job,identity,new float[]{Float.NaN,0,0}));
        assertThrows(IllegalArgumentException.class,()->repo.complete(job,identity,new float[]{1,0}));
        tx.executeWithoutResult(ignored->repo.fail(job));
        assertEquals("RETRYABLE_FAILURE",jdbc.queryForObject("SELECT task_state FROM qw_personal_definition_document WHERE preference_id=?",String.class,d.preferenceId()));
        assertTrue(repo.lexical(990L,d.principal(),"成本",20).stream().anyMatch(h->h.preferenceId()==d.preferenceId()));
        assertEquals(d.text(),store.current(d.preferenceId()).text());
    }
    @Test void softPersonalCandidateNeedsActualModelSelectionAndDoesNotClaimUnrelatedBaseUse() {
        var d=confirm(command("soft-owner","所得金额","用户确认所得金额为订单金额的一半。","soft-1",0));
        var repo=new PersonalDefinitionRetrievalRepository(jdbc);
        var service=new PersonalDefinitionRetrievalService(repo,store,Optional.empty(),Optional.empty(),Optional.empty());
        var soft=service.retrieve(990L,d.principal(),"看一下订单金额的一半");
        assertEquals(1,soft.size());assertEquals("USER_CANDIDATE",soft.get(0).getScope());
        var slice=new cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay.Slice(soft,List.of());
        assertTrue(slice.selected(cn.lgs.semevosql.learning.QueryCaseHints.empty(),List.of()).isEmpty());
        assertEquals("USER",slice.selected(cn.lgs.semevosql.learning.QueryCaseHints.empty(),List.of(d.preferenceId())).get(0).getScope());
        var mapping=SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER_CANDIDATE").sourceRecordId(19L).assetType("METRIC").assetKey("sales").build();
        var mappings=new cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay.Slice(List.of(mapping),List.of());
        var hints=new cn.lgs.semevosql.learning.QueryCaseHints(Set.of(),Set.of("sales"),Set.of(),Set.of(),Set.of(),Set.of(),List.of(),"FIXTURE",List.of(),1,Map.of());
        assertTrue(mappings.selected(hints,List.of()).isEmpty());assertEquals(1,mappings.selected(hints,List.of(19L)).size());
        assertThrows(IllegalArgumentException.class,()->mappings.selected(cn.lgs.semevosql.learning.QueryCaseHints.empty(),List.of(19L)));
        mapping.setScope("USER_DEFAULT_CANDIDATE");
        assertTrue(mappings.selected(hints,List.of()).isEmpty(),"Public selection must not claim an unused saved default");
        assertEquals(1,mappings.selected(hints,List.of(19L)).size());
        mapping.setScope("USER");
        assertEquals(1,mappings.selected(hints,List.of()).size(),"An explicit current binding remains authoritative");
    }
    @Test void legacyMigrationRetainsUsageAndMarksIncompleteFactsForReconfirmation() {
        long id=jdbc.queryForObject("SELECT id FROM qw_user_semantic_preference WHERE user_id='legacy-owner'",Long.class);
        var old=store.current(id);assertEquals("LEGACY_REFERENCE",old.sourceKind());assertEquals("旧口径",old.text());
        assertFalse(old.snapshot().path("completeDefinitionRecorded").asBoolean());assertEquals("NEEDS_RECONFIRMATION",old.taskState());
        assertEquals(1,jdbc.queryForObject("SELECT definition_revision FROM qw_user_semantic_preference_usage WHERE idempotency_key='old-use'",Integer.class));
    }
    @Test void correctionAppendsHistorySwitchesTextImmediatelyAndKeepsOldUses() {
        var first=confirm(command("history-owner","回款","回款按确认事实统计，不扣退款。","history-1",0));
        jdbc.update("INSERT INTO qw_user_semantic_preference_usage(preference_id,definition_revision,run_id,event_type,valid,idempotency_key) VALUES(?,1,'history-run','COUNTED',TRUE,'history-use')",first.preferenceId());
        var second=confirm(command("history-owner","回款","回款扣除退款，按退款确认日期归属。","history-2",1));
        assertEquals(2,second.revision());assertEquals("TEXT_ACTIVE",second.representation());assertEquals("PENDING",second.taskState());
        assertEquals("STALE",store.require(first.preferenceId(),1).taskState());assertEquals(first.text(),store.require(first.preferenceId(),1).text());
        assertTrue(jdbc.queryForObject("SELECT valid FROM qw_user_semantic_preference_usage WHERE idempotency_key='history-use'",Boolean.class));
        assertEquals(second.text(),store.applicable(990L,"history-owner","请统计回款").get(0).text());
        assertTrue(store.applicable(990L,"other-owner","请统计回款").isEmpty());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_user_semantic_definition_revision SET definition_text='伪造' WHERE preference_id=?",first.preferenceId()));
        assertThrows(IllegalArgumentException.class,()->confirm(command("history-owner","回款","迟到确认","history-3",1)));
    }
    @Test void concurrentRepeatedConfirmationUsesOneRevisionAndDifferentContentRollsBack() throws Exception {
        var c=command("concurrent-owner","转化率","以已支付客户数除以访问客户数。","concurrent-1",0);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<PersonalSemanticDefinitionStore.Definition> work=()->{start.await();return confirm(c);};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            var one=a.get(10,TimeUnit.SECONDS);var two=b.get(10,TimeUnit.SECONDS);assertEquals(one,two);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_definition_revision WHERE preference_id=?",Integer.class,one.preferenceId()));
            assertThrows(IllegalArgumentException.class,()->confirm(command("concurrent-owner","转化率","以订单数统计","concurrent-1",0)));
            assertEquals(one,store.current(one.preferenceId()));
        } finally {pool.shutdownNow();}
    }
    @Test void authorizationHistoryDoesNotAlterMeaningAndNonOwnerCannotShare() {
        var def=confirm(command("grant-owner","留存率","以客户注册月份为起点统计留存。","grant-1",0));
        assertThrows(SecurityException.class,()->tx.execute(ignored->{store.authorize(def.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.ALLOWED,"other-owner",null);return null;}));
        tx.execute(ignored->{store.authorize(def.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.ALLOWED,"grant-owner","grant-allow");return null;});
        assertEquals(def.contentHash(),store.current(def.preferenceId()).contentHash());assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,store.current(def.preferenceId()).sharing());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_authorization WHERE preference_id=?",Integer.class,def.preferenceId()));
    }
    @Test void fiveCompletedDistinctUsesAskOnceDeclineStopsAndNewMeaningDoesNotReuseCounts() {
        var def=confirm(command("count-owner","净额","按付款日统计净额。","count-1",0));
        var service=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        for(int i=1;i<=5;i++) {
            String id="personal-count-"+i;jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key) VALUES(?,'INTERACTIVE_QUERY',?,?,'SUCCEEDED',?)",id,id,id,id);
            tx.execute(ignored->{service.recordApplied(def.preferenceId(),1,id);service.recordApplied(def.preferenceId(),1,id);return null;});
            jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','scope','QUERY',?,17,'SUCCEEDED')",UUID.randomUUID().toString(),id,id,"a".repeat(64));
            var prompts=tx.execute(ignored->service.finalizeSuccessfulRun(id));assertEquals(i==5?1:0,prompts.size());
        }
        assertEquals(5,service.findById(def.preferenceId()).orElseThrow().hitCount());
        assertEquals(1,tx.execute(ignored->service.finalizeSuccessfulRun("personal-count-5")).size());
        tx.execute(ignored->service.dismissUpgrade(def.preferenceId()));
        assertEquals(PersonalSemanticDefinitionStore.Sharing.DECLINED,store.current(def.preferenceId()).sharing());
        assertTrue(tx.execute(ignored->service.finalizeSuccessfulRun("personal-count-5")).isEmpty());
        var corrected=confirm(command("count-owner","净额","改为按完成日统计净额。","count-2",1));
        assertEquals(0,service.findById(def.preferenceId()).orElseThrow().hitCount());
        assertTrue(tx.execute(ignored->service.finalizeSuccessfulRun("personal-count-5")).isEmpty());
        assertEquals(0,service.findById(def.preferenceId()).orElseThrow().hitCount());assertEquals(2,corrected.revision());
        tx.execute(ignored->{service.invalidateRunUsage("personal-count-1",def.preferenceId());return null;});
        assertEquals(0,service.findById(def.preferenceId()).orElseThrow().hitCount());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=? AND definition_revision=1 AND valid",Integer.class,def.preferenceId()));
    }
    @Test void failedActualQueryCountsButSavedOrUnexecutedDefinitionDoesNot() {
        var def=confirm(command("failure-owner","净增长","新增减去流失。","failure-1",0));
        var service=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        String id="personal-failed";jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key) VALUES(?,'INTERACTIVE_QUERY',?,?,'FAILED',?)",id,id,id,id);
        tx.execute(ignored->{service.recordApplied(def.preferenceId(),1,id);return null;});
        assertTrue(tx.execute(ignored->service.finalizeSuccessfulRun(id)).isEmpty());assertEquals(0,service.findById(def.preferenceId()).orElseThrow().hitCount());
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','scope','QUERY',?,17,'FAILED')",UUID.randomUUID().toString(),id,id,"b".repeat(64));
        assertTrue(tx.execute(ignored->service.finalizeSuccessfulRun(id)).isEmpty());assertEquals(1,service.findById(def.preferenceId()).orElseThrow().hitCount());
    }

    @Test void archivingRetainsAuditableHistoryAndOtherPrincipalsCannotReadActiveDefinition() {
        var def=confirm(command("archive-owner","复购","同一客户发生第二笔付款。","archive-1",0));
        var service=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        tx.execute(ignored->{service.delete(990L,"archive-owner","复购");return null;});
        assertTrue(store.applicable(990L,"archive-owner","看复购").isEmpty());assertEquals(def.text(),store.require(def.preferenceId(),1).text());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_definition_revision WHERE preference_id=?",Integer.class,def.preferenceId()));
    }
    PersonalSemanticDefinitionStore.Claimed claimFor(long id) {
        for(int i=0;i<100;i++) {
            var job=tx.execute(ignored->store.claim(java.time.Duration.ofSeconds(120))).orElseThrow();
            if(job.definition().preferenceId()==id)return job;
            tx.execute(ignored->store.fail(job,"TEST_DEFERRED",false));
        }
        throw new AssertionError("Fixture job not found");
    }
    @Test void oldWorkerCannotActivateAfterCorrectionAndFailureRetainsTextWithExponentialBackoff() {
        var first=confirm(command("worker-owner","贡献额","收入减费用。","worker-1",0));
        var job=claimFor(first.preferenceId());var second=confirm(command("worker-owner","贡献额","收入减费用和退款。","worker-2",1));
        assertFalse(tx.execute(ignored->store.complete(job,JsonNodeFactory.instance.objectNode().put("synthetic",true),null)).booleanValue());
        assertEquals("TEXT_ACTIVE",store.current(first.preferenceId()).representation());assertEquals(second.text(),store.current(first.preferenceId()).text());
        var current=claimFor(first.preferenceId());assertTrue(tx.execute(ignored->store.fail(current,"MODEL_UNAVAILABLE",false)).booleanValue());
        var after=store.current(first.preferenceId());assertEquals("TEXT_ACTIVE",after.representation());assertEquals("RETRYABLE_FAILURE",after.taskState());
        long seconds=jdbc.queryForObject("SELECT extract(epoch from next_attempt_at-update_time)::bigint FROM qw_user_semantic_representation WHERE preference_id=? AND source_revision=2",Long.class,first.preferenceId());
        assertTrue(seconds>=59&&seconds<=61);
        jdbc.update("UPDATE qw_user_semantic_representation SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second' WHERE preference_id=? AND source_revision=2",first.preferenceId());
        var retry=claimFor(first.preferenceId());assertEquals(2,retry.attempt());assertTrue(tx.execute(ignored->store.fail(retry,"MODEL_UNAVAILABLE",false)).booleanValue());
        seconds=jdbc.queryForObject("SELECT extract(epoch from next_attempt_at-update_time)::bigint FROM qw_user_semantic_representation WHERE preference_id=? AND source_revision=2",Long.class,first.preferenceId());assertTrue(seconds>=119&&seconds<=121);
    }
    @Test void SQLAuthorityGuardRejectsAnotherOwnerForgedTextAndUnconfirmedSuggestion() {
        var def=confirm(command("execution-owner","质量客群","已支付客户中累计消费超过用户确认门槛。","execution-1",0));
        var dependency=SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER").principalId("execution-owner")
            .sourceRecordId(def.preferenceId()).sourceRevision(def.revision()).sourceContentHash(def.contentHash()).definitionText(def.text())
            .assetType(def.assetType()).assetKey(def.assetKey()).build();
        var plan=SemanticBlueprint.builder().bindingDependencies(List.of(dependency)).build();var guard=new PersonalDefinitionExecutionGuard(store);
        assertDoesNotThrow(()->guard.requireAuthorized(990L,"execution-owner",plan));
        assertThrows(SecurityException.class,()->guard.requireAuthorized(990L,"other",plan));
        dependency.setDefinitionText("假文本");assertThrows(SecurityException.class,()->guard.requireAuthorized(990L,"execution-owner",plan));
        dependency.setSource("PROJECT_CANDIDATE");assertThrows(SecurityException.class,()->guard.requireAuthorized(990L,"execution-owner",plan));
    }

    @Test void naturalDefinitionAnswerAtomicallySavesOwnTextScopeAndJobAndRejectsStaleQuestion() {
        for(var scope:List.of(SemanticBindingScope.USER,SemanticBindingScope.PROJECT)) {
            String principal="answer-"+scope;String id=UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,owner_instance,lease_expire_time,project_id,project_version_id)
                VALUES(?,'INTERACTIVE_QUERY',?,?,'RUNNING',?,'personal-worker',CURRENT_TIMESTAMP+interval '5 minutes',990,990)
                """,id,id,id,id);
            var runs=new cn.lgs.semevosql.run.QueryRunService(new cn.lgs.semevosql.run.QueryRunRepository(jdbc),"personal-worker");
            var resolver=mock(RuntimePrincipalResolver.class);when(resolver.resolve(any())).thenReturn(principal);
            var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
            var service=new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),null,runs,null,null,
                mock(cn.lgs.semevosql.observability.SemEvoSQLMetrics.class),resolver,null,preferences,null,new cn.lgs.semevosql.common.LocalOperatorService());
            var question=tx.execute(ignored->service.createPlanningClarification(id,"请统计贡献金额",
                new cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING","贡献金额如何计算？",List.of(),"没有确认公式","贡献金额")));
            assertEquals("TEXT_DEFINITION",question.assetType());assertTrue(store.applicable(990L,principal,"贡献金额").isEmpty());
            var command=new RuntimeClarificationService.AnswerCommand(question.revision(),"answer-key-"+scope,"OTHER","贡献金额为收入减去费用，以元显示。",scope,principal);
            var operator=new cn.lgs.semevosql.common.OperatorContext(principal,"AUTHENTICATED",id,id);
            tx.execute(ignored->service.answer(id,question.clarificationId(),command,operator));
            var d=store.applicable(990L,principal,"请统计贡献金额").get(0);assertEquals(command.customAnswer(),d.text());
            assertEquals(scope==SemanticBindingScope.PROJECT?PersonalSemanticDefinitionStore.Sharing.ALLOWED:PersonalSemanticDefinitionStore.Sharing.PRIVATE,d.sharing());
            assertEquals("PENDING",d.taskState());assertEquals(1,d.revision());assertEquals(0,preferences.findById(d.preferenceId()).orElseThrow().hitCount());
            tx.execute(ignored->service.answer(id,question.clarificationId(),command,operator));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_definition_revision WHERE preference_id=?",Integer.class,d.preferenceId()));
            assertTrue(service.applyResolvedAnswer(id,"请统计贡献金额").contains(command.customAnswer()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_version WHERE project_id=990",Integer.class));
            // A second prompt freezes revision 1; another confirmed edit wins before that prompt is answered.
            tx.execute(ignored->runs.transition(id,cn.lgs.semevosql.run.QueryRun.RunStatus.RUNNING,"fixture-resume",null,null));
            var stale=tx.execute(ignored->service.createPlanningClarification(id,"请统计贡献金额",
                new cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING","需要怎样修改定义？",List.of(),"需要确认","贡献金额")));
            confirm(command(principal,"贡献金额","先确认的新口径","new-meaning-"+scope,1));
            var late=new RuntimeClarificationService.AnswerCommand(stale.revision(),"late-"+scope,"OTHER","迟到的旧口径",scope,principal);
            assertThrows(IllegalArgumentException.class,()->tx.execute(ignored->service.answer(id,stale.clarificationId(),late,operator)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,stale.clarificationId()));
            assertEquals("先确认的新口径",store.current(d.preferenceId()).text());
        }
    }

    @Test void snapshotFingerprintTracksFormulaTimeScaleAndPermissionsButIgnoresUnrelatedAssets() {
        var metric=SemanticCatalogSnapshot.Metric.builder().metricCode("total").modelCode("order").expression("SUM(amount)").timeColumn("created_at").unit("元").status(SemanticAssetStatus.ENABLED).build();
        var amount=SemanticCatalogSnapshot.Column.builder().modelCode("order").columnName("amount").dataType("decimal").status(SemanticAssetStatus.ENABLED).build();
        var catalog=SemanticCatalogSnapshot.builder().projectId(990L).projectVersionId(990L).metrics(new ArrayList<>(List.of(metric)))
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("order").physicalTable("shop.orders").datasourceId(17).status(SemanticAssetStatus.ENABLED).build()))
            .columns(new ArrayList<>(List.of(amount,SemanticCatalogSnapshot.Column.builder().modelCode("order").columnName("created_at").dataType("datetime").status(SemanticAssetStatus.ENABLED).build()))).build();
        var base=PersonalDefinitionSnapshot.capture(catalog,"METRIC","total");
        catalog.setProjectVersionId(991L);metric.setDescription("更多说明");metric.setBusinessName("新名称");
        catalog.getMetrics().add(SemanticCatalogSnapshot.Metric.builder().metricCode("unrelated").modelCode("order").expression("COUNT(*)").build());
        assertEquals(base.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(catalog,"METRIC","total").dependencyFingerprint());
        metric.setExpression("SUM(amount)/100");assertNotEquals(base.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(catalog,"METRIC","total").dependencyFingerprint());
        metric.setExpression("SUM(amount)");amount.setAllowSendToLlm(false);assertNotEquals(base.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(catalog,"METRIC","total").dependencyFingerprint());
        assertEquals("SUM(amount)",base.snapshot().path("target").path("expression").asText());
    }
    SemanticBlueprint.BindingDependency reference(PersonalSemanticDefinitionStore.Definition d) {
        return SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER").principalId(d.principal())
            .phrase(d.phrase()).sourceRecordId(d.preferenceId()).sourceRevision(d.revision()).sourceContentHash(d.contentHash())
            .assetType(d.assetType()).assetKey(d.assetKey()).definitionText(d.text()).dependencyFingerprint(d.dependencyFingerprint()).build();
    }
    SemanticCatalogSnapshot privateCatalog() {
        return SemanticCatalogSnapshot.builder().projectId(990L).projectVersionId(990L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("fixture_orders").physicalTable("personal_definition_orders")
                .datasourceId(17).businessName("合成订单").status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of("amount","created_at").stream().map(name->SemanticCatalogSnapshot.Column.builder().modelCode("fixture_orders")
                .columnName(name).dataType(name.equals("amount")?"DECIMAL(12,2)":"TIMESTAMP").allowAggregation(true).allowFilter(true)
                .allowProjection(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build()).toList())
            .metrics(new ArrayList<>()).build();
    }
    cn.lgs.semevosql.semantic.application.SemanticCatalogReadService reader(SemanticCatalogSnapshot catalog) {
        var repository=mock(SemanticCatalogRepository.class);
        when(repository.authoritativeCatalogHash(990L,990L)).thenReturn("sha256:fixture");
        when(repository.loadModelSlice(990L,990L,Set.of("fixture_orders"))).thenReturn(catalog);
        return new cn.lgs.semevosql.semantic.application.SemanticCatalogReadService(repository);
    }
    @Test void frozenPrivateStructureCompilesRealSQLWithoutChangingPublicCatalogAndRejectsForgedMeaning() {
        var original=confirm(command("structured-owner","调整额","订单金额合计的一半，按创建时间统计，单位元。","structure-1",0));
        var code="p_"+original.preferenceId()+"_1";
        var metricJson=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.parse("""
            {"code":"%s","name":"调整额","description":"订单金额合计的一半，按创建时间统计，单位元。",
             "entity":"fixture_orders","expression":{"op":"divide","left":{"op":"sum","arg":{"attribute":"amount"}},
             "right":{"literal":2},"onZero":"null"},"filters":[],"timeAttribute":"created_at","unit":"元"}
            """.formatted(code));
        var publicCatalog=privateCatalog();
        var metric=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.projectPrivateMetric(metricJson,publicCatalog);
        var capturedCatalog=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().convertValue(publicCatalog,SemanticCatalogSnapshot.class);
        capturedCatalog.getMetrics().add(metric);var captured=PersonalDefinitionSnapshot.capture(capturedCatalog,"METRIC",code);
        var structured=JsonNodeFactory.instance.objectNode().put("protocol","personal-metric-1.1")
            .put("sourceRevision",original.revision()).put("sourceContentHash",original.contentHash())
            .put("dependencyFingerprint",captured.dependencyFingerprint());
        structured.set("metric",metricJson);structured.set("dependencySnapshot",captured.snapshot());
        var job=claimFor(original.preferenceId());assertTrue(tx.execute(ignored->store.complete(job,structured,null)).booleanValue());
        var overlay=new cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay(store,reader(publicCatalog));
        var slice=overlay.prepare(990L,990L,original.principal(),List.of(reference(original)));
        assertEquals(1,slice.metrics().size());assertNotNull(slice.references().get(0).getRepresentationHash());assertTrue(publicCatalog.getMetrics().isEmpty());
        assertThrows(SecurityException.class,()->overlay.prepare(990L,990L,"other",List.of(reference(original))));
        var selection=SemanticBlueprint.MetricSelection.builder().metricCode(code).modelCode(metric.getModelCode()).businessName(metric.getBusinessName())
            .expression(metric.getExpression()).aggregation(metric.getAggregation()).filterExpression(metric.getFilterExpression())
            .timeColumn(metric.getTimeColumn()).unit(metric.getUnit()).build();
        var plan=SemanticBlueprint.builder().projectId(990L).projectVersionId(990L).compilerMode("DETERMINISTIC").executable(true)
            .models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("fixture_orders").physicalTable("personal_definition_orders").datasourceId(17).build()))
            .metrics(List.of(selection)).bindingDependencies(slice.references()).projections(List.of(SemanticBlueprint.ProjectionSelection.builder()
                .modelCode("fixture_orders").expression(metric.getExpression()).alias(code).projectionType("METRIC").build()))
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(17).modelCodes(List.of("fixture_orders"))
                .physicalTables(List.of("personal_definition_orders")).build())).limit(100).build();
        jdbc.execute("CREATE TABLE personal_definition_orders(amount NUMERIC(12,2),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO personal_definition_orders VALUES(80,'2026-01-01'),(20,'2026-01-02')");
        assertTrue(store.currentReference(990L,original.principal(),slice.references().get(0)));
        var authority=new PersonalDefinitionExecutionGuard(store);authority.catalogReader(reader(publicCatalog));
        assertDoesNotThrow(()->authority.requireAuthorized(990L,original.principal(),plan));
        var executable=overlay.frozenCatalog(publicCatalog,original.principal(),plan);
        var compiled=new cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler().compile(plan,executable,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0);
        assertEquals(0,new java.math.BigDecimal("50").compareTo(jdbc.queryForObject(compiled.sql(),java.math.BigDecimal.class,compiled.parameters().toArray())));

        var preflight=new cn.lgs.semevosql.semantic.compiler.QueryPreflightService().preflight("SELECT METRIC('"+code+"') AS "+code+" FROM fixture_orders",
            executable,plan,17,"postgresql");
        assertEquals(0,new java.math.BigDecimal("50").compareTo(jdbc.queryForObject(preflight.physicalSql(),java.math.BigDecimal.class)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_user_semantic_structure_revision SET structured_json='{}' WHERE preference_id=?",original.preferenceId()));
        selection.setExpression("SUM(amount)");assertThrows(SecurityException.class,()->overlay.frozenCatalog(publicCatalog,original.principal(),plan));
        selection.setExpression(metric.getExpression());publicCatalog.getColumns().get(0).setAllowAggregation(false);
        assertThrows(RuntimeException.class,()->overlay.frozenCatalog(publicCatalog,original.principal(),plan));
        publicCatalog.getColumns().get(0).setAllowAggregation(true);
        confirm(command(original.principal(),original.phrase(),"修订为三分之一。","structure-2",1));
        assertEquals("TEXT_ACTIVE",store.current(original.preferenceId()).representation());
        assertFalse(store.currentReference(990L,original.principal(),slice.references().get(0)));
        assertDoesNotThrow(()->overlay.frozenCatalog(publicCatalog,original.principal(),plan));
        assertEquals(1,executable.getMetrics().size());assertTrue(publicCatalog.getMetrics().isEmpty());
    }
    @Test void useRecorderRequiresExactQueryScopeAndRetainsSingleUseAfterFailureAndArchive() {
        var d=confirm(command("receipt-owner","贡献数","按确认的事实统计。","receipt-1",0));
        var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        var recorder=new PersonalDefinitionUseRecorder(jdbc,preferences);
        var plan=SemanticBlueprint.builder().bindingDependencies(List.of(reference(d))).build();
        String run="receipt-run";
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key) VALUES(?,'INTERACTIVE_QUERY',?,?,'FAILED',?)",run,run,run,run);
        var context=new cn.lgs.semevosql.sql.application.SqlExecutionAttemptService.Context(run,run,"source-scope",17);
        tx.execute(ignored->{recorder.record(plan,context);return null;});
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=?",Integer.class,d.preferenceId()));
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','unrelated-scope','QUERY',?,17,'FAILED')",UUID.randomUUID().toString(),run,run,"c".repeat(64));
        tx.execute(ignored->{recorder.record(plan,context);return null;});assertEquals(0,preferences.findById(d.preferenceId()).orElseThrow().hitCount());
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','source-scope','QUERY',?,17,'FAILED')",UUID.randomUUID().toString(),run,run,"d".repeat(64));
        tx.execute(ignored->{recorder.record(plan,context);recorder.record(plan,context);recorder.sweep();return null;});
        assertEquals(1,preferences.findById(d.preferenceId()).orElseThrow().hitCount());
        tx.execute(ignored->{preferences.delete(990L,d.principal(),d.phrase());recorder.sweep();return null;});
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=? AND event_type='COUNTED'",Integer.class,d.preferenceId()));
    }

    @Test void unsupportedASTKeepsConfirmedTextWithoutRepeatedJobsOrInventedUserQuestions() {
        var d=confirm(command("unsupported-owner","逐笔分配","按照用户确认的窗口顺序逐笔分配。","unsupported-1",0));
        var job=claimFor(d.preferenceId());assertTrue(tx.execute(ignored->store.retainText(job)).booleanValue());
        var result=store.current(d.preferenceId());assertEquals("TEXT_ACTIVE",result.representation());assertEquals("DONE",result.taskState());
        assertEquals(d.text(),result.text());assertNull(result.structured());
    }

}
