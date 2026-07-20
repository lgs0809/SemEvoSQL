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

import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Short database coordination; model and indexing work belong outside these methods. */
@Repository
public class ProjectDefinitionAssessmentRepository {
    private final JdbcTemplate jdbc;
    private final SemanticProjectRepository projects;
    private final ProjectDefinitionContributions contributions;
    public ProjectDefinitionAssessmentRepository(JdbcTemplate jdbc, SemanticProjectRepository projects,
            ProjectDefinitionContributions contributions) {
        this.jdbc=jdbc;this.projects=projects;this.contributions=contributions;
    }
    public record Work(ProjectDefinitionCandidateRepository.Candidate candidate,int evidenceRevision,
            long baseVersion,String catalogHash,String token,int attempt) {}

    @Transactional
    public Optional<Work> claim(Duration lease) {
        String token=UUID.randomUUID().toString();
        return jdbc.query("""
            WITH due AS (SELECT c.id FROM qw_project_definition_candidate c
              JOIN qw_project p ON p.id=c.project_id JOIN qw_project_version v ON v.id=p.active_version_id
              WHERE c.lifecycle NOT IN ('PUBLISHED','REJECTED') AND c.blocked_reason IS DISTINCT FROM 'ADMINISTRATOR_DEFER'
                AND c.approved_decision_id IS NULL AND v.status='PUBLISHED'
                AND ((c.assessment_state IN ('PENDING','RETRYABLE_FAILURE') AND c.assessment_next_attempt_at<=CURRENT_TIMESTAMP)
                  OR (c.assessment_state='RUNNING' AND c.assessment_lease_until<CURRENT_TIMESTAMP)
                  OR (c.assessment_state='DONE' AND (c.assessed_content_revision IS DISTINCT FROM c.content_revision
                    OR c.assessed_evidence_revision IS DISTINCT FROM c.evidence_revision
                    OR c.assessed_base_version_id IS DISTINCT FROM p.active_version_id
                    OR c.assessed_catalog_hash IS DISTINCT FROM v.catalog_hash
                    OR (c.blocked_reason='STRUCTURE_PENDING' AND EXISTS(SELECT 1 FROM qw_user_semantic_representation r
                      WHERE r.preference_id=c.source_preference_id AND r.source_revision=c.source_revision
                        AND r.representation_state='STRUCTURED_ACTIVE' AND r.structured_json->>'protocol'='personal-metric-1.1'))
                    OR c.assessment_next_attempt_at<=CURRENT_TIMESTAMP)))
              ORDER BY c.assessment_next_attempt_at,c.id FOR UPDATE OF c SKIP LOCKED LIMIT 1)
            UPDATE qw_project_definition_candidate c SET assessment_state='RUNNING',assessment_owner_token=?,
              assessment_attempt_count=assessment_attempt_count+1,assessment_lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond')
            FROM due,qw_project p,qw_project_version v
            WHERE c.id=due.id AND p.id=c.project_id AND v.id=p.active_version_id
            RETURNING c.id,c.project_id,c.content_revision,c.business_name,c.definition_text,c.content_hash,
              c.source_preference_id,c.source_revision,c.semantic_text,c.evidence_revision,v.id,v.catalog_hash,c.assessment_attempt_count
            """,(r,n)->new Work(new ProjectDefinitionCandidateRepository.Candidate(r.getLong(1),r.getLong(2),r.getInt(3),
                r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getInt(8),r.getString(9)),r.getInt(10),
                r.getLong(11),r.getString(12),token,r.getInt(13)),token,lease.toMillis()).stream().findFirst();
    }

    @Transactional
    public boolean complete(Work work,ProjectDefinitionContributions.Totals totals,JsonNode structure,
            JsonNode alignment,ProjectDefinitionPublicationPolicy.Decision decision) {
        var c=work.candidate();
        projects.lockProject(c.project());
        var rows=jdbc.queryForList("SELECT * FROM qw_project_definition_candidate WHERE id=? FOR UPDATE",c.id());
        if(rows.size()!=1)return false;
        var row=rows.get(0);
        if(!work.token().equals(row.get("assessment_owner_token")))return false;
        var active=jdbc.queryForList("SELECT p.active_version_id,v.catalog_hash FROM qw_project p JOIN qw_project_version v ON v.id=p.active_version_id WHERE p.id=?",c.project());
        boolean current=active.size()==1 && Objects.equals(((Number)active.get(0).get("active_version_id")).longValue(),work.baseVersion())
            && Objects.equals(active.get(0).get("catalog_hash"),work.catalogHash())
            && ((Number)row.get("content_revision")).intValue()==c.revision()
            && ((Number)row.get("evidence_revision")).intValue()==work.evidenceRevision()
            && c.hash().equals(row.get("content_hash"))
            && !Set.of("PUBLISHED","REJECTED").contains(row.get("lifecycle"))
            && !"ADMINISTRATOR_DEFER".equals(row.get("blocked_reason"))
            && row.get("approved_decision_id")==null
            && totals.fingerprint().equals(contributions.totals(c.id(),c.revision(),c.project()).fingerprint());
        if(!current) {
            jdbc.update("UPDATE qw_project_definition_candidate SET assessment_state='PENDING',assessment_owner_token=NULL,assessment_lease_until=NULL,assessment_next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND assessment_owner_token=?",c.id(),work.token());
            return false;
        }
        var report=JsonUtil.getObjectMapper().createObjectNode();
        report.set("contributions",JsonUtil.getObjectMapper().valueToTree(totals));
        report.set("alignment",alignment);report.set("decision",JsonUtil.getObjectMapper().valueToTree(decision));
        report.put("baseVersionId",work.baseVersion());report.put("catalogHash",work.catalogHash());
        String json=PersonalSemanticDefinitionStore.json(report);
        jdbc.update("""
            UPDATE qw_project_definition_candidate SET structured_json=?::jsonb,representation_hash=?,dependency_fingerprint=?,
              conflict_json=?::jsonb,lifecycle=?,blocked_reason=?,assessment_state='DONE',assessment_owner_token=NULL,
              assessment_lease_until=NULL,assessment_last_error=NULL,assessment_next_attempt_at=CURRENT_TIMESTAMP+interval '5 minutes',
              assessed_content_revision=?,assessed_evidence_revision=?,assessed_base_version_id=?,assessed_catalog_hash=?,
              assessed_contribution_fingerprint=?,assessment_json=?::jsonb,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP
            WHERE id=? AND assessment_owner_token=?
            """,structure==null?null:PersonalSemanticDefinitionStore.json(structure),structure==null?null:PersonalDefinitionSnapshot.hash(structure),
            structure==null?null:structure.path("dependencyFingerprint").asText(null),PersonalSemanticDefinitionStore.json(alignment),
            decision.lifecycle(),decision.blockedReason(),c.revision(),work.evidenceRevision(),work.baseVersion(),work.catalogHash(),
            totals.fingerprint(),json,c.id(),work.token());
        jdbc.update("""
            INSERT INTO qw_project_definition_assessment(candidate_id,content_revision,evidence_revision,base_version_id,
              catalog_hash,contribution_fingerprint,assessment_json) VALUES(?,?,?,?,?,?,?::jsonb)
            """,c.id(),c.revision(),work.evidenceRevision(),work.baseVersion(),work.catalogHash(),totals.fingerprint(),json);
        return true;
    }

    @Transactional
    public void fail(Work work,String reason) {
        long seconds=Math.min(21600L,60L*(1L<<Math.min(9,Math.max(0,work.attempt()-1))));
        jdbc.update("""
            UPDATE qw_project_definition_candidate SET assessment_state='RETRYABLE_FAILURE',assessment_owner_token=NULL,
              assessment_lease_until=NULL,assessment_last_error=?,assessment_next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 second')
            WHERE id=? AND content_revision=? AND assessment_state='RUNNING' AND assessment_owner_token=?
            """,reason!=null&&reason.matches("[A-Z_]{1,128}")?reason:"ASSESSMENT_UNAVAILABLE",seconds,
            work.candidate().id(),work.candidate().revision(),work.token());
    }

    public List<Map<String,Object>> list(long project) {
        return jdbc.queryForList("""
            SELECT c.id,c.content_revision,c.evidence_revision,c.row_revision,c.lifecycle,c.business_name,c.definition_text,
              c.blocked_reason,c.assessment_state,c.assessment_last_error,c.assessment_next_attempt_at,
              c.assessed_base_version_id,c.assessed_catalog_hash,c.assessed_content_revision,c.assessed_evidence_revision,
              c.assessed_contribution_fingerprint,c.assessment_json,c.structured_json,
              c.published_version_id,c.public_asset_key,c.representation_hash,c.approved_decision_id,p.publication
            FROM qw_project_definition_candidate c
            LEFT JOIN LATERAL (
              SELECT json_build_object('id',j.id,'state',j.state,'attempts',j.attempt_count,
                'nextAttemptAt',j.next_attempt_at,'lastError',j.last_error,
                'preparedVersionId',j.prepared_version_id,'finishedAt',j.finish_time) AS publication
              FROM qw_project_definition_publication j
              WHERE j.candidate_id=c.id AND j.project_id=c.project_id AND j.content_revision=c.content_revision
                AND j.source_representation_hash=c.representation_hash
              ORDER BY j.id DESC LIMIT 1
            ) p ON TRUE
            WHERE c.project_id=? ORDER BY c.id
            """,project).stream().<Map<String,Object>>map(row->{
                var result=new LinkedHashMap<>(row);
                for(String key:List.of("assessment_json","structured_json","publication")) {
                    Object value=result.get(key);result.put(key,value==null?null:parse(value.toString()));
                }
                var totals=contributions.totals(((Number)row.get("id")).longValue(),((Number)row.get("content_revision")).intValue(),project);
                result.put("contributions",totals);result.put("threshold_reached",totals.validUsers()>=3&&totals.validUses()>=5);
                var active=jdbc.queryForList("SELECT p.active_version_id,v.catalog_hash FROM qw_project p JOIN qw_project_version v ON v.id=p.active_version_id WHERE p.id=?",project);
                result.put("current_base_version_id",active.size()==1?active.get(0).get("active_version_id"):null);
                result.put("current_catalog_hash",active.size()==1?active.get(0).get("catalog_hash"):null);
                result.put("assessment_current",Objects.equals(row.get("content_revision"),row.get("assessed_content_revision"))
                    && Objects.equals(row.get("evidence_revision"),row.get("assessed_evidence_revision"))
                    && totals.fingerprint().equals(row.get("assessed_contribution_fingerprint")) && active.size()==1
                    && Objects.equals(active.get(0).get("active_version_id"),row.get("assessed_base_version_id"))
                    && Objects.equals(active.get(0).get("catalog_hash"),row.get("assessed_catalog_hash")));
                return result;
            }).toList();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> contributionEvidence(long project,long candidate,int offset,int limit) {
        if(offset<0||limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"贡献记录每页需为1至100条，页码不能为负");
        var rows=jdbc.queryForList("SELECT content_revision,evidence_revision FROM qw_project_definition_candidate WHERE id=? AND project_id=?",candidate,project);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该项目没有此建议");
        var revision=rows.get(0);
        var result=new LinkedHashMap<>(contributions.evidence(candidate,((Number)revision.get("content_revision")).intValue(),project,offset,limit));
        result.put("candidateId",candidate);result.put("projectId",project);
        result.put("contentRevision",revision.get("content_revision"));result.put("evidenceRevision",revision.get("evidence_revision"));
        return result;
    }

    public Optional<JsonNode> previousAlignment(Work work,String representationHash) {
        return jdbc.query("""
            SELECT assessment_json->'alignment' FROM qw_project_definition_candidate
            WHERE id=? AND assessed_content_revision=? AND content_hash=? AND assessed_base_version_id=?
              AND assessed_catalog_hash=? AND representation_hash=?
              AND assessment_json->'alignment'->>'relation' IN ('NEW','EQUIVALENT','CONFLICT')
            """,(r,n)->parse(r.getString(1)),work.candidate().id(),work.candidate().revision(),work.candidate().hash(),
            work.baseVersion(),work.catalogHash(),representationHash).stream().findFirst();
    }

    private static JsonNode parse(String json) {
        try{return JsonUtil.getObjectMapper().readTree(json);}catch(Exception invalid){throw new IllegalStateException("Invalid durable assessment",invalid);}
    }
}
