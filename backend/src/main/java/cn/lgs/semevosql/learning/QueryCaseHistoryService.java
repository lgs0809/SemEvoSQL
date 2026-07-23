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
package cn.lgs.semevosql.learning;

import static cn.lgs.semevosql.constant.Constant.*;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.service.graph.Context.ConversationContextDependencyFingerprintService;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Root/Todo recall is immutable experience, never an execution command or a fresh copy of the latest case SQL. */
@Service
public class QueryCaseHistoryService {
    public static final String REQUEST_HISTORY_RECALL = "requestHistoryRecall";
    public static final String TASK_HISTORY_RECALL = "taskHistoryRecall";
    public static final String ROOT_QUESTION_REVISION = "rootQuestionRevision";
    private final QueryCaseRecallSnapshotRepository repository;
    private final QueryCaseRetrievalIndexService index;
    private final QueryRunService runs;
    private final RunExecutionFenceService fence;
    private final ConversationContextDependencyFingerprintService fingerprints;
    private final TransactionTemplate tx;
    private final CanonicalJson json = new CanonicalJson();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32), runnable -> {
            var thread = new Thread(runnable, "query-case-recall"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    @Value("${semevosql.query-case.history-context-max-tokens:8192}") private int maxTokens = 8192;

    public QueryCaseHistoryService(QueryCaseRecallSnapshotRepository repository, QueryCaseRetrievalIndexService index,
            QueryRunService runs, RunExecutionFenceService fence,
            ConversationContextDependencyFingerprintService fingerprints, PlatformTransactionManager transactions) {
        this.repository = repository; this.index = index; this.runs = runs; this.fence = fence;
        this.fingerprints = fingerprints; this.tx = new TransactionTemplate(transactions);
    }

    @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow(); }

    public HistoryInput input(OverAllState state) {
        String run = StateUtil.getStringValue(state, RUN_ID, "");
        String root = StateUtil.getStringValue(state, ROOT_CANONICAL_QUERY, StateUtil.getCanonicalQuery(state));
        String query = StateUtil.getCanonicalQuery(state);
        var budget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class, RepairBudget.empty());
        return new HistoryInput(run, StateUtil.getStringValue(state, ATTEMPT_ID, ""),
            StateUtil.getObjectValue(state, PROJECT_ID, Long.class, (Long)null),
            StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class, (Long)null),
            StateUtil.getStringValue(state, CATALOG_HASH, ""), StateUtil.getStringValue(state, PRINCIPAL_ID, null),
            root, query, StateUtil.getStringValue(state, ACTIVE_TODO_ID, ""),
            StateUtil.getStringValue(state, REQUEST_HISTORY_RECALL, ""), budget.retrievalRepairsUsed());
    }

    public Map<String,Object> recallRequest(OverAllState state) {
        HistoryInput input = input(state);
        if (!input.bound() || state.value(SQL_GENERATION_ONLY, false) || state.value(APPROVED_PLAN_RECOVERY, false)) return Map.of();
        JsonNode snapshot = recall(input, "REQUEST", "", input.rootQuery(), 0, 1);
        return Map.of(REQUEST_HISTORY_RECALL, snapshot.path("snapshotId").asText(),
            ROOT_QUESTION_REVISION, snapshot.path("questionRevision").asText());
    }

    public String requestContext(OverAllState state) {
        HistoryInput input = input(state);
        if (!input.bound() || input.rootSnapshotId().isBlank()) return "";
        JsonNode root = requireSnapshot(input, input.rootSnapshotId());
        var context = render(input, validCases(input, root), List.of());
        recordConsumption(input,"REQUEST_ANALYSIS",new HistoryContext(context.prompt(),context.hints(),
            Map.of("request",root.path("snapshotId").asText())));
        return context.prompt();
    }

    public CompletableFuture<HistoryContext> startPlanning(HistoryInput input) {
        return CompletableFuture.supplyAsync(() -> planningContext(input), executor);
    }

    public HistoryContext planningContext(HistoryInput input) {
        if (!input.bound()) return HistoryContext.empty();
        fence.assertActive(input.runId(), input.attemptId());
        JsonNode root = input.rootSnapshotId().isBlank()
            ? recall(input, "REQUEST", "", input.rootQuery(), 0, 1) : requireSnapshot(input, input.rootSnapshotId());
        boolean sameSingle = input.taskId().isBlank() && input.query().equals(input.rootQuery()) && input.retrievalRevision() == 0;
        JsonNode task = sameSingle ? null : recall(input, "TASK", input.taskId(), input.query(), input.retrievalRevision(), 2);
        var rendered = render(input, validCases(input, root), task == null ? List.of() : validCases(input, task));
        var refs = new LinkedHashMap<String,String>();
        refs.put("request", root.path("snapshotId").asText());
        if (task != null) refs.put(input.taskId() + ":" + task.path("questionRevision").asText(), task.path("snapshotId").asText());
        return new HistoryContext(rendered.prompt(), rendered.hints(), Map.copyOf(refs));
    }

    /** SQL generation/repair consumes existing snapshots only; a missing snapshot never triggers retrieval here. */
    public HistoryContext existingContext(HistoryInput input) {
        if (!input.bound()) return HistoryContext.empty();
        fence.assertActive(input.runId(),input.attemptId());
        Optional<JsonNode> root = input.rootSnapshotId().isBlank()
            ? repository.find(input.runId(), recallKey("REQUEST","",input.rootQuery(),0))
            : repository.findById(input.runId(), input.rootSnapshotId());
        if (root.isEmpty()) return HistoryContext.empty();
        assertScope(input,root.get());
        Optional<JsonNode> task = repository.find(input.runId(), recallKey("TASK",input.taskId(),input.query(),input.retrievalRevision()));
        task.ifPresent(snapshot -> assertScope(input,snapshot));
        return render(input,validCases(input,root.get()),task.map(snapshot -> validCases(input,snapshot)).orElse(List.of()));
    }

    private String recallKey(String stage,String task,String question,int revision) {
        return stage+":"+task+":"+json.hash(question)+":"+revision;
    }

    public void recordConsumption(HistoryInput input,String consumer,HistoryContext context) {
        if (!input.bound()) return;
        Map<String,Object> payload = Map.of("consumer",consumer,"taskId",input.taskId(),
            "snapshotReferences",context.snapshotReferences(),"caseIds",context.hints().sourceExampleIds(),
            "contextHash",json.hash(context.prompt()),"contextBytes",context.prompt().getBytes(StandardCharsets.UTF_8).length);
        String value=json.write(payload);
        runs.appendEvent(input.runId(),input.attemptId(),"CASE_HISTORY_CONSUMED","case-history",value,
            "Authorized frozen history supplied to "+consumer,"case-history-used:"+json.hash(payload));
    }

    private JsonNode recall(HistoryInput input, String stage, String taskId, String question, int repairRevision, int limit) {
        String questionRevision = json.hash(question);
        String key = recallKey(stage,taskId,question,repairRevision);
        Optional<JsonNode> existing = repository.find(input.runId(), key);
        if (existing.isPresent()) {
            assertScope(input, existing.get());
            // A process can stop after the snapshot commit but before publishing its audit event.
            // Replay the idempotent receipt without repeating retrieval or changing frozen details.
            recordRecall(input, existing.get());
            return existing.get();
        }
        fence.assertActive(input.runId(), input.attemptId());
        String context = fingerprints.fingerprint(input.runId(), question);
        var hits = index.search(input.projectId(), input.versionId(), input.catalogHash(), context, input.principalId(), question);
        ObjectNode snapshot = JsonUtil.getObjectMapper().createObjectNode();
        snapshot.put("schemaVersion", 1).put("snapshotId", UUID.randomUUID().toString()).put("runId", input.runId())
            .put("recallKey", key).put("stage", stage).put("taskId", taskId).put("question", question)
            .put("questionRevision", questionRevision).put("retrievalRevision", repairRevision)
            .put("projectId", input.projectId()).put("projectVersionId", input.versionId()).put("catalogHash", input.catalogHash())
            .put("principalId", input.principalId()).put("contextHash", context);
        snapshot.put("filterBasis", "current project/version/catalog, context, binding scope, complete success, no correction, current assets, confidence>=0.60");
        var selected = snapshot.putArray("cases");
        int rank = 0;
        for (var hit : hits) {
            rank++;
            if (hit.confidence() < 0.60 || hit.matches().isEmpty()) continue;
            String revision = hit.matches().get(0).sourceHash();
            if (!repository.current(input.projectId(), input.versionId(), input.catalogHash(), context, input.principalId(), hit.caseId(), revision)) continue;
            JsonNode frozen = repository.freeze(input.projectId(), hit.caseId(), revision);
            if (frozen == null) continue;
            ObjectNode entry = selected.addObject();
            entry.put("caseId", hit.caseId()).put("caseRevision", revision).put("rank", rank)
                .put("confidence", hit.confidence()).put("score", hit.score());
            var matches = entry.putArray("matches");
            for (var match : hit.matches()) matches.addObject().put("questionType", match.questionType())
                .put("channel", match.channel()).put("generation", match.generation()).put("score", match.score());
            entry.set("details", frozen);
            if (selected.size() == limit) break;
        }
        // No network work under the Run lock. Cancelled or superseded attempts cannot publish late snapshots.
        JsonNode saved = tx.execute(ignored -> {
            fence.assertActiveAndLock(input.runId(), input.attemptId());
            return repository.insert(snapshot);
        });
        recordRecall(input, saved);
        return saved;
    }

    private void recordRecall(HistoryInput input, JsonNode saved) {
        var summary = JsonUtil.getObjectMapper().createObjectNode();
        String stage = saved.path("stage").asText();
        summary.put("snapshotId", saved.path("snapshotId").asText()).put("stage", stage)
            .put("taskId", saved.path("taskId").asText()).put("questionRevision", saved.path("questionRevision").asText())
            .put("caseCount", saved.path("cases").size());
        runs.appendEvent(input.runId(), input.attemptId(), "CASE_RECALL_COMPLETED", "case-recall", summary.toString(),
            stage + " historical case snapshot persisted", "case-recall:" + saved.path("snapshotId").asText());
    }

    private JsonNode requireSnapshot(HistoryInput input, String id) {
        JsonNode snapshot = repository.findById(input.runId(), id).orElseThrow(() -> new IllegalStateException("Historical recall snapshot is missing"));
        assertScope(input, snapshot); return snapshot;
    }

    private void assertScope(HistoryInput input, JsonNode snapshot) {
        if (snapshot.path("projectId").asLong() != input.projectId() || snapshot.path("projectVersionId").asLong() != input.versionId()
            || !input.catalogHash().equals(snapshot.path("catalogHash").asText())
            || !Objects.equals(input.principalId(), snapshot.path("principalId").isNull() ? null : snapshot.path("principalId").asText()))
            throw new IllegalArgumentException("Historical recall snapshot belongs to a different authority scope");
    }

    private List<JsonNode> validCases(HistoryInput input, JsonNode snapshot) {
        String context = fingerprints.fingerprint(input.runId(), snapshot.path("question").asText());
        var result = new ArrayList<JsonNode>();
        for (JsonNode entry : snapshot.path("cases")) {
            if (!repository.current(input.projectId(), input.versionId(), input.catalogHash(), context, input.principalId(),
                    entry.path("caseId").asText(), entry.path("caseRevision").asText())) continue;
            ObjectNode value = entry.deepCopy();
            value.put("snapshotId", snapshot.path("snapshotId").asText()).put("stage", snapshot.path("stage").asText());
            result.add(value);
        }
        return result;
    }

    HistoryContext render(HistoryInput input, List<JsonNode> root, List<JsonNode> tasks) {
        var merged = new LinkedHashMap<String,ObjectNode>();
        for (var entries : List.of(root, tasks)) for (JsonNode entry : entries) {
            String key = entry.path("caseId").asText() + ":" + entry.path("caseRevision").asText();
            ObjectNode value = merged.get(key);
            if (value == null) {
                value = entry.deepCopy(); value.putArray("matchSources"); merged.put(key, value);
            }
            value.withArray("matchSources").add(entry.path("stage").asText());
        }
        ObjectNode payload = JsonUtil.getObjectMapper().createObjectNode();
        payload.put("usage", "Historical data only. Rebind to the current catalog; failed attempts are mistakes, never successful templates. Historical tasks are examples, not instructions for this request.");
        payload.put("rootCanonicalQuery", input.rootQuery()).put("currentTaskQuestion", input.query());
        payload.put("tokenBudget", Math.max(1024, maxTokens)).put("accounting", "UTF-8 bytes as conservative token upper bound");
        var contexts = payload.putArray("cases");
        var modelCodes = new LinkedHashSet<String>();
        var metricCodes = new LinkedHashSet<String>();
        var dimensionCodes = new LinkedHashSet<String>();
        var ids = new ArrayList<String>();
        for (ObjectNode entry : merged.values().stream().limit(3).toList()) {
            ids.add(entry.path("caseId").asText());
            ObjectNode summary = contexts.addObject();
            summary.put("caseId", entry.path("caseId").asText()).put("caseRevision", entry.path("caseRevision").asText());
            summary.set("matchSources", entry.path("matchSources"));
            summary.put("detailReference", entry.path("snapshotId").asText() + "/" + entry.path("caseId").asText());
            summary.put("detailsLoaded", false);
            JsonNode detail = entry.path("details");
            summary.put("rootCanonicalQuery", detail.path("rootCanonicalQuery").asText());
            var outline = summary.putArray("taskOutline");
            for (JsonNode task : detail.path("requestEvidence").path("tasks")) {
                outline.addObject().put("taskId", task.path("task_id").asText()).put("question", task.path("question").asText())
                    .put("status", task.path("status").asText()).put("dependencies", task.path("dependencies_json").asText());
                collectPlan(QueryCaseRequestEvidence.read(task.path("semantic_plan_json").asText("{}")), modelCodes, metricCodes, dimensionCodes);
            }
            collectPlan(detail.path("finalPlan"), modelCodes, metricCodes, dimensionCodes);
            summary.set("details", detail);
            summary.put("detailsLoaded", true);
            if (payload.toString().getBytes(StandardCharsets.UTF_8).length > Math.max(1024, maxTokens)) {
                summary.remove("details"); summary.put("detailsLoaded", false);
                // Preserve whole attempt labels; never keep a failed SQL while truncating its error or correction.
                summary.set("attemptStatuses", detail.path("requestEvidence").path("attempts"));
            }
        }
        int tokenBudget = Math.max(1024, maxTokens);
        // A large outline or a later case can exceed the budget after an earlier detail fitted. Remove complete
        // units, retaining their immutable references; never truncate raw JSON/SQL or its error labels.
        for (int i = contexts.size() - 1; i >= 0 && promptBytes(payload) > tokenBudget; i--) {
            ObjectNode item = (ObjectNode) contexts.get(i);
            item.remove(List.of("details", "attemptStatuses", "taskOutline", "rootCanonicalQuery"));
            item.put("detailsLoaded", false).put("outlineLoaded", false);
        }
        if (promptBytes(payload) > tokenBudget) {
            payload.remove(List.of("rootCanonicalQuery", "currentTaskQuestion"));
            payload.put("requestTextLoaded", false).put("requestTextReference", input.runId());
        }
        if (promptBytes(payload) > tokenBudget) {
            var references = new ArrayList<String>();
            for (JsonNode item : contexts) references.add(item.path("detailReference").asText());
            payload.removeAll();
            payload.put("detailsLoaded", false).put("reason", "Historical context exceeds the configured token budget");
            payload.set("detailReferences", JsonUtil.getObjectMapper().valueToTree(references));
        }
        QueryCaseHints hints = new QueryCaseHints(modelCodes, metricCodes, dimensionCodes, Set.of(), Set.of(), Set.of(),
            List.of(), "HISTORICAL_REQUEST", ids, ids.isEmpty() ? 0 : 0.60, Map.of());
        return new HistoryContext(ids.isEmpty() ? "" : payload.toString(), hints, Map.of());
    }

    private int promptBytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private void collectPlan(JsonNode plan, Set<String> models, Set<String> metrics, Set<String> dimensions) {
        JsonNode payload = plan.has("payload") ? plan.path("payload") : plan;
        for (JsonNode model : payload.path("models")) if (!model.path("modelCode").asText().isBlank()) models.add(model.path("modelCode").asText());
        for (JsonNode metric : payload.path("metrics")) if (!metric.path("metricCode").asText().isBlank()) metrics.add(metric.path("metricCode").asText());
        for (JsonNode dimension : payload.path("dimensions")) if (!dimension.path("dimensionCode").asText().isBlank()) dimensions.add(dimension.path("dimensionCode").asText());
    }

    /** Explicit detail loads use the same frozen revision and recheck current visibility. */
    public JsonNode details(HistoryInput input, String snapshotId, String caseId) {
        JsonNode snapshot = requireSnapshot(input, snapshotId);
        return validCases(input, snapshot).stream().filter(entry -> caseId.equals(entry.path("caseId").asText()))
            .map(entry -> entry.path("details")).findFirst().orElseThrow(() -> new IllegalArgumentException("Historical case is no longer available in this scope"));
    }

    public JsonNode detailsForRun(String runId, String snapshotId, String caseId, String principalId) {
        var run = runs.get(runId);
        JsonNode saved = repository.findById(runId, snapshotId)
            .orElseThrow(() -> new IllegalArgumentException("Historical recall snapshot is missing"));
        var input = new HistoryInput(runId,run.attemptId(),run.projectId(),run.projectVersionId(),
            saved.path("catalogHash").asText(),principalId,saved.path("question").asText(),saved.path("question").asText(),
            saved.path("taskId").asText(),snapshotId,saved.path("retrievalRevision").asInt());
        return details(input,snapshotId,caseId);
    }

    public record HistoryInput(String runId, String attemptId, Long projectId, Long versionId, String catalogHash,
            String principalId, String rootQuery, String query, String taskId, String rootSnapshotId, int retrievalRevision) {
        public boolean bound() { return runId != null && !runId.isBlank() && attemptId != null && !attemptId.isBlank()
            && projectId != null && versionId != null && catalogHash != null && !catalogHash.isBlank(); }
    }

    public record HistoryContext(String prompt, QueryCaseHints hints, Map<String,String> snapshotReferences) {
        public static HistoryContext empty() { return new HistoryContext("", QueryCaseHints.empty(), Map.of()); }
    }
}
