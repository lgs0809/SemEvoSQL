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
package cn.lgs.semevosql.correction;

import cn.lgs.semevosql.clarification.RuntimePrincipalResolver;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.learning.QueryCaseQuarantineService;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A correction is a confirmed relationship between two requests, never an inferred success. */
@Service
public class RequirementCorrectionService {
    public static final String TYPE = "REQUEST_CORRECTION";
    public static final String CONFIRM = "CONFIRM_CORRECTION";
    public static final String INDEPENDENT = "INDEPENDENT_QUERY";
    private final QueryRunService runs;
    private final ConversationTurnRepository turns;
    private final RuntimePrincipalResolver principals;
    private final QueryCaseQuarantineService cases;
    private final QueryPatternTemplateService templates;

    public RequirementCorrectionService(QueryRunService runs, ConversationTurnRepository turns,
            RuntimePrincipalResolver principals, QueryCaseQuarantineService cases, QueryPatternTemplateService templates) {
        this.runs=runs; this.turns=turns; this.principals=principals; this.cases=cases; this.templates=templates;
    }

    public record Proposal(String targetRunId, long targetTurnRevision, String targetAttemptId,
        String targetRequestHash, String targetQuestion, String canonicalQuery, String principalId) { }

    @Transactional
    public Proposal prepare(String runId, String targetRunId, long revision, String canonical) {
        if (canonical == null || canonical.isBlank()) throw new IllegalArgumentException("A complete corrected query is required");
        QueryRun current=runs.get(runId);
        QueryRun target=runs.lockForUpdate(targetRunId);
        String principal=principals.resolve(current);
        requireSameOwner(current,target,principal);
        var turn=turns.findByRun(targetRunId).orElseThrow(() -> new IllegalStateException("Correction target context is unavailable"));
        if (turn.revision()!=revision || !"COMPLETED".equals(turn.status()))
            throw new IllegalStateException("Correction target context changed; ask again using current context");
        return new Proposal(targetRunId,revision,target.attemptId(),new CanonicalJson().hash(target.requestPayload()),
            turn.userQuestion(),canonical.trim(),principal);
    }

    @Transactional
    public void attach(String runId, String questionId, Proposal proposal) {
        runs.appendEvent(runId,"REQUEST_CORRECTION_PROPOSED","request-correction",json(proposal),
            "等待确认要纠正的历史查询",proposalKey(questionId));
    }

    public Proposal proposal(String runId, String questionId) {
        var event=runs.eventByIdempotency(runId,proposalKey(questionId))
            .orElseThrow(() -> new IllegalStateException("Correction question has no frozen target"));
        try { return JsonUtil.getObjectMapper().readValue(event.payload(),Proposal.class); }
        catch (Exception ex) { throw new IllegalStateException("Invalid correction proposal",ex); }
    }

    @Transactional
    public void answer(String runId, String questionId, String selected, String answerKey, OperatorContext operator) {
        Proposal proposal=proposal(runId,questionId);
        QueryRun current=runs.get(runId);
        if (operator==null || !Objects.equals(operator.operator(),proposal.principalId())
                || !Objects.equals(operator.operator(),principals.resolve(current)))
            throw new SecurityException("Only the request owner can confirm a requirement correction");
        if (!CONFIRM.equals(selected)) return;
        // The same Run lock coordinates capture/feedback/correction; a late capture cannot revive a case.
        QueryRun target=runs.lockForUpdate(proposal.targetRunId());
        requireSameOwner(current,target,operator.operator());
        if (!Objects.equals(target.attemptId(),proposal.targetAttemptId())
                || !Objects.equals(new CanonicalJson().hash(target.requestPayload()),proposal.targetRequestHash()))
            throw new IllegalStateException("Correction target request changed");
        turns.revokeConfirmedContext(target.runId(),proposal.targetTurnRevision());
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("targetRunId",target.runId()); evidence.put("targetAttemptId",target.attemptId());
        evidence.put("targetTurnRevision",proposal.targetTurnRevision());
        evidence.put("targetRequestHash",proposal.targetRequestHash()); evidence.put("correctedRunId",runId);
        evidence.put("canonicalQuery",proposal.canonicalQuery()); evidence.put("clarificationId",questionId);
        evidence.put("confirmationId",answerKey); evidence.put("confirmedBy",operator.operator());
        for (String affected : List.of(target.runId(),runId))
            runs.appendEvent(affected,"REQUEST_REQUIREMENT_CORRECTED","request-correction",json(evidence),
                "用户已确认纠正历史查询需求","requirement-correction:"+questionId);
        cases.quarantineCorrectedRequest(target.runId(),operator.operator(),questionId);
        templates.invalidateByRun(target.runId(),"User confirmed a requirement correction");
    }

    private void requireSameOwner(QueryRun current, QueryRun target, String principal) {
        if (current.runId().equals(target.runId()) || !target.terminal()
                || current.projectId()==null || current.threadId()==null
                || !Objects.equals(current.projectId(),target.projectId())
                || !Objects.equals(current.threadId(),target.threadId())
                || RuntimePrincipalResolver.ANONYMOUS.equals(principal)
                || !Objects.equals(principal,principals.resolve(target)))
            throw new SecurityException("Correction requires a terminal request from the same owner and conversation");
    }
    private static String proposalKey(String questionId) { return "requirement-correction-proposal:"+questionId; }
    private static String json(Object value) {
        try { return JsonUtil.getObjectMapper().writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Unable to encode correction evidence",ex); }
    }
}
