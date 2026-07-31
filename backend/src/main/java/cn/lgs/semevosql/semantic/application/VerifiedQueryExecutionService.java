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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.multisource.MultiSourceRunService.ResultArtifact;
import cn.lgs.semevosql.multisource.MultiSourceRunService.SourceSubRun;
import cn.lgs.semevosql.multisource.MultiSourceRunService.SourceSubRunStatus;
import cn.lgs.semevosql.multisource.MultiSourceSqlExecutionService;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Deterministic data-plane execution shared by the built-in Agent and external BYO-Agent adapters.
 * This service intentionally has no ChatModel/LLM dependency.
 */
@Service
@RequiredArgsConstructor
public class VerifiedQueryExecutionService {

	private final SemanticCatalogCache semanticCatalogCache;
	private final SemanticSqlCompiler semanticSqlCompiler;
	private final MultiSourceSqlExecutionService sqlExecutionService;
	private final MultiSourceRunService multiSourceRunService;

	private final RunExecutionFenceService executionFence;

	public ExecutionResult execute(String runId, String attemptId, String executionKey, Long projectId, Long versionId,
			String principalId, SemanticBlueprint plan) throws Exception {
		if (plan == null || !plan.isExecutable()) {
			throw new IllegalArgumentException("An executable Semantic Blueprint is required");
		}
		Map<Integer, SqlDialect> dialects = plan.getSourceSubPlans().stream()
			.filter(source -> source.getDatasourceId() != null)
			.collect(Collectors.toMap(SemanticBlueprint.SourceSubPlan::getDatasourceId,
					source -> sqlExecutionService.dialect(source.getDatasourceId()), (left, right) -> left,
					LinkedHashMap::new));
		CompiledSemanticQuery compiled = semanticSqlCompiler.compile(plan, semanticCatalogCache.get(projectId, versionId),
				dialects, Clock.systemUTC(), ZoneId.systemDefault());
		executionFence.assertActive(runId, attemptId);
		multiSourceRunService.initialize(runId, executionKey, projectId, versionId, plan, attemptId);
		Map<Integer, CompiledSourceQuery> compiledByDatasource = compiled.sources().stream()
			.collect(Collectors.toMap(CompiledSourceQuery::datasourceId, source -> source, (left, right) -> left,
					LinkedHashMap::new));

		for (SemanticBlueprint.SourceSubPlan sourcePlan : plan.getSourceSubPlans()) {
			executionFence.assertActive(runId, attemptId);
			CompiledSourceQuery sourceQuery = compiledByDatasource.get(sourcePlan.getDatasourceId());
			if (sourceQuery == null) {
				throw new IllegalStateException("Compiled query missing datasource " + sourcePlan.getDatasourceId());
			}
			SourceSubRun sourceRun = multiSourceRunService.get(runId, executionKey).sourceSubRuns().stream()
				.filter(candidate -> Objects.equals(candidate.datasourceId(), sourcePlan.getDatasourceId()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Source sub-run missing for datasource "
						+ sourcePlan.getDatasourceId()));
			if (sourceRun.status() == SourceSubRunStatus.COMPLETED) {
				continue;
			}
			if (sourceRun.status() == SourceSubRunStatus.FAILED || sourceRun.status() == SourceSubRunStatus.CANCELLED) {
				throw new IllegalStateException("Source sub-run is terminal and cannot execute: " + sourceRun.subRunId());
			}
			multiSourceRunService.startSource(runId, sourceRun.subRunId(), sourceQuery.sql(), attemptId);
			SemanticBlueprint sourceSemanticPlan = sourceSemanticPlan(plan, sourcePlan);
			try {
				String executionOwner = runId + ":source:" + sourceRun.subRunId();
				ResultSetBO result = sqlExecutionService.execute(projectId, versionId, principalId, executionOwner,
						sourcePlan.getDatasourceId(), Set.copyOf(sourcePlan.getPhysicalTables()), sourceQuery.sql(),
						sourceQuery.parameters(), sourceSemanticPlan, attemptId,
						"semantic-source:" + executionKey + ":" + sourceRun.subRunId());
					executionFence.assertActive(runId, attemptId);
				protectInternalMergeKey(result, plan, sourcePlan);
				executionFence.assertActive(runId, attemptId);
				String freshness = freshness(plan, sourcePlan, executionOwner, projectId);
				executionFence.assertActive(runId, attemptId);
				multiSourceRunService.completeSource(runId, sourceRun.subRunId(), sourceQuery.sql(), result, freshness,
						attemptId);
			}
			catch (Exception failure) {
				executionFence.assertActive(runId, attemptId);
				multiSourceRunService.failSource(runId, sourceRun.subRunId(), failure.getMessage(), attemptId);
				throw failure;
			}
		}

		executionFence.assertActive(runId, attemptId);
		ResultArtifact artifact = multiSourceRunService.merge(runId, executionKey, plan, attemptId);
		ResultSetBO merged = multiSourceRunService.resultSet(artifact);
		String allSql = compiled.sources().stream().map(CompiledSourceQuery::sql)
			.collect(Collectors.joining("\n-- next source --\n"));
		if (plan.getMergePlan() != null && plan.getSourceSubPlans().size() > 1) {
			allSql += "\n-- governed cross-source merge: policy=" + plan.getMergePlan().getPolicyCode() + ", type="
					+ plan.getMergePlan().getMergeType() + ", relationship=" + plan.getMergePlan().getRelationshipCode();
		}
		executionFence.assertActive(runId, attemptId);
		return new ExecutionResult(artifact, merged, allSql);
	}

	private String freshness(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source, String executionOwner,
			Long projectId) throws Exception {
		var notice = plan.getFreshnessNotices().stream()
			.filter(candidate -> Objects.equals(candidate.getDatasourceId(), source.getDatasourceId()))
			.findFirst().orElse(null);
		if (notice == null || notice.getBusinessDateField() == null || notice.getBusinessDateField().isBlank()) {
			return null;
		}
		return sqlExecutionService.readFreshnessWatermark(projectId, source.getDatasourceId(), executionOwner, source,
				notice);
	}

	private SemanticBlueprint sourceSemanticPlan(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source) {
		Set<String> modelCodes = Set.copyOf(source.getModelCodes());
		List<SemanticBlueprint.ProjectionSelection> sourceProjections = plan.getProjections()
			.stream()
			.filter(item -> item.getModelCode() == null || modelCodes.contains(item.getModelCode()))
			.toList();
		Set<String> sourceAliases = sourceProjections.stream()
			.map(SemanticBlueprint.ProjectionSelection::getAlias)
			.filter(alias -> alias != null && !alias.isBlank())
			.collect(Collectors.toSet());
		InternalMergeKey mergeKey = internalMergeKey(plan, source);
		List<SemanticBlueprint.GroupSelection> sourceGroups = new java.util.ArrayList<>(plan.getGroupBy()
			.stream()
			.filter(item -> modelCodes.contains(item.getModelCode()))
			.toList());
		if (mergeKey != null && plan.getMetrics().stream().anyMatch(item -> modelCodes.contains(item.getModelCode()))) {
			sourceGroups.add(SemanticBlueprint.GroupSelection.builder()
				.modelCode(mergeKey.modelCode())
				.columnName(mergeKey.columnName())
				.alias(mergeKey.alias())
				.build());
		}
		int sourceMaxRows = plan.getExpectedResult() == null || plan.getExpectedResult().getMaxRows() == null ? 100
				: plan.getExpectedResult().getMaxRows();
		if (mergeKey != null && plan.getMergePlan() != null && plan.getMergePlan().getMaxRows() != null) {
			sourceMaxRows = Math.max(sourceMaxRows, plan.getMergePlan().getMaxRows());
		}
		SemanticBlueprint.ExpectedResultShape expectedResult = plan.getExpectedResult() == null ? null
				: SemanticBlueprint.ExpectedResultShape.builder()
					.columns(sourceProjections.stream()
						.map(SemanticBlueprint.ProjectionSelection::getAlias)
						.filter(alias -> alias != null && !alias.isBlank())
						.toList())
					.grain(plan.getExpectedResult().getGrain())
					.maxRows(sourceMaxRows)
					.tabular(plan.getExpectedResult().getTabular())
					.chartable(plan.getExpectedResult().getChartable())
					.build();
		return SemanticBlueprint.builder()
			.projectId(plan.getProjectId())
			.projectVersionId(plan.getProjectVersionId())
			.canonicalQuery(plan.getCanonicalQuery())
			.compilerMode(plan.getCompilerMode())
			.computationIntent(plan.getComputationIntent())
			.bindingDependencies(plan.getBindingDependencies())
			.models(plan.getModels().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.metrics(plan.getMetrics().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.dimensions(plan.getDimensions().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.grains(plan.getGrains().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.relationships(plan.getRelationships().stream()
				.filter(item -> modelCodes.contains(item.getSourceModelCode())
						&& modelCodes.contains(item.getTargetModelCode())).toList())
			.rules(plan.getRules().stream()
				.filter(item -> item.getModelCode() == null || modelCodes.contains(item.getModelCode())).toList())
			.projections(sourceProjections)
			.filters(plan.getFilters().stream().filter(item -> modelCodes.contains(item.getModelCode())).toList())
			.timeRange(plan.getTimeRange() != null && modelCodes.contains(plan.getTimeRange().getModelCode())
					? plan.getTimeRange() : null)
			.groupBy(List.copyOf(sourceGroups))
			.orderBy(plan.getOrderBy().stream().filter(item -> sourceAliases.contains(item.getExpression())).toList())
			.limit(Math.max(plan.getLimit() == null ? 100 : plan.getLimit(), sourceMaxRows))
			.preAggregationModelCodes(plan.getPreAggregationModelCodes().stream().filter(modelCodes::contains).toList())
			.sourceSubPlans(List.of(source))
			.freshnessNotices(plan.getFreshnessNotices().stream()
				.filter(item -> Objects.equals(item.getDatasourceId(), source.getDatasourceId())).toList())
			.expectedResult(expectedResult)
			.validationWarnings(plan.getValidationWarnings())
			.validationErrors(List.of())
			.executable(true)
			.build();
	}

	private InternalMergeKey internalMergeKey(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source) {
		if (plan.getMergePlan() == null || plan.getSourceSubPlans().size() < 2) {
			return null;
		}
		Set<String> modelCodes = Set.copyOf(source.getModelCodes());
		SemanticCatalogSnapshot catalog = semanticCatalogCache.get(plan.getProjectId(), plan.getProjectVersionId());
		for (String alias : List.of(Objects.toString(plan.getMergePlan().getLeftInputKey(), ""),
				Objects.toString(plan.getMergePlan().getRightInputKey(), ""))) {
			if (alias.isBlank()) {
				continue;
			}
			SemanticCatalogSnapshot.Dimension dimension = catalog.getDimensions()
				.stream()
				.filter(item -> alias.equals(item.getDimensionCode()))
				.filter(item -> modelCodes.contains(item.getModelCode()))
				.findFirst()
				.orElse(null);
			if (dimension != null && dimension.getColumnName() != null && !dimension.getColumnName().isBlank()) {
				return new InternalMergeKey(dimension.getModelCode(), dimension.getColumnName(), alias);
			}
		}
		return null;
	}

	private void protectInternalMergeKey(ResultSetBO result, SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source) {
		InternalMergeKey key = internalMergeKey(plan, source);
		if (key == null || result == null || result.getData() == null) {
			return;
		}
		for (Map<String, String> row : result.getData()) {
			if (row == null || !row.containsKey(key.alias())) {
				continue;
			}
			String value = row.get(key.alias());
			if (value != null && !value.isBlank()) {
				row.put(key.alias(), sha256(value));
			}
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to protect internal merge key", ex);
		}
	}

	private record InternalMergeKey(String modelCode, String columnName, String alias) {
	}

	public record ExecutionResult(ResultArtifact artifact, ResultSetBO resultSet, String sql) {
	}
}
