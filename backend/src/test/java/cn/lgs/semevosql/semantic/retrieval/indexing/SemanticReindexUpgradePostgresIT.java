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
import cn.lgs.semevosql.semantic.retrieval.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Upgrade populated V44 metadata through the same Flyway migration as the deployed application. */
@Testcontainers
class SemanticReindexUpgradePostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Test void populatedUpgradePreservesPreviousVectorsRegistryAndDocuments() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("44").load().migrate();
        var jdbc=new JdbcTemplate(ds);String hash="a".repeat(64);
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(91,'reindex-upgrade','Synthetic upgrade','test','ACTIVE','test')");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(91,91,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
        new SemanticRetrievalDocumentRepository(jdbc).upsert(new SemanticRetrievalDocument("existing",91L,91L,hash,
            SemanticRetrievalDocument.DocumentType.MODEL,"MODEL","model:orders",1,"orders","orders",
            "付款金额","完整模型",hash,hash,"CATALOG","whole-model-v2","CATALOG_DESCRIPTION"));
        jdbc.update("INSERT INTO qw_semantic_retrieval_embedding(document_id,embedding_model,embedding_version,content_hash,dimension,embedding) VALUES('existing','synthetic','old',?,2,'[0.5,0.25]'::vector)",hash);
        jdbc.update("INSERT INTO qw_embedding_index_registry(index_scope,embedding_model,embedding_version,dimension,status) VALUES('SEMANTIC_CATALOG','synthetic','old',2,'ACTIVE')");
        jdbc.execute("CREATE INDEX idx_qw_semantic_retrieval_embedding_hnsw ON qw_semantic_retrieval_embedding USING hnsw((embedding::vector(2)) vector_cosine_ops)");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("45").load().migrate();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_document",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_embedding WHERE embedding_version='old'",Integer.class));
        assertEquals("old",jdbc.queryForObject("SELECT embedding_version FROM qw_embedding_index_registry",String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('idx_qw_semantic_embedding_hnsw_2')::text",String.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('idx_qw_semantic_retrieval_embedding_hnsw')::text",String.class));
        assertEquals("NOT_REQUESTED",new SemanticReindexWorkRepository(jdbc).status().get("status"));
    }
}
