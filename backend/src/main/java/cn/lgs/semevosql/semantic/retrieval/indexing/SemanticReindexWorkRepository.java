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

import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One idempotent obligation per index scope; revision and lease fence replacement and recovery. */
@Repository
public class SemanticReindexWorkRepository {
    private final JdbcTemplate jdbc;
    public SemanticReindexWorkRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> request(SemanticRetrievalIndexService.ConfiguredIdentity identity,String actor) {
        jdbc.update("""
            INSERT INTO qw_semantic_reindex_work AS old
                (index_scope,embedding_model,embedding_version,status,requested_by)
            VALUES ('SEMANTIC_CATALOG',?,?,'PENDING',?)
            ON CONFLICT(index_scope) DO UPDATE SET
                revision=old.revision+1,embedding_model=EXCLUDED.embedding_model,
                embedding_version=EXCLUDED.embedding_version,status='PENDING',requested_by=EXCLUDED.requested_by,
                attempt_count=0,indexed_documents=0,owner_token=NULL,lease_until=NULL,
                next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,update_time=CURRENT_TIMESTAMP
            WHERE old.status='DONE' OR old.embedding_model<>EXCLUDED.embedding_model
                OR old.embedding_version<>EXCLUDED.embedding_version
            """,identity.model(),identity.version(),actor);
        return status();
    }

    public Map<String,Object> status() {
        return jdbc.queryForList("""
            SELECT index_scope,revision,embedding_model,embedding_version,status,requested_by,
                attempt_count,indexed_documents,next_attempt_at,last_error,update_time
            FROM qw_semantic_reindex_work WHERE index_scope='SEMANTIC_CATALOG'
            """).stream().findFirst().orElse(Map.of("status","NOT_REQUESTED"));
    }

    public Optional<Work> claim() {
        return jdbc.query("""
            UPDATE qw_semantic_reindex_work SET status='PROCESSING',owner_token=?,
                lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes',attempt_count=attempt_count+1,
                update_time=CURRENT_TIMESTAMP
            WHERE index_scope='SEMANTIC_CATALOG' AND
              ((status IN ('PENDING','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP)
                OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP))
            RETURNING revision,embedding_model,embedding_version,owner_token,attempt_count
            """,(r,n)->new Work(r.getLong(1),new SemanticRetrievalIndexService.ConfiguredIdentity(r.getString(2),r.getString(3)),
                r.getString(4),r.getInt(5)),UUID.randomUUID().toString()).stream().findFirst();
    }

    public boolean renew(Work work) {
        return jdbc.update("""
            UPDATE qw_semantic_reindex_work SET lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes'
            WHERE index_scope='SEMANTIC_CATALOG' AND revision=? AND owner_token=?
                AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
            """,work.revision(),work.ownerToken())==1;
    }

    /** Caller holds a Spring transaction until index publication and DONE commit together. */
    public boolean lockCurrent(Work work) {
        return !jdbc.queryForList("""
            SELECT revision FROM qw_semantic_reindex_work WHERE index_scope='SEMANTIC_CATALOG'
                AND revision=? AND owner_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
            FOR UPDATE
            """,work.revision(),work.ownerToken()).isEmpty();
    }

    public boolean finish(Work work,Integer indexed,String errorCode) {
        double seconds=Math.min(3600,15L << Math.min(8,Math.max(0,work.attempt()-1)));
        return jdbc.update("""
            UPDATE qw_semantic_reindex_work SET status=?,indexed_documents=?,last_error=?,owner_token=NULL,
                lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+make_interval(secs=>?),update_time=CURRENT_TIMESTAMP
            WHERE index_scope='SEMANTIC_CATALOG' AND revision=? AND owner_token=?
                AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
            """,indexed==null?"RETRY":"DONE",indexed==null?0:indexed,errorCode,seconds,work.revision(),work.ownerToken())==1;
    }

    public record Work(long revision,SemanticRetrievalIndexService.ConfiguredIdentity identity,String ownerToken,int attempt) {}
}
