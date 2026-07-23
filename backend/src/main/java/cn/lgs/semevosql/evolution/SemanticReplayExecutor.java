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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.multisource.MultiSourceMergeEngine;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SensitiveResultSanitizer;
import cn.lgs.semevosql.sql.application.SqlCostGuard;
import cn.lgs.semevosql.sql.application.SqlExecutionAdmissionControl;
import cn.lgs.semevosql.sql.application.SqlExecutionGuard;
import cn.lgs.semevosql.sql.application.SqlPreflightPlanner;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationResult;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.util.DatabaseUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Executes replay SQL through the same guard, admission and result validation gates. */
@Service
@RequiredArgsConstructor
public class SemanticReplayExecutor {

	private static final int MAX_ROWS = 100;

	private static final int TIMEOUT_SECONDS = 10;

	private final DatabaseUtil databaseUtil;

	private final SqlExecutionGuard sqlExecutionGuard;

	private final SqlExecutionAdmissionControl admissionControl;

	private final SensitiveResultSanitizer sanitizer;

	private final SqlResultValidator resultValidator;

	private final MultiSourceMergeEngine mergeEngine;

	private final SqlPreflightPlanner sqlPreflightPlanner;

	private final SqlCostGuard sqlCostGuard;

	private final SemEvoSQLProperties properties;

	public List<Map<String, Object>> execute(Long projectId, SemanticCatalogSnapshot catalog, SemanticBlueprint plan,
			List<CompiledSourceQuery> sources) {
		return execute(projectId, catalog, plan, sources, "semantic-replay:" + java.util.UUID.randomUUID());
	}

	public List<Map<String, Object>> execute(Long projectId, SemanticCatalogSnapshot catalog, SemanticBlueprint plan,
			List<CompiledSourceQuery> sources, String cancellationKey) {
		return executeDetailed(projectId, catalog, plan, sources, cancellationKey).proof();
	}

	public ReplayExecution executeDetailed(Long projectId, SemanticCatalogSnapshot catalog, SemanticBlueprint plan,
			List<CompiledSourceQuery> sources, String cancellationKey) {
		long started = System.nanoTime();
		List<Map<String, Object>> proof = new ArrayList<>();
		List<ResultSetBO> successfulResults = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		long estimatedRows = 0;
		for (CompiledSourceQuery source : sources) {
			try {
				DbConfigBO config = databaseUtil.getDatasourceDbConfig(source.datasourceId());
				Set<String> allowed = Set.copyOf(source.physicalTables());
				sqlExecutionGuard.validate(source.sql(), config.getDialectType(), allowed, config.getSchema());
				SemEvoSQLProperties.SqlExecutionPolicy policy = properties.getSqlExecution();
				SqlCostGuard.CostAssessment staticCost = sqlCostGuard.validateSql(source.sql(), allowed,
						semanticTimeColumns(plan), policy);
				Accessor accessor = databaseUtil.getDatasourceAccessor(source.datasourceId());
				try (SqlExecutionAdmissionControl.Permit permit = admissionControl.acquire(projectId,
						source.datasourceId(), "semantic-replay")) {
					SqlCostGuard.CostAssessment explainCost = explain(accessor, config, source, cancellationKey,
							staticCost.tableCount(), policy);
					estimatedRows = saturatedAdd(estimatedRows, explainCost.estimatedRows());
					DbQueryParameter parameter = new DbQueryParameter().setSql(source.sql())
						.setParameters(source.parameters())
						.setSchema(config.getSchema())
						.setMaxRows(MAX_ROWS)
						.setQueryTimeoutSeconds(TIMEOUT_SECONDS)
						.setCancellationKey(cancellationKey + ":" + source.datasourceId());
					ResultSetBO result;
					try {
						result = accessor.executeSqlAndReturnObject(config, parameter);
					}
					catch (Exception ex) {
						permit.failure();
						throw new IllegalStateException("Replay SQL execution failed", ex);
					}
					sanitizer.sanitize(result, catalog);
					ValidationResult validation = resultValidator.validate(result, plan, MAX_ROWS);
					if (!validation.valid()) {
						throw new IllegalStateException(
								"Replay result validation failed: " + String.join("; ", validation.errors()));
					}
					permit.success();
					successfulResults.add(copy(result));
					Map<String, Object> sourceProof = new LinkedHashMap<>();
					sourceProof.put("datasourceId", source.datasourceId());
					sourceProof.put("rowCount", result.getData() == null ? 0 : result.getData().size());
					sourceProof.put("resultShapeHash", source.resultShapeHash());
					sourceProof.put("warnings", validation.warnings());
					sourceProof.put("columns", result.getColumn() == null ? List.of() : result.getColumn());
					sourceProof.put("rows", result.getData() == null ? List.of() : result.getData());
					sourceProof.put("authorityRank", authorityRank(plan, source.datasourceId()));
					sourceProof.put("freshness", freshness(plan, source.datasourceId()));
					sourceProof.put("estimatedRows", explainCost.estimatedRows());
					sourceProof.put("fullTableScan", explainCost.fullTableScan());
					sourceProof.put("costWarnings", explainCost.warnings());
					proof.add(java.util.Collections.unmodifiableMap(sourceProof));
				}
			}
			catch (RuntimeException ex) {
				if (!allowsPartial(plan) || sources.size() == 1) {
					throw ex;
				}
				String warning = "Datasource " + source.datasourceId() + " failed under ALLOW_PARTIAL: "
						+ Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
				warnings.add(warning);
				proof.add(Map.of("datasourceId", source.datasourceId(), "status", "FAILED", "error", warning));
			}
		}
		if (successfulResults.isEmpty()) {
			throw new IllegalStateException("Semantic Replay has no successful source result");
		}
		ResultSetBO merged = successfulResults.size() == 1 ? copy(successfulResults.get(0))
				: mergeEngine.merge(mergePolicy(plan), successfulResults);
		ValidationResult mergedValidation = resultValidator.validate(merged, plan, MAX_ROWS);
		if (!mergedValidation.valid()) {
			throw new IllegalStateException(
					"Merged replay result validation failed: " + String.join("; ", mergedValidation.errors()));
		}
		warnings.addAll(mergedValidation.warnings());
		Map<String, Object> mergeProof = new LinkedHashMap<>();
		mergeProof.put("artifactType", successfulResults.size() == 1 ? "SOURCE_RESULT" : "MERGED_RESULT");
		mergeProof.put("rowCount", merged.getData() == null ? 0 : merged.getData().size());
		mergeProof.put("columns", merged.getColumn() == null ? List.of() : merged.getColumn());
		mergeProof.put("rows", merged.getData() == null ? List.of() : merged.getData());
		mergeProof.put("partialFailurePolicy",
				plan.getMergePlan() == null ? "FAIL_ALL" : plan.getMergePlan().getPartialFailurePolicy());
		mergeProof.put("warnings", List.copyOf(warnings));
		proof.add(java.util.Collections.unmodifiableMap(mergeProof));
		long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		return new ReplayExecution(copy(merged), List.copyOf(proof), List.copyOf(warnings), latencyMs, sources.size(),
				successfulResults.size(), estimatedRows);
	}

	private SqlCostGuard.CostAssessment explain(Accessor accessor, DbConfigBO config, CompiledSourceQuery source,
			String cancellationKey, int tableCount, SemEvoSQLProperties.SqlExecutionPolicy policy) {
		if (!policy.isExplainEnabled()) {
			return new SqlCostGuard.CostAssessment(tableCount, 0, false, List.of());
		}
		String explainSql = sqlPreflightPlanner.explainSql(source.sql(), config.getDialectType()).orElse(null);
		if (explainSql == null) {
			return new SqlCostGuard.CostAssessment(tableCount, 0, false,
					List.of("EXPLAIN is not supported for this dialect"));
		}
		try {
			ResultSetBO explainResult = accessor.executeSqlAndReturnObject(config,
					new DbQueryParameter().setSql(explainSql)
						.setParameters(source.parameters())
						.setSchema(config.getSchema())
						.setMaxRows(Math.max(1, policy.getPreviewRows()))
						.setQueryTimeoutSeconds(policy.getPreflightTimeoutSeconds())
						.setCancellationKey(cancellationKey + ":" + source.datasourceId() + ":explain"));
			return sqlCostGuard.validateExplain(explainResult, tableCount, policy);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Replay EXPLAIN failed", ex);
		}
	}

	private Set<String> semanticTimeColumns(SemanticBlueprint plan) {
		java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
		plan.getMetrics()
			.stream()
			.map(SemanticBlueprint.MetricSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.forEach(columns::add);
		plan.getGrains()
			.stream()
			.map(SemanticBlueprint.GrainSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.forEach(columns::add);
		return Set.copyOf(columns);
	}

	private long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

	private ResultSetBO copy(ResultSetBO value) {
		List<Map<String, String>> rows = value.getData() == null ? List.of()
				: value.getData().stream().<Map<String, String>>map(row -> new LinkedHashMap<>(row)).toList();
		return ResultSetBO.builder()
			.column(value.getColumn() == null ? List.of() : new ArrayList<>(value.getColumn()))
			.data(rows)
			.errorMsg(value.getErrorMsg())
			.build();
	}

	private boolean allowsPartial(SemanticBlueprint plan) {
		return plan.getMergePlan() != null
				&& "ALLOW_PARTIAL".equalsIgnoreCase(plan.getMergePlan().getPartialFailurePolicy());
	}

	private MergePolicy mergePolicy(SemanticBlueprint plan) {
		if (plan.getMergePlan() == null) {
			throw new IllegalStateException("Multi-source Replay requires a Merge Plan");
		}
		SemanticBlueprint.MergePlan value = plan.getMergePlan();
		return MergePolicy.builder()
			.policyCode(value.getPolicyCode())
			.mergeType(value.getMergeType())
			.relationshipCode(value.getRelationshipCode())
			.leftInputKey(value.getLeftInputKey())
			.rightInputKey(value.getRightInputKey())
			.outputKey(value.getOutputKey())
			.inputGrain(value.getInputGrain())
			.nullPolicy(value.getNullPolicy())
			.duplicatePolicy(value.getDuplicatePolicy())
			.maxRows(value.getMaxRows())
			.partialFailurePolicy(value.getPartialFailurePolicy())
			.calculationExpression(value.getCalculationExpression())
			.build();
	}

	private Integer authorityRank(SemanticBlueprint plan, Integer datasourceId) {
		return plan.getSourceSubPlans()
			.stream()
			.filter(value -> Objects.equals(value.getDatasourceId(), datasourceId))
			.map(SemanticBlueprint.SourceSubPlan::getAuthorityRank)
			.findFirst()
			.orElse(null);
	}

	private Map<String, Object> freshness(SemanticBlueprint plan, Integer datasourceId) {
		return plan.getFreshnessNotices()
			.stream()
			.filter(value -> Objects.equals(value.getDatasourceId(), datasourceId))
			.findFirst()
			.<Map<String, Object>>map(
					value -> Map.of("businessDateField", Objects.toString(value.getBusinessDateField(), ""), "timeZone",
							Objects.toString(value.getTimeZone(), ""), "freshnessType",
							Objects.toString(value.getFreshnessType(), ""), "latencyMinutes",
							value.getLatencyMinutes() == null ? 0 : value.getLatencyMinutes()))
			.orElse(Map.of());
	}

	public record ReplayExecution(ResultSetBO finalResult, List<Map<String, Object>> proof, List<String> warnings,
			long latencyMs, int sourceCount, int successfulSourceCount, long estimatedRows) {
	}

}
