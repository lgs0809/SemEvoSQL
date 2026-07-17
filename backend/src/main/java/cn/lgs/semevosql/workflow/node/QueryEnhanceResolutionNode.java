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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.*;
import static cn.lgs.semevosql.service.graph.checkpoint.NativeClarificationBoundary.APPLIED_ANSWERS;
import cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The model result is checkpointed before this node can create a durable human wait. */
@Component
@RequiredArgsConstructor
public class QueryEnhanceResolutionNode implements NodeAction {
    private final RuntimeClarificationService clarifications;
    private final QueryRunService runs;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        var enhancement = StateUtil.getObjectValue(state, REQUEST_ENHANCEMENT_OUTPUT, RequestQueryEnhancement.class);
        if (enhancement == null) throw new IllegalStateException("Request enhancement has no durable result");
        String runId = StateUtil.getStringValue(state, RUN_ID, "");
        String original = StateUtil.getStringValue(state, ORIGINAL_REQUEST, StateUtil.getStringValue(state, INPUT_KEY, ""));
        var answers = state.<Map<String, Long>>value(APPLIED_ANSWERS).orElse(Map.of());
        if (!runId.isBlank()) {
            var payload = new HashMap<String, Object>();
            payload.put("originalQuery", original);
            payload.put("enhancement", enhancement);
            payload.put("threadId", StateUtil.getStringValue(state, TRACE_THREAD_ID, ""));
            payload.put("confirmedAnswers", answers);
            var envelope = StateUtil.getObjectValue(state, CONVERSATION_CONTEXT_ENVELOPE,
                cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.class,
                (cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope) null);
            var sources = new java.util.ArrayList<Map<String, Object>>();
            for (Long sequence : enhancement.contextTurns()) {
                var source = new HashMap<String, Object>();
                source.put("sequence", sequence);
                source.put("threadId", StateUtil.getStringValue(state, TRACE_THREAD_ID, ""));
                var turn = envelope == null ? null : java.util.stream.Stream.concat(
                    envelope.recentTurns().stream(), envelope.retrievedTurns().stream())
                    .filter(candidate -> candidate.sequence() == sequence).findFirst().orElse(null);
                source.put("sourceRunId", turn == null ? null : turn.sourceRunId());
                source.put("sourceRevision", turn == null ? null : turn.sourceRevision());
                source.put("identityStatus", turn != null && turn.sourceRunId() != null && turn.sourceRevision() != null
                    ? "FROZEN" : "LEGACY_UNAVAILABLE");
                sources.add(source);
            }
            payload.put("contextSources", sources);
            String context = JsonUtil.getObjectMapper().writeValueAsString(state.value(CONVERSATION_CONTEXT_ENVELOPE)
                .orElse(StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "")));
            payload.put("contextSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(context.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            runs.appendEvent(runId, StateUtil.getStringValue(state, ATTEMPT_ID, null), "REQUEST_ENHANCEMENT_COMPLETED",
                QUERY_ENHANCE_RESOLVE_NODE, JsonUtil.getObjectMapper().writeValueAsString(payload),
                enhancement.ready() ? "完整问题已确认" : "等待确认追问对象", "request-enhancement:" + runId + ":" + answers.size());
        }
        if (!enhancement.ready()) {
            var budget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class, RepairBudget.empty());
            cn.lgs.semevosql.clarification.RuntimeClarification question;
            if (enhancement.correction()) {
                var envelope=StateUtil.getObjectValue(state,CONVERSATION_CONTEXT_ENVELOPE,
                    cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.class,
                    (cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope)null);
                var target=envelope==null ? null : java.util.stream.Stream.concat(envelope.recentTurns().stream(),envelope.retrievedTurns().stream())
                    .filter(turn -> enhancement.contextTurns().contains(turn.sequence())).findFirst().orElse(null);
                if (target==null || target.sourceRunId()==null || target.sourceRevision()==null)
                    question=clarifications.createContextClarification(runId,original,"需要纠正哪一次查询？请补充原问题及修正内容。",java.util.List.of());
                else question=clarifications.createRequirementCorrection(runId,original,target.sourceRunId(),
                    target.sourceRevision(),enhancement.canonicalQuery());
            } else question = clarifications.createContextClarification(runId, original, enhancement.question(), enhancement.options());
            throw new RuntimeClarificationRequiredException(runId, question.clarificationId(),
                Map.of(QUERY_REPAIR_BUDGET, QueryRepairPolicy.withClarificationsUsed(budget,
                    clarifications.additionalQuestionsUsed(runId))));
        }
        return Map.of(ORIGINAL_REQUEST, original,
            ROOT_CANONICAL_QUERY, StateUtil.getStringValue(state, ROOT_CANONICAL_QUERY, enhancement.canonicalQuery()),
            QUERY_ENHANCE_NODE_OUTPUT, enhancement.queryOutput(),
            INPUT_KEY, enhancement.canonicalQuery(), ACTIVE_QUERY, enhancement.canonicalQuery());
    }
}
