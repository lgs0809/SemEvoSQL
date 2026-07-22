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

/** Shared, explicitly authorized suggestions. These rows are not published Catalog assets. */
@Repository
public class ProjectDefinitionCandidateRepository {
    private final JdbcTemplate jdbc;
    public ProjectDefinitionCandidateRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Candidate(long id,long project,int revision,String name,String text,String hash,long preference,int sourceRevision,String indexText) {}
    public record Hit(Candidate candidate,double score) {}
    public record Work(Candidate candidate,String token,int attempt) {}
    // Authorization is a revisioned fact. A later private meaning does not revoke earlier shared meaning implicitly.
    private static final String VISIBLE="""
        c.lifecycle IN ('ACCUMULATING','NEEDS_ADMIN_REVIEW','READY_FOR_PUBLISH')
        AND COALESCE(c.blocked_reason,'') NOT IN ('WRONG_DEFINITION','UNSAFE_DEFINITION','DEPENDENCY_INVALID')
        AND EXISTS(SELECT 1 FROM qw_project_definition_source s
          JOIN qw_user_semantic_preference p ON p.id=s.preference_id AND p.project_id=c.project_id AND NOT p.archived
          WHERE s.candidate_id=c.id AND s.candidate_content_revision=c.content_revision
            AND
        """+PersonalDefinitionSharingPolicy.SOURCE_AUTHORIZED+")";
    private static final String FIELDS="c.id,c.project_id,c.content_revision,c.business_name,c.definition_text,c.content_hash,c.source_preference_id,c.source_revision,c.semantic_text";
    private Candidate map(java.sql.ResultSet r)throws java.sql.SQLException{return new Candidate(r.getLong(1),r.getLong(2),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getInt(8),r.getString(9));}
    public List<Hit> lexical(Long project,String query,int limit) {
        if(project==null||query==null||!query.codePoints().anyMatch(Character::isLetterOrDigit)||limit<=0)return List.of();
        return jdbc.query("""
            WITH terms AS (SELECT term FROM unnest(tsvector_to_array(to_tsvector('simple',qw_semantic_tokenize_v1(?)))) term
              ORDER BY char_length(term) DESC,term LIMIT 64),
            input AS (SELECT to_tsquery('simple',string_agg(quote_literal(term),' | ')) AS query FROM terms)
            SELECT
            """+FIELDS+",ts_rank(c.lexical_vector,input.query) AS score FROM qw_project_definition_candidate c CROSS JOIN input "+
            "WHERE c.project_id=? AND "+VISIBLE+" AND c.lexical_vector @@ input.query ORDER BY score DESC,c.id LIMIT ?",
            (r,n)->new Hit(map(r),r.getDouble(10)),query,project,Math.min(20,limit));
    }
    public boolean hasVectors(Long project,EmbeddingEncodingIdentity identity) {
        return project!=null&&identity!=null&&Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM qw_project_definition_candidate c WHERE c.project_id=? AND "+VISIBLE+
            " AND c.embedding IS NOT NULL AND c.embedding_model=? AND c.embedding_version=?)",Boolean.class,project,identity.model(),identity.version()));
    }
    public List<Hit> vector(Long project,float[] vector,EmbeddingEncodingIdentity identity,int limit) {
        if(project==null||identity==null||limit<=0)return List.of();
        String value=PersonalDefinitionRetrievalRepository.vector(vector);
        if(identity.dimensions()!=null&&identity.dimensions()!=vector.length)return List.of();
        return jdbc.query("SELECT "+FIELDS+",1-(c.embedding <=> ?::vector) AS score FROM qw_project_definition_candidate c "+
            "WHERE c.project_id=? AND "+VISIBLE+" AND c.embedding IS NOT NULL AND c.embedding_model=? AND c.embedding_version=? "+
            "AND c.embedding_dimensions=? ORDER BY c.embedding <=> ?::vector,c.id LIMIT ?",
            (r,n)->new Hit(map(r),r.getDouble(10)),value,project,identity.model(),identity.version(),vector.length,value,Math.min(20,limit));
    }
    public Optional<Candidate> visible(Long project,long id) {
        return jdbc.query("SELECT "+FIELDS+" FROM qw_project_definition_candidate c WHERE c.project_id=? AND c.id=? AND "+VISIBLE,
            (r,n)->map(r),project,id).stream().findFirst();
    }
    public long forSource(long preference,int revision) {
        return jdbc.queryForObject("SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=? AND definition_revision=?",Long.class,preference,revision);
    }
    @Transactional
    public Optional<Work> claim(EmbeddingEncodingIdentity identity,Duration lease) {
        if(identity==null)return Optional.empty();String token=UUID.randomUUID().toString();
        return jdbc.query("""
            WITH due AS (SELECT c.id FROM qw_project_definition_candidate c WHERE
            """+VISIBLE+"""
            AND ((c.index_state IN ('PENDING','RETRYABLE_FAILURE') AND c.index_next_attempt_at<=CURRENT_TIMESTAMP)
              OR (c.index_state='RUNNING' AND c.index_lease_until<CURRENT_TIMESTAMP)
              OR (c.index_state='DONE' AND (c.embedding_model IS DISTINCT FROM ? OR c.embedding_version IS DISTINCT FROM ?)))
            ORDER BY c.index_next_attempt_at,c.id FOR UPDATE OF c SKIP LOCKED LIMIT 1)
            UPDATE qw_project_definition_candidate c SET index_state='RUNNING',index_owner_token=?,index_attempt_count=index_attempt_count+1,
              index_lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),update_time=CURRENT_TIMESTAMP
            FROM due WHERE c.id=due.id RETURNING
            """+FIELDS+",c.index_attempt_count",(r,n)->new Work(map(r),token,r.getInt(10)),identity.model(),identity.version(),token,lease.toMillis()).stream().findFirst();
    }
    @Transactional
    public boolean complete(Work work,EmbeddingEncodingIdentity identity,float[] vector) {
        String value=PersonalDefinitionRetrievalRepository.vector(vector);
        if(identity.dimensions()!=null&&identity.dimensions()!=vector.length)throw new IllegalArgumentException("Embedding contract mismatch");
        return jdbc.update("""
            UPDATE qw_project_definition_candidate c SET embedding=?::vector,embedding_model=?,embedding_version=?,embedding_dimensions=?,
              index_state='DONE',index_owner_token=NULL,index_lease_until=NULL,index_last_error=NULL,update_time=CURRENT_TIMESTAMP
            WHERE c.id=? AND c.content_revision=? AND c.content_hash=? AND c.semantic_text=? AND c.index_state='RUNNING' AND c.index_owner_token=? AND
            """+VISIBLE,value,identity.model(),identity.version(),vector.length,work.candidate().id(),work.candidate().revision(),
            work.candidate().hash(),work.candidate().indexText(),work.token())==1;
    }
    @Transactional
    public void fail(Work work) {
        long seconds=Math.min(21600L,60L*(1L<<Math.min(9,Math.max(0,work.attempt()-1))));
        jdbc.update("""
            UPDATE qw_project_definition_candidate SET index_state='RETRYABLE_FAILURE',index_owner_token=NULL,index_lease_until=NULL,
              index_last_error='EMBEDDING_UNAVAILABLE',index_next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 second'),update_time=CURRENT_TIMESTAMP
            WHERE id=? AND content_revision=? AND content_hash=? AND index_state='RUNNING' AND index_owner_token=?
            """,seconds,work.candidate().id(),work.candidate().revision(),work.candidate().hash(),work.token());
    }
    public void lock(long id){jdbc.queryForList("SELECT id FROM qw_project_definition_candidate WHERE id=? FOR UPDATE",id);}
    public void freeze(String question,String option,Candidate candidate,String principal) {
        jdbc.update("""
            INSERT INTO qw_clarification_candidate_base(clarification_id,option_code,candidate_id,content_revision,project_id,principal_id,definition_text,content_hash)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(clarification_id,option_code) DO NOTHING
            """,question,option,candidate.id(),candidate.revision(),candidate.project(),principal,candidate.text(),candidate.hash());
    }
    public Optional<Candidate> questionBase(String question,String option,Long project,String principal) {
        return jdbc.query("""
            SELECT candidate_id,project_id,content_revision,'' AS business_name,definition_text,content_hash,0 AS preference_id,0 AS source_revision,'' AS semantic_text
            FROM qw_clarification_candidate_base WHERE clarification_id=? AND option_code=? AND project_id=? AND principal_id=?
            """,(r,n)->map(r),question,option,project,principal).stream().findFirst();
    }
    public boolean alreadyConfirmed(Long project,String principal,long candidate,int revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision r
              ON r.preference_id=p.id AND r.revision=p.current_revision
            WHERE p.project_id=? AND p.user_id=? AND NOT p.archived AND r.source_kind='PROJECT_ADOPTION'
              AND r.definition_snapshot->'projectCandidate'->>'id'=? AND r.definition_snapshot->'projectCandidate'->>'contentRevision'=?)
            """,Boolean.class,project,principal,Long.toString(candidate),Integer.toString(revision)));
    }
}
