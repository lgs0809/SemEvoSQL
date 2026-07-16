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
package cn.lgs.semevosql.semantic.retrieval.indexing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.semantic.retrieval.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL migration, partial-index writes, transactions and lease races. Model vectors are synthetic. */
@Testcontainers
class SemanticReindexPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    SemanticReindexWorkRepository work;
    SemanticRetrievalDocumentRepository documents;
    static final String HASH="a".repeat(64);

    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);transactions=new DataSourceTransactionManager(ds);
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(91,'reindex-test','Synthetic reindex test','test','ACTIVE','test')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(91,91,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM qw_semantic_retrieval_document");
        jdbc.update("DELETE FROM qw_embedding_index_registry");jdbc.update("DELETE FROM qw_semantic_reindex_work");
        work=new SemanticReindexWorkRepository(jdbc);documents=new SemanticRetrievalDocumentRepository(jdbc);
    }
    SemanticRetrievalDocument document(String id,String hash) {
        return new SemanticRetrievalDocument(id,91L,91L,HASH,SemanticRetrievalDocument.DocumentType.MODEL,"MODEL","model:"+id,
            1,id,id,"支付金额","支付金额"+hash,hash,hash,"CATALOG","whole-model-v2","CATALOG_DESCRIPTION");
    }
    SemanticRetrievalIndexService.ConfiguredIdentity identity(String version) {
        return new SemanticRetrievalIndexService.ConfiguredIdentity("synthetic-model",version);
    }

    @Test void repeatedRequestsAndConcurrentClaimsHaveOneObligation() throws Exception {
        work.request(identity("v1"),"admin");var first=work.claim().orElseThrow();
        work.request(identity("v1"),"admin");assertEquals(1L,work.status().get("revision"));assertTrue(work.renew(first));
        assertTrue(work.claim().isEmpty());assertTrue(work.finish(first,null,"SYNTHETIC_503"));
        assertEquals("RETRY",work.status().get("status"));assertTrue(work.claim().isEmpty());
        jdbc.update("UPDATE qw_semantic_reindex_work SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=java.util.stream.IntStream.range(0,8).mapToObj(n->(Callable<Boolean>)()->work.claim().isPresent()).toList();
            int claimed=0;for(var result:pool.invokeAll(calls,10,TimeUnit.SECONDS))if(result.get())claimed++;
            assertEquals(1,claimed);
        }finally{pool.shutdownNow();}
    }

    @Test void restartReclaimsExpiredLeaseAndRejectsOldCompletion() {
        work.request(identity("v1"),"admin");var old=work.claim().orElseThrow();
        jdbc.update("UPDATE qw_semantic_reindex_work SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        assertFalse(work.renew(old));assertFalse(work.finish(old,2,null));
        var recovered=new SemanticReindexWorkRepository(jdbc).claim().orElseThrow();
        assertNotEquals(old.ownerToken(),recovered.ownerToken());assertEquals(2,recovered.attempt());
        assertFalse(work.finish(old,2,null));assertTrue(work.finish(recovered,2,null));
    }

    @Test void replacementDuringEncodingCannotPublishTheOldGeneration() {
        var index=mock(SemanticRetrievalIndexService.class);when(index.reindexIdentity()).thenReturn(identity("v1"));
        var service=new SemanticReindexMaintenanceService(work,index,transactions);service.request("admin");
        doAnswer(call->{work.request(identity("v2"),"admin");return null;}).when(index).stageReindex(identity("v1"));
        assertTrue(service.processOne());verify(index,never()).publishStagedReindex(any());
        assertEquals("PENDING",work.status().get("status"));assertEquals(2L,work.status().get("revision"));
    }

    @Test void publicationAndDoneRollbackTogether() {
        var index=mock(SemanticRetrievalIndexService.class);when(index.reindexIdentity()).thenReturn(identity("v1"));
        var service=new SemanticReindexMaintenanceService(work,index,transactions);service.request("admin");
        when(index.publishStagedReindex(any())).thenAnswer(call->{
            jdbc.update("INSERT INTO qw_embedding_index_registry(index_scope,embedding_model,embedding_version,dimension,status) VALUES('SEMANTIC_CATALOG','synthetic','failed',2,'ACTIVE')");
            throw new IllegalStateException("SYNTHETIC_COMMIT_FAILURE");
        });
        assertTrue(service.processOne());assertEquals("RETRY",work.status().get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_embedding_index_registry",Integer.class));
    }

    @Test void partialBuildSurvivesFailureReusesVectorsAndKeepsPreviousEncoding() {
        var revision=new AtomicReference<>("v1");var dimension=new AtomicInteger(2);var calls=new AtomicInteger();
        var failSecond=new AtomicBoolean(false);
        var model=mock(EmbeddingModel.class);when(model.call(any(EmbeddingRequest.class))).thenAnswer(call->{
            int n=calls.incrementAndGet();if(failSecond.get() && n==2)throw new IllegalStateException("SYNTHETIC_PROVIDER_BUSY");
            var request=call.getArgument(0,EmbeddingRequest.class);var vectors=new ArrayList<Embedding>();
            for(int i=0;i<request.getInstructions().size();i++){
                float[] vector=new float[dimension.get()];Arrays.fill(vector,0.5f);vectors.add(new Embedding(vector,i));
            }
            return new EmbeddingResponse(vectors);
        });
        EmbeddingModelIdentityProvider provider=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "synthetic-model",Map.of("revision",revision.get(),"dimensions",dimension.get())));
        var index=new SemanticRetrievalIndexService(jdbc,Optional.of(model),Optional.of(provider));
        var docs=List.of(document("a",HASH),document("b",HASH));docs.forEach(documents::upsert);
        index.indexDocuments(docs);String old=index.reindexIdentity().version();
        revision.set("v2");dimension.set(3);calls.set(0);failSecond.set(true);
        var service=new SemanticReindexMaintenanceService(work,index,transactions);service.request("admin");
        assertTrue(service.processOne());assertEquals("RETRY",work.status().get("status"));
        assertEquals(old,jdbc.queryForObject("SELECT embedding_version FROM qw_embedding_index_registry",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class));
        failSecond.set(false);jdbc.update("UPDATE qw_semantic_reindex_work SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        assertTrue(service.processOne());assertEquals("DONE",work.status().get("status"));assertEquals(3,calls.get());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class));
        assertEquals(index.reindexIdentity().version(),jdbc.queryForObject("SELECT embedding_version FROM qw_embedding_index_registry",String.class));
        index.assertReady(91L,91L,HASH);
    }

    @Test void changedContentAfterStagingRejectsPublicationAndRetainsRegistry() {
        var model=mock(EmbeddingModel.class);when(model.call(any(EmbeddingRequest.class)))
            .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{0.5f,0.25f},0))));
        var revision=new AtomicReference<>("v1");
        EmbeddingModelIdentityProvider provider=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "synthetic-model",Map.of("revision",revision.get())));
        var index=new SemanticRetrievalIndexService(jdbc,Optional.of(model),Optional.of(provider));
        var original=document("a",HASH);documents.upsert(original);index.indexDocuments(List.of(original));
        String old=index.reindexIdentity().version();revision.set("v2");var next=index.reindexIdentity();index.stageReindex(next);
        documents.upsert(document("a","b".repeat(64)));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->index.publishStagedReindex(next)));
        assertEquals(old,jdbc.queryForObject("SELECT embedding_version FROM qw_embedding_index_registry",String.class));
    }
    @Test void catalogChangeDuringEncodingKeepsRejectedVectorRetryableAndQueueResumable() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var model=mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(call->{
            if(calls.incrementAndGet()==1) {
                entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
            }
            return new EmbeddingResponse(List.of(new Embedding(new float[]{0.5f,0.25f},0)));
        });
        EmbeddingModelIdentityProvider provider=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "synthetic-model",Map.of("revision","v1")));
        var index=new SemanticRetrievalIndexService(jdbc,Optional.of(model),Optional.of(provider));
        var queue=new SemanticIndexWorkRepository(jdbc);
        documents.upsert(document("a",HASH));documents.upsert(document("b",HASH));
        var worker=new SemanticIndexWorker(queue,documents,index,Runnable::run);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(worker::processOne);
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            String updatedCatalog="b".repeat(64);
            // Draft publication preparation may change only the catalog scope, not this document's text.
            jdbc.update("UPDATE qw_semantic_retrieval_document SET catalog_hash=? WHERE id='a'",updatedCatalog);
            release.countDown();
            assertFalse(pending.get(10,TimeUnit.SECONDS));
            assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM qw_semantic_document_index_work WHERE document_id='a'",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class));
            assertEquals(1L,jdbc.queryForObject("SELECT revision FROM qw_semantic_document_index_work WHERE document_id='a'",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM qw_semantic_document_index_work WHERE document_id='b'",Integer.class));
            jdbc.update("UPDATE qw_semantic_document_index_work SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE document_id='a'");
            // Both due obligations finish, irrespective of their timestamp ordering.
            assertTrue(worker.processOne());
            assertTrue(worker.processOne());
            assertEquals("DONE",jdbc.queryForObject("SELECT status FROM qw_semantic_document_index_work WHERE document_id='a'",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE document_id='a'",Integer.class));
            var cached=index.indexDocuments(List.of(documents.findById("a").orElseThrow()));
            assertTrue(cached.vectorAvailable());assertEquals(0,cached.indexedDocuments());assertEquals(3,calls.get());
            assertFalse(worker.processOne());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='DONE'",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }


    @Test void failedMaintenanceStopsDocumentDrainUntilDurableRetryCompletes() {
        var calls=new AtomicInteger();var model=mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenAnswer(call->{
            if(calls.incrementAndGet()==1)throw new IllegalStateException("SYNTHETIC_PROVIDER_BUSY");
            return new EmbeddingResponse(List.of(new Embedding(new float[]{0.5f,0.25f},0)));
        });
        EmbeddingModelIdentityProvider provider=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "synthetic-model",Map.of("revision","v1")));
        var index=new SemanticRetrievalIndexService(jdbc,Optional.of(model),Optional.of(provider));
        var maintenance=new SemanticReindexMaintenanceService(work,index,transactions);
        var queue=new SemanticIndexWorkRepository(jdbc);
        documents.upsert(document("a",HASH));documents.upsert(document("b",HASH));
        var worker=new SemanticIndexWorker(queue,documents,index,Runnable::run);
        worker.setMaintenance(maintenance);maintenance.request("admin");worker.wake();
        assertAll("Failed maintenance must preserve the following durable obligations",
            ()->assertEquals("RETRY",work.status().get("status")),
            ()->assertEquals(1,calls.get(),"No further provider call in the same failed drain"),
            ()->assertEquals(0,jdbc.queryForObject("SELECT sum(attempt_count) FROM qw_semantic_document_index_work",Integer.class),"No document claimed"),
            ()->assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='PENDING'",Integer.class)),
            ()->assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class)));
        // Advance only this disposable test's maintenance retry clock; retain its revision and lease fences.
        jdbc.update("UPDATE qw_semantic_reindex_work SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        worker.wake();
        assertEquals("DONE",work.status().get("status"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='DONE'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class));
        assertEquals(3,calls.get(),"Both staged vectors reused by the following document drain");
        documents.upsert(document("c",HASH));worker.wake();
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='DONE'",Integer.class));
        assertEquals(4,calls.get(),"An empty maintenance queue still permits normal document work");
    }

}
