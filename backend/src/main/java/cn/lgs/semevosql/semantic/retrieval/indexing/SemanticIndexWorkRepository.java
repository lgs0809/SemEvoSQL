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

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The row revision and a fresh lease token fence every completion, including a reclaimed attempt. */
@Repository
public class SemanticIndexWorkRepository {
    private final JdbcTemplate jdbc;
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }
    public SemanticIndexWorkRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Optional<Work> claim() {
        String token=UUID.randomUUID().toString();
        return jdbc.query("""
            WITH due AS (SELECT document_id FROM qw_semantic_document_index_work
              WHERE (status IN ('PENDING','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP)
                 OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP)
              ORDER BY next_attempt_at,document_id FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE qw_semantic_document_index_work w SET status='PROCESSING',owner_token=?,
              lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes',attempt_count=attempt_count+1,update_time=CURRENT_TIMESTAMP
            FROM due WHERE w.document_id=due.document_id
            RETURNING w.document_id,w.revision,w.content_hash,w.source_fingerprint,w.owner_token,w.attempt_count
            """,(r,n)->new Work(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6)),token)
            .stream().findFirst();
    }

    public boolean renew(Work work) {
        return jdbc.update("""
            UPDATE qw_semantic_document_index_work SET lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes'
            WHERE document_id=? AND revision=? AND owner_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
            """,work.documentId(),work.revision(),work.ownerToken())==1;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean finish(Work work, boolean succeeded) {
        long seconds=Math.min(3600,5L << Math.min(10,Math.max(0,work.attempt()-1)));
        boolean changed=jdbc.update("""
            UPDATE qw_semantic_document_index_work SET status=?,owner_token=NULL,lease_until=NULL,
              next_attempt_at=CURRENT_TIMESTAMP+make_interval(secs=>?),last_error=?,update_time=CURRENT_TIMESTAMP
            WHERE document_id=? AND revision=? AND owner_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
            """,succeeded?"DONE":"RETRY",(double)seconds,succeeded?null:"EMBEDDING_NOT_READY_RETRY_SCHEDULED",
            work.documentId(),work.revision(),work.ownerToken())==1;
        if(changed && succeeded && events!=null) {
            var documents=jdbc.queryForList("SELECT project_id,project_version_id,catalog_hash FROM qw_semantic_retrieval_document WHERE id=? AND content_hash=? AND source_fingerprint=?",work.documentId(),work.contentHash(),work.sourceFingerprint());
            if(documents.size()==1) {
                var d=documents.get(0);
                events.publishEvent(new IndexCompleted(((Number)d.get("project_id")).longValue(),((Number)d.get("project_version_id")).longValue(),d.get("catalog_hash").toString()));
            }
        }
        return changed;
    }

    public record Work(String documentId,long revision,String contentHash,String sourceFingerprint,String ownerToken,int attempt) {}
    public record IndexCompleted(Long projectId,Long versionId,String catalogHash) {}
}
