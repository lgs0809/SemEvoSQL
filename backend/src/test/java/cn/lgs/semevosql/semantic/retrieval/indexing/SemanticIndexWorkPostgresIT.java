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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL transaction/lease races. Synthetic index responses test scheduling, not model quality. */
@Testcontainers
class SemanticIndexWorkPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    SemanticRetrievalDocumentRepository documents;
    SemanticIndexWorkRepository jobs;
    static final String HASH="a".repeat(64);

    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(91,'index-work-test','Synthetic index test','test','ACTIVE','test')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(91,91,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM qw_semantic_retrieval_document WHERE project_id=91");
        documents=new SemanticRetrievalDocumentRepository(jdbc);jobs=new SemanticIndexWorkRepository(jdbc);
    }
    SemanticRetrievalDocument document(String id,String hash) {
        return new SemanticRetrievalDocument(id,91L,91L,HASH,SemanticRetrievalDocument.DocumentType.MODEL,"MODEL","model:"+id,
            1,id,id,"付款金额","付款金额"+hash,hash,hash,"CATALOG","whole-model-v2","CATALOG_DESCRIPTION");
    }
    Map<String,Object> row() { return jdbc.queryForMap("SELECT * FROM qw_semantic_document_index_work"); }

    @Test void completionWakeIsDeliveredOnlyAfterCommitAndNeverForRollbackFailureOrStaleLease() {
        var delivered=new ArrayList<SemanticIndexWorkRepository.IndexCompleted>();
        var listener=new org.springframework.transaction.event.TransactionalApplicationListenerAdapter<
            org.springframework.context.PayloadApplicationEvent<SemanticIndexWorkRepository.IndexCompleted>>(event->delivered.add(event.getPayload()));
        listener.setTransactionPhase(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
        jobs.setEvents(event->listener.onApplicationEvent(new org.springframework.context.PayloadApplicationEvent<>(this,
            (SemanticIndexWorkRepository.IndexCompleted)event)));
        documents.upsert(document("wake",HASH));var work=jobs.claim().orElseThrow();
        transaction.executeWithoutResult(status->{
            assertTrue(jobs.finish(work,true));assertTrue(delivered.isEmpty());
            status.setRollbackOnly();
        });
        assertTrue(delivered.isEmpty());assertEquals("PROCESSING",row().get("status"));
        transaction.executeWithoutResult(status->{assertTrue(jobs.finish(work,true));assertTrue(delivered.isEmpty());});
        assertEquals(List.of(new SemanticIndexWorkRepository.IndexCompleted(91L,91L,HASH)),delivered);
        transaction.executeWithoutResult(status->assertFalse(jobs.finish(work,true)));
        assertEquals(1,delivered.size());
        documents.upsert(document("wake","b".repeat(64)));var latest=jobs.claim().orElseThrow();
        transaction.executeWithoutResult(status->assertFalse(jobs.finish(work,true)));
        transaction.executeWithoutResult(status->assertTrue(jobs.finish(latest,false)));
        assertEquals(1,delivered.size());assertEquals("RETRY",row().get("status"));
    }

    @Test void contentAndWorkRollbackTogetherAndUnchangedWritesDoNotReencode() {
        transaction.executeWithoutResult(status->{documents.upsert(document("a",HASH));status.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work",Integer.class));
        documents.upsert(document("a",HASH));var first=jobs.claim().orElseThrow();assertTrue(jobs.finish(first,true));
        documents.upsert(document("a",HASH));assertEquals("DONE",row().get("status"));assertEquals(1L,row().get("revision"));
        assertTrue(jobs.claim().isEmpty());
    }
    @Test void newRepresentationFencesOldCompletionEvenWhenTheDocumentIdentityIsUnchanged() {
        documents.upsert(document("a",HASH));var old=jobs.claim().orElseThrow();
        documents.upsert(document("a","b".repeat(64)));assertFalse(jobs.finish(old,true));assertFalse(jobs.renew(old));
        var next=jobs.claim().orElseThrow();assertEquals(2,next.revision());assertEquals(1,next.attempt());
        assertTrue(jobs.finish(next,true));assertEquals("DONE",row().get("status"));
    }
    @Test void expiredLeaseCannotCommitAndReclaimedAttemptGetsANewToken() {
        documents.upsert(document("a",HASH));var old=jobs.claim().orElseThrow();
        jdbc.update("UPDATE qw_semantic_document_index_work SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        assertFalse(jobs.finish(old,true));assertFalse(jobs.renew(old));
        var next=jobs.claim().orElseThrow();assertEquals(2,next.attempt());assertNotEquals(old.ownerToken(),next.ownerToken());
        assertFalse(jobs.finish(old,false));assertTrue(jobs.finish(next,true));
    }
    @Test void transientFailurePersistsAndRetryUsesTheLatestAuthoritativeDocument() {
        documents.upsert(document("a",HASH));var index=mock(SemanticRetrievalIndexService.class);
        when(index.indexDocuments(anyList())).thenReturn(new SemanticRetrievalIndexService.IndexingResult(0,false))
            .thenReturn(new SemanticRetrievalIndexService.IndexingResult(1,true));
        var worker=new SemanticIndexWorker(jobs,documents,index,Runnable::run);
        assertFalse(worker.processOne());assertEquals("RETRY",row().get("status"));assertFalse(worker.processOne());
        jdbc.update("UPDATE qw_semantic_document_index_work SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        assertTrue(worker.processOne());assertEquals("DONE",row().get("status"));verify(index,times(2)).indexDocuments(anyList());
    }
    @Test void failedEncodingStopsTheDrainWithoutConsumingPendingDocuments() {
        documents.upsert(document("a",HASH));documents.upsert(document("b",HASH));documents.upsert(document("c",HASH));
        var index=mock(SemanticRetrievalIndexService.class);
        when(index.indexDocuments(anyList())).thenReturn(new SemanticRetrievalIndexService.IndexingResult(0,false))
            .thenReturn(new SemanticRetrievalIndexService.IndexingResult(1,true));
        var worker=new SemanticIndexWorker(jobs,documents,index,Runnable::run);
        worker.scan();
        assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM qw_semantic_document_index_work WHERE document_id='a'",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='PENDING' AND attempt_count=0",Integer.class));
        verify(index,times(1)).indexDocuments(anyList());
        worker.scan();
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work WHERE status='DONE' AND attempt_count=1",Integer.class));
        verify(index,times(3)).indexDocuments(anyList());
    }
    @Test void concurrentScannersClaimOnceAndDeletedDocumentsLeaveNoOrphanWork() throws Exception {
        documents.upsert(document("a",HASH));var pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> calls=java.util.stream.IntStream.range(0,8)
                .mapToObj(n->(Callable<Boolean>)()->jobs.claim().isPresent()).toList();
            int claimed=0;for(var result:pool.invokeAll(calls,20,TimeUnit.SECONDS))if(result.get())claimed++;
            assertEquals(1,claimed);
        } finally { pool.shutdownNow(); }
        jdbc.update("DELETE FROM qw_semantic_retrieval_document WHERE id='a'");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work",Integer.class));
    }
    @Test void missedWakeupAndExecutorRejectionAreRecoveredByASubsequentScanner() {
        documents.upsert(document("a",HASH));var index=mock(SemanticRetrievalIndexService.class);
        when(index.indexDocuments(anyList())).thenReturn(new SemanticRetrievalIndexService.IndexingResult(0,true));
        var rejected=new SemanticIndexWorker(jobs,documents,index,r->{throw new RejectedExecutionException();});
        rejected.scan();assertEquals("PENDING",row().get("status"));verifyNoInteractions(index);
        new SemanticIndexWorker(jobs,documents,index,Runnable::run).scan();assertEquals("DONE",row().get("status"));
    }
}
