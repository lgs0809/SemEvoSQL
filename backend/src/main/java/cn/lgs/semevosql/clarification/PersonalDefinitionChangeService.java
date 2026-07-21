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

import cn.lgs.semevosql.exception.ModelOutputInvalidException;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import cn.lgs.semevosql.semantic.application.SemanticPlanningOutcome;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Revisioned proposals and submitted HITL receipts, separate from corrections to data-query requirements. */
@Service
public class PersonalDefinitionChangeService {
    public static final String TYPE="SEMANTIC_DEFINITION_UPDATE";
    private final JdbcTemplate jdbc;
    private final PersonalSemanticDefinitionStore definitions;
    private final UserSemanticPreferenceService preferences;
    private final SemanticDocumentExtractionClient model;
    private cn.lgs.semevosql.learning.QueryCaseQuarantineService quarantine;
    @org.springframework.beans.factory.annotation.Autowired
    public void quarantine(cn.lgs.semevosql.learning.QueryCaseQuarantineService service){this.quarantine=service;}
    public PersonalDefinitionChangeService(JdbcTemplate jdbc,PersonalSemanticDefinitionStore definitions,
            UserSemanticPreferenceService preferences,SemanticDocumentExtractionClient model) {
        this.jdbc=jdbc;this.definitions=definitions;this.preferences=preferences;this.model=model;
    }
    public record HistoryUse(String runId,int definitionRevision,boolean shared,String question,String queriedAt) {
        public HistoryUse(String runId,int revision,boolean shared){this(runId,revision,shared,null,null);}
        public String display() {
            String label=question==null||question.isBlank()?"已保存的历史查询":question.replace('\n',' ').trim();
            if(label.length()>180)label=label.substring(0,180)+"…";
            return (queriedAt==null?"":queriedAt+"：")+label;
        }
    }
    public record Proposal(long preferenceId,int revision,int authorizationRevision,String contentHash,
            String phrase,String oldText,PersonalSemanticDefinitionStore.Sharing oldSharing,String newText,
            List<HistoryUse> history,List<String> affectedRuns,ModelCallResult modelEvidence,String previousQuestionId) {
        public Proposal(long id,int revision,int authorizationRevision,String hash,String phrase,String oldText,
                PersonalSemanticDefinitionStore.Sharing sharing,String newText,List<HistoryUse> history,
                List<String> affectedRuns,ModelCallResult evidence) {
            this(id,revision,authorizationRevision,hash,phrase,oldText,sharing,newText,history,affectedRuns,evidence,null);
        }
        public Proposal {history=List.copyOf(history);affectedRuns=List.copyOf(affectedRuns);}
        public boolean scopeOnly(){return newText==null;}
    }

    /** No transaction or write is held open during a model invocation. */
    public Proposal propose(long project,String principal,String currentMessage,String completeRequest,Long deadline) {
        var candidates=preferences.applicableDefinitions(project,principal,currentMessage+"\n"+completeRequest);
        if(candidates.isEmpty())throw new IllegalArgumentException("请说明要修改的本人已确认口径名称。");
        var versions=new HashMap<Long,Integer>();
        var histories=new HashMap<Long,List<HistoryUse>>();
        var visible=new ArrayList<Map<String,Object>>();
        for(var definition:candidates) {
            versions.put(definition.preferenceId(),authorizationRevision(definition));
            var history=history(definition);histories.put(definition.preferenceId(),history);
            visible.add(Map.of("definitionId",definition.preferenceId(),"sourceRevision",definition.revision(),
                "sourceContentHash",definition.contentHash(),"phrase",definition.phrase(),"completeDefinition",definition.text(),
                "sharing",definition.sharing().name(),"actualQueryUses",history));
        }
        String prompt="""
            Identify the existing personal definition the user wants to change. The supplied definitions belong to
            the authenticated current user and project. Do not execute SQL, save a definition or grant consent.
            A sharing/save-scope change is independent of whether a historical data answer was correct.
            Return exactly one object with these six fields:
            {"targetDefinitionId":1,"sourceRevision":1,"sourceContentHash":"supplied hash",
             "definitionText":null,"intentExcerpt":"verbatim current-message excerpt","affectedRunIds":[]}.
            Use exactly one supplied id/revision/hash. definitionText=null means computation unchanged, including
            requests to share an existing meaning or use it privately. Do not copy historical text into a new text
            definition for a scope-only change. A changed complete definition must be a verbatim excerpt of the
            current message; do not invent omitted formula, conditions, time ownership or units.
            affectedRunIds only contains supplied actual query ids explicitly denied/corrected by this message,
            or whose sharing consent is explicitly withdrawn. Sharing withdrawal does not deny result correctness.
            Asking to include valid past uses in sharing never denies them: keep affectedRunIds empty.
            History can include older calculation revisions. Never transfer old formula uses into a new formula;
            selecting current valid history only shares uses of the current calculation revision.
            The program presents full old/new meaning, future scope and history choices for human confirmation.
            Your output is a proposal, never an authorization to apply it. No SQL or requested data result is needed.
            Submitted follow-up user answers take precedence over the original request. They are authenticated
            source text, not an authorization: always present a new proposal for confirmation before saving.
            """;
        var call=model.complete(ModelCallPurpose.SEMANTIC_PLANNING,prompt,
            PersonalSemanticDefinitionStore.json(Map.of("currentUserMessage",currentMessage,"completeRequest",completeRequest,
                "confirmedDefinitions",visible)),deadline==null?null:Duration.ofMillis(Math.max(1,deadline-System.currentTimeMillis())));
        try {
            JsonNode root=JsonUtil.getObjectMapper().reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.response());
            if(!root.isObject()||root.size()!=6||!root.path("targetDefinitionId").isIntegralNumber()
                    ||!root.path("sourceRevision").isIntegralNumber()||!root.path("sourceContentHash").isTextual()
                    ||!root.has("definitionText")||!root.path("intentExcerpt").isTextual()||!root.path("affectedRunIds").isArray())
                throw new IllegalArgumentException("Invalid definition-change proposal");
            var selected=candidates.stream().filter(d->d.preferenceId()==root.path("targetDefinitionId").asLong())
                .findFirst().orElseThrow(()->new IllegalArgumentException("Definition target outside authenticated candidates"));
            if(selected.revision()!=root.path("sourceRevision").asInt()||!selected.contentHash().equals(root.path("sourceContentHash").asText()))
                throw new IllegalArgumentException("Definition target revision mismatch");
            String excerpt=root.path("intentExcerpt").asText();
            if(excerpt.isBlank()||!currentMessage.contains(excerpt))throw new IllegalArgumentException("Change intent must be current user text");
            String text=null;
            if(!root.path("definitionText").isNull()) {
                if(!root.path("definitionText").isTextual())throw new IllegalArgumentException("Invalid proposed definition text");
                text=root.path("definitionText").asText();
                if(text.isBlank()||text.length()>20000||!currentMessage.contains(text)||!text.contains(selected.phrase()))
                    throw new IllegalArgumentException("A changed definition must preserve the complete current user excerpt");
                if(text.equals(selected.text()))text=null;
            }
            var history=histories.get(selected.preferenceId());
            var allowed=history.stream().map(HistoryUse::runId).collect(java.util.stream.Collectors.toSet());
            var affected=new ArrayList<String>();
            for(var id:root.path("affectedRunIds")) {
                if(!id.isTextual()||!allowed.contains(id.textValue())||affected.contains(id.textValue()))
                    throw new IllegalArgumentException("History target outside owned query uses");
                affected.add(id.textValue());
            }
            return new Proposal(selected.preferenceId(),selected.revision(),versions.get(selected.preferenceId()),selected.contentHash(),
                selected.phrase(),selected.text(),selected.sharing(),text,history,affected,call);
        }catch(Exception invalid) {
            throw new ModelOutputInvalidException("模型未能定位这次口径修改，请重试或补充具体口径名称和完整含义。",invalid);
        }
    }

    public SemanticPlanningOutcome.ClarificationRequired clarification(Proposal p) {
        var options=new ArrayList<SemanticPlanningOutcome.Option>();
        options.add(new SemanticPlanningOutcome.Option("CONFIRM_FUTURE","确认修改，只影响以后的使用",TYPE,null));
        if(p.scopeOnly())options.add(new SemanticPlanningOutcome.Option("CONFIRM_VALID_HISTORY",
            "允许项目分享，并将当前计算口径的既有有效使用纳入项目建议",TYPE,null));
        if(!p.affectedRuns().isEmpty())options.add(new SemanticPlanningOutcome.Option("CONFIRM_INVALIDATE_TARGETS",
            "确认修改，并否定所列查询对这条口径的使用",TYPE,null));
        if(p.scopeOnly()&&!p.affectedRuns().isEmpty())options.add(new SemanticPlanningOutcome.Option("CONFIRM_WITHDRAW_TARGETS",
            "只撤回所列历史使用的共享认可，查询结果保留",TYPE,null));
        options.add(new SemanticPlanningOutcome.Option("OTHER","其他，我再补充",null,null));
        options.add(new SemanticPlanningOutcome.Option("CANCEL","取消本次修改",null,null));
        String oldScope=p.oldSharing()==PersonalSemanticDefinitionStore.Sharing.ALLOWED?"允许分享为项目建议":"本人使用";
        String target=p.scopeOnly()?"计算含义保持完全不变。":p.newText();
        String history=p.history().isEmpty()?"没有既有有效查询使用。":p.history().size()+"次既有有效查询使用："+
            p.history().stream().map(use->use.display()+(use.definitionRevision()==p.revision()?"":"（此前计算口径）"))
                .collect(java.util.stream.Collectors.joining("\n"));
        String refresh="";
        if(p.previousQuestionId()!=null) {
            var previous=frozen(p.previousQuestionId());
            refresh="刚才的问题已过期，旧答案没有保存。请按下面的最新内容重新确认。\n刚才看到的定义："+
                previous.oldText()+"\n刚才看到的范围："+
                (previous.oldSharing()==PersonalSemanticDefinitionStore.Sharing.ALLOWED?"允许分享为项目建议":"本人使用")+"。\n";
        }
        return new SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING",
            refresh+"请确认“"+p.phrase()+"”的修改。\n原定义："+p.oldText()+"\n原范围："+oldScope+"。\n修改后："+target+"\n"+history+
                (p.affectedRuns().isEmpty()?"":"\n本次拟影响的查询："+p.history().stream().filter(use->p.affectedRuns().contains(use.runId()))
                    .map(HistoryUse::display).collect(java.util.stream.Collectors.joining("\n"))),options,
            "选择保存范围及历史影响后才提交。仅改范围保留同一计算修订和使用次数；改含义重新累计。以后仅本人使用不会自动撤销过去已授权共享，项目建议仍须审核／发布。",p.phrase());
    }

    @Transactional
    public void freeze(String question,long project,String principal,Proposal p) {
        definitions.lockPreferences(List.of(p.preferenceId()));
        var current=definitions.current(p.preferenceId());
        requireCurrent(current,project,principal,p);
        jdbc.update("""
            INSERT INTO qw_personal_definition_change(clarification_id,preference_id,definition_revision,
                authorization_revision,source_content_hash,proposal_json) VALUES (?,?,?,?,?,?::jsonb)
            """,question,p.preferenceId(),p.revision(),p.authorizationRevision(),p.contentHash(),PersonalSemanticDefinitionStore.json(p));
    }
    private int authorizationRevision(PersonalSemanticDefinitionStore.Definition d) {
        return jdbc.queryForObject("SELECT max(authorization_revision) FROM qw_user_semantic_authorization WHERE preference_id=? AND definition_revision=?",
            Integer.class,d.preferenceId(),d.revision());
    }
    private void requireCurrent(PersonalSemanticDefinitionStore.Definition d,long project,String principal,Proposal p) {
        if(d.projectId()!=project||!d.principal().equals(principal))throw new SecurityException("Definition change owner/project mismatch");
        if(d.revision()!=p.revision()||!d.contentHash().equals(p.contentHash())||authorizationRevision(d)!=p.authorizationRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"这条口径或共享范围已变更，请核对最新定义后重新确认。");
    }
    private Proposal frozen(String question) {
        String text=jdbc.queryForObject("SELECT proposal_json::text FROM qw_personal_definition_change WHERE clarification_id=?",String.class,question);
        try{return JsonUtil.getObjectMapper().readValue(text,Proposal.class);}
        catch(Exception invalid){throw new IllegalStateException("Stored definition change is invalid",invalid);}
    }

    /** Rebase an unsubmitted, complete proposal for a fresh explicit confirmation; never reuse the old answer. */
    @Transactional
    public Optional<Proposal> refreshIfChanged(String question,long project,String principal) {
        var old=frozen(question);definitions.lockPreferences(List.of(old.preferenceId()));
        var current=definitions.current(old.preferenceId());
        if(current.projectId()!=project||!current.principal().equals(principal))throw new SecurityException("Definition refresh owner/project mismatch");
        var history=history(current);int authorization=authorizationRevision(current);
        if(current.revision()==old.revision()&&current.contentHash().equals(old.contentHash())
                &&authorization==old.authorizationRevision()&&history.equals(old.history()))return Optional.empty();
        var actualTargets=history.stream().map(HistoryUse::runId).collect(java.util.stream.Collectors.toSet());
        if(!actualTargets.containsAll(old.affectedRuns()))throw new ResponseStatusException(HttpStatus.CONFLICT,
            "指定的历史查询已经变化，请补充要调整的查询，不能自动改动其他记录。");
        String proposed=Objects.equals(old.newText(),current.text())?null:old.newText();
        return Optional.of(new Proposal(current.preferenceId(),current.revision(),authorization,current.contentHash(),
            current.phrase(),current.text(),current.sharing(),proposed,history,old.affectedRuns(),old.modelEvidence(),question));
    }

    /** Invoked only by the authenticated answer transaction, never by model planning. */
    @Transactional
    public void submit(RuntimeClarification question,RuntimeClarificationService.AnswerCommand answer,long project,String principal) {
        if(Set.of("OTHER","CANCEL").contains(answer.selectedOption()))return;
        if(answer.customAnswer()!=null&&!answer.customAnswer().isBlank())throw new IllegalArgumentException("请选择其他来补充修改内容，不能覆盖待确认的定义。");
        if(!Set.of("CONFIRM_FUTURE","CONFIRM_VALID_HISTORY","CONFIRM_INVALIDATE_TARGETS","CONFIRM_WITHDRAW_TARGETS").contains(answer.selectedOption()))
            throw new IllegalArgumentException("Unknown definition change confirmation");
        if(answer.scope()!=SemanticBindingScope.USER&&answer.scope()!=SemanticBindingScope.PROJECT)
            throw new IllegalArgumentException("长期口径修改须选择本人使用或允许项目分享。");
        var p=frozen(question.clarificationId());
        definitions.lockPreferences(List.of(p.preferenceId()));
        var old=definitions.current(p.preferenceId());requireCurrent(old,project,principal,p);
        if(!p.history().equals(history(old)))throw new ResponseStatusException(HttpStatus.CONFLICT,
            "这条口径的历史使用已变化，请核对最新影响范围后重新确认。");
        if("CONFIRM_VALID_HISTORY".equals(answer.selectedOption())&&(!p.scopeOnly()||answer.scope()!=SemanticBindingScope.PROJECT))
            throw new IllegalArgumentException("只有计算含义不变且允许项目分享时可纳入既有有效使用。");
        if(("CONFIRM_INVALIDATE_TARGETS".equals(answer.selectedOption())||"CONFIRM_WITHDRAW_TARGETS".equals(answer.selectedOption()))
                &&p.affectedRuns().isEmpty())throw new IllegalArgumentException("Explicit historical targets required");
        if(p.scopeOnly())for(var use:p.history()) {
            boolean share=("CONFIRM_VALID_HISTORY".equals(answer.selectedOption())
                &&use.definitionRevision()==p.revision())||use.shared();
            if("CONFIRM_WITHDRAW_TARGETS".equals(answer.selectedOption())&&p.affectedRuns().contains(use.runId()))share=false;
            jdbc.update("""
                INSERT INTO qw_personal_sharing_use_decision(preference_id,definition_revision,run_id,allowed,clarification_id)
                VALUES(?,?,?,?,?)
                """,p.preferenceId(),use.definitionRevision(),use.runId(),share,question.clarificationId());
        }
        var sharing=answer.scope()==SemanticBindingScope.PROJECT?PersonalSemanticDefinitionStore.Sharing.ALLOWED:PersonalSemanticDefinitionStore.Sharing.PRIVATE;
        String source="clarification:"+question.clarificationId();
        PersonalSemanticDefinitionStore.Definition result;
        if(p.scopeOnly())result=definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,principal,old.phrase(),old.text(),
            old.assetType(),old.assetKey(),old.label(),old.sourceKind(),source,old.baseVersionId(),old.snapshot(),old.dependencyFingerprint(),sharing,old.revision()));
        else {
            var saved=preferences.confirmText(project,old.baseVersionId(),principal,old.phrase(),p.newText(),source,sharing,old.revision());
            result=definitions.current(saved.id());
        }
        if("CONFIRM_INVALIDATE_TARGETS".equals(answer.selectedOption()))
            p.affectedRuns().forEach(run->{
                preferences.invalidateRunUsage(run,p.preferenceId());
                if(quarantine==null)throw new IllegalStateException("Query-case correction service unavailable");
                quarantine.quarantineCorrectedSemanticUse(run,principal,question.clarificationId());
            });
        jdbc.update("""
            INSERT INTO qw_personal_definition_change_receipt(clarification_id,result_preference_id,result_revision,selected_scope,history_choice)
            VALUES(?,?,?,?,?)
            """,question.clarificationId(),result.preferenceId(),result.revision(),answer.scope().name(),answer.selectedOption());
    }
    public Optional<String> completion(String run,long project,String principal) {
        var receipts=jdbc.queryForList("""
            SELECT change.proposal_json->>'phrase' AS phrase,d.definition_text,c.selected_scope,c.result_revision,c.history_choice
            FROM qw_personal_definition_change_receipt c JOIN qw_runtime_clarification q ON q.clarification_id=c.clarification_id
            JOIN qw_personal_definition_change change ON change.clarification_id=c.clarification_id
            JOIN qw_user_semantic_preference p ON p.id=c.result_preference_id
            JOIN qw_user_semantic_definition_revision d ON d.preference_id=c.result_preference_id AND d.revision=c.result_revision
            WHERE q.run_id=? AND q.status='ANSWERED' AND p.project_id=? AND p.user_id=? ORDER BY c.create_time DESC LIMIT 1
            """,run,project,principal);
        if(receipts.isEmpty())return Optional.empty();var receipt=receipts.get(0);
        String historyChoice=Objects.toString(receipt.get("history_choice"));
        String history=switch(historyChoice) {
            case "CONFIRM_VALID_HISTORY" -> "已将你确认的当前计算口径既有有效使用纳入项目建议。";
            case "CONFIRM_INVALIDATE_TARGETS" -> "已撤回你指定查询对这条口径的有效使用，相关旧查询案例已隔离。";
            case "CONFIRM_WITHDRAW_TARGETS" -> "已撤回你指定历史使用的共享认可，查询结果保留。";
            default -> "仅调整以后的使用，过去已授权共享和查询记录保留。";
        };
        return Optional.of("已确认更新“"+receipt.get("phrase")+"”。\n口径："+receipt.get("definition_text")+"\n"+
            ("PROJECT".equals(receipt.get("selected_scope"))?"你可以继续使用，并允许分享为项目建议；公共发布仍按项目流程处理。":"以后的使用仅供本人。")+
            "\n"+history+"本次没有重新执行数据查询。");
    }
    public List<HistoryUse> history(PersonalSemanticDefinitionStore.Definition d) {
        return jdbc.query("""
            SELECT u.run_id,u.definition_revision,COALESCE((SELECT x.allowed FROM qw_personal_sharing_use_decision x
                WHERE x.preference_id=u.preference_id AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id
                ORDER BY x.id DESC LIMIT 1),
                (SELECT a.choice='ALLOWED' FROM qw_user_semantic_authorization a
                    WHERE a.preference_id=u.preference_id AND a.definition_revision=u.definition_revision
                    ORDER BY a.authorization_revision DESC LIMIT 1),FALSE) AS shared,r.request_payload,v.created_by,
                (SELECT t.user_question FROM qw_conversation_turn t WHERE t.run_id=r.run_id ORDER BY t.create_time LIMIT 1),r.create_time::text
            FROM qw_user_semantic_preference_usage u JOIN qw_query_run r ON r.run_id=u.run_id AND r.project_id=?
            LEFT JOIN qw_project_conversation v ON v.conversation_id=r.thread_id AND v.project_id=r.project_id AND v.status<>'DELETED'
            WHERE u.preference_id=? AND u.valid AND u.event_type='COUNTED'
              AND r.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
              AND r.run_type IN ('INTERACTIVE_QUERY','EXTERNAL_MCP_QUERY')
              AND EXISTS(SELECT 1 FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id AND x.phase='QUERY')
            ORDER BY u.run_id
            """,(r,n)->new OwnedHistory(new HistoryUse(r.getString(1),r.getInt(2),r.getBoolean(3),r.getString(6),r.getString(7)),
                RuntimePrincipalResolver.storedPrincipal(r.getString(4),r.getString(5))),
            d.projectId(),d.preferenceId())
            .stream().filter(use->d.principal().equals(use.owner())).map(OwnedHistory::use).toList();
    }
    private record OwnedHistory(HistoryUse use,String owner) {}
}
