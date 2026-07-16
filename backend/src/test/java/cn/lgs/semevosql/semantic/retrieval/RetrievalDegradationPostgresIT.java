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
import static cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType.MODEL;
import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.properties.ModelClientProperties;
import cn.lgs.semevosql.service.aimodelconfig.DynamicModelFactory;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Real migrated PostgreSQL FTS/pgvector + production HTTP clients; model outputs are synthetic fault fixtures. */
@Testcontainers
class RetrievalDegradationPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static final String HASH="a".repeat(64);
    @BeforeAll static void migrate(){
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();jdbc=new JdbcTemplate(ds);
    }
    @BeforeEach void isolateScenario() {
        // This container belongs only to this test class. Each scenario has its own encoder contract.
        jdbc.update("DELETE FROM qw_semantic_retrieval_document");
        jdbc.update("DELETE FROM qw_embedding_index_registry WHERE index_scope='SEMANTIC_CATALOG'");
    }
    SemanticRetrievalDocument doc(String id,long project,long version,String hash,String model,int datasource){
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (?,?,?,'synthetic','ACTIVE','test') ON CONFLICT DO NOTHING",project,"deadline-"+project,"Deadline "+project);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (?,?,?,?,'DRAFT','COMPLETED',1,0,?) ON CONFLICT DO NOTHING",version,project,version,"1.0."+version,version);
        return new SemanticRetrievalDocument(id,project,version,hash,MODEL,"MODEL","model:"+model,datasource,model,model,
            "支付金额 paid amount","支付金额 paid amount",HASH,HASH,"CATALOG","1","CATALOG_DESCRIPTION");
    }
    ModelConfigDTO config(int port,boolean embedding){
        var config=new ModelConfigDTO();config.setBaseUrl("http://127.0.0.1:"+port);config.setRequestTimeoutSeconds(60);
        config.setModelName(embedding?"Qwen/Qwen3-VL-Embedding-2B":"Qwen/Qwen3-VL-Reranker-2B");config.setEmbeddingDimensions(2);return config;
    }
    @Test void actualNetworkDeadlinesPreserveAuthorizedFallbackThenRecoverWithoutReindexing(){
        var fault=new AtomicReference<>("NONE");var embeddings=new AtomicInteger();var reranks=new AtomicInteger();
        var server=HttpServer.create().host("127.0.0.1").port(0).handle((request,response)->{
            boolean embedding=request.uri().equals("/v1/embeddings");
            (embedding?embeddings:reranks).incrementAndGet();
            if(fault.get().equals("BOTH")||fault.get().equals(embedding?"EMBEDDING":"RERANK"))return request.receive().then(Mono.never());
            return request.receive().aggregate().asString().flatMap(body->{
                try {
                    var input=JsonUtil.getObjectMapper().readTree(body);var rows=new ArrayList<Map<String,Object>>();
                    int count=input.path(embedding?"input":"documents").size();
                    for(int n=0;n<count;n++)rows.add(embedding?Map.of("index",n,"embedding",List.of(0.25,0.5)):
                        Map.of("index",n,"relevance_score",1d-n*0.01));
                    String payload=JsonUtil.getObjectMapper().writeValueAsString(Map.of(embedding?"data":"results",rows));
                    return response.header("Content-Type","application/json").sendString(Mono.just(payload)).then();
                }catch(Exception failure){return Mono.error(failure);}
            });
        }).bindNow();
        try {
            var factory=new DynamicModelFactory(new ModelClientProperties());
            var embedding=factory.createEmbeddingModel(config(server.port(),true));var rerank=factory.createRerankModel(config(server.port(),false));
            EmbeddingModelIdentityProvider identity=()->Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
                "synthetic-deadline-vector",Map.of("dimension",2)));
            var index=new SemanticRetrievalIndexService(jdbc,Optional.of(embedding),Optional.of(identity));
            var repository=new SemanticRetrievalDocumentRepository(jdbc);
            var documents=List.of(doc("allowed",1,1,HASH,"orders",1),doc("foreign-project",2,2,HASH,"orders",1),
                doc("foreign-version",1,3,HASH,"orders",1),doc("foreign-hash",1,1,"b".repeat(64),"stale_orders",1),
                doc("foreign-source",1,1,HASH,"payments",2),doc("foreign-model",1,1,HASH,"refunds",1));
            documents.forEach(repository::upsert);index.indexDocuments(documents);index.assertReady(1L,1L,HASH);
            var scope=new SemanticRetrievalScope(1,Set.of("orders"),Set.of(MODEL),Set.of("model:orders"));
            var service=new SemanticHybridRetrievalService(repository,index,()->rerank);
            for(String mode:List.of("EMBEDDING","RERANK","BOTH","NONE")){
                fault.set(mode);embeddings.set(0);reranks.set(0);long started=System.nanoTime();
                var hits=service.retrieve(1L,1L,HASH,"支付金额 paid amount",scope,5);
                long elapsed=(System.nanoTime()-started)/1_000_000;
                assertEquals(1,hits.size());assertEquals("model:orders",hits.get(0).assetKey());
                var expected=new HashSet<>(Set.of("FTS","RRF"));
                if(!Set.of("EMBEDDING","BOTH").contains(mode))expected.add("VECTOR");
                if(!Set.of("RERANK","BOTH").contains(mode))expected.add("RERANK");
                assertEquals(expected,hits.get(0).channelRanks().keySet());assertEquals(1,embeddings.get());assertEquals(1,reranks.get());
                assertTrue(elapsed<(mode.equals("BOTH")?12500:7500));
                assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding",Integer.class));
                System.out.println("REAL_PG_RETRIEVAL_DEGRADATION fault="+mode+" elapsedMs="+elapsed+" channels="+expected+
                    " authorizedHits=1 embeddingHttpRequests=1 rerankHttpRequests=1 vectorsUnchanged=6");
            }
        }finally {server.disposeNow();}
    }

    @Test void staleEncoderCannotReplaceANewerModelDocumentVector() {
        var repository = new SemanticRetrievalDocumentRepository(jdbc);
        var original = doc("late-model-document", 91, 91, HASH, "late_orders", 1);
        repository.upsert(original);
        var embedding = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
        String clientType = new DynamicModelFactory(new ModelClientProperties())
            .createEmbeddingModel(config(1, true)).getClass().getName();
        EmbeddingModelIdentityProvider identity = () -> Optional.of(new EmbeddingModelIdentityProvider.EmbeddingModelIdentity(
            "synthetic-deadline-vector", Map.of("dimension", 2, "implementation", clientType)));
        var index = new SemanticRetrievalIndexService(jdbc, Optional.of(embedding), Optional.of(identity));
        var changedHash = "c".repeat(64);
        org.mockito.Mockito.when(embedding.call(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            // The source revision changes while an older encoding call is still outstanding.
            jdbc.update("UPDATE qw_semantic_retrieval_document SET content_hash=?,source_fingerprint=?,semantic_text=? WHERE id=?",
                changedHash, changedHash, "a corrected complete model", original.id());
            return new org.springframework.ai.embedding.EmbeddingResponse(List.of(
                new org.springframework.ai.embedding.Embedding(new float[]{0.2f, 0.4f}, 0)));
        });
        try {
            assertEquals(0, index.indexDocuments(List.of(original)).indexedDocuments());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE document_id=?",
                Integer.class, original.id()));
            assertEquals(changedHash, repository.findExisting(91L,MODEL,"model:late_orders").orElseThrow().contentHash());
        } finally { jdbc.update("DELETE FROM qw_project WHERE id=91"); }
    }
}
