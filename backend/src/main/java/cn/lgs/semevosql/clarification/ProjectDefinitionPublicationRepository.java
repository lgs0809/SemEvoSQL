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
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Publication jobs bind immutable evidence and a public baseline; stale jobs never silently adopt new inputs. */
@Repository
public class ProjectDefinitionPublicationRepository {
    private final JdbcTemplate jdbc;
    private final SemanticProjectRepository projects;
    private final ProjectDefinitionContributions contributions;
    private final OperatorContext.Resolver operators;
    private final ProjectScopeService scope;
    public ProjectDefinitionPublicationRepository(JdbcTemplate jdbc,SemanticProjectRepository projects,ProjectDefinitionContributions contributions,
            OperatorContext.Resolver operators,ProjectScopeService scope) {
        this.jdbc=jdbc;this.projects=projects;this.contributions=contributions;this.operators=operators;this.scope=scope;
    }
    public record Work(long id,long project,long candidate,int contentRevision,int evidenceRevision,String fingerprint,
            long baseVersion,String catalogHash,JsonNode structure,String representationHash,String assetKey,String name,String text,
            Long preparedVersion,String initialCatalogHash,String materializedCatalogHash,String token,int attempt,
            Long decision,String action,String target,String publicName,String operator,String operatorSource) {}

    public List<Long> eligibleProjects() {
        return jdbc.queryForList("SELECT DISTINCT project_id FROM qw_project_definition_candidate WHERE lifecycle='READY_FOR_PUBLISH' AND assessment_state='DONE' ORDER BY project_id",Long.class);
    }
    @Transactional
    public void enqueueAutomatic(long project) {
        projects.lockProject(project);
        var candidates=jdbc.queryForList("""
            SELECT c.* FROM qw_project_definition_candidate c WHERE c.project_id=? AND c.lifecycle='READY_FOR_PUBLISH'
              AND c.assessment_state='DONE' AND c.approved_decision_id IS NULL AND c.assessment_json->'alignment'->>'relation'='NEW'
            ORDER BY c.id FOR UPDATE
            """,project);
        for(var c:candidates) {
            long id=((Number)c.get("id")).longValue();int content=((Number)c.get("content_revision")).intValue();
            var totals=contributions.totals(id,content,project);
            if(!qualified(c,totals,project))continue;
            var structure=read(c.get("structured_json"));
            jdbc.update("""
                INSERT INTO qw_project_definition_publication(candidate_id,project_id,content_revision,evidence_revision,
                  contribution_fingerprint,base_version_id,base_catalog_hash,source_structure,source_representation_hash,
                  public_asset_key,operator,reason)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,'semevosql-system','First publication: at least three trusted sharing users and five actual logical QUERY uses; no public conflict')
                ON CONFLICT DO NOTHING
                """,id,project,content,c.get("evidence_revision"),totals.fingerprint(),c.get("assessed_base_version_id"),c.get("assessed_catalog_hash"),
                PersonalSemanticDefinitionStore.json(structure),c.get("representation_hash"),ProjectDefinitionCatalogMaterializer.assetCode(id,structure));
        }
    }
    private boolean qualified(Map<String,Object> c,ProjectDefinitionContributions.Totals totals,long project) {
        return totals.validUsers()>=3&&totals.validUses()>=5&&inputsCurrent(c,totals,project);
    }
    private boolean inputsCurrent(Map<String,Object> c,ProjectDefinitionContributions.Totals totals,long project) {
        var active=jdbc.queryForList("SELECT p.active_version_id,v.catalog_hash FROM qw_project p JOIN qw_project_version v ON v.id=p.active_version_id WHERE p.id=?",project);
        return totals.authorizedSources()>0&&c.get("structured_json")!=null
            && Objects.equals(c.get("content_revision"),c.get("assessed_content_revision"))
            && Objects.equals(c.get("evidence_revision"),c.get("assessed_evidence_revision"))
            && totals.fingerprint().equals(c.get("assessed_contribution_fingerprint"))
            && active.size()==1&&Objects.equals(active.get(0).get("active_version_id"),c.get("assessed_base_version_id"))
            && Objects.equals(active.get(0).get("catalog_hash"),c.get("assessed_catalog_hash"));
    }
    @Transactional
    public Optional<Work> claim(Duration lease) {
        String token=UUID.randomUUID().toString();
        return jdbc.query("""
            WITH due AS (SELECT id FROM qw_project_definition_publication
              WHERE (state IN ('PENDING','RETRYABLE_FAILURE') AND next_attempt_at<=CURRENT_TIMESTAMP)
                OR (state='BUILDING' AND lease_until<CURRENT_TIMESTAMP)
              ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE qw_project_definition_publication j SET state='BUILDING',owner_token=?,attempt_count=attempt_count+1,
              lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond') FROM due,qw_project_definition_candidate c
            WHERE j.id=due.id AND c.id=j.candidate_id RETURNING j.*,c.business_name,c.definition_text
            """,(r,n)->new Work(r.getLong("id"),r.getLong("project_id"),r.getLong("candidate_id"),r.getInt("content_revision"),r.getInt("evidence_revision"),
                r.getString("contribution_fingerprint"),r.getLong("base_version_id"),r.getString("base_catalog_hash"),read(r.getString("source_structure")),
                r.getString("source_representation_hash"),r.getString("public_asset_key"),r.getString("business_name"),r.getString("definition_text"),
                r.getObject("prepared_version_id",Long.class),r.getString("initial_catalog_hash"),r.getString("materialized_catalog_hash"),token,r.getInt("attempt_count"),
                r.getObject("decision_id",Long.class),r.getString("action"),r.getString("target_asset_key"),r.getString("public_name"),r.getString("operator"),r.getString("operator_source")),token,lease.toMillis()).stream().findFirst();
    }
    public boolean owns(Work work) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_project_definition_publication WHERE id=? AND state='BUILDING' AND owner_token=? AND lease_until>CURRENT_TIMESTAMP)",Boolean.class,work.id(),work.token()));
    }
    /** Caller holds project -> candidate coordination inside its final publication transaction. */
    public void assertCurrent(Work work) {
        var rows=jdbc.queryForList("SELECT * FROM qw_project_definition_candidate WHERE id=? AND project_id=? FOR UPDATE",work.candidate(),work.project());
        if(rows.size()!=1||!owns(work))throw new Stale();var c=rows.get(0);
        assertInputsCurrent(work,c);
    }
    private void assertInputsCurrent(Work work,Map<String,Object> c) {
        var totals=contributions.totals(work.candidate(),work.contentRevision(),work.project());
        if(work.decision()!=null) {
            var actor=new OperatorContext(work.operator(),work.operatorSource(),"definition-publication-"+work.id(),"definition-publication-"+work.id());
            try{scope.requireProject(work.project(),actor);if(!operators.administrator(actor))throw new Stale();}
            catch(org.springframework.web.server.ResponseStatusException denied){throw new Stale();}
        }
        if(!"READY_FOR_PUBLISH".equals(c.get("lifecycle")) || !inputsCurrent(c,totals,work.project())
            || ((Number)c.get("content_revision")).intValue()!=work.contentRevision()
            || ((Number)c.get("evidence_revision")).intValue()!=work.evidenceRevision()
            || !totals.fingerprint().equals(work.fingerprint())
            || !Objects.equals(c.get("assessed_base_version_id"),work.baseVersion())
            || !Objects.equals(c.get("assessed_catalog_hash"),work.catalogHash())
            || !Objects.equals(c.get("representation_hash"),work.representationHash())
            || !Objects.equals(c.get("approved_decision_id"),work.decision())
            || (work.decision()==null && (!qualified(c,totals,work.project())
                ||!"NEW".equals(read(c.get("assessment_json")).path("alignment").path("relation").asText()))))throw new Stale();
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void prepared(Work work,long version,String initialHash) {
        if(jdbc.update("UPDATE qw_project_definition_publication SET prepared_version_id=?,initial_catalog_hash=? WHERE id=? AND state='BUILDING' AND owner_token=? AND prepared_version_id IS NULL",version,initialHash,work.id(),work.token())!=1)
            throw new Stale();
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void materialized(Work work,String hash) {
        if(jdbc.update("UPDATE qw_project_definition_publication SET materialized_catalog_hash=? WHERE id=? AND state='BUILDING' AND owner_token=? AND materialized_catalog_hash IS NULL",hash,work.id(),work.token())!=1)
            throw new Stale();
    }
    public Work refresh(Work work) {
        var rows=jdbc.queryForList("SELECT prepared_version_id,initial_catalog_hash,materialized_catalog_hash FROM qw_project_definition_publication WHERE id=? AND state='BUILDING' AND owner_token=?",work.id(),work.token());
        if(rows.size()!=1)throw new Stale();var row=rows.get(0);
        return new Work(work.id(),work.project(),work.candidate(),work.contentRevision(),work.evidenceRevision(),work.fingerprint(),work.baseVersion(),
            work.catalogHash(),work.structure(),work.representationHash(),work.assetKey(),work.name(),work.text(),
            row.get("prepared_version_id")==null?null:((Number)row.get("prepared_version_id")).longValue(),
            (String)row.get("initial_catalog_hash"),(String)row.get("materialized_catalog_hash"),work.token(),work.attempt(),
            work.decision(),work.action(),work.target(),work.publicName(),work.operator(),work.operatorSource());
    }
    public void complete(Work work,long version) {
        if(jdbc.update("UPDATE qw_project_definition_publication SET state='DONE',owner_token=NULL,lease_until=NULL,last_error=NULL,finish_time=CURRENT_TIMESTAMP WHERE id=? AND state='BUILDING' AND owner_token=?",work.id(),work.token())!=1)
            throw new Stale();
        jdbc.update("UPDATE qw_project_definition_candidate SET lifecycle='PUBLISHED',blocked_reason=NULL,published_version_id=?,public_asset_key=?,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",version,work.assetKey(),work.candidate());
        jdbc.update("""
            INSERT INTO qw_project_definition_publication_event(publication_id,candidate_id,project_id,from_version_id,to_version_id,
              public_asset_key,operator,reason,payload)
            SELECT id,candidate_id,project_id,base_version_id,?,public_asset_key,operator,reason,
              jsonb_build_object('contentRevision',content_revision,'evidenceRevision',evidence_revision,
                'contributionFingerprint',contribution_fingerprint,'sourceRepresentationHash',source_representation_hash,
                'action',action,'decisionId',decision_id,'targetAsset',target_asset_key)
            FROM qw_project_definition_publication WHERE id=?
            """,version,work.id());
    }
    @Transactional
    public void fail(Work work,boolean stale) {
        fail(work,stale,stale?"PUBLICATION_INPUT_CHANGED":"PUBLICATION_PREPARATION_UNAVAILABLE");
    }
    @Transactional
    public void failWaitingForIndex(Work work) {
        fail(work,false,"PUBLICATION_WAITING_FOR_INDEX");
    }
    private void fail(Work work,boolean stale,String error) {
        projects.lockProject(work.project());
        jdbc.queryForList("SELECT id FROM qw_project_definition_candidate WHERE id=? FOR UPDATE",work.candidate());
        long seconds=Math.min(21600L,60L*(1L<<Math.min(9,Math.max(0,work.attempt()-1))));
        int changed=jdbc.update("""
            UPDATE qw_project_definition_publication SET state=?,owner_token=NULL,lease_until=NULL,last_error=?,
              next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 second') WHERE id=? AND state='BUILDING' AND owner_token=?
            """,stale?"STALE":"RETRYABLE_FAILURE",error,seconds,work.id(),work.token());
        if(changed==1&&stale&&work.decision()!=null)
            jdbc.update("UPDATE qw_project_definition_candidate SET approved_decision_id=NULL,assessment_state='PENDING',assessment_next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND approved_decision_id=? AND lifecycle='READY_FOR_PUBLISH'",work.candidate(),work.decision());
    }
    public List<Work> waitingForIndex() {
        return jdbc.query("""
            SELECT j.*,c.business_name,c.definition_text FROM qw_project_definition_publication j
            JOIN qw_project_definition_candidate c ON c.id=j.candidate_id AND c.project_id=j.project_id
            WHERE j.state='RETRYABLE_FAILURE' AND j.last_error='PUBLICATION_WAITING_FOR_INDEX'
              AND j.prepared_version_id IS NOT NULL AND j.materialized_catalog_hash IS NOT NULL
            ORDER BY j.project_id,j.id LIMIT 64
            """,(r,n)->new Work(r.getLong("id"),r.getLong("project_id"),r.getLong("candidate_id"),r.getInt("content_revision"),r.getInt("evidence_revision"),
                r.getString("contribution_fingerprint"),r.getLong("base_version_id"),r.getString("base_catalog_hash"),read(r.getString("source_structure")),
                r.getString("source_representation_hash"),r.getString("public_asset_key"),r.getString("business_name"),r.getString("definition_text"),
                r.getObject("prepared_version_id",Long.class),r.getString("initial_catalog_hash"),r.getString("materialized_catalog_hash"),null,r.getInt("attempt_count"),
                r.getObject("decision_id",Long.class),r.getString("action"),r.getString("target_asset_key"),r.getString("public_name"),r.getString("operator"),r.getString("operator_source")));
    }
    /** Called in a short dependency transaction before checking the exact catalog/index. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,noRollbackFor=Stale.class)
    public void assertIndexWaitCurrent(Work work) {
        assertIndexRetryCurrent(work,false);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertManualIndexRetryCurrent(Work work) {
        assertIndexRetryCurrent(work,true);
    }
    private void assertIndexRetryCurrent(Work work,boolean manual) {
        projects.lockProject(work.project());
        var rows=jdbc.queryForList("SELECT * FROM qw_project_definition_candidate WHERE id=? AND project_id=? FOR UPDATE",work.candidate(),work.project());
        if(rows.size()!=1)throw new Stale();
        var jobs=jdbc.queryForList("""
            SELECT j.prepared_version_id,j.materialized_catalog_hash FROM qw_project_definition_publication j
            JOIN qw_project_version v ON v.id=j.prepared_version_id AND v.project_id=j.project_id
            WHERE j.id=? AND j.candidate_id=? AND j.project_id=? AND j.state='RETRYABLE_FAILURE'
              AND (j.last_error='PUBLICATION_WAITING_FOR_INDEX' OR (? AND j.last_error IN ('PUBLICATION_PREPARATION_UNAVAILABLE','PUBLICATION_INDEX_READY')))
              AND j.owner_token IS NULL AND j.lease_until IS NULL AND v.status IN ('DRAFT','VALIDATED') AND v.analysis_status='COMPLETED'
              AND j.content_revision=? AND j.evidence_revision=? AND j.contribution_fingerprint=?
              AND j.source_representation_hash=? AND j.base_version_id=? AND j.base_catalog_hash=?
              AND j.decision_id IS NOT DISTINCT FROM ? FOR UPDATE OF j,v
            """,work.id(),work.candidate(),work.project(),manual,work.contentRevision(),work.evidenceRevision(),work.fingerprint(),
                work.representationHash(),work.baseVersion(),work.catalogHash(),work.decision());
        if(jobs.size()!=1 || work.preparedVersion()==null || work.materializedCatalogHash()==null
                || !Objects.equals(jobs.get(0).get("prepared_version_id"),work.preparedVersion())
                || !Objects.equals(jobs.get(0).get("materialized_catalog_hash"),work.materializedCatalogHash()))throw new Stale();
        assertInputsCurrent(work,rows.get(0));
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean indexDependencyReady(Work work) {
        return indexDependencyReady(work,false);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean manuallyRetryReadyIndex(Work work) {
        return indexDependencyReady(work,true);
    }
    private boolean indexDependencyReady(Work work,boolean manual) {
        return jdbc.update("""
            UPDATE qw_project_definition_publication SET next_attempt_at=CURRENT_TIMESTAMP,last_error='PUBLICATION_INDEX_READY'
            WHERE id=? AND state='RETRYABLE_FAILURE'
              AND (last_error='PUBLICATION_WAITING_FOR_INDEX' OR (? AND last_error='PUBLICATION_PREPARATION_UNAVAILABLE'))
              AND prepared_version_id=? AND materialized_catalog_hash=? AND owner_token IS NULL AND lease_until IS NULL
            """,work.id(),manual,work.preparedVersion(),work.materializedCatalogHash())==1;
    }
    public Optional<Work> retryable(long project,long candidate,long publication) {
        return jdbc.query("""
            SELECT j.*,c.business_name,c.definition_text FROM qw_project_definition_publication j
            JOIN qw_project_definition_candidate c ON c.id=j.candidate_id AND c.project_id=j.project_id
            WHERE j.project_id=? AND j.candidate_id=? AND j.id=? AND j.state='RETRYABLE_FAILURE'
            """,(r,n)->new Work(r.getLong("id"),r.getLong("project_id"),r.getLong("candidate_id"),r.getInt("content_revision"),r.getInt("evidence_revision"),
                r.getString("contribution_fingerprint"),r.getLong("base_version_id"),r.getString("base_catalog_hash"),read(r.getString("source_structure")),
                r.getString("source_representation_hash"),r.getString("public_asset_key"),r.getString("business_name"),r.getString("definition_text"),
                r.getObject("prepared_version_id",Long.class),r.getString("initial_catalog_hash"),r.getString("materialized_catalog_hash"),null,r.getInt("attempt_count"),
                r.getObject("decision_id",Long.class),r.getString("action"),r.getString("target_asset_key"),r.getString("public_name"),r.getString("operator"),r.getString("operator_source")),project,candidate,publication).stream().findFirst();
    }
    public static class Stale extends RuntimeException { public Stale(){super("PUBLICATION_INPUT_CHANGED");} }
    private static JsonNode read(Object data) {
        try{return JsonUtil.getObjectMapper().readTree(data.toString());}catch(Exception invalid){throw new IllegalStateException("Invalid publication evidence",invalid);}
    }
}
