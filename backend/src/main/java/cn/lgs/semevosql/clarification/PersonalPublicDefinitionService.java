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
import cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Identity-based reminders before approval/execution; model selection never grants update or sharing consent. */
@Service
public class PersonalPublicDefinitionService {
    public static final String TYPE="PERSONAL_PUBLIC_UPDATE";
    private final JdbcTemplate jdbc;
    private final PersonalSemanticDefinitionStore definitions;
    private final SemanticBindingTargetValidator targets;
    private final SemanticProjectRepository projects;
    private final SemanticCatalogLookupService catalogLookup;
    public PersonalPublicDefinitionService(JdbcTemplate jdbc,PersonalSemanticDefinitionStore definitions,
            SemanticBindingTargetValidator targets,SemanticProjectRepository projects,SemanticCatalogLookupService catalogLookup) {
        this.jdbc=jdbc;this.definitions=definitions;this.targets=targets;this.projects=projects;this.catalogLookup=catalogLookup;
    }
    public record Difference(PersonalSemanticDefinitionStore.Definition personal,String type,String key,
        PersonalDefinitionSnapshot.Captured current,String publicText) {}

    public Optional<Difference> next(Long project,Long version,String principal,String query,String runId) {
        var applicable=definitions.applicable(project,principal,query);
        if(applicable.isEmpty()) return Optional.empty();
        var relations=new LinkedHashMap<Long,List<Target>>();
        applicable.forEach(d->relations.put(d.preferenceId(),correspondences(d)));
        var refs=applicable.stream().flatMap(d->relations.get(d.preferenceId()).stream()
            .map(t->new SemanticCatalogLookupService.BindingRef(new SemanticCatalogLookupService.AssetRef(t.type(),t.key()),d.phrase()))).toList();
        var textPhrases=applicable.stream().filter(d->"TEXT_DEFINITION".equals(d.assetType()))
            .map(d->new SemanticCatalogLookupService.BindingPhrase("METRIC",d.phrase())).toList();
        var explicit=catalogLookup.loadRuntimeBindings(project,version,refs,textPhrases,query);
        for(var personal:applicable) {
            if(RuntimeBindingApplicability.shadowed(explicit,query,personal.assetType(),personal.assetKey(),personal.phrase())) continue;
            for(var target:relations.get(personal.preferenceId())) {
                var current=targets.captureAsset(project,version,target.type(),target.key());
                if(sameMeaning(personal,current)||acknowledged(personal,target.type(),target.key(),current.dependencyFingerprint())
                    ||temporaryAnswer(runId,personal,current.dependencyFingerprint()))continue;
                return Optional.of(new Difference(personal,target.type(),target.key(),current,describe(current.snapshot())));
            }
        }
        return Optional.empty();
    }
    /** Ask only about private definitions actually selected, including authorized soft recall with a different phrase. */
    public Optional<Difference> nextSelected(Long project,Long version,String principal,
            List<SemanticBlueprint.BindingDependency> selected,String runId) {
        for(var reference:selected) {
            if(!"USER".equals(reference.getSource()))continue;
            if(reference.getSourceRecordId()==null||reference.getSourceRevision()==null
                    ||!Objects.equals(principal,reference.getPrincipalId()))
                throw new SecurityException("Selected private definition revision required");
            var personal=definitions.require(reference.getSourceRecordId(),reference.getSourceRevision());
            cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay.requireSource(project,principal,reference,personal);
            var head=definitions.current(personal.preferenceId());
            if(head.revision()!=personal.revision()||!head.contentHash().equals(personal.contentHash()))stale();
            for(var target:correspondences(personal)) {
                var current=targets.captureAsset(project,version,target.type(),target.key());
                if(sameMeaning(personal,current)||acknowledged(personal,target.type(),target.key(),current.dependencyFingerprint())
                    ||temporaryAnswer(runId,personal,current.dependencyFingerprint()))continue;
                return Optional.of(new Difference(personal,target.type(),target.key(),current,describe(current.snapshot())));
            }
        }
        return Optional.empty();
    }
    private record Target(String type,String key) {}
    private List<Target> correspondences(PersonalSemanticDefinitionStore.Definition d) {
        if(!"TEXT_DEFINITION".equals(d.assetType()))return List.of(new Target(d.assetType(),d.assetKey()));
        // Only an actual published source association proves the relation. Same names are not identity.
        return jdbc.query("""
            SELECT DISTINCT c.public_asset_key FROM qw_project_definition_source s
            JOIN qw_project_definition_candidate c ON c.id=s.candidate_id
            WHERE s.preference_id=? AND s.definition_revision=? AND c.project_id=?
              AND c.lifecycle='PUBLISHED' AND c.public_asset_key IS NOT NULL
            """,(rs,n)->new Target("METRIC",rs.getString(1)),d.preferenceId(),d.revision(),d.projectId());
    }
    static boolean sameMeaning(PersonalSemanticDefinitionStore.Definition d,PersonalDefinitionSnapshot.Captured current) {
        if(!"TEXT_DEFINITION".equals(d.assetType()))return Objects.equals(d.dependencyFingerprint(),current.dependencyFingerprint());
        if(d.structured()==null||!"personal-metric-1.1".equals(d.structured().path("protocol").asText()))return false;
        try {
            var catalog=catalog(current.snapshot(),d.projectId(),current.versionId());
            var personal=OfflineCatalogProtocol.projectPrivateMetric(d.structured().path("metric"),catalog);
            var published=JsonUtil.getObjectMapper().convertValue(current.snapshot().path("target"),SemanticCatalogSnapshot.Metric.class);
            if(!ProjectDefinitionAssessor.sameCalculation(personal,published))return false;
            personal.setMetricCode(published.getMetricCode());
            catalog.setMetrics(new ArrayList<>(List.of(personal)));
            return current.dependencyFingerprint().equals(PersonalDefinitionSnapshot.capture(catalog,"METRIC",personal.getMetricCode()).dependencyFingerprint());
        } catch(RuntimeException unavailable){return false;}
    }
    private static SemanticCatalogSnapshot catalog(JsonNode snapshot,Long project,Long version) {
        var mapper=JsonUtil.getObjectMapper();return SemanticCatalogSnapshot.builder().projectId(project).projectVersionId(version)
            .models(List.of(mapper.convertValue(snapshot.path("model"),SemanticCatalogSnapshot.Model.class)))
            .columns(mapper.convertValue(snapshot.path("columns"),mapper.getTypeFactory().constructCollectionType(List.class,SemanticCatalogSnapshot.Column.class)))
            .grains(mapper.convertValue(snapshot.path("grains"),mapper.getTypeFactory().constructCollectionType(List.class,SemanticCatalogSnapshot.Grain.class)))
            .rules(mapper.convertValue(snapshot.path("rules"),mapper.getTypeFactory().constructCollectionType(List.class,SemanticCatalogSnapshot.Rule.class))).build();
    }
    public boolean acknowledged(PersonalSemanticDefinitionStore.Definition d,String type,String key,String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM qw_personal_public_choice WHERE project_id=? AND principal_id=? AND preference_id=?
              AND personal_revision=? AND personal_content_hash=? AND asset_type=? AND asset_key=?
              AND public_meaning_fingerprint=? AND choice IN ('KEEP_PERSONAL','ADOPT_PUBLIC'))
            """,Boolean.class,d.projectId(),d.principal(),d.preferenceId(),d.revision(),d.contentHash(),type,key,fingerprint));
    }
    private boolean temporaryAnswer(String run,PersonalSemanticDefinitionStore.Definition d,String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM qw_personal_public_choice c JOIN qw_runtime_clarification q USING(clarification_id)
              WHERE q.run_id=? AND c.preference_id=? AND c.personal_revision=? AND c.public_meaning_fingerprint=?
                AND c.choice='OTHER' AND q.selected_scope='QUERY')
            """,Boolean.class,run,d.preferenceId(),d.revision(),fingerprint));
    }
    public Set<Long> temporaryOverrides(String run) {
        return new HashSet<>(jdbc.query("""
            SELECT c.preference_id FROM qw_personal_public_choice c JOIN qw_runtime_clarification q USING(clarification_id)
              WHERE q.run_id=? AND c.choice='OTHER' AND q.selected_scope='QUERY'
            """,(rs,n)->rs.getLong(1),run));
    }
    public void freeze(String question,Difference difference) {
        var d=difference.personal();
        var head=definitions.current(d.preferenceId());
        if(head.revision()!=d.revision()||!head.contentHash().equals(d.contentHash()))stale();
        jdbc.update("""
            INSERT INTO qw_personal_public_question(clarification_id,project_id,principal_id,preference_id,personal_revision,
                personal_content_hash,asset_type,asset_key,public_version_id,public_meaning_fingerprint,
                public_definition_text,public_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
            ON CONFLICT(clarification_id) DO NOTHING
            """,question,d.projectId(),d.principal(),d.preferenceId(),d.revision(),d.contentHash(),difference.type(),difference.key(),
            difference.current().versionId(),difference.current().dependencyFingerprint(),difference.publicText(),PersonalSemanticDefinitionStore.json(difference.current().snapshot()));
    }
    /** Invoked inside the existing answer transaction, after owner/revision checks. */
    public void answer(RuntimeClarification question,RuntimeClarificationService.AnswerCommand answer) {
        if("CANCEL".equals(answer.selectedOption()))return;
        var base=jdbc.queryForMap("SELECT * FROM qw_personal_public_question WHERE clarification_id=?",question.clarificationId());
        long project=((Number)base.get("project_id")).longValue();long id=((Number)base.get("preference_id")).longValue();
        String principal=base.get("principal_id").toString();
        if(!principal.equals(answer.answeredBy()))throw new SecurityException("Only the personal definition owner can choose its update");
        projects.lockProject(project);definitions.lockPreferences(List.of(id));
        var d=definitions.current(id);
        if(!Objects.equals(d.projectId(),project)||!Objects.equals(d.principal(),principal)
                ||d.revision()!=((Number)base.get("personal_revision")).intValue()||!d.contentHash().equals(base.get("personal_content_hash")))stale();
        String type=base.get("asset_type").toString(),key=base.get("asset_key").toString();
        var latest=targets.captureAsset(project,targets.activeVersionId(project),type,key);
        if(!latest.dependencyFingerprint().equals(base.get("public_meaning_fingerprint")))stale();
        String choice=answer.selectedOption();
        if(Set.of("KEEP_PERSONAL","ADOPT_PUBLIC").contains(choice)&&answer.customAnswer()!=null&&!answer.customAnswer().isBlank())
            throw new IllegalArgumentException("A new meaning must use the Other option");
        if(!"OTHER".equals(choice)&&answer.scope()!=SemanticBindingScope.QUERY)
            throw new IllegalArgumentException("Choosing an existing definition does not authorize promotion");
        if("KEEP_PERSONAL".equals(choice)&&"METRIC".equals(d.assetType()))
            cn.lgs.semevosql.semantic.application.FrozenPersonalMetric.project(d,catalog(latest.snapshot(),project,latest.versionId()));
        var effective=d;
        if("ADOPT_PUBLIC".equals(choice)) {
            // Copy the displayed immutable definition, never a mutable follow-latest pointer.
            effective=definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,principal,d.phrase(),
                base.get("public_definition_text").toString(),type,key,d.phrase(),"ASSET_CONFIRMATION",
                "clarification:"+question.clarificationId(),((Number)base.get("public_version_id")).longValue(),
                read(base.get("public_snapshot")),latest.dependencyFingerprint(),PersonalSemanticDefinitionStore.Sharing.PRIVATE,d.revision()));
        } else if("OTHER".equals(choice)&&answer.scope()!=SemanticBindingScope.QUERY) {
            var snapshot=JsonUtil.getObjectMapper().createObjectNode().put("completeDefinitionRecorded",true).put("confirmedText",answer.customAnswer());
            effective=definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,principal,d.phrase(),answer.customAnswer(),
                "TEXT_DEFINITION",UserSemanticPreferenceService.normalizePhrase(d.phrase()),d.phrase(),"TEXT_CONFIRMATION",
                "clarification:"+question.clarificationId(),((Number)base.get("public_version_id")).longValue(),snapshot,null,
                answer.scope()==SemanticBindingScope.PROJECT?PersonalSemanticDefinitionStore.Sharing.ALLOWED:PersonalSemanticDefinitionStore.Sharing.PRIVATE,d.revision()));
        }
        jdbc.update("""
            INSERT INTO qw_personal_public_choice(clarification_id,project_id,principal_id,preference_id,personal_revision,
                personal_content_hash,asset_type,asset_key,public_version_id,public_meaning_fingerprint,choice)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,question.clarificationId(),project,principal,effective.preferenceId(),effective.revision(),effective.contentHash(),type,key,
            base.get("public_version_id"),latest.dependencyFingerprint(),choice);
    }
    static String describe(JsonNode snapshot) {
        var target=snapshot.path("target");var text=new ArrayList<String>();
        text.add(target.path("businessName").asText(target.path("metricCode").asText()));
        var labels=new LinkedHashMap<String,String>();labels.put("description","完整说明");labels.put("expression","计算公式");
        labels.put("aggregation","聚合方式");labels.put("filterExpression","固定条件");labels.put("timeColumn","统计时间");labels.put("unit","单位");
        labels.forEach((field,label)->{if(target.hasNonNull(field)&&!target.path(field).asText().isBlank())text.add(label+"："+target.path(field).asText());});
        text.add("统计对象："+snapshot.path("model").path("businessName").asText());
        if(snapshot.path("model").hasNonNull("description"))text.add("对象说明："+snapshot.path("model").path("description").asText());
        if(snapshot.path("model").hasNonNull("sourceJson")) {
            var source=read(snapshot.path("model").path("sourceJson").asText());
            for(var filter:source.path("filters"))text.add("固定对象条件："+filter.path("attribute").asText()+" "+filter.path("op").asText()+" "+filter.path("value").asText());
        }
        for(var grain:snapshot.path("grains"))text.add("粒度："+grain.path("keyColumns").asText());
        return String.join("；",text);
    }
    private static JsonNode read(Object value){try{return JsonUtil.getObjectMapper().readTree(value.toString());}catch(Exception e){throw new IllegalArgumentException("Invalid immutable public snapshot",e);}}
    private static void stale(){throw new ResponseStatusException(HttpStatus.CONFLICT,"所见个人或公共口径已改变，请刷新差异并重新确认");}
}
