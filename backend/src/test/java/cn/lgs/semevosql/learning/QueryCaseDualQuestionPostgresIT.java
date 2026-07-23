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
import cn.lgs.semevosql.learning.QueryCaseQuestionIndexRepository.Scope;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real SQL/index/lease tests; synthetic vectors test retrieval mechanics, not model relevance. */
@Testcontainers
class QueryCaseDualQuestionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    QueryCaseRetrievalIndexService index;
    QueryCaseQuestionIndexRepository repository;
    EmbeddingModel model;
    long project;
    static final String HASH="b".repeat(64);
    static final AtomicInteger projects=new AtomicInteger();

    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);
    }
    @BeforeEach void setup() {
        project=projects.incrementAndGet();
        model=mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(a -> new EmbeddingResponse(((EmbeddingRequest)a.getArgument(0)).getInstructions().stream().map(x->new Embedding(new float[]{1,0},0)).toList()));
        index=new QueryCaseRetrievalIndexService(jdbc,Optional.of(model),Optional.empty(),Optional.empty(),Runnable::run);
        repository=new QueryCaseQuestionIndexRepository(jdbc);
    }
    Scope scope() { return new Scope(project,1L,HASH,null,"alice"); }

    String fixture(String original,String rewritten) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key,episode_id) VALUES (?,'INTERACTIVE_QUERY','SUCCEEDED',?,?)",id,id,id);
        jdbc.update("""
            INSERT INTO qw_query_example(id,project_id,project_version_id,catalog_hash,episode_id,attempt_id,run_id,
              original_question,normalized_question,typed_ir_json,quality_proof_json,sql_text,sql_hash,fingerprint,status)
            VALUES (?,?,1,?,?,?,?,?,?,'{"models":[]}','{}','SELECT 1',?,?,'APPROVED')
            """,id,project,HASH,id,id,id,original,rewritten,HASH,id.replace("-","").repeat(2));
        jdbc.update("""
            INSERT INTO qw_query_example_asset_ref(id,query_example_id,asset_type,asset_key,asset_fingerprint,catalog_hash)
            VALUES (?,?,'MODEL','orders',?,?)
            """,UUID.randomUUID().toString(),id,HASH,HASH);
        index.indexApprovedCase(id,rewritten);
        return id;
    }
    void dependency(String id,String scope,String user) {
        jdbc.update("""
            INSERT INTO qw_query_case_binding_dependency(query_example_id,asset_type,asset_key,binding_scope,binding_source,principal_id)
            VALUES (?,'METRIC','paid',?,?,?)
            """,id,scope,scope,user);
    }
    List<QueryCaseQuestionIndexRepository.Hit> lexical(String text,int limit) { return repository.lexical(scope(),text,limit); }
    void vectors() { assertTrue(index.buildPendingVectors(project,100)>0); }

    @Test void twoRealTextsHaveSeparateRowsAndEitherTextRecallsTheSameCase() {
        String id=fixture("退款投诉", "paid_amount January");
        assertEquals(id,lexical("退款",10).get(0).caseId());
        assertEquals("ORIGINAL_QUERY",lexical("退款",10).get(0).questionType());
        assertEquals(id,lexical("paid amount",10).get(0).caseId());
        assertEquals("REWRITTEN_QUERY",lexical("paid amount",10).get(0).questionType());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_case_question_index WHERE query_example_id=?",Integer.class,id));
    }
    @Test void identicalTextsKeepBothTypesButOneCasePerChannelAndOneRrfVote() {
        String id=fixture("支付金额", "支付金额"); vectors();
        assertEquals(1,lexical("支付金额",10).size());
        var result=index.search(project,1L,HASH,null,"alice","支付金额");
        assertEquals(1,result.size());assertEquals(id,result.get(0).caseId());
        assertEquals(2d/61,result.get(0).score(),1e-12);
        assertEquals(2,result.get(0).matches().size());
        verify(model,times(2)).call(any(EmbeddingRequest.class)); // one shared build + one query
    }
    @Test void vectorCanFindCaseOutsideTheEntireLexicalCandidatePool() {
        String id=fixture("omega", "zeta");vectors();
        assertTrue(lexical("alpha",10).isEmpty());
        var results=index.search(project,1L,HASH,null,"alice","alpha");
        assertEquals(id,results.get(0).caseId());
        assertEquals(List.of("VECTOR"),results.get(0).matches().stream().map(QueryCaseQuestionIndexRepository.Hit::channel).toList());
    }
    @Test void emptyTermsNeverReturnTheCorpusOrCallEmbedding() {
        fixture("支付金额", "支付金额");
        assertTrue(index.search(project,1L,HASH,null,"alice"," !!! | & ").isEmpty());
        verifyNoInteractions(model);
    }
    @Test void orTermsAndMixedIdentifiersAreParameterized() {
        String id=fixture("退款", "orders paid_amount");
        assertEquals(id,lexical("unknown paid",10).get(0).caseId());
        assertEquals(id,lexical("退款 paid_amount",10).get(0).caseId());
        assertDoesNotThrow(()->lexical("'); DROP TABLE qw_query_example; --",10));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_example WHERE id=?",Integer.class,id));
    }
    @Test void permissionsApplyBeforeLimitAndToBothChannels() {
        String privateId=fixture("alpha alpha alpha", "alpha alpha alpha");dependency(privateId,"USER","bob");
        String publicId=fixture("alpha beta", "alpha beta");vectors();
        assertEquals(List.of(publicId),lexical("alpha",1).stream().map(QueryCaseQuestionIndexRepository.Hit::caseId).toList());
        var i=index.identity();
        assertEquals(List.of(publicId),repository.vector(scope(),new float[]{1,0},i.model(),i.version(),1).stream().map(QueryCaseQuestionIndexRepository.Hit::caseId).toList());
        assertEquals(2,repository.lexical(new Scope(project,1L,HASH,null,"bob"),"alpha",10).size());
    }
    @Test void allScopeVersionCatalogAndContextBoundariesFailClosed() {
        String id=fixture("alpha", "alpha");
        assertTrue(repository.lexical(new Scope(project+99,1L,HASH,null,"alice"),"alpha",10).isEmpty());
        assertTrue(repository.lexical(new Scope(project,2L,HASH,null,"alice"),"alpha",10).isEmpty());
        assertTrue(repository.lexical(new Scope(project,1L,"c".repeat(64),null,"alice"),"alpha",10).isEmpty());
        dependency(id,"QUERY",null); assertTrue(lexical("alpha",10).isEmpty());
        jdbc.update("DELETE FROM qw_query_case_binding_dependency WHERE query_example_id=?",id);
        jdbc.update("UPDATE qw_query_example SET conversation_independent=false,context_hash=? WHERE id=?",HASH,id);
        index.indexApprovedCase(id,null);
        assertTrue(lexical("alpha",10).isEmpty());
        assertEquals(1,repository.lexical(new Scope(project,1L,HASH,HASH,"alice"),"alpha",10).size());
    }
    @Test void legacyEmbeddedPrivateDependencyIsFilteredBeforeLimit() {
        String id=fixture("alpha", "alpha");
        jdbc.update("UPDATE qw_query_example SET typed_ir_json=?::jsonb WHERE id=?",
            "{\"bindingDependencies\":[{\"scope\":\"USER\",\"principalId\":\"bob\"}]}",id);
        index.indexApprovedCase(id,null);
        assertTrue(lexical("alpha",10).isEmpty());
    }
    @Test void withdrawalAndNegativeFeedbackAreImmediateWithoutWaitingForIndexDeletion() {
        String id=fixture("alpha", "alpha");vectors();
        jdbc.update("UPDATE qw_query_example SET status='QUARANTINED' WHERE id=?",id);
        assertTrue(index.search(project,1L,HASH,null,"alice","alpha").isEmpty());
        jdbc.update("UPDATE qw_query_example SET status='APPROVED' WHERE id=?",id);
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,1,false)",id,id,id);
        assertTrue(index.search(project,1L,HASH,null,"alice","alpha").isEmpty());
    }
    @Test void staleSourceIsNotRecalledAndLateBuildCannotOverwriteNewGeneration() {
        String id=fixture("alpha", "alpha");
        var old=jdbc.queryForMap("SELECT * FROM qw_query_case_question_index WHERE query_example_id=? AND question_type='ORIGINAL_QUERY'",id);
        jdbc.update("UPDATE qw_query_case_question_index SET embedding_claim_token='late' WHERE query_example_id=?",id);
        jdbc.update("UPDATE qw_query_example SET original_question='changed' WHERE id=?",id);
        assertTrue(lexical("alpha",10).isEmpty());
        assertEquals(0,index.publishVector(old,"late",index.identity(),new float[]{1,0}));
        index.indexApprovedCase(id,null);
        assertEquals(0,index.publishVector(old,"late",index.identity(),new float[]{1,0}));
        assertEquals(id,lexical("changed",10).get(0).caseId());
    }
    @Test void identicalContentDoesNotRebuildAndProofOnlyChangeReusesVectors() {
        String id=fixture("alpha", "alpha");vectors();
        long generation=jdbc.queryForObject("SELECT MAX(generation) FROM qw_query_case_question_index WHERE query_example_id=?",Long.class,id);
        index.indexApprovedCase(id,null);
        assertEquals(generation,jdbc.queryForObject("SELECT MAX(generation) FROM qw_query_case_question_index WHERE query_example_id=?",Long.class,id));
        jdbc.update("UPDATE qw_query_example SET quality_proof_json='{\"revision\":2}' WHERE id=?",id);
        index.indexApprovedCase(id,null);
        assertEquals(0,index.buildPendingVectors(project,100));
        verify(model,times(1)).call(any(EmbeddingRequest.class));
    }
    @Test void failedBuildPersistsBackoffAndQueryNeverBuildsDocumentVectors() {
        String id=fixture("alpha", "alpha");
        when(model.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("synthetic provider outage"));
        assertEquals(0,index.buildPendingVectors(project,100));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_case_question_index WHERE query_example_id=? AND retry_count=1 AND next_retry_at>CURRENT_TIMESTAMP AND embedding_claim_token IS NULL",Integer.class,id));
        clearInvocations(model);
        assertEquals(1,index.search(project,1L,HASH,null,"alice","alpha").size());
        verify(model,times(1)).call(any(EmbeddingRequest.class));
        assertEquals(0,index.buildPendingVectors(project,100));
        verify(model,times(1)).call(any(EmbeddingRequest.class));
    }
    @Test void ftsFailureStillUsesIndependentVectorChannel() {
        String id=fixture("alpha", "alpha");vectors();
        jdbc.execute("ALTER TABLE qw_query_case_question_index RENAME search_vector TO temporary_unavailable");
        try { assertEquals(id,index.search(project,1L,HASH,null,"alice","alpha").get(0).caseId()); }
        finally { jdbc.execute("ALTER TABLE qw_query_case_question_index RENAME temporary_unavailable TO search_vector"); }
    }
    @Test void legacyMissingOriginalIsNotFabricatedAndScannerBackfills() {
        String id=fixture(null,"alpha");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qw_query_case_question_index WHERE query_example_id=?",Integer.class,id));
        jdbc.update("DELETE FROM qw_query_case_question_index WHERE query_example_id=?",id);
        assertEquals(1,index.synchronizeTexts(project,10));
        assertEquals(0,index.synchronizeTexts(project,10));
        assertEquals("REWRITTEN_QUERY",lexical("alpha",10).get(0).questionType());
    }
    @Test void channelLimitCountsCasesNotQuestionRecordsAndTieOrderIsStable() {
        for(int n=0;n<14;n++) fixture("alpha", "alpha");
        var first=lexical("alpha",10);
        assertEquals(10,first.size());
        assertEquals(first,lexical("alpha",10));
        assertEquals(first.stream().map(QueryCaseQuestionIndexRepository.Hit::caseId).sorted().toList(),first.stream().map(QueryCaseQuestionIndexRepository.Hit::caseId).toList());
    }
    @Test void concurrentBuildersClaimEachDocumentOnlyOnce() throws Exception {
        fixture("alpha", "alpha");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(a->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0)));});
        var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->index.buildPendingVectors(project,20));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(0,index.buildPendingVectors(project,20));
            release.countDown();assertEquals(2,first.get(10,TimeUnit.SECONDS));
            verify(model,times(1)).call(any(EmbeddingRequest.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void versionedEvidenceIndexesTheOriginalAndFirstRewrittenTexts() {
        String id=fixture("legacyoriginal", "legacynormalized");
        jdbc.update("UPDATE qw_query_example SET quality_proof_json=?::jsonb WHERE id=?",
            "{\"payload\":{\"requestEvidence\":{\"originalQuery\":\"用户原始问题\",\"rootCanonicalQuery\":\"第一次增强问题\"}}}",id);
        index.indexApprovedCase(id,null);
        assertEquals("ORIGINAL_QUERY",lexical("用户原始",10).get(0).questionType());
        assertEquals("REWRITTEN_QUERY",lexical("第一次增强",10).get(0).questionType());
        assertTrue(lexical("legacynormalized",10).isEmpty());
    }
    @Test void missingOneQuestionProjectionIsNotReportedReady() {
        String id=fixture("alpha", "beta");vectors();
        assertEquals("INDEX_READY",index.readiness(project).status());
        jdbc.update("DELETE FROM qw_query_case_question_index WHERE query_example_id=? AND question_type='ORIGINAL_QUERY'",id);
        assertNotEquals("INDEX_READY",index.readiness(project).status());
    }
    @Test void anonymousPrincipalCannotReadUnownedPrivateBindingAndNullLegacyArrayIsSafe() {
        String id=fixture("alpha", "alpha");dependency(id,"USER",null);
        assertTrue(repository.lexical(new Scope(project,1L,HASH,null,null),"alpha",10).isEmpty());
        jdbc.update("DELETE FROM qw_query_case_binding_dependency WHERE query_example_id=?",id);
        jdbc.update("UPDATE qw_query_example SET typed_ir_json='{\"bindingDependencies\":null}' WHERE id=?",id);
        index.indexApprovedCase(id,null);
        assertEquals(1,lexical("alpha",10).size());
    }

    @Test void backgroundUsesIndexBudgetWhileRecallKeepsInteractiveModel() {
        String id=fixture("alpha", "alpha");
        EmbeddingModel background=mock(EmbeddingModel.class);
        when(background.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0))));
        index=new QueryCaseRetrievalIndexService(jdbc,Optional.of(model),Optional.empty(),
            Optional.of(()->background),Runnable::run);
        vectors();verifyNoInteractions(model);
        assertEquals(id,index.search(project,1L,HASH,null,"alice","alpha").get(0).caseId());
        verify(background,times(1)).call(any(EmbeddingRequest.class));
        verify(model,times(1)).call(any(EmbeddingRequest.class));
    }
    @Test void rejectedExecutorDoesNotPermanentlySuppressScans() {
        AtomicInteger submissions=new AtomicInteger();
        var service=new QueryCaseRetrievalIndexService(jdbc,Optional.of(model),Optional.empty(),Optional.empty(),
            task->{submissions.incrementAndGet();throw new RejectedExecutionException("fixture saturated");});
        service.scan();service.scan();assertEquals(2,submissions.get());
        verifyNoInteractions(model);
    }
    @Test void expiredWorkerCannotPublishEvenBeforeAnotherWorkerClaims() {
        String id=fixture("alpha", "alpha");
        jdbc.update("UPDATE qw_query_case_question_index SET embedding_claim_token='expired',embedding_lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE query_example_id=?",id);
        var doc=jdbc.queryForMap("SELECT * FROM qw_query_case_question_index WHERE query_example_id=? AND question_type='ORIGINAL_QUERY'",id);
        assertEquals(0,index.publishVector(doc,"expired",index.identity(),new float[]{1,0}));
        vectors();assertEquals("INDEX_READY",index.readiness(project).status());
    }

}
