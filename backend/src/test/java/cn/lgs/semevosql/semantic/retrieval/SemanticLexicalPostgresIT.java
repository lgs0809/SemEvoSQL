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
import static org.mockito.Mockito.*;
import static cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Actual FTS/tsvector/GIN and scope filtering in disposable PostgreSQL, without model calls. */
@Testcontainers
class SemanticLexicalPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static final AtomicLong PROJECTS=new AtomicLong();
    static final String HASH="e".repeat(64);
    SemanticRetrievalDocumentRepository repository;
    long project;
    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);
    }
    @BeforeEach void setup(){project=PROJECTS.incrementAndGet();repository=new SemanticRetrievalDocumentRepository(jdbc);}
    SemanticRetrievalDocument doc(String id,long p,long version,String hash,String model,int datasource,String text){
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (?,?,?,'synthetic','ACTIVE','test') ON CONFLICT DO NOTHING",p,"fts-"+p,"FTS "+p);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (?,?,?,?,'DRAFT','COMPLETED',1,0,?) ON CONFLICT DO NOTHING",version,p,version,"1.0."+version,version);
        return new SemanticRetrievalDocument(id,p,version,hash,MODEL,"MODEL","model:"+model,datasource,model,model,
            text,text,HASH,HASH,"CATALOG","1","CATALOG_DESCRIPTION");
    }
    String fixture(String model,int datasource,String text){
        String id=UUID.randomUUID().toString();repository.upsert(doc(id,project,project,HASH,model,datasource,text));return id;
    }
    Map<String,Double> search(String query,SemanticRetrievalScope scope,int limit){
        return repository.lexicalScores(project,project,HASH,query,scope,limit);
    }
    @ParameterizedTest @ValueSource(strings={"支付金额","paid_amount","Paid_AMOUNT","支付 paid_amount","ＰＡＩＤ＿ＡＭＯＵＮＴ"})
    void chineseEnglishMixedAndNormalizedIdentifiersMatch(String query){
        String id=fixture("orders",1,"已支付订单 支付金额 paid_amount");
        fixture("customers",1,"客户姓名 customer_name");
        assertEquals(Set.of(id),search(query,SemanticRetrievalScope.all(),10).keySet());
    }
    @ParameterizedTest @ValueSource(strings={""," ? ! _ ","🙂", "   "})
    void noEffectiveTermsDoesNotReturnAllRecords(String query){
        fixture("orders",1,"paid amount 支付金额");assertTrue(search(query,SemanticRetrievalScope.all(),10).isEmpty());
    }
    @Test void projectVersionHashAndHardScopeAreAppliedBeforeLimit(){
        String a=fixture("orders",1,"paid amount 支付金额");
        fixture("refunds",2,"paid amount 支付金额 支付金额 支付金额");
        repository.upsert(doc(UUID.randomUUID().toString(),project+10000,project+10000,HASH,"foreign",1,"paid amount 支付金额"));
        repository.upsert(doc(UUID.randomUUID().toString(),project,project+20000,HASH,"old_version",1,"paid amount 支付金额"));
        repository.upsert(doc(UUID.randomUUID().toString(),project,project,"f".repeat(64),"old_hash",1,"paid amount 支付金额"));
        var scope=new SemanticRetrievalScope(1,Set.of("orders"),Set.of(MODEL),Set.of("model:orders"));
        assertEquals(Set.of(a),search("paid amount 支付金额",scope,1).keySet());
        assertEquals(List.of(a),repository.findByIds(project,project,HASH,scope,List.of(a,"foreign")).stream().map(SemanticRetrievalDocument::id).toList());
        assertTrue(search("paid",new SemanticRetrievalScope(null,Set.of(),Set.of(METRIC),Set.of()),10).isEmpty());
    }
    @Test void generatedVectorTracksTextChangesWithoutBackgroundLag(){
        String id=fixture("orders",1,"alpha");assertEquals(Set.of(id),search("alpha",SemanticRetrievalScope.all(),10).keySet());
        repository.upsert(doc(id,project,project,HASH,"orders",1,"beta"));
        assertTrue(search("alpha",SemanticRetrievalScope.all(),10).isEmpty());
        assertEquals(Set.of(id),search("beta",SemanticRetrievalScope.all(),10).keySet());
    }
    @Test void equalRanksHaveStableIdsAndOnlySelectedTextsAreRead(){
        var ids=new TreeSet<String>();for(int n=0;n<30;n++)ids.add(fixture("orders"+n,1,"paid amount"));
        var hits=search("paid",SemanticRetrievalScope.all(),3);
        assertEquals(ids.stream().limit(3).toList(),new ArrayList<>(hits.keySet()));
        assertEquals(3,repository.findByIds(project,project,HASH,SemanticRetrievalScope.all(),hits.keySet()).size());
    }
    @Test void oneChannelUsesOrTermsAndDoesNotRequireEveryWord(){
        String a=fixture("orders",1,"paid");String b=fixture("refunds",1,"refund");
        assertEquals(Set.of(a,b),search("paid refund unknown",SemanticRetrievalScope.all(),10).keySet());
    }
    @Test void unicodeSupplementaryHanIsTokenizedAndPunctuationNeverBecomesTsquerySyntax(){
        String id=fixture("unicode",1,"𠀀一订单");
        assertEquals(Set.of(id),search("𠀀一",SemanticRetrievalScope.all(),10).keySet());
        assertTrue(search("' | ! : ) (",SemanticRetrievalScope.all(),10).isEmpty());
    }
    @Test void migrationCreatesGeneratedVectorAndGinIndex(){
        assertEquals("s",jdbc.queryForObject("SELECT attgenerated::text FROM pg_attribute WHERE attrelid='qw_semantic_retrieval_document'::regclass AND attname='lexical_search_vector'",String.class));
        assertTrue(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='idx_qw_semantic_document_fts'",String.class).contains("USING gin"));
    }

    @Test void unrelatedExpansionStillLoadsOnlyTheHitAndRetainsActualExplain() {
        String id=fixture("target",1,"needleFTS 支付金额");
        jdbc.update("""
            INSERT INTO qw_semantic_retrieval_document(id,project_id,project_version_id,catalog_hash,
              document_type,asset_type,asset_key,datasource_id,model_code,physical_table,lexical_text,
              semantic_text,source_fingerprint,content_hash,generation_status)
            SELECT md5(?||n),?,?,?,'MODEL','MODEL','model:noise'||n,1,'noise'||n,'noise'||n,
              'unrelated customer address','synthetic unrelated full text',?,?,'CATALOG_DESCRIPTION'
            FROM generate_series(1,5000) n
            """, "fts-scale-"+project,project,project,HASH,HASH,HASH);
        jdbc.execute("ANALYZE qw_semantic_retrieval_document");
        var traced=spy(jdbc);
        var observed=new SemanticRetrievalDocumentRepository(traced);
        var hits=observed.lexicalScores(project,project,HASH,"needleFTS",SemanticRetrievalScope.all(),3);
        assertEquals(Set.of(id),hits.keySet());
        assertEquals(1,observed.findByIds(project,project,HASH,SemanticRetrievalScope.all(),hits.keySet()).size());
        var query=org.mockito.ArgumentCaptor.forClass(String.class);
        var params=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(traced,times(2)).queryForList(query.capture(),params.capture());
        String explain=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+query.getAllValues().get(0),
            String.class,params.getAllValues().get(0));
        System.out.println("FTS_SCALE_5001_ACTUAL_PLAN "+explain);
        assertTrue(explain.contains("lexical_search_vector"));
    }
}
