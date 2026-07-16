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
package cn.lgs.semevosql.semantic.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.semantic.retrieval.indexing.SemanticIndexWorkRepository;
import cn.lgs.semevosql.semantic.retrieval.indexing.SemanticIndexWorker;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL/index-worker tests. Model certificates/vectors are explicitly synthetic fixtures. */
class SemanticEmbeddingReusePostgresIT {
    static PostgreSQLContainer<?> pg;
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static final String MODEL="synthetic-actual-model", HASH="a".repeat(64), REVISION="1".repeat(40);
    FixtureEncoder encoder;
    SemanticRetrievalIndexService index;
    SemanticRetrievalDocumentRepository documents;
    SemanticRetrievalDocument source;

    @BeforeAll static void migrate() {
        String external=System.getenv("SEMEVOSQL_CACHE_IT_JDBC_URL");
        var migration=Flyway.configure().locations("classpath:db/migration/postgresql");
        if(external==null) {
            pg=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            pg.start();dataSource=new DriverManagerDataSource(pg.getJdbcUrl()+"&stringtype=unspecified",pg.getUsername(),pg.getPassword());
        } else {
            String schema=System.getenv("SEMEVOSQL_CACHE_IT_SCHEMA");
            if(!external.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]+")
                    ||schema==null||!schema.matches("semevosql_cache_native_[0-9a-f]{12}"))
                throw new IllegalArgumentException("Native cache IT needs a new task-owned local schema");
            dataSource=new DriverManagerDataSource(external+"?currentSchema="+schema+",public&stringtype=unspecified",
                System.getenv("SEMEVOSQL_CACHE_IT_DB_USER"),System.getenv("SEMEVOSQL_CACHE_IT_DB_PASSWORD"));
            if(Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)",Boolean.class,schema)))
                throw new IllegalArgumentException("Retain earlier native schema; use a fresh name");
            migration.schemas(schema).defaultSchema(schema);
        }
        migration.dataSource(dataSource).load().migrate();jdbc=new JdbcTemplate(dataSource);
    }
    @AfterAll static void stop(){if(pg!=null)pg.stop();}
    @BeforeEach void fixture() {
        // Only this disposable test DB or task-owned synthetic schema is touched.
        jdbc.update("DELETE FROM qw_project");jdbc.update("DELETE FROM qw_embedding_index_registry");
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(91,'atomic-cache','Atomic cache fixture','test','ACTIVE','test'),(94,'atomic-other','Other cache fixture','test','ACTIVE','test')");
        for(int version:List.of(91,92,94))jdbc.update("""
            INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,
                semantic_major,semantic_minor,semantic_patch,catalog_hash)
            VALUES(?,?,?,?,'DRAFT','COMPLETED',1,0,?,?)
            """,version,version==94?94:91,version,"1.0."+version,version,HASH);
        encoder=new FixtureEncoder();index=index(jdbc);documents=new SemanticRetrievalDocumentRepository(jdbc);
        source=doc("source",91L,91L,"complete permission-visible input",HASH,1,"orders","public.orders","whole-model-v2");
    }
    SemanticRetrievalIndexService index(JdbcTemplate connection) {
        EmbeddingModelIdentityProvider identity=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "fixture:"+MODEL,EmbeddingEncodingIdentity.attributes("fixture",MODEL,"http://fixture","/v1/embeddings",2)));
        return new SemanticRetrievalIndexService(connection,Optional.of(encoder),Optional.of(identity));
    }
    void publishSource() {
        documents.upsert(source);assertTrue(index.indexDocuments(List.of(source)).vectorAvailable());
        jdbc.update("UPDATE qw_project_version SET status='PUBLISHED' WHERE id=91");
    }
    SemanticRetrievalDocument target() {return doc("target",91L,92L,source.semanticText(),HASH,1,"orders","public.orders","whole-model-v2");}
    SemanticRetrievalDocument doc(String id,Long project,Long version,String text,String fingerprint,Integer datasource,
            String model,String physical,String builder) {
        return new SemanticRetrievalDocument(id,project,version,HASH,SemanticRetrievalDocument.DocumentType.MODEL,
            "MODEL","model:orders",datasource,model,physical,text,text,fingerprint,HASH,"OFFLINE_CATALOG",builder,"CATALOG_DESCRIPTION");
    }
    @Test void samePublishedInputCopiesCertifiedVectorAndRetainsExactSourceProvenance() {
        publishSource();var target=target();documents.upsert(target);
        assertTrue(index.indexDocuments(List.of(target)).vectorAvailable());assertEquals(1,encoder.calls.get());
        var row=jdbc.queryForMap("SELECT e.*,e.embedding::text AS actual_vector FROM qw_semantic_retrieval_embedding e WHERE document_id='target'");
        assertNotNull(row.get("encoding_identity"));assertNotNull(row.get("reuse_source"));
        assertTrue(row.get("reuse_source").toString().contains("source"));
        assertTrue(row.get("reuse_source").toString().contains("91"));
        assertEquals("[0.25,0.5]",row.get("actual_vector"));
        assertEquals(encoder.profile().sha256(),row.get("encoding_profile_sha256"));
    }
    @Test void oldUnknownVectorIsNeverCertifiedByALaterCurrentProfile() {
        encoder.certify=false;publishSource();encoder.certify=true;
        var target=target();documents.upsert(target);
        assertTrue(index.indexDocuments(List.of(target)).vectorAvailable());assertEquals(2,encoder.calls.get());
        assertNull(jdbc.queryForMap("SELECT encoding_identity FROM qw_semantic_retrieval_embedding WHERE document_id='source'").get("encoding_identity"));
        var row=jdbc.queryForMap("SELECT encoding_identity,reuse_source FROM qw_semantic_retrieval_embedding WHERE document_id='target'");
        assertNotNull(row.get("encoding_identity"));assertNull(row.get("reuse_source"));
    }
    @ParameterizedTest
    @ValueSource(strings={"text","permission","source","datasource","model","physical","builder","project","unpublished","revision","unknown-profile","corrupt-vector"})
    void anyAuthorityInputOrActualEncoderChangeMissesRatherThanReusing(String change) {
        publishSource();var target=target();
        switch(change) {
            case "text" -> target=doc("target",91L,92L,"new complete input",HASH,1,"orders","public.orders","whole-model-v2");
            case "permission" -> target=doc("target",91L,92L,"complete input with allowSendToLlm=false",HASH,1,"orders","public.orders","whole-model-v2");
            case "source" -> target=doc("target",91L,92L,source.semanticText(),"b".repeat(64),1,"orders","public.orders","whole-model-v2");
            case "datasource" -> target=doc("target",91L,92L,source.semanticText(),HASH,2,"orders","public.orders","whole-model-v2");
            case "model" -> target=doc("target",91L,92L,source.semanticText(),HASH,1,"other-model","public.orders","whole-model-v2");
            case "physical" -> target=doc("target",91L,92L,source.semanticText(),HASH,1,"orders","private.orders","whole-model-v2");
            case "builder" -> target=doc("target",91L,92L,source.semanticText(),HASH,1,"orders","public.orders","whole-model-v3");
            case "project" -> target=doc("target",94L,94L,source.semanticText(),HASH,1,"orders","public.orders","whole-model-v2");
            case "unpublished" -> jdbc.update("UPDATE qw_project_version SET status='DRAFT' WHERE id=91");
            case "revision" -> encoder.revision="2".repeat(40);
            case "unknown-profile" -> encoder.knownProfile=false;
            case "corrupt-vector" -> jdbc.update("UPDATE qw_semantic_retrieval_embedding SET embedding='[0.5,0.25]' WHERE document_id='source'");
            default -> throw new AssertionError(change);
        }
        documents.upsert(target);assertTrue(index.indexDocuments(List.of(target)).vectorAvailable());
        assertEquals(2,encoder.calls.get(),change);assertNull(jdbc.queryForMap(
            "SELECT reuse_source FROM qw_semantic_retrieval_embedding WHERE document_id='target'").get("reuse_source"),change);
    }
    @Test void sourceChangedAfterReadIsRecheckedAtomicallyBeforeCopy() {
        publishSource();var target=target();documents.upsert(target);
        var guarded=new JdbcTemplate(dataSource){
            @Override public int update(String sql,Object... args) {
                if(sql.contains("certified_source AS MATERIALIZED"))
                    jdbc.update("UPDATE qw_semantic_retrieval_document SET physical_table='changed.orders' WHERE id='source'");
                return super.update(sql,args);
            }
        };
        assertTrue(index(guarded).indexDocuments(List.of(target)).vectorAvailable());assertEquals(2,encoder.calls.get());
        assertNull(jdbc.queryForMap("SELECT reuse_source FROM qw_semantic_retrieval_embedding WHERE document_id='target'").get("reuse_source"));
    }
    @Test void sourceVectorChangedAfterCertificationIsRecheckedBeforeCopy() {
        publishSource();var target=target();documents.upsert(target);
        var guarded=new JdbcTemplate(dataSource){
            @Override public int update(String sql,Object... args) {
                if(sql.contains("certified_source AS MATERIALIZED"))
                    jdbc.update("UPDATE qw_semantic_retrieval_embedding SET embedding='[0.5,0.25]' WHERE document_id='source'");
                return super.update(sql,args);
            }
        };
        assertTrue(index(guarded).indexDocuments(List.of(target)).vectorAvailable());assertEquals(2,encoder.calls.get());
        assertNull(jdbc.queryForMap("SELECT reuse_source FROM qw_semantic_retrieval_embedding WHERE document_id='target'").get("reuse_source"));
    }
    @Test void changedTargetPhysicalIdentityCannotReceiveEarlierVector() {
        publishSource();var target=target();documents.upsert(target);
        var guarded=new JdbcTemplate(dataSource){
            @Override public int update(String sql,Object... args) {
                if(sql.contains("certified_source AS MATERIALIZED"))
                    jdbc.update("UPDATE qw_semantic_retrieval_document SET physical_table='changed.orders' WHERE id='target'");
                return super.update(sql,args);
            }
        };
        assertFalse(new SemanticEmbeddingReuseRepository(guarded).reuse(target,index.reindexIdentity(),encoder.profile()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE document_id='target'",Integer.class));
        assertEquals(1,encoder.calls.get());
    }
    @Test void registeredModelAndDimensionChangesStillRequireExplicitReindexBeforeCache() {
        publishSource();var target=target();documents.upsert(target);
        var original=jdbc.queryForMap("SELECT embedding_model,embedding_version,dimension FROM qw_embedding_index_registry");
        for(var change:List.of("model","dimension")) {
            EmbeddingModelIdentityProvider identity=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
                "fixture:"+(change.equals("model")?"other-model":MODEL),EmbeddingEncodingIdentity.attributes(
                    "fixture",change.equals("model")?"other-model":MODEL,"http://fixture","/v1/embeddings",
                    change.equals("dimension")?3:2)));
            var changed=new SemanticRetrievalIndexService(jdbc,Optional.of(encoder),Optional.of(identity));
            assertThrows(SemanticRetrievalIndexService.EmbeddingReindexRequiredException.class,
                ()->changed.indexDocuments(List.of(target)),change);
        }
        assertEquals(original,jdbc.queryForMap("SELECT embedding_model,embedding_version,dimension FROM qw_embedding_index_registry"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE document_id='target'",Integer.class));
        assertEquals(1,encoder.calls.get());
    }
    @Test void duplicateNativeWorkersClaimOneTargetAndCreateOneStableReusePointer() throws Exception {
        publishSource();var queue=new SemanticIndexWorkRepository(jdbc);
        var worker=new SemanticIndexWorker(queue,documents,index,Runnable::run);
        assertTrue(worker.processOne()); // The original source's normal obligation, already encoded.
        documents.upsert(target());var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=java.util.stream.IntStream.range(0,8).mapToObj(i->(Callable<Boolean>)worker::processOne).toList();
            int successes=0;for(var future:pool.invokeAll(tasks,10,TimeUnit.SECONDS))if(future.get())successes++;
            assertEquals(1,successes);assertEquals(1,encoder.calls.get());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE document_id='target' AND reuse_source IS NOT NULL",Integer.class));
            assertEquals("DONE",jdbc.queryForObject("SELECT status FROM qw_semantic_document_index_work WHERE document_id='target'",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM qw_semantic_document_index_work WHERE document_id='target'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    static class FixtureEncoder implements CertifiedEmbeddingModel {
        final AtomicInteger calls=new AtomicInteger();boolean certify=true,knownProfile=true;String revision=REVISION;
        AtomicEmbeddingIdentity.Profile profile(){return AtomicEmbeddingIdentity.profile(AtomicEmbeddingFixture.profile(MODEL,2,revision),MODEL,2);}
        @Override public Optional<AtomicEmbeddingIdentity.Profile> currentDocumentProfile(){return knownProfile?Optional.of(profile()):Optional.empty();}
        @Override public float[] embed(Document document){return EmbeddingModelSupport.embedTexts(this,List.of(document.getText())).get(0);}
        @Override public EmbeddingResponse call(EmbeddingRequest request){
            calls.incrementAndGet();var outputs=new ArrayList<Embedding>();var vectors=new ArrayList<float[]>();
            for(int i=0;i<request.getInstructions().size();i++){var vector=new float[]{0.25f,0.5f};vectors.add(vector);outputs.add(new Embedding(vector,i));}
            Map<String,Object> metadata=Map.of();
            if(certify){var identity=AtomicEmbeddingIdentity.response(AtomicEmbeddingFixture.response(
                AtomicEmbeddingFixture.profile(MODEL,2,revision),request.getInstructions(),vectors),request.getInstructions(),vectors,MODEL,2);
                metadata=Map.of(AtomicEmbeddingIdentity.METADATA_KEY,identity);}
            return new EmbeddingResponse(outputs,new EmbeddingResponseMetadata(MODEL,null,metadata));
        }
    }
}
