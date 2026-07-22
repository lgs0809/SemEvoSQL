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

import cn.lgs.semevosql.semantic.application.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable database contract fixtures; no model success or real user uses are manufactured. */
@Testcontainers
class ProjectDefinitionCandidatePostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;static TransactionTemplate tx;static PersonalSemanticDefinitionStore definitions;
    static long nextProject=9400;long project;ProjectDefinitionCandidateRepository repository;ProjectDefinitionCandidateService service;
    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));definitions=new PersonalSemanticDefinitionStore(jdbc);
    }
    @BeforeEach void project() {
        // Each test owns a new project; previous synthetic projects must not enter the global worker queue.
        jdbc.update("UPDATE qw_user_semantic_preference SET archived=TRUE WHERE project_id>? AND project_id<=?",9400,nextProject);
        jdbc.update("UPDATE qw_project_definition_candidate SET assessment_state='PENDING',assessment_owner_token=NULL,assessment_lease_until=NULL,assessment_next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE project_id>? AND project_id<=?",9400,nextProject);
        jdbc.update("UPDATE qw_project_definition_publication SET state='STALE',owner_token=NULL,lease_until=NULL WHERE project_id>? AND project_id<=? AND state IN ('PENDING','BUILDING','RETRYABLE_FAILURE')",9400,nextProject);
        project=++nextProject;
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,'Synthetic shared suggestions','test','ACTIVE','fixture')",project,"shared-"+project);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','COMPLETED',1,0,0)",project,project);
        repository=new ProjectDefinitionCandidateRepository(jdbc);
        var personal=new PersonalDefinitionRetrievalService(new PersonalDefinitionRetrievalRepository(jdbc),definitions,Optional.empty(),Optional.empty(),Optional.empty());
        service=new ProjectDefinitionCandidateService(repository,definitions,mock(SemanticCatalogReadService.class),Optional.empty(),personal,Optional.empty());
    }
    PersonalSemanticDefinitionStore.Definition define(String principal,String phrase,String text,PersonalSemanticDefinitionStore.Sharing sharing) {
        var snapshot=JsonNodeFactory.instance.objectNode().put("completeDefinitionRecorded",true).put("confirmedText",text);
        return tx.execute(ignored->definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,principal,phrase,text,
            "TEXT_DEFINITION",UserSemanticPreferenceService.normalizePhrase(phrase),phrase,"TEXT_CONFIRMATION",UUID.randomUUID().toString(),project,snapshot,null,sharing,definitions.currentRevision(project,principal,phrase))));
    }
    ProjectDefinitionCandidateRepository.Candidate candidate(PersonalSemanticDefinitionStore.Definition definition) {
        long id=jdbc.queryForObject("SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=? AND definition_revision=?",Long.class,definition.preferenceId(),definition.revision());
        return repository.visible(project,id).orElseThrow();
    }
    @Test void scopeOnlyConfirmationPreservesMeaningAndUsesButHasAnImmutableIdempotentReceipt() {
        var original=define("alice","实收余额","付款扣除退款，按付款时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var confirm=new PersonalSemanticDefinitionStore.Confirmation(project,"alice",original.phrase(),original.text(),original.assetType(),
            original.assetKey(),original.label(),original.sourceKind(),"scope-only-"+project,project,original.snapshot(),null,PersonalSemanticDefinitionStore.Sharing.ALLOWED,1);
        var shared=tx.execute(ignored->definitions.confirm(confirm));
        assertEquals(1,shared.revision());assertEquals(original.contentHash(),shared.contentHash());
        assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,shared.sharing());
        assertEquals(1,tx.execute(ignored->definitions.confirm(confirm)).revision());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_source WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_definition_revision WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertTrue(repository.visible(project,candidate(shared).id()).isPresent());
    }
    @Test void privateNeverLeaksAndSharedTextIsSearchableBeforeStructureOrEncoding() {
        var privateOnly=define("alice","私人规则","OnlyPrivateBusinessTerm",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        assertTrue(repository.lexical(project,"OnlyPrivateBusinessTerm",20).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_source WHERE preference_id=?",Integer.class,privateOnly.preferenceId()));
        var shared=define("bob","归集金额","订单创建金额的一半，包含所有状态，单位元。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(shared);
        assertTrue(repository.lexical(project,"订单金额的一半",20).stream().anyMatch(h->h.candidate().id()==c.id()));
        var suggestions=service.retrieve(project,project,"carol","订单金额的一半");
        assertEquals(1,suggestions.size());assertEquals("TEXT_ONLY",suggestions.get(0).representation());
        assertTrue(service.retrieve(project,project,"bob","订单金额的一半").isEmpty());
        assertTrue(repository.lexical(project+1000,"订单金额的一半",20).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_version WHERE project_id=?",Integer.class,project));
    }
    @Test void sameNameAndEvenSameTextAreNotProofOfEquivalence() {
        var a=define("alice","净额","收入扣除费用。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var b=define("bob","净额","收入扣除费用和退款。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=define("carol","净额","收入扣除费用。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        assertNotEquals(candidate(a).id(),candidate(b).id());assertNotEquals(candidate(a).id(),candidate(c).id());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_candidate WHERE project_id=?",Integer.class,project));
    }
    @Test void realPgvectorChannelFiltersVisibilityBeforeLimitAndLateEncodingCannotReviveWithdrawal() {
        var d=define("alice","Shared label","Current confirmed meaning",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(d);var identity=new EmbeddingEncodingIdentity("synthetic-fixture","synthetic-3d",3);
        var work=tx.execute(ignored->repository.claim(identity,java.time.Duration.ofMinutes(5))).orElseThrow();
        assertEquals(c.id(),work.candidate().id());assertTrue(tx.execute(ignored->repository.complete(work,identity,new float[]{1,0,0})).booleanValue());
        assertTrue(repository.lexical(project,"NoLexicalMatch",20).isEmpty());
        assertEquals(c.id(),repository.vector(project,new float[]{1,0,0},identity,1).get(0).candidate().id());
        assertTrue(repository.vector(project+1000,new float[]{1,0,0},identity,1).isEmpty());
        assertFalse(repository.hasVectors(project,new EmbeddingEncodingIdentity("different","different",3)));
        jdbc.update("UPDATE qw_project_definition_candidate SET index_state='PENDING' WHERE id=?",c.id());
        var late=tx.execute(ignored->repository.claim(identity,java.time.Duration.ofMinutes(5))).orElseThrow();
        tx.executeWithoutResult(ignored->definitions.authorize(d.preferenceId(),d.revision(),PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw"));
        assertTrue(repository.visible(project,c.id()).isEmpty());
        assertFalse(tx.execute(ignored->repository.complete(late,identity,new float[]{1,0,0})).booleanValue());
        assertTrue(repository.vector(project,new float[]{1,0,0},identity,1).isEmpty());
    }
    private record Question(RuntimeClarificationService runtime,RuntimeClarification question,String run,String principal) {}
    Question question(ProjectDefinitionCandidateRepository.Candidate candidate,String principal) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,owner_instance,lease_expire_time,project_id,project_version_id) VALUES(?,'INTERACTIVE_QUERY',?,?,'RUNNING',?,'fixture-worker',CURRENT_TIMESTAMP+interval '5 minutes',?,?)",id,id,id,id,project,project);
        var runs=new cn.lgs.semevosql.run.QueryRunService(new cn.lgs.semevosql.run.QueryRunRepository(jdbc),"fixture-worker");
        var resolver=mock(RuntimePrincipalResolver.class);when(resolver.resolve(any())).thenReturn(principal);
        var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        var runtime=new RuntimeClarificationService(new RuntimeClarificationRepository(jdbc),null,runs,null,null,
            mock(cn.lgs.semevosql.observability.SemEvoSQLMetrics.class),resolver,null,preferences,null,new cn.lgs.semevosql.common.LocalOperatorService());
        runtime.projectDefinitions(service);
        var options=List.of(new SemanticPlanningOutcome.Option(ProjectDefinitionCandidateService.option(candidate.id(),candidate.revision()),candidate.text(),"TEXT_DEFINITION",null),
            new SemanticPlanningOutcome.Option("OTHER","其他",null,null),new SemanticPlanningOutcome.Option("CANCEL","取消",null,null));
        var prompt=tx.execute(ignored->runtime.createPlanningClarification(id,"统计"+candidate.name(),new SemanticPlanningOutcome.ClarificationRequired(
            "METRIC_MISSING","是否采用这份尚未发布的建议？",options,"需要确认",candidate.name())));
        return new Question(runtime,prompt,id,principal);
    }
    void answer(Question q,SemanticBindingScope scope) {
        var cmd=new RuntimeClarificationService.AnswerCommand(q.question().revision(),"answer-"+q.run(),q.question().options().get(0).code(),null,scope,q.principal());
        var operator=new cn.lgs.semevosql.common.OperatorContext(q.principal(),"AUTHENTICATED",q.run(),q.run());
        tx.execute(ignored->q.runtime().answer(q.run(),q.question().clarificationId(),cmd,operator));
    }
    @Test void normalAnswerCreatesOwnImmutableDefinitionAndLinkWithoutCountingAUseOrPublishing() {
        var original=define("alice","归集金额","所有状态订单的下单金额合计的一半，按订单创建时间，元。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);
        var privateQuestion=question(c,"bob");answer(privateQuestion,SemanticBindingScope.USER);answer(privateQuestion,SemanticBindingScope.USER);
        var own=definitions.applicable(project,"bob","归集金额").get(0);
        assertEquals(original.text(),own.text());assertEquals("PROJECT_ADOPTION",own.sourceKind());
        assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,own.sharing());
        assertEquals(c.id(),jdbc.queryForObject("SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=?",Long.class,own.preferenceId()));
        var sharedQuestion=question(c,"carol");answer(sharedQuestion,SemanticBindingScope.PROJECT);
        assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,definitions.applicable(project,"carol","归集金额").get(0).sharing());
        assertTrue(service.retrieve(project,project,"bob","归集金额").isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id IN (SELECT preference_id FROM qw_project_definition_source WHERE candidate_id=?)",Integer.class,c.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_version WHERE project_id=?",Integer.class,project));
        tx.executeWithoutResult(ignored->definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw-alice"));
        var carol=definitions.applicable(project,"carol","归集金额").get(0);
        tx.executeWithoutResult(ignored->definitions.authorize(carol.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"carol","withdraw-carol"));
        assertTrue(repository.visible(project,c.id()).isEmpty());assertEquals(original.text(),definitions.current(own.preferenceId()).text());
    }
    @Test void withdrawnOrChangedSuggestionRejectsOldAnswerAtomically() {
        var original=define("alice","归集金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);var q=question(c,"bob");
        tx.executeWithoutResult(ignored->definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->answer(q,SemanticBindingScope.USER));
        assertTrue(definitions.applicable(project,"bob","归集金额").isEmpty());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qw_runtime_clarification WHERE clarification_id=?",String.class,q.question().clarificationId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,q.question().clarificationId()));
    }

    @Test @Timeout(15)
    void confirmationAndWithdrawalSerializeAtTheSameCandidateInBothOrders() throws Exception {
        for(boolean confirmFirst:List.of(true,false)) {
            var original=define("alice",confirmFirst?"先确认金额":"先撤回金额","全部订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
            var c=candidate(original);String reader=confirmFirst?"bob-first":"bob-second";var q=question(c,reader);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            var pool=Executors.newFixedThreadPool(2);
            var secondPid=new CompletableFuture<Integer>();
            try {
                var first=pool.submit(()->tx.execute(ignored->{
                    if(confirmFirst)answer(q,SemanticBindingScope.USER);
                    else definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw-first");
                    held.countDown();await(release);return true;
                }));
                assertTrue(held.await(5,TimeUnit.SECONDS));
                var second=pool.submit(()->tx.execute(ignored->{
                    secondPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    if(confirmFirst)definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw-second");
                    else answer(q,SemanticBindingScope.USER);
                    return true;
                }));
                awaitDatabaseLock(secondPid.get(5,TimeUnit.SECONDS));
                release.countDown();assertTrue(first.get(5,TimeUnit.SECONDS).booleanValue());
                if(confirmFirst) {
                    assertTrue(second.get(5,TimeUnit.SECONDS).booleanValue());
                    assertEquals(original.text(),definitions.applicable(project,reader,original.phrase()).get(0).text());
                    assertEquals("ANSWERED",jdbc.queryForObject("SELECT status FROM qw_runtime_clarification WHERE clarification_id=?",String.class,q.question().clarificationId()));
                } else {
                    var error=assertThrows(ExecutionException.class,()->second.get(5,TimeUnit.SECONDS));
                    assertInstanceOf(org.springframework.web.server.ResponseStatusException.class,error.getCause());
                    assertTrue(definitions.applicable(project,reader,original.phrase()).isEmpty());
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_runtime_clarification_answer WHERE clarification_id=?",Integer.class,q.question().clarificationId()));
                }
                assertTrue(repository.visible(project,c.id()).isEmpty());
            } finally {release.countDown();pool.shutdownNow();}
        }
    }

    @Test @Timeout(15)
    void sharingActionWaitsForCandidateWithoutHoldingPersonalHead() throws Exception {
        var original=define("alice","协调金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);var held=new CountDownLatch(1);var continueWriter=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);var secondPid=new CompletableFuture<Integer>();
        var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        try {
            var coordinator=pool.submit(()->tx.execute(ignored->{
                repository.lock(c.id());held.countDown();await(continueWriter);
                // A reverse head-before-candidate writer would deadlock this update.
                definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","coordinated-withdrawal");
                return true;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            var sharing=pool.submit(()->tx.execute(ignored->{
                secondPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                preferences.allowSharing(original.preferenceId());return true;
            }));
            awaitDatabaseLock(secondPid.get(5,TimeUnit.SECONDS));continueWriter.countDown();
            assertTrue(coordinator.get(5,TimeUnit.SECONDS).booleanValue());assertTrue(sharing.get(5,TimeUnit.SECONDS).booleanValue());
            assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,definitions.current(original.preferenceId()).sharing());
        } finally {continueWriter.countDown();pool.shutdownNow();}
    }

    @Test void expiredIndexLeaseCannotCommitOrFailTheReplacementWork() {
        var original=define("alice","租约金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var identity=new EmbeddingEncodingIdentity("synthetic-fixture","synthetic-lease",3);
        var first=tx.execute(ignored->repository.claim(identity,java.time.Duration.ofMinutes(5))).orElseThrow();
        jdbc.update("UPDATE qw_project_definition_candidate SET index_lease_until=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",candidate(original).id());
        var replacement=tx.execute(ignored->repository.claim(identity,java.time.Duration.ofMinutes(5))).orElseThrow();
        assertNotEquals(first.token(),replacement.token());
        assertFalse(tx.execute(ignored->repository.complete(first,identity,new float[]{1,0,0})).booleanValue());
        tx.executeWithoutResult(ignored->repository.fail(first));
        assertEquals(replacement.token(),jdbc.queryForObject("SELECT index_owner_token FROM qw_project_definition_candidate WHERE id=?",String.class,replacement.candidate().id()));
        assertTrue(tx.execute(ignored->repository.complete(replacement,identity,new float[]{0,1,0})).booleanValue());
    }

    @Test void explicitPromotionAuthorizesFullMeaningWithoutLegacyAliasOrPublicVersion() {
        var original=define("alice","份额金额","全部订单金额的一半，按下单时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        var aliases=mock(ProjectSemanticAliasProposalService.class);
        var local=new cn.lgs.semevosql.common.LocalOperatorService();
        var scope=new cn.lgs.semevosql.project.application.ProjectScopeService(jdbc,local);
        var workflow=new ProjectSemanticAliasWorkflowService(mock(cn.lgs.semevosql.project.domain.SemanticProjectRepository.class),
            aliases,preferences,local,scope,repository);
        var owner=new cn.lgs.semevosql.common.OperatorContext("alice","AUTHENTICATED","promotion","promotion");
        var confirmation=new ProjectSemanticAliasWorkflowService.SharingConfirmation(original.revision(),original.contentHash());
        var first=tx.execute(ignored->workflow.promotePreference(original.preferenceId(),confirmation,owner));
        var repeat=tx.execute(ignored->workflow.promotePreference(original.preferenceId(),confirmation,owner));
        assertEquals(first.candidateId(),repeat.candidateId());
        assertNull(first.sourceVersionId());
        assertEquals(1,definitions.current(original.preferenceId()).revision());
        assertEquals(original.text(),definitions.current(original.preferenceId()).text());
        assertEquals(PersonalSemanticDefinitionStore.Sharing.ALLOWED,definitions.current(original.preferenceId()).sharing());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_source WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_version WHERE project_id=?",Integer.class,project));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertThrows(SecurityException.class,()->tx.execute(ignored->workflow.promotePreference(original.preferenceId(),
            confirmation,new cn.lgs.semevosql.common.OperatorContext("bob","AUTHENTICATED","promotion","promotion"))));
        var revised=define("alice","份额金额","全部订单金额的四分之一，按下单时间，单位元。",PersonalSemanticDefinitionStore.Sharing.PRIVATE);
        assertEquals(2,revised.revision());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(ignored->workflow.promotePreference(original.preferenceId(),confirmation,owner)));
        assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,definitions.current(original.preferenceId()).sharing());
        verifyNoInteractions(aliases);
    }

    @Test @Timeout(15)
    void concurrentDuplicateUsageDoesNotChangeEvidenceMoreThanOnceOrPretendAQueryCompleted() throws Exception {
        var original=define("alice","使用金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);var q=question(c,"bob");
        var preferences=new UserSemanticPreferenceService(jdbc,mock(SemanticBindingTargetValidator.class));
        long before=jdbc.queryForObject("SELECT evidence_revision FROM qw_project_definition_candidate WHERE id=?",Long.class,c.id());
        var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++)tasks.add(pool.submit(()->{await(start);tx.executeWithoutResult(ignored->preferences.recordApplied(original.preferenceId(),1,q.run()));}));
            start.countDown();for(var task:tasks)task.get(5,TimeUnit.SECONDS);
        } finally {start.countDown();pool.shutdownNow();}
        assertEquals(before+1,jdbc.queryForObject("SELECT evidence_revision FROM qw_project_definition_candidate WHERE id=?",Long.class,c.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE run_id=?",Integer.class,q.run()));
        assertEquals("APPLIED",jdbc.queryForObject("SELECT event_type FROM qw_user_semantic_preference_usage WHERE run_id=?",String.class,q.run()));
        assertTrue(tx.execute(ignored->preferences.finalizeSuccessfulRun(q.run())).isEmpty());
        assertEquals(0,preferences.findById(original.preferenceId()).orElseThrow().hitCount());
    }

    @Test void contributionEvidenceRequiresTrustedOwnersRevisionConsentAndActualQueryReceipts() {
        var original=define("alice","统计金额","全部订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);
        var bobQuestion=question(c,"bob");answer(bobQuestion,SemanticBindingScope.USER);
        var carolQuestion=question(c,"carol");answer(carolQuestion,SemanticBindingScope.PROJECT);
        var bob=definitions.applicable(project,"bob","统计金额").get(0);
        var carol=definitions.applicable(project,"carol","统计金额").get(0);
        var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        for(String user:List.of("alice","bob","carol")) {
            var account=new cn.lgs.semevosql.common.LocalSecurityProperties.Account();account.setProjectIds(List.of(project));
            security.getAccounts().put(user,account);
        }
        var contributions=new ProjectDefinitionContributions(jdbc,security,new cn.lgs.semevosql.common.OperatorContextProperties());
        fixtureContribution(original,"alice","FAILED","QUERY",true);
        fixtureContribution(original,"alice","SUCCEEDED","COUNT",true);
        fixtureContribution(original,"carol","SUCCEEDED","QUERY",true); // Wrong owner cannot endorse Alice's meaning.
        fixtureContribution(bob,"bob","SUCCEEDED","QUERY",true); // Private adoption stays private.
        fixtureContribution(carol,"carol","FAILED","QUERY",true); // QUERY receipt counts even when synthesis fails.
        fixtureContribution(carol,"carol","SUCCEEDED","QUERY",false); // Explicit invalidation removes only this use.
        var before=contributions.totals(c.id(),1,project);
        assertEquals(2,before.authorizedSources());assertEquals(2,before.validUsers());assertEquals(2,before.validUses());
        var evidence=contributions.evidence(c.id(),1,project,0,100);
        assertEquals(before,evidence.get("totals"));
        var receipts=(List<Map<String,Object>>)evidence.get("records");
        assertEquals(4,receipts.size()); // Private adoption and another query owner's receipt remain private.
        assertEquals(2,receipts.stream().filter(r->Boolean.TRUE.equals(r.get("counted"))).count());
        assertTrue(receipts.stream().noneMatch(r->"bob".equals(r.get("user_id"))));
        assertEquals(1,receipts.stream().filter(r->"WITHDRAWN".equals(r.get("contribution_state"))).count());
        var repository=assessmentRepository(security);
        assertEquals(before,repository.contributionEvidence(project,c.id(),0,100).get("totals"));
        assertEquals(2,((List<?>)repository.contributionEvidence(project,c.id(),1,2).get("records")).size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repository.contributionEvidence(project+1,c.id(),0,100));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repository.contributionEvidence(project,c.id(),0,101));
        tx.executeWithoutResult(ignored->definitions.authorize(carol.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"carol","withdraw-counts"));
        var withdrawn=contributions.totals(c.id(),1,project);
        assertEquals(1,withdrawn.authorizedSources());assertEquals(1,withdrawn.validUsers());assertEquals(1,withdrawn.validUses());
        assertNotEquals(before.fingerprint(),withdrawn.fingerprint());
        var history=contributions.evidence(c.id(),1,project,0,100);
        assertEquals(withdrawn,history.get("totals"));
        assertEquals(4,((List<?>)history.get("records")).size());
        assertEquals(1,((List<Map<String,Object>>)history.get("records")).stream().filter(r->"SOURCE_INELIGIBLE".equals(r.get("contribution_state"))).count());
        assertEquals(withdrawn.fingerprint(),contributions.totals(c.id(),1,project).fingerprint()); // Read-only history changes no approval input.
        fixtureContribution(carol,"carol","SUCCEEDED","QUERY",true); // A new private use after withdrawal is not exposed.
        assertEquals(4,contributions.evidence(c.id(),1,project,0,100).get("totalRecords"));
        security.getAccounts().remove("alice");
        assertEquals(0,contributions.totals(c.id(),1,project).validUses());
        assertEquals(0,contributions.totals(c.id(),1,project).authorizedSources());
    }

    @Test void sharedQuorumCannotBeManufacturedFromUnregisteredOrSingleUserAliases() {
        var original=define("alice","账号金额","全部订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);fixtureContribution(original,"alice","SUCCEEDED","QUERY",true);
        var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        var operator=new cn.lgs.semevosql.common.OperatorContextProperties();
        var contributions=new ProjectDefinitionContributions(jdbc,security,operator);
        assertEquals(0,contributions.totals(c.id(),1,project).validUsers());
        security.setEnabled(false);
        assertEquals(0,contributions.totals(c.id(),1,project).validUsers());
        operator.setDefaultOperator("alice");
        assertEquals(1,contributions.totals(c.id(),1,project).validUsers());
    }

    private ProjectDefinitionAssessmentRepository assessmentRepository(cn.lgs.semevosql.common.LocalSecurityProperties security) {
        var projects=fixtureProjects();
        var operators=new cn.lgs.semevosql.common.OperatorContextProperties();operators.setDefaultOperator("alice");
        return new ProjectDefinitionAssessmentRepository(jdbc,projects,new ProjectDefinitionContributions(jdbc,security,operators));
    }
    private cn.lgs.semevosql.project.domain.SemanticProjectRepository fixtureProjects() {
        var projects=mock(cn.lgs.semevosql.project.domain.SemanticProjectRepository.class);
        doAnswer(call->{jdbc.queryForList("SELECT id FROM qw_project WHERE id=? FOR UPDATE",(Long)call.getArgument(0));return null;})
            .when(projects).lockProject(anyLong());
        return projects;
    }
    private void publishedAssessmentFixture() {
        // Disposable lifecycle fixtures exercise commit guards; these are not real publication evidence.
        jdbc.update("UPDATE qw_project_version SET status='PUBLISHED',catalog_hash=repeat('a',64) WHERE id=?",project);
        jdbc.update("UPDATE qw_project SET active_version_id=? WHERE id=?",project,project);
    }
    private ProjectDefinitionContributions.Totals assessmentTotals(ProjectDefinitionCandidateRepository.Candidate c) {
        var operators=new cn.lgs.semevosql.common.OperatorContextProperties();operators.setDefaultOperator("alice");
        return new ProjectDefinitionContributions(jdbc,new cn.lgs.semevosql.common.LocalSecurityProperties(),operators).totals(c.id(),c.revision(),project);
    }
    @Test void durableAssessmentPreservesActualCountsAndHistoryAndCannotClaimTheSameLeaseTwice() {
        var original=define("alice","评估金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);publishedAssessmentFixture();
        var assessments=assessmentRepository(new cn.lgs.semevosql.common.LocalSecurityProperties());
        var work=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();
        assertEquals(c.id(),work.candidate().id());assertTrue(tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).isEmpty());
        var totals=assessmentTotals(c);var decision=ProjectDefinitionPublicationPolicy.assess(
            new ProjectDefinitionPublicationPolicy.Evidence(1,0,0,true,true,false,false,true));
        var alignment=JsonNodeFactory.instance.objectNode().put("relation","NEW");
        assertTrue(tx.execute(ignored->assessments.complete(work,totals,null,alignment,decision)).booleanValue());
        assertFalse(tx.execute(ignored->assessments.complete(work,totals,null,alignment,decision)).booleanValue());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_assessment WHERE candidate_id=?",Integer.class,c.id()));
        var row=assessments.list(project).get(0);
        assertEquals("ACCUMULATING",row.get("lifecycle"));assertEquals(false,row.get("threshold_reached"));assertEquals(true,row.get("assessment_current"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_version WHERE project_id=?",Integer.class,project));
        assertEquals(original.text(),definitions.current(original.preferenceId()).text());
    }
    @Test void withdrawalWhileModelRunsInvalidatesItsAssessmentAndRetainsPersonalMeaning() {
        var original=define("alice","撤回金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);publishedAssessmentFixture();var assessments=assessmentRepository(new cn.lgs.semevosql.common.LocalSecurityProperties());
        var work=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();var totals=assessmentTotals(c);
        tx.executeWithoutResult(ignored->definitions.authorize(original.preferenceId(),1,PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw-during-assessment"));
        assertFalse(tx.execute(ignored->assessments.complete(work,totals,null,JsonNodeFactory.instance.objectNode(),
            new ProjectDefinitionPublicationPolicy.Decision("READY_FOR_PUBLISH",true,true,null,true))).booleanValue());
        assertEquals("PENDING",jdbc.queryForObject("SELECT assessment_state FROM qw_project_definition_candidate WHERE id=?",String.class,c.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_assessment WHERE candidate_id=?",Integer.class,c.id()));
        assertEquals(original.text(),definitions.current(original.preferenceId()).text());
        jdbc.update("UPDATE qw_project_definition_candidate SET assessment_next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE id=?",c.id());
    }
    @Test void changedPublicBaselineAndExpiredLeaseCannotSubmitLateAssessment() {
        var original=define("alice","基线金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);publishedAssessmentFixture();var assessments=assessmentRepository(new cn.lgs.semevosql.common.LocalSecurityProperties());
        var work=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();var totals=assessmentTotals(c);
        jdbc.update("UPDATE qw_project_version SET catalog_hash=repeat('b',64) WHERE id=?",project);
        assertFalse(tx.execute(ignored->assessments.complete(work,totals,null,JsonNodeFactory.instance.objectNode(),
            new ProjectDefinitionPublicationPolicy.Decision("READY_FOR_PUBLISH",true,true,null,true))).booleanValue());
        var latest=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();
        assertNotEquals(work.token(),latest.token());assertEquals("b".repeat(64),latest.catalogHash());
        assessments.fail(work,"LATE_PROVIDER_RESPONSE");
        assertEquals(latest.token(),jdbc.queryForObject("SELECT assessment_owner_token FROM qw_project_definition_candidate WHERE id=?",String.class,c.id()));
        assessments.fail(latest,"ASSESSMENT_UNAVAILABLE");
        assertTrue(tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).isEmpty());
        assertEquals("RETRYABLE_FAILURE",jdbc.queryForObject("SELECT assessment_state FROM qw_project_definition_candidate WHERE id=?",String.class,c.id()));
    }
    @Test void trustedAccountRemovalInvalidatesAssessmentEvenWithoutChangingEvidenceRevision() {
        var original=define("alice","身份金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);publishedAssessmentFixture();var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        var account=new cn.lgs.semevosql.common.LocalSecurityProperties.Account();account.setProjectIds(List.of(project));security.getAccounts().put("alice",account);
        var assessments=assessmentRepository(security);var work=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();
        var totals=assessmentTotals(c);security.getAccounts().clear();
        assertFalse(tx.execute(ignored->assessments.complete(work,totals,null,JsonNodeFactory.instance.objectNode(),
            new ProjectDefinitionPublicationPolicy.Decision("READY_FOR_PUBLISH",true,true,null,true))).booleanValue());
        assertEquals(work.evidenceRevision(),jdbc.queryForObject("SELECT evidence_revision FROM qw_project_definition_candidate WHERE id=?",Integer.class,c.id()));
        jdbc.update("UPDATE qw_project_definition_candidate SET assessment_next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE id=?",c.id());
    }

    @Test void publicationEnqueueIsIdempotentAndNewEvidenceInvalidatesExactPreparedInputs() {
        var original=define("alice","发布金额","订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);var c=candidate(original);
        var b=question(c,"bob");answer(b,SemanticBindingScope.PROJECT);var d=question(c,"carol");answer(d,SemanticBindingScope.PROJECT);
        fixtureContribution(original,"alice","SUCCEEDED","QUERY",true);fixtureContribution(original,"alice","SUCCEEDED","QUERY",true);
        fixtureContribution(original,"alice","SUCCEEDED","QUERY",true);
        fixtureContribution(definitions.applicable(project,"bob",original.phrase()).get(0),"bob","SUCCEEDED","QUERY",true);
        fixtureContribution(definitions.applicable(project,"carol",original.phrase()).get(0),"carol","SUCCEEDED","QUERY",true);
        var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        for(String user:List.of("alice","bob","carol")) {
            var account=new cn.lgs.semevosql.common.LocalSecurityProperties.Account();account.setProjectIds(List.of(project));security.getAccounts().put(user,account);
        }
        var contributions=new ProjectDefinitionContributions(jdbc,security,new cn.lgs.semevosql.common.OperatorContextProperties());
        publishedAssessmentFixture();var assessments=assessmentRepository(security);
        var assessment=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();var totals=contributions.totals(c.id(),1,project);
        assertEquals(3,totals.validUsers());assertEquals(5,totals.validUses());
        var structure=JsonNodeFactory.instance.objectNode();structure.putObject("metric").put("entity","orders");
        var policy=ProjectDefinitionPublicationPolicy.assess(new ProjectDefinitionPublicationPolicy.Evidence(3,3,5,true,true,false,false,true));
        assertTrue(tx.execute(ignored->assessments.complete(assessment,totals,structure,JsonNodeFactory.instance.objectNode().put("relation","NEW"),policy)).booleanValue());
        assertEquals("NEW",assessments.previousAlignment(assessment,PersonalDefinitionSnapshot.hash(structure)).orElseThrow().path("relation").asText());
        assertTrue(assessments.previousAlignment(assessment,"different-structure-identity").isEmpty());
        var jobs=new ProjectDefinitionPublicationRepository(jdbc,fixtureProjects(),contributions,new cn.lgs.semevosql.common.OperatorContext.Resolver(),
            org.mockito.Mockito.mock(cn.lgs.semevosql.project.application.ProjectScopeService.class));
        tx.executeWithoutResult(ignored->jobs.enqueueAutomatic(project));tx.executeWithoutResult(ignored->jobs.enqueueAutomatic(project));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication WHERE candidate_id=?",Integer.class,c.id()));
        var work=tx.execute(ignored->jobs.claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        assertTrue(jobs.owns(work));assertDoesNotThrow(()->tx.executeWithoutResult(ignored->jobs.assertCurrent(work)));
        fixtureContribution(original,"alice","SUCCEEDED","QUERY",true);
        assertThrows(ProjectDefinitionPublicationRepository.Stale.class,()->tx.executeWithoutResult(ignored->jobs.assertCurrent(work)));
        jobs.fail(work,true);assertEquals("STALE",jdbc.queryForObject("SELECT state FROM qw_project_definition_publication WHERE id=?",String.class,work.id()));
        assertEquals("READY_FOR_PUBLISH",jdbc.queryForObject("SELECT lifecycle FROM qw_project_definition_candidate WHERE id=?",String.class,c.id()));
        assertEquals(project,jdbc.queryForObject("SELECT active_version_id FROM qw_project WHERE id=?",Long.class,project));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication_event WHERE candidate_id=?",Integer.class,c.id()));
    }

    // Explicit contract fixtures in a disposable test database; not real-model acceptance evidence.
    private record DecisionFixture(ProjectDefinitionCandidateRepository.Candidate candidate,
            ProjectDefinitionDecisionService service,ProjectDefinitionDecisionService.Request request,
            ProjectDefinitionPublicationRepository jobs,cn.lgs.semevosql.common.LocalSecurityProperties security,
            ProjectDefinitionAssessmentRepository assessments,ProjectDefinitionAssessmentRepository.Work assessment) {}
    private DecisionFixture decisionFixture() {
        var original=define("alice","待审批金额","全部订单金额的一半。",PersonalSemanticDefinitionStore.Sharing.ALLOWED);
        var c=candidate(original);publishedAssessmentFixture();
        var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        for(String user:List.of("alice","bob")) {
            var a=new cn.lgs.semevosql.common.LocalSecurityProperties.Account();a.setAdministrator(true);a.setProjectIds(List.of(project));security.getAccounts().put(user,a);
        }
        var configured=new cn.lgs.semevosql.common.OperatorContextProperties();
        var resolver=new cn.lgs.semevosql.common.OperatorContext.Resolver(configured,security);
        var scope=new cn.lgs.semevosql.project.application.ProjectScopeService(jdbc,new cn.lgs.semevosql.common.LocalOperatorService(security));
        var projects=fixtureProjects();
        when(projects.findProject(project)).thenReturn(Optional.of(cn.lgs.semevosql.project.domain.SemanticProject.builder().id(project).activeVersionId(project).build()));
        when(projects.findVersion(project)).thenReturn(Optional.of(cn.lgs.semevosql.project.domain.SemanticProjectVersion.builder().id(project).projectId(project).catalogHash("a".repeat(64)).build()));
        var contributions=new ProjectDefinitionContributions(jdbc,security,configured);
        var assessments=new ProjectDefinitionAssessmentRepository(jdbc,projects,contributions);
        var work=tx.execute(ignored->assessments.claim(java.time.Duration.ofMinutes(3))).orElseThrow();
        var totals=contributions.totals(c.id(),1,project);
        var structure=JsonNodeFactory.instance.objectNode();structure.putObject("metric").put("entity","orders");
        var policy=ProjectDefinitionPublicationPolicy.assess(new ProjectDefinitionPublicationPolicy.Evidence(1,0,0,true,true,false,false,true));
        assertTrue(tx.execute(ignored->assessments.complete(work,totals,structure,JsonNodeFactory.instance.objectNode().put("relation","NEW"),policy)).booleanValue());
        var catalogs=mock(SemanticCatalogRepository.class);when(catalogs.loadCatalog(project,project)).thenReturn(SemanticCatalogSnapshot.builder().projectId(project).projectVersionId(project).build());
        var alias=mock(ProjectSemanticAliasService.class);when(alias.applicable(anyLong(),anyLong(),anyString())).thenReturn(List.of());
        var decisionService=new ProjectDefinitionDecisionService(jdbc,projects,catalogs,contributions,scope,resolver,alias);
        var request=new ProjectDefinitionDecisionService.Request(ProjectDefinitionDecisionService.Action.EARLY_CREATE,"真实审批契约fixture，明确不作为真实模型验收",1,work.evidenceRevision(),project,"a".repeat(64),totals.fingerprint(),PersonalDefinitionSnapshot.hash(structure),null,null);
        return new DecisionFixture(c,decisionService,request,new ProjectDefinitionPublicationRepository(jdbc,projects,contributions,resolver,scope),security,assessments,work);
    }
    private cn.lgs.semevosql.common.OperatorContext decisionActor(String user,String key) {
        return new cn.lgs.semevosql.common.OperatorContext(user,"AUTHENTICATED",key,key);
    }
    @Test void administratorEarlyApprovalIsImmutableIdempotentAndDoesNotInventUses() {
        var f=decisionFixture();var actor=decisionActor("alice","early-"+project);
        var first=tx.execute(ignored->f.service().decide(project,f.candidate().id(),f.request(),actor));
        assertEquals(first,tx.execute(ignored->f.service().decide(project,f.candidate().id(),f.request(),actor)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=?",Integer.class,f.candidate().preference()));
        var work=tx.execute(ignored->f.jobs().claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        assertEquals("EARLY_CREATE",work.action());assertEquals("alice",work.operator());assertNotNull(work.decision());
        assertDoesNotThrow(()->tx.executeWithoutResult(ignored->f.jobs().assertCurrent(work)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_project_definition_decision SET reason='changed' WHERE id=?",work.decision()));
        var changed=new ProjectDefinitionDecisionService.Request(ProjectDefinitionDecisionService.Action.DEFER,"changed operation",1,f.request().evidenceRevision(),project,"a".repeat(64),f.request().contributionFingerprint(),f.request().representationHash(),null,null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(ignored->f.service().decide(project,f.candidate().id(),changed,actor)));
        f.security().account("alice").setAdministrator(false);
        assertThrows(ProjectDefinitionPublicationRepository.Stale.class,()->tx.executeWithoutResult(ignored->f.jobs().assertCurrent(work)));
        tx.executeWithoutResult(ignored->f.jobs().fail(work,true));
        assertNull(jdbc.queryForObject("SELECT approved_decision_id FROM qw_project_definition_candidate WHERE id=?",Long.class,f.candidate().id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication_event WHERE candidate_id=?",Integer.class,f.candidate().id()));
    }
    @Test void withdrawnEvidenceInvalidatesApprovalAndTerminalReceiptRollbackIsAtomic() {
        var f=decisionFixture();
        tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor("alice","rollback-"+project)));
        var work=tx.execute(ignored->f.jobs().claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(ignored->{
            f.jobs().assertCurrent(work);f.jobs().complete(work,project);throw new IllegalStateException("Synthetic final transaction failure");
        }));
        assertEquals("READY_FOR_PUBLISH",jdbc.queryForObject("SELECT lifecycle FROM qw_project_definition_candidate WHERE id=?",String.class,f.candidate().id()));
        assertEquals("BUILDING",jdbc.queryForObject("SELECT state FROM qw_project_definition_publication WHERE id=?",String.class,work.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication_event WHERE candidate_id=?",Integer.class,f.candidate().id()));
        var d=definitions.require(f.candidate().preference(),1);
        tx.executeWithoutResult(ignored->definitions.authorize(d.preferenceId(),d.revision(),PersonalSemanticDefinitionStore.Sharing.PRIVATE,"alice","withdraw-decision-"+project));
        assertThrows(ProjectDefinitionPublicationRepository.Stale.class,()->tx.executeWithoutResult(ignored->f.jobs().assertCurrent(work)));
        assertEquals(d.text(),definitions.require(d.preferenceId(),d.revision()).text());
    }

    @Test void administratorListSeparatesPublicationRetryFromCompletedAssessmentAndFencesItsIdentity() {
        var f=decisionFixture();
        assertNull(f.assessments().list(project).get(0).get("publication"));
        tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor("alice","publication-status-"+project)));
        var work=tx.execute(ignored->f.jobs().claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        tx.executeWithoutResult(ignored->f.jobs().fail(work,false));
        var row=f.assessments().list(project).get(0);
        assertEquals("DONE",row.get("assessment_state"));
        var status=(com.fasterxml.jackson.databind.JsonNode)row.get("publication");
        assertNotNull(status,"A completed comparison must not hide a deferred publication");
        assertEquals(work.id(),status.path("id").asLong());
        assertEquals("RETRYABLE_FAILURE",status.path("state").asText());
        assertEquals(1,status.path("attempts").asInt());
        assertEquals("PUBLICATION_PREPARATION_UNAVAILABLE",status.path("lastError").asText());
        assertFalse(status.path("nextAttemptAt").isNull());
        assertFalse(status.has("owner_token"));assertFalse(status.has("source_structure"));
        assertTrue(f.assessments().list(project+1000).isEmpty());
        jdbc.update("UPDATE qw_project_definition_candidate SET representation_hash='sha256:changed' WHERE id=?",f.candidate().id());
        assertNull(f.assessments().list(project).get(0).get("publication"),"An older executable definition must not describe the current one");
        jdbc.update("UPDATE qw_project_definition_candidate SET representation_hash=?,content_revision=content_revision+1 WHERE id=?",f.request().representationHash(),f.candidate().id());
        assertNull(f.assessments().list(project).get(0).get("publication"),"An older content revision must not describe the current one");
        assertEquals("RETRYABLE_FAILURE",jdbc.queryForObject("SELECT state FROM qw_project_definition_publication WHERE id=?",String.class,work.id()));
    }
    @Test void indexDependencyDeferralKeepsUnknownBackoffAndWakesOnlyTheExactReadyApproval() throws Exception {
        var f=decisionFixture();
        tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor("alice","index-dependency-"+project)));
        var work=tx.execute(ignored->f.jobs().claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        long draft=project+1000000;
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch,parent_version_id) VALUES(?,?,2,'1.1.0','DRAFT','COMPLETED',1,1,0,?)",draft,project,project);
        var catalog=SemanticCatalogSnapshot.builder().projectId(project).projectVersionId(draft).build();
        var hash=SemanticCatalogFingerprint.fingerprint(catalog);
        tx.executeWithoutResult(ignored->{f.jobs().prepared(work,draft,hash);f.jobs().materialized(work,hash);});
        var prepared=f.jobs().refresh(work);
        // Compile/run the same method against the frozen old JAR. Its ordinary failure
        // loses the known dependency cause; the first assertion is the genuine red.
        java.lang.reflect.Method defer;
        try{defer=f.jobs().getClass().getMethod("failWaitingForIndex",ProjectDefinitionPublicationRepository.Work.class);}
        catch(NoSuchMethodException oldBehavior){defer=null;}
        if(defer==null)tx.executeWithoutResult(ignored->f.jobs().fail(prepared,false));
        else {var method=defer;tx.executeWithoutResult(ignored->invokeNative(method,f.jobs(),prepared));}
        assertEquals("PUBLICATION_WAITING_FOR_INDEX",jdbc.queryForObject("SELECT last_error FROM qw_project_definition_publication WHERE id=?",String.class,work.id()),
            "A known index dependency must remain distinguishable from an unknown publication failure");
        var catalogRepository=mock(SemanticCatalogRepository.class);when(catalogRepository.loadCatalog(project,draft)).thenReturn(catalog);
        var index=mock(cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.class);
        var dependencyClass=Class.forName("cn.lgs.semevosql.clarification.ProjectDefinitionIndexDependencyService");
        var dependencyTarget=dependencyClass.getConstructor(ProjectDefinitionPublicationRepository.class,SemanticCatalogRepository.class,
            cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.class).newInstance(transactionalProxy(f.jobs()),catalogRepository,index);
        var dependency=transactionalProxy(dependencyTarget);
        var wake=dependencyClass.getMethod("wake",ProjectDefinitionPublicationRepository.Work.class);
        var waiting=(java.util.List<?>)f.jobs().getClass().getMethod("waitingForIndex").invoke(f.jobs());
        assertEquals(1,waiting.size());var exact=(ProjectDefinitionPublicationRepository.Work)waiting.get(0);
        var incomplete=new cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.IndexReadiness(
            cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_BUILDING,6,5,"one actual dependency remains");
        doThrow(new cn.lgs.semevosql.semantic.retrieval.SemanticIndexNotReadyException(project,draft,incomplete)).when(index).assertReady(project,draft,hash);
        var before=jdbc.queryForMap("SELECT state,attempt_count,next_attempt_at,last_error,owner_token,lease_until FROM qw_project_definition_publication WHERE id=?",work.id());
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue());
        assertEquals(before,jdbc.queryForMap("SELECT state,attempt_count,next_attempt_at,last_error,owner_token,lease_until FROM qw_project_definition_publication WHERE id=?",work.id()));
        doNothing().when(index).assertReady(project,draft,hash);
        f.security().account("alice").setAdministrator(false);
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue(),"Revoked approval authority cannot be awakened");
        f.security().account("alice").setAdministrator(true);
        jdbc.update("UPDATE qw_project_definition_candidate SET representation_hash='sha256:changed' WHERE id=?",f.candidate().id());
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue(),"Changed executable definition cannot inherit a waiting job");
        jdbc.update("UPDATE qw_project_definition_candidate SET representation_hash=? WHERE id=?",f.request().representationHash(),f.candidate().id());
        when(catalogRepository.loadCatalog(project,draft)).thenReturn(SemanticCatalogSnapshot.builder().projectId(project).projectVersionId(draft+1).build());
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue());
        when(catalogRepository.loadCatalog(project,draft)).thenReturn(catalog);
        assertTrue(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue());
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue(),"A repeated dependency event must not enqueue or advance twice");
        assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM qw_project_definition_publication WHERE id=?",Integer.class,work.id()));
        assertEquals("RETRYABLE_FAILURE",jdbc.queryForObject("SELECT state FROM qw_project_definition_publication WHERE id=?",String.class,work.id()));
        assertEquals("PUBLICATION_INDEX_READY",jdbc.queryForObject("SELECT last_error FROM qw_project_definition_publication WHERE id=?",String.class,work.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication_event WHERE candidate_id=?",Integer.class,f.candidate().id()));
        assertEquals(project,jdbc.queryForObject("SELECT active_version_id FROM qw_project WHERE id=?",Long.class,project));
        var claimed=tx.execute(ignored->f.jobs().claim(java.time.Duration.ofMinutes(5))).orElseThrow();
        assertEquals(work.id(),claimed.id());assertEquals(2,claimed.attempt());
        tx.executeWithoutResult(ignored->f.jobs().fail(claimed,false));
        var unknown=jdbc.queryForMap("SELECT state,attempt_count,next_attempt_at,last_error FROM qw_project_definition_publication WHERE id=?",work.id());
        assertTrue(((java.util.List<?>)f.jobs().getClass().getMethod("waitingForIndex").invoke(f.jobs())).isEmpty());
        assertFalse(tx.execute(ignored->invokeWake(wake,dependency,exact)).booleanValue());
        assertEquals(unknown,jdbc.queryForMap("SELECT state,attempt_count,next_attempt_at,last_error FROM qw_project_definition_publication WHERE id=?",work.id()));
        var requestClass=Class.forName("cn.lgs.semevosql.clarification.ProjectDefinitionIndexDependencyService$CheckRequest");
        var request=requestClass.getConstructor(long.class,int.class,String.class).newInstance(work.id(),1,work.representationHash());
        var check=dependencyClass.getMethod("check",long.class,long.class,requestClass,cn.lgs.semevosql.common.OperatorContext.class);
        when(index.readiness(project,draft,hash)).thenReturn(incomplete);
        var actor=decisionActor("alice","manual-check-"+project);
        var notReady=tx.execute(ignored->invokeNative(check,dependency,project,f.candidate().id(),request,actor));
        assertEquals("NOT_READY",invokeNative(notReady.getClass().getMethod("status"),notReady));
        assertEquals(unknown,jdbc.queryForMap("SELECT state,attempt_count,next_attempt_at,last_error FROM qw_project_definition_publication WHERE id=?",work.id()));
        var wrong=requestClass.getConstructor(long.class,int.class,String.class).newInstance(work.id(),2,work.representationHash());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->tx.execute(ignored->invokeNative(check,dependency,project,f.candidate().id(),wrong,actor))).getStatusCode());
        when(index.readiness(project,draft,hash)).thenReturn(new cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.IndexReadiness(
            cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_READY,6,6,"controlled exact complete coverage"));
        var pool=Executors.newFixedThreadPool(4);var futures=new ArrayList<Future<String>>();
        try {
            for(int n=0;n<4;n++)futures.add(pool.submit(()->tx.execute(ignored->{
                var result=invokeNative(check,dependency,project,f.candidate().id(),request,actor);
                try{return (String)invokeNative(result.getClass().getMethod("status"),result);}
                catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
            })));
            var outcomes=new ArrayList<String>();for(var future:futures)outcomes.add(future.get(10,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"QUEUED"));assertEquals(3,Collections.frequency(outcomes,"ALREADY_QUEUED"));
        } finally {pool.shutdownNow();}
        assertEquals(2,jdbc.queryForObject("SELECT attempt_count FROM qw_project_definition_publication WHERE id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication WHERE candidate_id=?",Integer.class,f.candidate().id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication_event WHERE candidate_id=?",Integer.class,f.candidate().id()));
    }
    private static boolean invokeWake(java.lang.reflect.Method method,Object service,ProjectDefinitionPublicationRepository.Work work) {
        return (boolean)invokeNative(method,service,work);
    }
    private static Object invokeNative(java.lang.reflect.Method method,Object service,Object... args) {
        try{return method.invoke(service,args);}
        catch(java.lang.reflect.InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw new IllegalStateException("Native dependency fixture invocation failed",failure.getCause());
        }
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native dependency fixture invocation failed",failure);}
    }
    private static Object transactionalProxy(Object target) {
        var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx.getTransactionManager(),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return factory.getProxy();
    }
    @Test void deferredSuggestionPreservesPersonalDefinitionAndLateAssessmentCannotUndoDecision() {
        var f=decisionFixture();var r=f.request();
        var defer=new ProjectDefinitionDecisionService.Request(ProjectDefinitionDecisionService.Action.DEFER,"等待业务证据",r.contentRevision(),r.evidenceRevision(),r.baseVersion(),r.catalogHash(),r.contributionFingerprint(),r.representationHash(),null,null);
        tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),defer,decisionActor("alice","defer-"+project)));
        assertEquals("NEEDS_ADMIN_REVIEW",jdbc.queryForObject("SELECT lifecycle FROM qw_project_definition_candidate WHERE id=?",String.class,f.candidate().id()));
        assertEquals("ADMINISTRATOR_DEFER",jdbc.queryForObject("SELECT blocked_reason FROM qw_project_definition_candidate WHERE id=?",String.class,f.candidate().id()));
        assertTrue(tx.execute(ignored->f.assessments().claim(java.time.Duration.ofMinutes(3))).isEmpty());
        assertEquals("全部订单金额的一半。",definitions.require(f.candidate().preference(),1).text());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication WHERE candidate_id=?",Integer.class,f.candidate().id()));
        var resume=new ProjectDefinitionDecisionService.Request(ProjectDefinitionDecisionService.Action.RESUME,"材料补齐后继续评估",r.contentRevision(),r.evidenceRevision(),r.baseVersion(),r.catalogHash(),r.contributionFingerprint(),r.representationHash(),null,null);
        tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),resume,decisionActor("alice","resume-"+project)));
        assertTrue(tx.execute(ignored->f.assessments().claim(java.time.Duration.ofMinutes(3))).isPresent());
    }
    @Test void twoAdministratorsCannotApproveCompetingActionsForTheSameInputs() throws Exception {
        var f=decisionFixture();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var calls=new ArrayList<Future<Boolean>>();
            for(String user:List.of("alice","bob"))calls.add(executor.submit(()->{await(start);try {
                tx.executeWithoutResult(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor(user,"race-"+project+"-"+user)));return true;
            }catch(org.springframework.web.server.ResponseStatusException stale){assertEquals(409,stale.getStatusCode().value());return false;}}));
            start.countDown();int approved=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))approved++;
            assertEquals(1,approved);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_decision WHERE candidate_id=?",Integer.class,f.candidate().id()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_publication WHERE candidate_id=?",Integer.class,f.candidate().id()));
        }finally{executor.shutdownNow();}
    }
    @Test void ordinaryMemberAndOtherProjectAdministratorCannotApproveOrAlterPrivateMeaning() {
        var f=decisionFixture();f.security().account("alice").setAdministrator(false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor("alice","denied-member"))));
        f.security().account("bob").setProjectIds(List.of(project+1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(ignored->f.service().decide(project,f.candidate().id(),f.request(),decisionActor("bob","denied-project"))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_decision WHERE candidate_id=?",Integer.class,f.candidate().id()));
        assertEquals("全部订单金额的一半。",definitions.require(f.candidate().preference(),1).text());
    }

    private void fixtureContribution(PersonalSemanticDefinitionStore.Definition d,String owner,String status,String phase,boolean valid) {
        String run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,project_id,project_version_id,request_payload) VALUES(?,'INTERACTIVE_QUERY',?,?,?,?,?,?,?)",
            run,run,run,status,run,project,project,PersonalSemanticDefinitionStore.json(Map.of("principalId",owner)));
        jdbc.update("INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status) VALUES(?,?,?,'fixture','fixture',?,repeat('0',64),1,'FAILED')",
            UUID.randomUUID().toString(),run,run,phase);
        tx.executeWithoutResult(ignored->{definitions.lockPreferences(List.of(d.preferenceId()));
            jdbc.update("INSERT INTO qw_user_semantic_preference_usage(preference_id,definition_revision,run_id,event_type,valid,idempotency_key) VALUES(?,?,?,'COUNTED',?,?)",
                d.preferenceId(),d.revision(),run,valid,run);});
    }

    private static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(5,TimeUnit.SECONDS));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static void awaitDatabaseLock(int pid) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid)))return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("Concurrent transaction never waited at its database coordination row");
    }
}
