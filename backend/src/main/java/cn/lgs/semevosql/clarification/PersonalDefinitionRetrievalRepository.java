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

import cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Owner-filtered read projection; ranks are relevance signals, never semantic equivalence. */
@Repository
public class PersonalDefinitionRetrievalRepository {
    private final JdbcTemplate jdbc;
    public PersonalDefinitionRetrievalRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Hit(long preferenceId,int revision,String hash,String text,double score) {}
    public record Work(long preferenceId,int revision,String hash,String text,String token,int attempt) {}
    private static final String CURRENT="""
        JOIN qw_user_semantic_preference p ON p.id=d.preference_id AND p.current_revision=d.source_revision AND NOT p.archived
        JOIN qw_user_semantic_definition_revision r ON r.preference_id=d.preference_id AND r.revision=d.source_revision
          AND r.content_hash=d.source_content_hash
        """;
    private static final String OWNER="d.project_id=? AND d.principal_id=? AND p.project_id=d.project_id AND p.user_id=d.principal_id";
    public List<Hit> lexical(Long project,String principal,String query,int limit) {
        if(!valid(project,principal,query,limit))return List.of();
        return jdbc.query("""
            WITH terms AS (SELECT term FROM unnest(tsvector_to_array(to_tsvector('simple',qw_semantic_tokenize_v1(?)))) term
                ORDER BY char_length(term) DESC,term LIMIT 64),
            input AS (SELECT to_tsquery('simple',string_agg(quote_literal(term),' | ')) AS query FROM terms)
            SELECT d.preference_id,d.source_revision,d.source_content_hash,d.semantic_text,ts_rank(d.lexical_vector,input.query) AS score
            FROM qw_personal_definition_document d
            """+CURRENT+" CROSS JOIN input WHERE "+OWNER+" AND d.lexical_vector @@ input.query ORDER BY score DESC,d.preference_id LIMIT ?",
            (r,n)->new Hit(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getDouble(5)),query,project,principal,Math.min(20,limit));
    }
    public boolean hasVectors(Long project,String principal,EmbeddingEncodingIdentity identity) {
        if(project==null||principal==null||principal.isBlank()||identity==null)return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_personal_definition_document d "+CURRENT+
            " WHERE "+OWNER+" AND d.embedding IS NOT NULL AND d.embedding_model=? AND d.embedding_version=?)",
            Boolean.class,project,principal,identity.model(),identity.version()));
    }
    public List<Hit> vector(Long project,String principal,float[] vector,EmbeddingEncodingIdentity identity,int limit) {
        if(project==null||principal==null||principal.isBlank()||identity==null||limit<=0)return List.of();
        String serialized=vector(vector);if(identity.dimensions()!=null&&identity.dimensions()!=vector.length)return List.of();
        return jdbc.query("SELECT d.preference_id,d.source_revision,d.source_content_hash,d.semantic_text,1-(d.embedding <=> ?::vector) AS score "+
            "FROM qw_personal_definition_document d "+CURRENT+" WHERE "+OWNER+
            " AND d.embedding IS NOT NULL AND d.embedding_model=? AND d.embedding_version=? AND d.embedding_dimensions=? "+
            "ORDER BY d.embedding <=> ?::vector,d.preference_id LIMIT ?",
            (r,n)->new Hit(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getDouble(5)),
            serialized,project,principal,identity.model(),identity.version(),vector.length,serialized,Math.min(20,limit));
    }
    @Transactional
    public Optional<Work> claim(EmbeddingEncodingIdentity identity,Duration lease) {
        if(identity==null)return Optional.empty();String token=UUID.randomUUID().toString();
        return jdbc.query("""
            WITH due AS (SELECT d.preference_id FROM qw_personal_definition_document d
            """+CURRENT+"""
            WHERE ((d.task_state IN ('PENDING','RETRYABLE_FAILURE') AND d.next_attempt_at<=CURRENT_TIMESTAMP)
              OR (d.task_state='RUNNING' AND d.lease_until<CURRENT_TIMESTAMP)
              OR (d.task_state='DONE' AND (d.embedding_model IS DISTINCT FROM ? OR d.embedding_version IS DISTINCT FROM ?)))
            ORDER BY d.next_attempt_at,d.preference_id FOR UPDATE OF d SKIP LOCKED LIMIT 1)
            UPDATE qw_personal_definition_document d SET task_state='RUNNING',owner_token=?,attempt_count=attempt_count+1,
              lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),update_time=CURRENT_TIMESTAMP
            FROM due WHERE d.preference_id=due.preference_id
            RETURNING d.preference_id,d.source_revision,d.source_content_hash,d.semantic_text,d.attempt_count
            """,(r,n)->new Work(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),token,r.getInt(5)),
            identity.model(),identity.version(),token,lease.toMillis()).stream().findFirst();
    }
    @Transactional
    public boolean complete(Work work,EmbeddingEncodingIdentity identity,float[] value) {
        String serialized=vector(value);
        if(identity.dimensions()!=null&&identity.dimensions()!=value.length)throw new IllegalArgumentException("Embedding dimension differs from configured contract");
        return jdbc.update("""
            UPDATE qw_personal_definition_document d SET embedding=?::vector,embedding_model=?,embedding_version=?,embedding_dimensions=?,
                task_state='DONE',owner_token=NULL,lease_until=NULL,last_error=NULL,update_time=CURRENT_TIMESTAMP
            WHERE d.preference_id=? AND d.source_revision=? AND d.source_content_hash=? AND d.task_state='RUNNING' AND d.owner_token=?
                AND EXISTS(SELECT 1 FROM qw_user_semantic_preference p WHERE p.id=d.preference_id
                    AND p.current_revision=d.source_revision AND NOT p.archived)
            """,serialized,identity.model(),identity.version(),value.length,work.preferenceId(),work.revision(),work.hash(),work.token())==1;
    }
    @Transactional
    public void fail(Work work) {
        long seconds=Math.min(21600L,60L*(1L<<Math.min(9,Math.max(0,work.attempt()-1))));
        jdbc.update("""
            UPDATE qw_personal_definition_document SET task_state='RETRYABLE_FAILURE',owner_token=NULL,lease_until=NULL,
                last_error='EMBEDDING_UNAVAILABLE',next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 second'),update_time=CURRENT_TIMESTAMP
            WHERE preference_id=? AND source_revision=? AND source_content_hash=? AND task_state='RUNNING' AND owner_token=?
            """,seconds,work.preferenceId(),work.revision(),work.hash(),work.token());
    }
    static String vector(float[] value) {
        if(value==null||value.length==0||value.length>16000)throw new IllegalArgumentException("Bounded embedding required");
        boolean nonzero=false;for(float v:value){if(!Float.isFinite(v))throw new IllegalArgumentException("Finite embedding required");nonzero|=v!=0;}
        if(!nonzero)throw new IllegalArgumentException("Nonzero embedding required");return Arrays.toString(value);
    }
    private static boolean valid(Long project,String principal,String query,int limit) {
        return project!=null&&principal!=null&&!principal.isBlank()&&query!=null&&query.codePoints().anyMatch(Character::isLetterOrDigit)&&limit>0;
    }
}
