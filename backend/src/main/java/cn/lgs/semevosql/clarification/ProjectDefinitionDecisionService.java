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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Decisions capture precisely reviewed inputs. They authorize the normal publisher, never direct public writes. */
@Service
public class ProjectDefinitionDecisionService {
    public enum Action { EARLY_CREATE, RENAME, OVERWRITE, ASSOCIATE, REJECT, DEFER, RESUME }
    public record Request(Action action,String reason,int contentRevision,int evidenceRevision,
            long baseVersion,String catalogHash,String contributionFingerprint,String representationHash,
            String targetAsset,String publicName) {}
    private final JdbcTemplate jdbc;
    private final SemanticProjectRepository projects;
    private final SemanticCatalogRepository catalogs;
    private final ProjectDefinitionContributions contributions;
    private final ProjectScopeService scope;
    private final OperatorContext.Resolver operators;
    private final ProjectSemanticAliasService aliases;
    public ProjectDefinitionDecisionService(JdbcTemplate jdbc,SemanticProjectRepository projects,SemanticCatalogRepository catalogs,
            ProjectDefinitionContributions contributions,ProjectScopeService scope,OperatorContext.Resolver operators,
            ProjectSemanticAliasService aliases) {
        this.jdbc=jdbc;this.projects=projects;this.catalogs=catalogs;this.contributions=contributions;
        this.scope=scope;this.operators=operators;this.aliases=aliases;
    }
    @Transactional
    public Map<String,Object> decide(long project,long candidate,Request request,OperatorContext operator) {
        scope.requireProject(project,operator);
        if(!operators.administrator(operator))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有项目管理员可以审批公共口径");
        if(request==null||request.action()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)
            throw new IllegalArgumentException("需要选择操作并填写审批理由");
        if(operator.idempotencyKey().length()>255)throw new IllegalArgumentException("审批请求标识过长");
        String hash=PersonalDefinitionSnapshot.hash(JsonUtil.getObjectMapper().valueToTree(Map.of("candidate",candidate,"request",request)));
        projects.lockProject(project);
        var prior=jdbc.queryForList("SELECT * FROM qw_project_definition_decision WHERE project_id=? AND operator=? AND idempotency_key=?",project,operator.operator(),operator.idempotencyKey());
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.get(0).get("request_hash")))conflict("同一请求不能改成另一项审批");
            return receipt(prior.get(0));
        }
        var rows=jdbc.queryForList("SELECT * FROM qw_project_definition_candidate WHERE id=? AND project_id=? FOR UPDATE",candidate,project);
        if(rows.size()!=1)throw new IllegalArgumentException("项目候选不存在");
        var c=rows.get(0);var totals=contributions.totals(candidate,request.contentRevision(),project);
        var base=projects.findVersion(request.baseVersion()).orElseThrow(()->new IllegalArgumentException("公共基线不存在"));
        var head=projects.findProject(project).orElseThrow();
        if(!Objects.equals(head.getActiveVersionId(),request.baseVersion())||!Objects.equals(base.getProjectId(),project)
                ||!Objects.equals(base.getCatalogHash(),request.catalogHash())
                ||((Number)c.get("content_revision")).intValue()!=request.contentRevision()
                ||((Number)c.get("evidence_revision")).intValue()!=request.evidenceRevision()
                ||!totals.fingerprint().equals(request.contributionFingerprint()))conflict("内容、贡献或公共基线已经变化，请刷新后重新审核");
        if("PUBLISHED".equals(c.get("lifecycle")))conflict("该候选已发布，后续变更需要新的候选修订");
        boolean dismiss=Set.of(Action.REJECT,Action.DEFER,Action.RESUME).contains(request.action());
        var snapshot=catalogs.loadCatalog(project,request.baseVersion());
        var alignment=read(c.get("assessment_json")).path("alignment");
        String name=request.publicName()==null||request.publicName().isBlank()?Objects.toString(c.get("business_name")):request.publicName().trim();
        String target=request.targetAsset();
        String asset=null;
        if(!dismiss) {
            if(totals.authorizedSources()==0||c.get("structured_json")==null||!"DONE".equals(c.get("assessment_state"))
                    ||!Objects.equals(c.get("content_revision"),c.get("assessed_content_revision"))
                    ||!Objects.equals(c.get("evidence_revision"),c.get("assessed_evidence_revision"))
                    ||!Objects.equals(c.get("assessed_base_version_id"),request.baseVersion())
                    ||!Objects.equals(c.get("assessed_catalog_hash"),request.catalogHash())
                    ||!totals.fingerprint().equals(c.get("assessed_contribution_fingerprint"))
                    ||!Objects.equals(c.get("representation_hash"),request.representationHash())
                    ||!read(c.get("assessment_json")).path("decision").path("administratorMayApprove").asBoolean())
                conflict("结构、依赖、授权或评估尚未有效，管理员也不能跳过");
            var structure=read(c.get("structured_json"));String relation=alignment.path("relation").asText();
            if(request.action()==Action.EARLY_CREATE && !"NEW".equals(relation))conflict("已有关联公共口径，请明确选择覆盖、关联或另建名称");
            if(Set.of(Action.OVERWRITE,Action.ASSOCIATE).contains(request.action())) {
                var metric=snapshot.getMetrics().stream().filter(m->Objects.equals(m.getMetricCode(),target)&&m.getStatus()==SemanticAssetStatus.ENABLED).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("必须选择当前有效的公共指标"));
                if(!metric.getModelCode().equals(structure.path("metric").path("entity").asText()))conflict("候选与目标模型角色不同，不能直接覆盖");
                if(request.action()==Action.ASSOCIATE && (!"EQUIVALENT".equals(relation)
                    ||!java.util.stream.StreamSupport.stream(alignment.path("targets").spliterator(),false).anyMatch(t->target.equals(t.asText()))))
                    conflict("该目标尚未被验证为计算含义等价");
                asset=target;name=metric.getBusinessName();
            } else {
                if(name.length()>255)throw new IllegalArgumentException("公共名称过长");
                String normalized=UserSemanticPreferenceService.normalizePhrase(name);
                if(snapshot.getMetrics().stream().anyMatch(m->normalized.equals(UserSemanticPreferenceService.normalizePhrase(m.getBusinessName())))
                    ||aliases.applicable(project,request.baseVersion(),name).stream().anyMatch(v->normalized.equals(v.normalizedPhrase())))
                    conflict("该名称已有公共含义，请选择另一个明确名称或覆盖现有指标");
                asset=ProjectDefinitionCatalogMaterializer.assetCode(candidate,structure);
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_project_definition_publication WHERE candidate_id=? AND state IN ('PENDING','BUILDING','RETRYABLE_FAILURE'))",Boolean.class,candidate)))
                conflict("已有发布正在处理，请先查看其结果");
        }
        JsonNode seen=JsonUtil.getObjectMapper().valueToTree(Map.of("request",request,"candidateText",c.get("definition_text"),"contentHash",c.get("content_hash"),"contributions",totals));
        long decision=jdbc.queryForObject("""
            INSERT INTO qw_project_definition_decision(candidate_id,project_id,action,operator,operator_source,idempotency_key,request_hash,reason,seen_inputs)
            VALUES(?,?,?,?,?,?,?,?,?::jsonb) RETURNING id
            """,Long.class,candidate,project,request.action().name(),operator.operator(),operator.source(),operator.idempotencyKey(),hash,request.reason().trim(),PersonalSemanticDefinitionStore.json(seen));
        if(dismiss) {
            jdbc.update("UPDATE qw_project_definition_publication SET state='STALE',owner_token=NULL,lease_until=NULL,last_error='ADMINISTRATOR_DECISION_CHANGED' WHERE candidate_id=? AND state IN ('PENDING','BUILDING','RETRYABLE_FAILURE')",candidate);
            jdbc.update("UPDATE qw_project_definition_candidate SET lifecycle=?,blocked_reason=?,approved_decision_id=NULL,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
                request.action()==Action.REJECT?"REJECTED":request.action()==Action.RESUME?"ACCUMULATING":"NEEDS_ADMIN_REVIEW",
                request.action()==Action.RESUME?null:"ADMINISTRATOR_"+request.action(),candidate);
            if(request.action()==Action.RESUME)
                jdbc.update("UPDATE qw_project_definition_candidate SET assessment_state='PENDING',assessment_owner_token=NULL,assessment_lease_until=NULL,assessment_next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",candidate);
        } else {
            jdbc.update("""
                INSERT INTO qw_project_definition_publication(candidate_id,project_id,content_revision,evidence_revision,contribution_fingerprint,
                  base_version_id,base_catalog_hash,source_structure,source_representation_hash,public_asset_key,operator,operator_source,reason,
                  decision_id,action,target_asset_key,public_name)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?)
                """,candidate,project,request.contentRevision(),request.evidenceRevision(),totals.fingerprint(),request.baseVersion(),request.catalogHash(),
                c.get("structured_json").toString(),request.representationHash(),asset,operator.operator(),operator.source(),request.reason().trim(),decision,request.action().name(),target,name);
            jdbc.update("UPDATE qw_project_definition_candidate SET lifecycle='READY_FOR_PUBLISH',blocked_reason=NULL,approved_decision_id=?,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",decision,candidate);
        }
        return receipt(jdbc.queryForMap("SELECT * FROM qw_project_definition_decision WHERE id=?",decision));
    }
    private Map<String,Object> receipt(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);result.put("seen_inputs",read(row.get("seen_inputs")));return result;
    }
    private JsonNode read(Object value) {
        if(value==null)return JsonUtil.getObjectMapper().createObjectNode();
        try{return JsonUtil.getObjectMapper().readTree(value.toString());}catch(Exception invalid){throw new IllegalStateException("Invalid decision inputs",invalid);}
    }
    private void conflict(String reason){throw new ResponseStatusException(HttpStatus.CONFLICT,reason);}
}
