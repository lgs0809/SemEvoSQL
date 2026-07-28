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
package cn.lgs.semevosql.external.mcp;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.FilterBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.multisource.MultiSourceRunService.ResultArtifact;
import cn.lgs.semevosql.project.application.ProjectRuntimeGate;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunErrorPresenter;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import cn.lgs.semevosql.semantic.application.VerifiedQueryExecutionService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * BYO-Agent MCP facade. Natural-language reasoning belongs to the caller; SemEvoSQL only exposes its published
 * semantic catalog and deterministic governed query data plane. No ChatModel/LLM dependency is allowed here.
 */
@Service
@RequiredArgsConstructor
public class ExternalSemanticQueryFacade {

    private final ProjectScopeService projectScope;
    private final ProjectRuntimeGate runtimeGate;
    private final SemanticCatalogApplicationService catalogService;
    private final VerifiedQueryExecutionService executionService;
    private final MultiSourceRunService multiSourceRunService;
    private final QueryRunService runService;
    private final QueryRunErrorPresenter runErrorPresenter;
    private final ProjectMcpRepository repository;
    private final ProjectMcpProperties properties;

    public SemanticSearchResult search(ProjectMcpDeployment deployment, String query, Integer limit) {
        requireDeployment(deployment, "search_semantics");
        ProjectRuntimeContext runtime = runtimeGate.requireReadyByProject(deployment.projectId());
        String normalized = required(query, "query");
        int boundedLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 50));
        var recall = catalogService.recallPlanning(deployment.projectId(), runtime.projectVersionId(), normalized,
                boundedLimit);
        List<SemanticSearchHit> hits = recall.hits().stream().map(this::searchHit).toList();
        repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                "SEARCH_SEMANTICS", "SUCCEEDED", "hits=" + hits.size());
        return new SemanticSearchResult(hits);
    }

    public SemanticContextResult context(ProjectMcpDeployment deployment, List<SemanticAssetRef> assets) {
        requireDeployment(deployment, "get_semantic_context");
        ProjectRuntimeContext runtime = runtimeGate.requireReadyByProject(deployment.projectId());
        if (assets == null || assets.isEmpty()) {
            throw new IllegalArgumentException("assets are required");
        }
        SemanticCatalogSnapshot snapshot = catalogService.getCatalog(deployment.projectId(), runtime.projectVersionId());
        List<SemanticAssetContext> result = assets.stream().map(ref -> resolveContext(snapshot, ref)).toList();
        repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                "GET_SEMANTIC_CONTEXT", "SUCCEEDED", "assets=" + result.size());
        return new SemanticContextResult(result);
    }

    public PlanValidationResult validate(ProjectMcpDeployment deployment, ExternalQueryPlan request) {
        requireDeployment(deployment, "validate_query_plan");
        ProjectRuntimeContext runtime = runtimeGate.requireReadyByProject(deployment.projectId());
        try {
            SemanticBlueprint plan = buildPlan(deployment, runtime.projectVersionId(), request);
            List<String> errors = new ArrayList<>(plan.getValidationErrors());
            if ("CONSTRAINED_GENERATION".equalsIgnoreCase(plan.getCompilerMode())) {
                errors.add("This plan requires model-based constrained generation; split or simplify the query for the MCP data plane");
            }
            boolean valid = plan.isExecutable() && errors.isEmpty();
            repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                    "VALIDATE_QUERY_PLAN", valid ? "SUCCEEDED" : "REJECTED", "errors=" + errors.size());
            return new PlanValidationResult(valid, plan.getCompilerMode(), plan, List.copyOf(errors),
                    List.copyOf(plan.getValidationWarnings()));
        }
        catch (RuntimeException ex) {
            repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                    "VALIDATE_QUERY_PLAN", "REJECTED", ex.getClass().getSimpleName());
            return new PlanValidationResult(false, null, null, List.of(message(ex)), List.of());
        }
    }

    public QueryExecutionResult execute(ProjectMcpDeployment deployment, ExternalQueryPlan request) {
        requireDeployment(deployment, "execute_query_plan");
        ProjectRuntimeContext runtime = runtimeGate.requireReadyByProject(deployment.projectId());
        SemanticBlueprint plan = buildPlan(deployment, runtime.projectVersionId(), request);
        if ("CONSTRAINED_GENERATION".equalsIgnoreCase(plan.getCompilerMode())) {
            throw new IllegalArgumentException(
                    "This query requires model-based constrained generation; the BYO-Agent MCP path accepts deterministic plans only");
        }
        String payload = json(request);
        String fingerprint = retryFingerprint(deployment.deploymentId(), payload);
        String queryId = "mcp_" + fingerprint.substring(0, 32);
        String threadId = "mcp-data-plane:" + queryId;
        String idempotencyKey = "mcp-data-plane:" + fingerprint;
        QueryRun run = runService.create(new CreateRunCommand(RunType.EXTERNAL_MCP_QUERY, deployment.projectId(),
                runtime.projectVersionId(), threadId, queryId, idempotencyKey, payload,
                "{\"mode\":\"MCP_BYO_AGENT\",\"chatModelAllowed\":false}"));
        if (repository.insertSubmittedHandle(queryId, deployment.deploymentId(), deployment.projectId(), run.runId(),
                fingerprint, request.question()) == 0) {
            return getResult(deployment, queryId);
        }
		run = runService.bindExecution(run.runId(), queryId, queryId + ":attempt", threadId);
        repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                "EXECUTE_QUERY_PLAN", "STARTED", queryId);
        try {
			VerifiedQueryExecutionService.ExecutionResult executed = executionService.execute(run.runId(), run.attemptId(),
					"mcp", deployment.projectId(), runtime.projectVersionId(), deployment.principalId(), plan);
            runService.transition(run.runId(), RunStatus.SUCCEEDED, "mcp-data-plane", null, null);
            runService.appendEvent(run.runId(), "RUN_SUCCEEDED", "mcp-data-plane", null,
                    "External MCP governed query completed", "run-succeeded:" + run.runId());
            repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                    "EXECUTE_QUERY_PLAN", "SUCCEEDED", queryId);
            return completed(queryId, executed.artifact(), executed.resultSet());
        }
        catch (Exception ex) {
            runService.transition(run.runId(), RunStatus.FAILED, "mcp-data-plane", "MCP_QUERY_EXECUTION_FAILED", message(ex));
            runService.appendEvent(run.runId(), "RUN_FAILED", "mcp-data-plane", null,
                    "External MCP governed query failed", "run-failed:" + run.runId());
            repository.failHandle(queryId, message(ex));
            repository.audit(deployment.deploymentId(), deployment.projectId(), deployment.principalId(),
                    "EXECUTE_QUERY_PLAN", "FAILED", queryId);
            return new QueryExecutionResult(queryId, "FAILED", null, 0, List.of(), List.of(), null,
                    runErrorPresenter.present("MCP_QUERY_EXECUTION_FAILED"));
        }
    }

    public QueryExecutionResult getResult(ProjectMcpDeployment deployment, String queryId) {
        requireDeployment(deployment, "get_query_result");
        ProjectMcpRepository.ExternalQueryHandle handle = repository.findHandle(required(queryId, "queryId"))
            .orElseThrow(() -> new IllegalArgumentException("queryId does not exist"));
        if (!deployment.deploymentId().equals(handle.deploymentId()) || !deployment.projectId().equals(handle.projectId())) {
            throw new SecurityException("queryId does not belong to this MCP deployment");
        }
        if (!StringUtils.hasText(handle.runId())) {
            throw new IllegalArgumentException("queryId is not a BYO-Agent query result handle");
        }
        QueryRun run = runService.get(handle.runId());
        if (run.runType() != RunType.EXTERNAL_MCP_QUERY) {
            throw new IllegalArgumentException("queryId is not an external MCP query result handle");
        }
        if (run.status() == RunStatus.SUCCEEDED) {
            ResultArtifact artifact = multiSourceRunService.mergedArtifact(run.runId())
                .orElseThrow(() -> new IllegalStateException("Completed query has no merged result artifact"));
            return completed(queryId, artifact, multiSourceRunService.resultSet(artifact));
        }
        if (run.status() == RunStatus.FAILED || run.status() == RunStatus.CANCELLED || run.status() == RunStatus.EXPIRED) {
            return new QueryExecutionResult(queryId, "FAILED", null, 0, List.of(), List.of(), null,
                    runErrorPresenter.present(run));
        }
        return new QueryExecutionResult(queryId, "RUNNING", null, 0, List.of(), List.of(), null, null);
    }

    private SemanticBlueprint buildPlan(ProjectMcpDeployment deployment, Long semanticVersionId, ExternalQueryPlan request) {
        if (request == null) {
            throw new IllegalArgumentException("plan is required");
        }
        String question = required(request.question(), "question");
        SemanticCatalogSnapshot snapshot = catalogService.getCatalog(deployment.projectId(), semanticVersionId);
        Set<String> modelCodes = resolveModels(snapshot, request);
        if (modelCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one governed model must be selected by the submitted semantic plan");
        }
        Set<String> physicalTables = snapshot.getModels().stream()
            .filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
            .filter(model -> modelCodes.contains(model.getModelCode()))
            .map(SemanticCatalogSnapshot.Model::getPhysicalTable)
            .filter(StringUtils::hasText)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<FilterBindingHint> filters = request.filters().stream()
            .map(filter -> new FilterBindingHint(firstText(filter.rawText(), "mcp-filter"),
                    required(filter.modelCode(), "filter.modelCode"), required(filter.columnName(), "filter.columnName"),
                    required(filter.operator(), "filter.operator"), filter.value(), "MCP_EXTERNAL", 1.0d))
            .toList();
        TimeBindingHint timeBinding = request.timeBinding() == null ? null
                : new TimeBindingHint(Objects.toString(request.timeBinding().rawText(), ""),
                        required(request.timeBinding().modelCode(), "timeBinding.modelCode"),
                        required(request.timeBinding().columnName(), "timeBinding.columnName"), "MCP_EXTERNAL", 1.0d,
                        request.timeBinding().groupGranularity());
        QueryCaseHints hints = new QueryCaseHints(modelCodes, request.metricCodes(), request.dimensionCodes(),
                request.grainCodes(), request.relationshipCodes(), request.ruleCodes(), List.of(), filters, List.of(),
                timeBinding, true, "MCP_EXTERNAL", List.of(), 1.0d, Map.of());
        SemanticBlueprint plan = catalogService.buildBlueprint(deployment.projectId(), semanticVersionId, question,
                physicalTables, hints);
        if (!plan.isExecutable()) {
            throw new IllegalArgumentException("Submitted semantic plan is not executable: "
                    + String.join("; ", plan.getValidationErrors()));
        }
        return plan;
    }

    private Set<String> resolveModels(SemanticCatalogSnapshot snapshot, ExternalQueryPlan request) {
        Set<String> models = new LinkedHashSet<>(request.modelCodes());
        for (String code : request.metricCodes()) {
            SemanticCatalogSnapshot.Metric asset = snapshot.getMetrics().stream()
                .filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED && code.equals(metric.getMetricCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or disabled metric: " + code));
            models.add(asset.getModelCode());
        }
        for (String code : request.dimensionCodes()) {
            SemanticCatalogSnapshot.Dimension asset = snapshot.getDimensions().stream()
                .filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED
                        && code.equals(dimension.getDimensionCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or disabled dimension: " + code));
            models.add(asset.getModelCode());
        }
        for (String code : request.grainCodes()) {
            SemanticCatalogSnapshot.Grain asset = snapshot.getGrains().stream()
                .filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED && code.equals(grain.getGrainCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or disabled grain: " + code));
            models.add(asset.getModelCode());
        }
        for (String code : request.ruleCodes()) {
            SemanticCatalogSnapshot.Rule asset = snapshot.getRules().stream()
                .filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED && code.equals(rule.getRuleCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or disabled rule: " + code));
            if (StringUtils.hasText(asset.getModelCode())) {
                models.add(asset.getModelCode());
            }
        }
        for (String code : request.relationshipCodes()) {
            SemanticCatalogSnapshot.Relationship asset = snapshot.getRelationships().stream()
                .filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED
                        && code.equals(relationship.getRelationshipCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown or disabled relationship: " + code));
            models.add(asset.getSourceModelCode());
            models.add(asset.getTargetModelCode());
        }
        request.filters().forEach(filter -> models.add(required(filter.modelCode(), "filter.modelCode")));
        if (request.timeBinding() != null) {
            models.add(required(request.timeBinding().modelCode(), "timeBinding.modelCode"));
        }
        Set<String> enabledModels = snapshot.getModels().stream()
            .filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
            .map(SemanticCatalogSnapshot.Model::getModelCode)
            .collect(java.util.stream.Collectors.toSet());
        for (String model : models) {
            if (!enabledModels.contains(model)) {
                throw new IllegalArgumentException("Unknown or disabled semantic model: " + model);
            }
        }
        return Set.copyOf(models);
    }

    private SemanticAssetContext resolveContext(SemanticCatalogSnapshot snapshot, SemanticAssetRef ref) {
        String type = required(ref == null ? null : ref.assetType(), "assetType").toUpperCase(java.util.Locale.ROOT);
        String key = required(ref.assetKey(), "assetKey");
        Object value = switch (type) {
            case "MODEL" -> snapshot.getModels().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getModelCode()))
                .findFirst().orElse(null);
            case "METRIC" -> snapshot.getMetrics().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getMetricCode()))
                .findFirst().orElse(null);
            case "DIMENSION" -> snapshot.getDimensions().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getDimensionCode()))
                .filter(item -> sendableColumn(snapshot, item.getModelCode(), item.getColumnName())).findFirst().orElse(null);
            case "RELATIONSHIP" -> snapshot.getRelationships().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getRelationshipCode()))
                .findFirst().orElse(null);
            case "GRAIN" -> snapshot.getGrains().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getGrainCode()))
                .findFirst().orElse(null);
            case "RULE" -> snapshot.getRules().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && key.equals(item.getRuleCode()))
                .findFirst().orElse(null);
            case "ENUM_VALUE" -> snapshot.getEnumValues().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED)
                .filter(item -> key.equals(item.getModelCode() + ":" + item.getColumnName() + ":" + item.getValueCode()))
                .filter(item -> sendableColumn(snapshot, item.getModelCode(), item.getColumnName())).findFirst().orElse(null);
            case "TIME_COLUMN", "COLUMN" -> snapshot.getColumns().stream()
                .filter(item -> item.getStatus() == SemanticAssetStatus.ENABLED && Boolean.TRUE.equals(item.getAllowSendToLlm()))
                .filter(item -> key.equals(item.getModelCode() + ":" + item.getColumnName())).findFirst().orElse(null);
            default -> null;
        };
        if (value == null) {
            throw new IllegalArgumentException("Semantic asset not found: " + type + ":" + key);
        }
        return new SemanticAssetContext(type, key, value);
    }

    private boolean sendableColumn(SemanticCatalogSnapshot snapshot, String modelCode, String columnName) {
        return snapshot.getColumns().stream()
            .filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
            .filter(column -> Objects.equals(modelCode, column.getModelCode())
                    && Objects.equals(columnName, column.getColumnName()))
            .anyMatch(column -> Boolean.TRUE.equals(column.getAllowSendToLlm()));
    }

    private SemanticSearchHit searchHit(RetrievalHit hit) {
        return new SemanticSearchHit(hit.assetType(), externalAssetKey(hit.assetType(), hit.assetKey()), hit.modelCode(),
                hit.score(), hit.channelRanks(), hit.channelScores());
    }

    private String externalAssetKey(String assetType, String assetKey) {
        String type = Objects.toString(assetType, "").trim().toLowerCase(java.util.Locale.ROOT);
        String prefix = switch (type) {
            case "model" -> "model:";
            case "metric" -> "metric:";
            case "dimension" -> "dimension:";
            case "enum_value" -> "enum_value:";
            case "relationship" -> "relationship:";
            case "grain" -> "grain:";
            case "rule" -> "rule:";
            default -> "";
        };
        if (!prefix.isEmpty() && StringUtils.hasText(assetKey) && assetKey.startsWith(prefix)) {
            return assetKey.substring(prefix.length());
        }
        return assetKey;
    }

    private QueryExecutionResult completed(String queryId, ResultArtifact artifact, ResultSetBO resultSet) {
        List<String> columns = resultSet == null || resultSet.getColumn() == null ? List.of() : resultSet.getColumn();
        List<Map<String, String>> rows = resultSet == null || resultSet.getData() == null ? List.of() : resultSet.getData();
        return new QueryExecutionResult(queryId, "COMPLETED", artifact.artifactId(), artifact.rowCount(), columns, rows,
                artifact.contentHash(), null);
    }

    private void requireDeployment(ProjectMcpDeployment deployment, String operation) {
        if (deployment == null || deployment.status() != ProjectMcpDeployment.Status.RUNNING) {
            throw new IllegalStateException("MCP deployment is not running");
        }
        OperatorContext operator = new OperatorContext(deployment.principalId(), "MCP_EXTERNAL",
                UUID.randomUUID().toString(), operation);
        projectScope.requireProject(deployment.projectId(), operator);
    }

    private String json(Object value) {
        try {
            return JsonUtil.getObjectMapper().writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize external semantic blueprint", ex);
        }
    }

    private static String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String message(Throwable error) {
        return firstText(error == null ? null : error.getMessage(), error == null ? null : error.getClass().getSimpleName(),
                "Unknown error");
    }

    private String retryFingerprint(String deploymentId, String payload) {
        long window = Math.max(1, properties.getRetryWindowSeconds());
        long bucket = Instant.now().getEpochSecond() / window;
        return sha256(deploymentId + "|" + bucket + "|" + payload);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record SemanticSearchResult(List<SemanticSearchHit> hits) {
    }

    public record SemanticSearchHit(String assetType, String assetKey, String modelCode, double score,
            Map<String, Integer> channelRanks, Map<String, Double> channelScores) {
    }

    public record SemanticAssetRef(String assetType, String assetKey) {
    }

    public record SemanticAssetContext(String assetType, String assetKey, Object definition) {
    }

    public record SemanticContextResult(List<SemanticAssetContext> assets) {
    }

    public record ExternalQueryPlan(String question, Set<String> modelCodes, Set<String> metricCodes,
            Set<String> dimensionCodes, Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
            List<ExternalFilter> filters, ExternalTimeBinding timeBinding) {
        public ExternalQueryPlan {
            modelCodes = Set.copyOf(modelCodes == null ? Set.of() : modelCodes);
            metricCodes = Set.copyOf(metricCodes == null ? Set.of() : metricCodes);
            dimensionCodes = Set.copyOf(dimensionCodes == null ? Set.of() : dimensionCodes);
            grainCodes = Set.copyOf(grainCodes == null ? Set.of() : grainCodes);
            relationshipCodes = Set.copyOf(relationshipCodes == null ? Set.of() : relationshipCodes);
            ruleCodes = Set.copyOf(ruleCodes == null ? Set.of() : ruleCodes);
            filters = List.copyOf(filters == null ? List.of() : filters);
        }
    }

    public record ExternalFilter(String rawText, String modelCode, String columnName, String operator, Object value) {
    }

    public record ExternalTimeBinding(String rawText, String modelCode, String columnName, String groupGranularity) {
    }

    public record PlanValidationResult(boolean valid, String compilerMode, SemanticBlueprint normalizedPlan,
            List<String> errors, List<String> warnings) {
    }

    public record QueryExecutionResult(String queryId, String status, String artifactId, long rowCount,
            List<String> columns, List<Map<String, String>> rows, String contentHash,
            QueryRunErrorPresenter.ErrorPresentation error) {
    }
}
