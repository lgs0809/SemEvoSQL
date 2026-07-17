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

import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.service.aimodelconfig.ModelConfigDataService;
import cn.lgs.semevosql.semantic.retrieval.EmbeddingIndexModelProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Two genuine question texts; asynchronous vector maintenance and independent SQL-ranked channels. */
@Service
public class QueryCaseRetrievalIndexService {
    private static final Logger log = LoggerFactory.getLogger(QueryCaseRetrievalIndexService.class);
    private final JdbcTemplate jdbc;
    private final QueryCaseQuestionIndexRepository questions;
    private final Optional<EmbeddingModel> embeddingModel;
    private final Optional<ModelConfigDataService> configService;
    private final CanonicalJson json = new CanonicalJson();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final Executor worker;
    private final Optional<EmbeddingIndexModelProvider> indexModelProvider;
    @Value("${semevosql.query-case.channel-limit:10}") private int channelLimit = 10;
    @Value("${semevosql.query-case.shortlist-limit:5}") private int shortlistLimit = 5;

    public QueryCaseRetrievalIndexService(JdbcTemplate jdbc, Optional<EmbeddingModel> embeddingModel,
            Optional<ModelConfigDataService> configService, Optional<EmbeddingIndexModelProvider> indexModelProvider,
            @Qualifier("semEvoSQLCaseIndexExecutor") Executor worker) {
        this.jdbc=jdbc; this.embeddingModel=embeddingModel; this.configService=configService;
        this.questions=new QueryCaseQuestionIndexRepository(jdbc);
        this.indexModelProvider=indexModelProvider; this.worker=worker;
    }

    // Source texts only: an absent legacy original is NOT invented from the rewritten question.
    static final String SOURCE_TEXTS = """
        SELECT q.id, qw_query_case_source_hash(q) AS source_hash, text.question_type, text.question_text
        FROM qw_query_example q CROSS JOIN LATERAL (VALUES
          ('ORIGINAL_QUERY', COALESCE(NULLIF(q.quality_proof_json#>>'{payload,requestEvidence,originalQuery}',''),
              NULLIF(q.quality_proof_json#>>'{requestEvidence,originalQuery}',''),
              NULLIF(q.original_question,''))),
          ('REWRITTEN_QUERY', COALESCE(NULLIF(q.quality_proof_json#>>'{payload,requestEvidence,rootCanonicalQuery}',''),
              NULLIF(q.quality_proof_json#>>'{requestEvidence,rootCanonicalQuery}',''),
              NULLIF(q.normalized_question,'')))
        ) AS text(question_type,question_text)
        WHERE q.status='APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
          AND text.question_text IS NOT NULL
        """;

    @Transactional
    public void indexApprovedCase(String caseId, String ignoredNormalizedQuestion) {
        for (var row : jdbc.queryForList(SOURCE_TEXTS + " AND q.id=?", caseId)) upsertText(row);
        // Also remove a projection if a genuine source text was withdrawn.
        jdbc.update("DELETE FROM qw_query_case_question_index d WHERE d.query_example_id=? AND NOT EXISTS ("
            + SOURCE_TEXTS + " AND q.id=d.query_example_id AND text.question_type=d.question_type)", caseId);
    }

    private void upsertText(Map<String,Object> row) {
        String text = row.get("question_text").toString();
        String hash = json.hash(text);
        jdbc.update("""
            INSERT INTO qw_query_case_question_index AS old
                (query_example_id,question_type,question_text,text_hash,source_hash,tokenizer_version,tokenized_text)
            SELECT ?,?,?,?,?,?,? FROM qw_query_example q
                WHERE q.id=? AND qw_query_case_source_hash(q)=? AND q.status='APPROVED'
            ON CONFLICT (query_example_id,question_type) DO UPDATE SET
                question_text=EXCLUDED.question_text,text_hash=EXCLUDED.text_hash,source_hash=EXCLUDED.source_hash,
                tokenizer_version=EXCLUDED.tokenizer_version,tokenized_text=EXCLUDED.tokenized_text,
                generation=nextval('qw_query_case_question_generation'),
                embedding=CASE WHEN old.text_hash=EXCLUDED.text_hash THEN old.embedding ELSE NULL END,
                embedding_claim_token=NULL,embedding_lease_until=NULL,retry_count=0,next_retry_at=CURRENT_TIMESTAMP,
                last_error=NULL,update_time=CURRENT_TIMESTAMP
            WHERE old.source_hash<>EXCLUDED.source_hash OR old.text_hash<>EXCLUDED.text_hash
                OR old.tokenizer_version<>EXCLUDED.tokenizer_version
            """, row.get("id"), row.get("question_type"), text, hash, row.get("source_hash"),
            QueryCaseQuestionIndexRepository.TOKENIZER_VERSION, String.join(" ", QueryCaseTextFeatures.tokens(text)),
            row.get("id"), row.get("source_hash"));
    }

    @Transactional
    public void remove(String caseId) {
        jdbc.update("DELETE FROM qw_query_case_question_index WHERE query_example_id=?",caseId);
        jdbc.update("DELETE FROM qw_query_case_term WHERE query_example_id=?",caseId);
        jdbc.update("DELETE FROM qw_query_case_embedding WHERE query_example_id=?",caseId);
    }

    @Scheduled(fixedDelayString="${semevosql.query-case.index-scan-ms:30000}", initialDelayString="${semevosql.query-case.index-scan-ms:30000}")
    public void scan() {
        if (!scanning.compareAndSet(false,true)) return;
        try { worker.execute(() -> {
            try { synchronizeTexts(null,100); buildPendingVectors(null,20); }
            catch (RuntimeException error) { log.warn("Case index maintenance deferred: {}", error.getClass().getSimpleName()); }
            finally { scanning.set(false); }
        }); } catch (RejectedExecutionException error) {
            scanning.set(false);
            log.debug("Case index worker busy; persisted work will be retried by the next scan");
        }
    }

    int synchronizeTexts(Long projectId, int limit) {
        String filter=projectId==null?"":" AND q.project_id=?";
        List<Object> args=new ArrayList<>(); if(projectId!=null) args.add(projectId); args.add(limit);
        var rows=jdbc.queryForList("SELECT DISTINCT source.id FROM ("+SOURCE_TEXTS+filter+"""
            ) source LEFT JOIN qw_query_case_question_index d
              ON d.query_example_id=source.id AND d.question_type=source.question_type
            WHERE d.query_example_id IS NULL OR d.source_hash<>source.source_hash OR d.tokenizer_version<>'%s'
            ORDER BY source.id LIMIT ?
            """.formatted(QueryCaseQuestionIndexRepository.TOKENIZER_VERSION),args.toArray());
        rows.forEach(row -> indexApprovedCase(row.get("id").toString(),null));
        return rows.size();
    }

    /** Explicit maintenance reuses unchanged vectors; does not drop data or indexes. */
    public synchronized int reindexApprovedCases() { return reindexApprovedCases(null); }
    public synchronized int reindexApprovedCases(Long projectId) {
        while(synchronizeTexts(projectId,100)>0) { /* bounded SQL pages */ }
        int total=0, batch;
        do { batch=buildPendingVectors(projectId,20); total+=batch; } while(batch>0);
        return total;
    }

    int buildPendingVectors(Long projectId, int limit) {
        if(embeddingModel.isEmpty()) return 0;
        Identity identity=identity();
        String token=UUID.randomUUID().toString();
        String filter=projectId==null?"":" AND q.project_id=?";
        List<Object> args=new ArrayList<>(List.of(identity.model(),identity.version()));
        if(projectId!=null) args.add(projectId);
        args.add(Math.max(1,Math.min(4,limit)));args.add(token);
        var claimed=jdbc.queryForList("""
            WITH pending AS (
              SELECT d.query_example_id,d.question_type FROM qw_query_case_question_index d
              JOIN qw_query_example q ON q.id=d.query_example_id
              WHERE q.status='APPROVED' AND d.source_hash=qw_query_case_source_hash(q)
                AND (d.embedding IS NULL OR d.embedding_model<>? OR d.embedding_version<>?)
                AND d.next_retry_at<=CURRENT_TIMESTAMP
                AND (d.embedding_lease_until IS NULL OR d.embedding_lease_until<CURRENT_TIMESTAMP)
                %s ORDER BY d.next_retry_at,d.query_example_id,d.question_type LIMIT ? FOR UPDATE OF d SKIP LOCKED
            ) UPDATE qw_query_case_question_index d SET embedding_claim_token=?,
                embedding_lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes'
              FROM pending p WHERE d.query_example_id=p.query_example_id AND d.question_type=p.question_type
              RETURNING d.*
            """.formatted(filter), args.toArray());
        Map<String,float[]> reused=new HashMap<>();
        int success=0;
        for(var doc:claimed) {
            try {
                String hash=doc.get("text_hash").toString();
                float[] vector=reused.get(hash);
                if(vector==null) {
                    var backgroundModel=indexModelProvider.map(EmbeddingIndexModelProvider::currentIndexEmbeddingModel)
                        .orElseGet(embeddingModel::orElseThrow);
                    var vectors=EmbeddingModelSupport.embedTexts(backgroundModel,List.of(doc.get("question_text").toString()));
                    if(vectors.size()!=1) throw new IllegalStateException("Embedding cardinality mismatch");
                    vector=vectors.get(0);
                    QueryCaseQuestionIndexRepository.vectorLiteral(vector);
                    if(identity.dimension()!=null && identity.dimension()!=vector.length)
                        throw new IllegalStateException("Configured embedding dimension mismatch");
                    reused.put(hash,vector);
                }
                if(!identity.equals(identity())) throw new IllegalStateException("Embedding identity changed during build");
                success+=publishVector(doc,token,identity,vector);
            } catch(RuntimeException error) {
                jdbc.update("""
                    UPDATE qw_query_case_question_index SET embedding_claim_token=NULL,embedding_lease_until=NULL,
                      retry_count=retry_count+1,next_retry_at=CURRENT_TIMESTAMP+
                        make_interval(secs=>LEAST(3600,30*power(2,LEAST(retry_count,7)))::integer),last_error=?
                    WHERE query_example_id=? AND question_type=? AND generation=? AND embedding_claim_token=?
                    """,error.getClass().getSimpleName(),doc.get("query_example_id"),doc.get("question_type"),doc.get("generation"),token);
                log.warn("Case vector build deferred: {}",error.getClass().getSimpleName());
            }
        }
        return success;
    }

    int publishVector(Map<String,Object> doc,String token,Identity identity,float[] vector) {
        return jdbc.update("""
            UPDATE qw_query_case_question_index d SET embedding=?::vector,embedding_model=?,embedding_version=?,
                embedding_dimension=?,embedding_claim_token=NULL,embedding_lease_until=NULL,retry_count=0,last_error=NULL,
                update_time=CURRENT_TIMESTAMP
            WHERE query_example_id=? AND question_type=? AND generation=? AND embedding_claim_token=?
              AND embedding_lease_until>CURRENT_TIMESTAMP
              AND EXISTS(SELECT 1 FROM qw_query_example q WHERE q.id=d.query_example_id
                AND q.status='APPROVED' AND qw_query_case_source_hash(q)=d.source_hash)
            """,QueryCaseQuestionIndexRepository.vectorLiteral(vector),identity.model(),identity.version(),vector.length,
            doc.get("query_example_id"),doc.get("question_type"),doc.get("generation"),token);
    }

    public List<RankedCase> search(Long project,Long version,String catalog,String context,String principal,String question) {
        if(project==null||version==null||catalog==null||question==null||QueryCaseTextFeatures.queryTerms(question,32).isEmpty()) return List.of();
        var scope=new QueryCaseQuestionIndexRepository.Scope(project,version,catalog,context,principal);
        List<QueryCaseQuestionIndexRepository.Hit> lexical=List.of(),vector=List.of();
        try { lexical=questions.lexical(scope,question,channelLimit); }
        catch(RuntimeException error) { log.warn("Case FTS unavailable: {}",error.getClass().getSimpleName()); }
        if(embeddingModel.isPresent()) {
            try {
                Identity identity=identity();
                var vectors=EmbeddingModelSupport.embedTexts(embeddingModel.orElseThrow(),List.of(question));
                if(vectors.size()!=1) throw new IllegalStateException("Query embedding cardinality mismatch");
                if(identity.dimension()!=null && identity.dimension()!=vectors.get(0).length)
                    throw new IllegalStateException("Query embedding dimension mismatch");
                if(!identity.equals(identity())) throw new IllegalStateException("Query embedding identity changed");
                vector=questions.vector(scope,vectors.get(0),identity.model(),identity.version(),channelLimit);
            } catch(RuntimeException error) { log.warn("Case vector unavailable; FTS retained: {}",error.getClass().getSimpleName()); }
        }
        var result=fuse(lexical,vector).stream().limit(Math.max(1,Math.min(20,shortlistLimit)))
            .filter(r -> r.matches().stream().allMatch(questions::current)).toList();
        log.info("Case recall channels fts={} vector={} shortlist={}",lexical.size(),vector.size(),result.size());
        return result;
    }

    static List<RankedCase> fuse(List<QueryCaseQuestionIndexRepository.Hit> lexical,List<QueryCaseQuestionIndexRepository.Hit> vector) {
        Map<String,Double> scores=new HashMap<>();
        Map<String,List<QueryCaseQuestionIndexRepository.Hit>> matches=new HashMap<>();
        for(var channel:List.of(lexical,vector)) {
            int rank=0;Set<String> seen=new HashSet<>();
            for(var hit:channel) {
                if(!seen.add(hit.caseId())) continue;
                scores.merge(hit.caseId(),1d/(60+(++rank)),Double::sum);
                matches.computeIfAbsent(hit.caseId(),ignored->new ArrayList<>()).add(hit);
            }
        }
        return scores.entrySet().stream().map(e -> new RankedCase(e.getKey(),e.getValue(),
            matches.get(e.getKey()).stream().mapToDouble(QueryCaseQuestionIndexRepository.Hit::confidence).max().orElse(0),
            List.copyOf(matches.get(e.getKey())))).sorted(Comparator.comparingDouble(RankedCase::score).reversed()
                .thenComparing(RankedCase::caseId)).toList();
    }

    Identity identity() {
        ModelConfigDTO config = configService.map(s -> s.getActiveConfigByType(ModelType.EMBEDDING)).orElse(null);
        if (config != null) {
            var attributes = cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity.attributes(
                config.getProvider(), config.getModelName(), config.getBaseUrl(), config.getEmbeddingsPath(), config.getEmbeddingDimensions());
            var identity = cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity.configured(
                Objects.toString(config.getProvider(), "") + ":" + Objects.toString(config.getModelName(), ""), attributes);
            return new Identity(identity.model(), identity.version(), identity.dimensions());
        }
        String implementation = embeddingModel.map(m -> m.getClass().getName()).orElse("none");
        return new Identity(implementation, json.hash(Map.of("implementation", implementation)), null);
    }

    public QueryCaseIndexReadiness readiness(Long projectId) {
        Identity identity=identity();
        List<Object> args=new ArrayList<>(List.of(identity.model(),identity.version()));
        if(projectId!=null) args.add(projectId);
        var row=jdbc.queryForMap("""
            SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE
              EXISTS (SELECT 1 FROM qw_query_case_question_index d WHERE d.query_example_id=outer_case.id)
              AND NOT EXISTS (SELECT 1 FROM (%s) source
                LEFT JOIN qw_query_case_question_index d ON d.query_example_id=source.id AND d.question_type=source.question_type
                WHERE source.id=outer_case.id AND (d.query_example_id IS NULL OR d.source_hash<>source.source_hash
                  OR d.embedding IS NULL OR d.embedding_model<>? OR d.embedding_version<>?))) AS ready
            FROM qw_query_example outer_case WHERE outer_case.status='APPROVED' AND outer_case.rebind_status IN ('VALID','REBOUND')
            """.formatted(SOURCE_TEXTS)+(projectId==null?"":" AND outer_case.project_id=?"),args.toArray());
        long total=((Number)row.get("total")).longValue(),ready=((Number)row.get("ready")).longValue();
        return new QueryCaseIndexReadiness(ready==total?"INDEX_READY":ready==0?"LEXICAL_ONLY":"PARTIAL",total,ready,
            identity.dimension(),ready==total?"双问题索引已就绪":"全文召回可用，后台正在补充当前模型的双问题向量");
    }

    record Identity(String model,String version,Integer dimension) { }
    public record RankedCase(String caseId,double score,double confidence,List<QueryCaseQuestionIndexRepository.Hit> matches) { }
    public record QueryCaseIndexReadiness(String status,long approvedCaseCount,long vectorCount,Integer dimension,String detail) { }
}
