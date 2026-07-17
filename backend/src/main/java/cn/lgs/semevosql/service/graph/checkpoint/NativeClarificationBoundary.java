/*
 * Copyright 2024-2026 the original author or authors.
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
package cn.lgs.semevosql.service.graph.checkpoint;

import static cn.lgs.semevosql.constant.Constant.*;
import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import org.springframework.stereotype.Component;

/** A native checkpoint before waiting, and a versioned answer applied by the graph on resume. */
@Component
public class NativeClarificationBoundary {
    public static final String DETECT_NODE = "RuntimeClarificationDetectNode";
    public static final String RESUME_NODE = "RuntimeClarificationResumeNode";
    public static final String QUESTION_ID = "native_clarification_id";
    public static final String RETURN_NODE = "native_clarification_return_node";
    public static final String APPLIED_ANSWERS = "native_clarification_applied_answers";
    public static final String BASE_QUERY = "native_clarification_base_query";
    private final RuntimeClarificationService service;
    private final RuntimeClarificationRepository repository;
    private final RunExecutionFenceService fence;
    public NativeClarificationBoundary(RuntimeClarificationService service, RuntimeClarificationRepository repository,
            RunExecutionFenceService fence) { this.service=service;this.repository=repository;this.fence=fence; }

    public Map<String,Object> detect(OverAllState state) {
        var token=fence.assertActive(state);
        String runId=StateUtil.getStringValue(state,RUN_ID,"");
        String query=StateUtil.getStringValue(state,INPUT_KEY,"");
        var pending=service.detect(runId,StateUtil.getObjectValue(state,PROJECT_ID,Long.class),
            StateUtil.getObjectValue(state,PROJECT_VERSION_ID,Long.class),query,
            state.<List<String>>value(FORCED_PHYSICAL_TABLES).orElse(List.of()));
        if(pending.isPresent()) {
            var update = new HashMap<String,Object>(pause(state,pending.get().clarificationId(),DETECT_NODE));
            var budget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET,
                cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget.class, cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget.empty());
            update.put(QUERY_REPAIR_BUDGET,cn.lgs.semevosql.review.QueryRepairPolicy.withClarificationsUsed(budget,
                service.additionalQuestionsUsed(runId)));
            return update;
        }
        fence.assertActive(token);
        return Map.of(QUESTION_ID,"");
    }
    public static Map<String,Object> pause(OverAllState state,String id,String returnNode) {
        return Map.of(QUESTION_ID,id,RETURN_NODE,returnNode,BASE_QUERY,StateUtil.getCanonicalQuery(state));
    }
    public static boolean pending(OverAllState state) {return !StateUtil.getStringValue(state,QUESTION_ID,"").isBlank();}
    public Map<String,Object> resume(OverAllState state) {
        var token=fence.assertActive(state);
        String runId=StateUtil.getStringValue(state,RUN_ID,"");
        String id=StateUtil.getStringValue(state,QUESTION_ID,"");
        var visited=new HashSet<String>();
        var answer=repository.find(id).orElseThrow(()->new IllegalStateException("Native clarification is missing"));
        while(answer.status()==RuntimeClarification.ClarificationStatus.SUPERSEDED && "REPLACED".equals(answer.resolutionSource())) {
            if(!visited.add(answer.clarificationId())||!answer.runId().equals(runId)||answer.resolvedValue()==null)
                throw new IllegalStateException("Invalid native clarification replacement chain");
            answer=repository.find(answer.resolvedValue()).orElseThrow(()->new IllegalStateException("Replacement clarification is missing"));
        }
        id=answer.clarificationId();
        if(!answer.runId().equals(runId)||answer.status()!=RuntimeClarification.ClarificationStatus.ANSWERED
                || "CANCEL".equals(answer.selectedOption()))
            throw new IllegalStateException("Native clarification has no accepted answer to resume");
        Map<String,Long> applied=new LinkedHashMap<>(state.<Map<String,Long>>value(APPLIED_ANSWERS).orElse(Map.of()));
        Long prior=applied.get(id);
        if(prior!=null && prior!=answer.revision())throw new IllegalStateException("Applied clarification revision changed");
        String query=prior==null ? service.applyResolvedAnswer(runId,StateUtil.getStringValue(state,BASE_QUERY,""))
            : StateUtil.getStringValue(state,INPUT_KEY,"");
        applied.put(id,answer.revision());
        fence.assertActive(token);
        var update=new HashMap<String,Object>(Map.of(QUESTION_ID,"",APPLIED_ANSWERS,applied,INPUT_KEY,query,ACTIVE_QUERY,query));
        service.confirmedRequirementQuery(answer).ifPresent(canonical -> {
            update.put(REQUEST_ENHANCEMENT_OUTPUT,new cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement(
                "READY",canonical,List.of(canonical),List.of(),"",List.of()));
            update.put(RETURN_NODE,QUERY_ENHANCE_RESOLVE_NODE);
        });
        return update;
    }
}
