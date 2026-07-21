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

import cn.lgs.semevosql.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional source of truth. Model work never runs inside a confirmation transaction. */
@Service
public class PersonalSemanticDefinitionStore {
    private final JdbcTemplate jdbc;
    private org.springframework.context.ApplicationEventPublisher events;
    public PersonalSemanticDefinitionStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @org.springframework.beans.factory.annotation.Autowired
    public PersonalSemanticDefinitionStore(JdbcTemplate jdbc,org.springframework.context.ApplicationEventPublisher events){this(jdbc);this.events=events;}
    public record Confirmed(long preferenceId,int revision) {}
    public enum Sharing {PRIVATE,AWAITING_CONSENT,ALLOWED,DECLINED}
    public record Definition(long preferenceId,int revision,long projectId,String principal,String phrase,String text,
        String assetType,String assetKey,String label,String sourceKind,String sourceId,Long baseVersionId,
        JsonNode snapshot,String contentHash,String dependencyFingerprint,String representation,String taskState,
        JsonNode structured,Sharing sharing) {}
    public record Confirmation(Long projectId,String principal,String phrase,String text,String assetType,String assetKey,
        String label,String sourceKind,String sourceId,Long baseVersionId,JsonNode snapshot,String dependencyFingerprint,
        Sharing sharing,Integer expectedRevision) {}

    public int currentRevision(Long project,String principal,String phrase) {
        return jdbc.query("SELECT current_revision FROM qw_user_semantic_preference WHERE project_id=? AND user_id=? AND normalized_phrase=?",
            (rs,n)->rs.getInt(1),project,principal,UserSemanticPreferenceService.normalizePhrase(phrase)).stream().findFirst().orElse(0);
    }
    public void freezeQuestionBase(String question,Long project,Long version,String principal,String phrase) {
        jdbc.update("""
            INSERT INTO qw_clarification_definition_base(clarification_id,project_id,principal_id,normalized_phrase,expected_personal_revision,base_version_id)
            VALUES (?,?,?,?,?,?) ON CONFLICT(clarification_id) DO NOTHING
            """,question,project,principal,UserSemanticPreferenceService.normalizePhrase(phrase),currentRevision(project,principal,phrase),version);
    }
    public int questionBase(String question,Long project,Long version,String principal,String phrase) {
        var base=jdbc.queryForMap("SELECT * FROM qw_clarification_definition_base WHERE clarification_id=?",question);
        if(!Objects.equals(project,base.get("project_id"))||!Objects.equals(version,base.get("base_version_id"))
                ||!Objects.equals(principal,base.get("principal_id"))||!Objects.equals(UserSemanticPreferenceService.normalizePhrase(phrase),base.get("normalized_phrase")))
            throw new SecurityException("Definition confirmation base mismatch");
        return ((Number)base.get("expected_personal_revision")).intValue();
    }

    @Transactional
    public Definition confirm(Confirmation c) {
        require(c.projectId()!=null&&text(c.principal())&&text(c.phrase())&&c.phrase().length()<=500,"Project, principal and bounded phrase required");
        require(text(c.text())&&c.text().length()<=20000,"Complete definition text required");
        require(c.snapshot()!=null&&c.snapshot().isObject(),"Definition source snapshot required");
        require(Set.of("ASSET_CONFIRMATION","TEXT_CONFIRMATION","PROJECT_ADOPTION").contains(c.sourceKind()),"Unknown confirmation source");
        require(text(c.label())&&c.label().length()<=500&&text(c.assetType())&&text(c.assetKey()),"Definition identity required");
        String normalized=UserSemanticPreferenceService.normalizePhrase(c.phrase());
        require(!normalized.isBlank()&&normalized.length()<=500,"Invalid phrase");
        String hash=PersonalDefinitionSnapshot.hash(Map.of("text",c.text().trim(),"assetType",c.assetType(),"assetKey",c.assetKey(),
            "label",c.label().trim(),"snapshot",c.snapshot(),"dependency",Objects.toString(c.dependencyFingerprint(),"")));
        // Every shared-source writer locks candidate coordination rows before a personal head.
        var previous=jdbc.queryForList("SELECT id FROM qw_user_semantic_preference WHERE project_id=? AND user_id=? AND normalized_phrase=?",
            c.projectId(),c.principal().trim(),normalized);
        var locks=new TreeSet<Long>();
        if(!previous.isEmpty())locks.addAll(sharedCandidates(((Number)previous.get(0).get("id")).longValue()));
        if("PROJECT_ADOPTION".equals(c.sourceKind())) {
            long adopted=c.snapshot().path("projectCandidate").path("id").asLong();
            require(adopted>0,"Shared adoption identity required");locks.add(adopted);
        }
        lockSharedCandidates(locks);
        jdbc.update("""
            INSERT INTO qw_user_semantic_preference(project_id,user_id,normalized_phrase,display_phrase,asset_type,asset_key,business_label,next_upgrade_prompt_at)
            VALUES (?,?,?,?,?,?,?,5) ON CONFLICT(project_id,user_id,normalized_phrase) DO NOTHING
            """,c.projectId(),c.principal().trim(),normalized,c.phrase().trim(),c.assetType(),c.assetKey(),c.label().trim());
        var head=jdbc.queryForMap("SELECT id,current_revision,archived FROM qw_user_semantic_preference WHERE project_id=? AND user_id=? AND normalized_phrase=? FOR UPDATE",
            c.projectId(),c.principal().trim(),normalized);
        long id=((Number)head.get("id")).longValue();int current=((Number)head.get("current_revision")).intValue();
        if(text(c.sourceId())) {
            var prior=jdbc.queryForList("SELECT preference_id,definition_revision,content_hash,sharing_choice FROM qw_personal_confirmation_receipt WHERE source_id=?",c.sourceId());
            if(!prior.isEmpty()) {
                var row=prior.get(0);
                require(((Number)row.get("preference_id")).longValue()==id&&row.get("content_hash").equals(hash),"Confirmation source already has different content");
                require(row.get("sharing_choice").equals(Objects.requireNonNullElse(c.sharing(),Sharing.PRIVATE).name()),"Confirmation source already has different authorization");
                return require(id,((Number)row.get("definition_revision")).intValue());
            }
        }
        if(c.expectedRevision()!=null)require(c.expectedRevision()==current,"Personal definition changed; refresh before confirmation");
        Definition old=current==0?null:require(id,current);
        Sharing share=Objects.requireNonNullElse(c.sharing(),Sharing.PRIVATE);
        // Scope/confirmation changes do not create a new computation or reset its valid use history.
        if(old!=null&&old.contentHash().equals(hash)&&!Boolean.TRUE.equals(head.get("archived"))) {
            if(old.sharing()!=share)authorize(id,current,share,c.principal(),c.sourceId());
            receipt(c.sourceId(),id,current,hash,share);
            return require(id,current);
        }
        int revision=current+1;
        jdbc.update("""
            INSERT INTO qw_user_semantic_definition_revision(preference_id,revision,definition_text,asset_type,asset_key,business_label,
                source_kind,source_id,base_version_id,definition_snapshot,content_hash,dependency_fingerprint)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
            """,id,revision,c.text().trim(),c.assetType(),c.assetKey(),c.label().trim(),c.sourceKind(),c.sourceId(),c.baseVersionId(),json(c.snapshot()),hash,c.dependencyFingerprint());
        jdbc.update("""
            INSERT INTO qw_user_semantic_authorization(preference_id,definition_revision,authorization_revision,choice,principal_id,source_id)
            VALUES (?,?,1,?,?,?)
            """,id,revision,share.name(),c.principal(),c.sourceId());
        jdbc.update("""
            INSERT INTO qw_user_semantic_representation(preference_id,source_revision,source_content_hash,dependency_fingerprint)
            VALUES (?,?,?,?)
            """,id,revision,hash,c.dependencyFingerprint());
        jdbc.update("""
            UPDATE qw_user_semantic_representation SET task_state='STALE',owner_token=NULL,lease_until=NULL,update_time=CURRENT_TIMESTAMP
            WHERE preference_id=? AND source_revision<>? AND task_state IN ('PENDING','RUNNING','RETRYABLE_FAILURE')
            """,id,revision);
        jdbc.update("""
            UPDATE qw_user_semantic_preference SET current_revision=?,archived=FALSE,display_phrase=?,asset_type=?,asset_key=?,business_label=?,
                hit_count=0,next_upgrade_prompt_at=5,upgrade_prompt_pending=FALSE,upgrade_dismissed=?,
                correction_count=correction_count+CASE WHEN current_revision>0 THEN 1 ELSE 0 END,update_time=CURRENT_TIMESTAMP
            WHERE id=?
            """,revision,c.phrase().trim(),c.assetType(),c.assetKey(),c.label().trim(),share==Sharing.DECLINED,id);
        receipt(c.sourceId(),id,revision,hash,share);
        if(events!=null)events.publishEvent(new Confirmed(id,revision));
        return require(id,revision);
    }

    private void receipt(String source,long id,int revision,String hash,Sharing sharing) {
        if(text(source))jdbc.update("INSERT INTO qw_personal_confirmation_receipt(source_id,preference_id,definition_revision,content_hash,sharing_choice) VALUES(?,?,?,?,?)",
            source,id,revision,hash,sharing.name());
    }

    @Transactional
    public void authorize(long id,int revision,Sharing choice,String principal,String sourceId) {
        lockSharedCandidates(sharedCandidates(id));
        var row=jdbc.queryForMap("SELECT user_id,current_revision,archived FROM qw_user_semantic_preference WHERE id=? FOR UPDATE",id);
        if(!Objects.equals(row.get("user_id"),principal))throw new SecurityException("Only the definition owner can authorize sharing");
        require(((Number)row.get("current_revision")).intValue()==revision&&!Boolean.TRUE.equals(row.get("archived")),"Stale personal revision");
        var current=require(id,revision);if(current.sharing()==choice)return;
        int auth=jdbc.queryForObject("SELECT COALESCE(max(authorization_revision),0)+1 FROM qw_user_semantic_authorization WHERE preference_id=? AND definition_revision=?",Integer.class,id,revision);
        jdbc.update("INSERT INTO qw_user_semantic_authorization VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,revision,auth,choice.name(),principal,sourceId);
        jdbc.update("UPDATE qw_user_semantic_preference SET upgrade_prompt_pending=FALSE,upgrade_dismissed=?,update_time=CURRENT_TIMESTAMP WHERE id=?",choice==Sharing.DECLINED,id);
    }
    private List<Long> sharedCandidates(long preference) {
        return jdbc.query("SELECT DISTINCT candidate_id FROM qw_project_definition_source WHERE preference_id=? ORDER BY candidate_id",
            (r,n)->r.getLong(1),preference);
    }
    private void lockSharedCandidates(Collection<Long> ids) {
        for(long candidate:ids)jdbc.queryForList("SELECT id FROM qw_project_definition_candidate WHERE id=? FOR UPDATE",candidate);
    }
    /** All contribution writers use the same candidate-before-personal order, including batches. */
    public void lockPreferences(Collection<Long> preferences) {
        var ordered=new TreeSet<>(preferences);
        var candidates=new TreeSet<Long>();
        for(long preference:ordered)candidates.addAll(sharedCandidates(preference));
        lockSharedCandidates(candidates);
        for(long preference:ordered)jdbc.queryForObject("SELECT id FROM qw_user_semantic_preference WHERE id=? FOR UPDATE",Long.class,preference);
    }
    @Transactional
    public void archive(Long project,String principal,String normalizedPhrase) {
        var ids=jdbc.query("SELECT id FROM qw_user_semantic_preference WHERE project_id=? AND user_id=? AND normalized_phrase=?",
            (r,n)->r.getLong(1),project,principal,normalizedPhrase);
        for(long id:ids)lockSharedCandidates(sharedCandidates(id));
        jdbc.update("UPDATE qw_user_semantic_preference SET archived=TRUE,upgrade_prompt_pending=FALSE,update_time=CURRENT_TIMESTAMP WHERE project_id=? AND user_id=? AND normalized_phrase=?",
            project,principal,normalizedPhrase);
        for(long id:ids)jdbc.update("UPDATE qw_project_definition_candidate SET evidence_revision=evidence_revision+1,row_revision=row_revision+1 WHERE id IN (SELECT candidate_id FROM qw_project_definition_source WHERE preference_id=?)",id);
    }
    public record Claimed(Definition definition,String token,int attempt) {}
    @Transactional
    public Optional<Claimed> claim(java.time.Duration lease) {
        String token=UUID.randomUUID().toString();
        var jobs=jdbc.query("""
            WITH due AS (
              SELECT r.preference_id,r.source_revision FROM qw_user_semantic_representation r
              JOIN qw_user_semantic_preference p ON p.id=r.preference_id AND p.current_revision=r.source_revision AND NOT p.archived
              WHERE ((r.task_state IN ('PENDING','RETRYABLE_FAILURE') AND r.next_attempt_at<=CURRENT_TIMESTAMP)
                OR (r.task_state='RUNNING' AND r.lease_until<CURRENT_TIMESTAMP))
              ORDER BY r.next_attempt_at,r.preference_id FOR UPDATE OF r SKIP LOCKED LIMIT 1)
            UPDATE qw_user_semantic_representation r SET task_state='RUNNING',owner_token=?,attempt_count=attempt_count+1,
              lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),update_time=CURRENT_TIMESTAMP
            FROM due WHERE r.preference_id=due.preference_id AND r.source_revision=due.source_revision
            RETURNING r.preference_id,r.source_revision,r.attempt_count
            """,(rs,n)->new long[]{rs.getLong(1),rs.getInt(2),rs.getInt(3)},token,lease.toMillis());
        if(jobs.isEmpty())return Optional.empty();var job=jobs.get(0);
        return Optional.of(new Claimed(require(job[0],Math.toIntExact(job[1])),token,Math.toIntExact(job[2])));
    }
    @Transactional
    public boolean complete(Claimed job,JsonNode structured,String expectedDependency) {
        require(structured!=null&&structured.isObject(),"Validated structure required");
        var d=job.definition();require(Objects.equals(d.dependencyFingerprint(),expectedDependency),"Worker dependency identity changed");var head=jdbc.queryForMap("SELECT current_revision,archived FROM qw_user_semantic_preference WHERE id=? FOR UPDATE",d.preferenceId());
        if(((Number)head.get("current_revision")).intValue()!=d.revision()||Boolean.TRUE.equals(head.get("archived")))return false;
        boolean changed=jdbc.update("""
            UPDATE qw_user_semantic_representation SET structured_json=?::jsonb,representation_state='STRUCTURED_ACTIVE',task_state='DONE',
                owner_token=NULL,lease_until=NULL,last_error=NULL,update_time=CURRENT_TIMESTAMP
            WHERE preference_id=? AND source_revision=? AND task_state='RUNNING' AND owner_token=? AND source_content_hash=?
                AND dependency_fingerprint IS NOT DISTINCT FROM ?
            """,json(structured),d.preferenceId(),d.revision(),job.token(),d.contentHash(),expectedDependency)==1;
        if(changed)jdbc.update("""
            INSERT INTO qw_user_semantic_structure_revision(preference_id,source_revision,representation_hash,structured_json)
            VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING
            """,d.preferenceId(),d.revision(),PersonalDefinitionSnapshot.hash(structured),json(structured));
        return changed;
    }
    public record Structure(String hash,JsonNode content) {}
    public Structure currentStructure(Definition d) {
        require("STRUCTURED_ACTIVE".equals(d.representation())&&d.structured()!=null,"Structured representation not active");
        return jdbc.query("SELECT representation_hash,structured_json::text FROM qw_user_semantic_structure_revision WHERE preference_id=? AND source_revision=? AND structured_json=?::jsonb",
            (rs,n)->new Structure(rs.getString(1),read(rs.getString(2))),d.preferenceId(),d.revision(),json(d.structured())).stream().findFirst()
            .orElseThrow(()->new IllegalStateException("STRUCTURED_PROVENANCE_UNAVAILABLE"));
    }
    public Structure structure(long id,int revision,String hash) {
        return jdbc.query("SELECT representation_hash,structured_json::text FROM qw_user_semantic_structure_revision WHERE preference_id=? AND source_revision=? AND representation_hash=?",
            (rs,n)->new Structure(rs.getString(1),read(rs.getString(2))),id,revision,hash).stream().findFirst()
            .orElseThrow(()->new IllegalArgumentException("Frozen structure identity unavailable"));
    }
    @Transactional
    public boolean fail(Claimed job,String errorCode,boolean needsConfirmation) {
        // No provider message or private prompt is written into the public job error.
        require(errorCode!=null&&errorCode.matches("[A-Z_]{1,128}"),"Stable error code required");
        long seconds=Math.min(21600L,60L*(1L<<Math.min(9,Math.max(0,job.attempt()-1))));
        return jdbc.update("""
            UPDATE qw_user_semantic_representation SET task_state=?,last_error=?,owner_token=NULL,lease_until=NULL,
                next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 second'),update_time=CURRENT_TIMESTAMP
            WHERE preference_id=? AND source_revision=? AND task_state='RUNNING' AND owner_token=? AND source_content_hash=?
            """,needsConfirmation?"NEEDS_RECONFIRMATION":"RETRYABLE_FAILURE",errorCode,seconds,job.definition().preferenceId(),
            job.definition().revision(),job.token(),job.definition().contentHash())==1;
    }

    @Transactional
    public boolean retainText(Claimed job) {
        return jdbc.update("""
            UPDATE qw_user_semantic_representation SET task_state='DONE',last_error='STRUCTURING_UNSUPPORTED_CAPABILITY',
                owner_token=NULL,lease_until=NULL,update_time=CURRENT_TIMESTAMP
            WHERE preference_id=? AND source_revision=? AND task_state='RUNNING' AND owner_token=? AND source_content_hash=?
            """,job.definition().preferenceId(),job.definition().revision(),job.token(),job.definition().contentHash())==1;
    }

    public List<Definition> applicable(Long project,String principal,String query) {
        if(project==null||!text(principal)||!text(query))return List.of();
        String normalized=UserSemanticPreferenceService.normalizePhrase(query);
        return jdbc.query(select()+" WHERE p.project_id=? AND p.user_id=? AND NOT p.archived AND d.revision=p.current_revision ORDER BY length(p.normalized_phrase) DESC,p.id",this::map,project,principal)
            .stream().filter(d->normalized.contains(UserSemanticPreferenceService.normalizePhrase(d.phrase()))).toList();
    }
    public Definition require(long id,int revision) {
        return jdbc.query(select()+" WHERE p.id=? AND d.revision=?",this::map,id,revision).stream().findFirst()
            .orElseThrow(()->new IllegalArgumentException("Personal definition revision not found"));
    }
    public Definition current(long id) {
        return jdbc.query(select()+" WHERE p.id=? AND d.revision=p.current_revision AND NOT p.archived",this::map,id).stream().findFirst()
            .orElseThrow(()->new IllegalArgumentException("Active personal definition not found"));
    }
    /** Historical plans may execute their frozen revision; newly recalled cases must use today's active meaning. */
    public boolean currentReference(Long project,String principal,cn.lgs.semevosql.semantic.domain.SemanticBlueprint.BindingDependency reference) {
        if(!"USER".equals(reference.getSource()))return !"PROJECT_CANDIDATE".equals(reference.getSource());
        if(reference.getSourceRecordId()==null||reference.getSourceRevision()==null)return false;
        try {
            var active=current(reference.getSourceRecordId());
            if(active.revision()!=reference.getSourceRevision()||!Objects.equals(principal,reference.getPrincipalId()))return false;
            cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay.requireSource(project,principal,reference,active);
            if(reference.getRepresentationCode()!=null) {
                if(reference.getRepresentationHash()==null)return false;
                if("METRIC".equals(active.assetType()))return active.contentHash().equals(reference.getRepresentationHash())
                    &&("p_"+active.preferenceId()+"_"+active.revision()).equals(reference.getRepresentationCode());
                var frozen=structure(active.preferenceId(),active.revision(),reference.getRepresentationHash()).content();
                if(!Objects.equals(active.contentHash(),frozen.path("sourceContentHash").asText()))return false;
            }
            return true;
        } catch(IllegalArgumentException|SecurityException unavailable){return false;}
    }
    private String select(){return """
        SELECT p.id,p.project_id,p.user_id,p.display_phrase,d.*,r.representation_state,r.task_state,r.structured_json,
        (SELECT a.choice FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision
          ORDER BY a.authorization_revision DESC LIMIT 1) AS sharing
        FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id
        JOIN qw_user_semantic_representation r ON r.preference_id=d.preference_id AND r.source_revision=d.revision
        """;}
    private Definition map(ResultSet rs,int ignored)throws SQLException {
        return new Definition(rs.getLong("id"),rs.getInt("revision"),rs.getLong("project_id"),rs.getString("user_id"),rs.getString("display_phrase"),
            rs.getString("definition_text"),rs.getString("asset_type"),rs.getString("asset_key"),rs.getString("business_label"),rs.getString("source_kind"),
            rs.getString("source_id"),rs.getObject("base_version_id",Long.class),read(rs.getString("definition_snapshot")),rs.getString("content_hash"),
            rs.getString("dependency_fingerprint"),rs.getString("representation_state"),rs.getString("task_state"),read(rs.getString("structured_json")),Sharing.valueOf(rs.getString("sharing")));
    }
    public static String json(Object value){try{return CanonicalJson.write(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static JsonNode read(String value){if(value==null)return null;try{return JsonUtil.getObjectMapper().readTree(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static boolean text(String value){return value!=null&&!value.isBlank();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
