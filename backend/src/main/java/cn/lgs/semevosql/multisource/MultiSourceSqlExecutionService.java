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
package cn.lgs.semevosql.multisource;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.SqlTraceRequest;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SensitiveResultSanitizer;
import cn.lgs.semevosql.sql.application.SqlCostGuard;
import cn.lgs.semevosql.sql.application.SqlExecutionAdmissionControl;
import cn.lgs.semevosql.sql.application.SqlExecutionGuard;
import cn.lgs.semevosql.sql.application.SqlPreflightPlanner;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.service.nl2sql.Nl2SqlService;
import cn.lgs.semevosql.util.DatabaseUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiSourceSqlExecutionService {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

	private final SemEvoSQLProperties properties;

	private final SqlExecutionGuard sqlExecutionGuard;

	private final SqlExecutionAdmissionControl admissionControl;

	private final SqlCostGuard sqlCostGuard;

	private final SqlPreflightPlanner sqlPreflightPlanner;

	private final SensitiveResultSanitizer sensitiveResultSanitizer;

	private final SqlResultValidator sqlResultValidator;

	private final SemanticCatalogCache semanticCatalogCache;

	private final SemEvoSQLProductionService productionService;

	public SqlDialect dialect(Integer datasourceId) {
		if (datasourceId == null || datasourceId <= 0) {
			throw new IllegalArgumentException("datasourceId must be positive");
		}
		return SqlDialect.from(databaseUtil.getDatasourceDbConfig(datasourceId).getDialectType());
	}

	public ResultSetBO execute(Long projectId, Long projectVersionId, String executionOwner, Integer datasourceId,
			Set<String> allowedTables, String sql, SemanticBlueprint semanticPlan) throws Exception {
		return execute(projectId, projectVersionId, executionOwner, executionOwner, datasourceId, allowedTables, sql,
				List.of(), semanticPlan, null, null);
	}

	public ResultSetBO execute(Long projectId, Long projectVersionId, String principalId, String executionOwner,
			Integer datasourceId, Set<String> allowedTables, String sql, List<Object> parameters,
			SemanticBlueprint semanticPlan) throws Exception {
		return execute(projectId, projectVersionId, principalId, executionOwner, datasourceId, allowedTables, sql, parameters,
				semanticPlan, null, null);
	}

	public ResultSetBO execute(Long projectId, Long projectVersionId, String principalId, String executionOwner,
			Integer datasourceId, Set<String> allowedTables, String sql, List<Object> parameters,
			SemanticBlueprint semanticPlan, String attemptId, String traceKey) throws Exception {
		long startNanos = System.nanoTime();
		Map<String, Object> guardSummary = new LinkedHashMap<>();
		Map<String, Object> costSummary = new LinkedHashMap<>();
		Map<String, Object> explainSummary = new LinkedHashMap<>();
		Map<String, Object> previewSummary = new LinkedHashMap<>();
		Map<String, Object> resultSummary = new LinkedHashMap<>();
		String normalizedSql = nl2SqlService.sqlTrim(sql);
		if (datasourceId == null || datasourceId <= 0) {
			throw new IllegalArgumentException("datasourceId must be positive");
		}
		Set<String> normalizedAllowedTables = allowedTables == null ? Set.of()
				: allowedTables.stream()
					.filter(value -> value != null && !value.isBlank())
					.collect(Collectors.toCollection(LinkedHashSet::new));
		if (normalizedAllowedTables.isEmpty()) {
			throw new IllegalArgumentException("Source subplan must expose at least one physical table");
		}
		if (normalizedSql == null || normalizedSql.isBlank()) {
			throw new IllegalArgumentException("Generated SQL is empty");
		}
		if (normalizedSql.length() > properties.getSqlExecution().getMaxSqlLength()) {
			throw new IllegalArgumentException(
					"SQL text exceeds configured max length: " + properties.getSqlExecution().getMaxSqlLength());
		}
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		SemanticCatalogSnapshot catalog = semanticCatalogCache.get(projectId, projectVersionId);
		String effectiveExecutionOwner = executionOwner == null || executionOwner.isBlank() ? "multi-source"
				: executionOwner;
		SqlExecutionAdmissionControl.Permit permit = admissionControl.acquire(projectId, datasourceId, principalId);
		try {
			SqlExecutionGuard.GuardResult guard = sqlExecutionGuard.validate(normalizedSql, dbConfig.getDialectType(),
					normalizedAllowedTables, dbConfig.getSchema());
			guardSummary.put("decision", "PASS");
			guardSummary.put("referencedTables", guard.referencedTables());
			guardSummary.put("allowedTables", normalizedAllowedTables);
			Set<String> timeColumns = semanticTimeColumns(semanticPlan);
			SqlCostGuard.CostAssessment staticCost = sqlCostGuard.validateSql(normalizedSql, guard.referencedTables(), timeColumns,
					properties.getSqlExecution());
			costSummary.put("decision", "PASS");
			costSummary.put("tableCount", staticCost.tableCount());
			costSummary.put("timeColumns", timeColumns);
			Accessor accessor = databaseUtil.getDatasourceAccessor(datasourceId);
			SqlCostGuard.CostAssessment explainCost = runPreflight(accessor, dbConfig, normalizedSql, parameters,
					staticCost.tableCount(), catalog, effectiveExecutionOwner, explainSummary, previewSummary, semanticPlan);
			if (explainCost != null) {
				putExplainCost(explainSummary, explainCost);
			}
			ResultSetBO result = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(normalizedSql, dbConfig.getSchema(), properties.getSqlExecution().getMaxRows(),
							properties.getSqlExecution().getQueryTimeoutSeconds(), effectiveExecutionOwner, parameters));
			sensitiveResultSanitizer.sanitize(result, catalog);
			SqlResultValidator.ValidationResult validation = sqlResultValidator.validate(result, semanticPlan,
					properties.getSqlExecution().getMaxRows());
			resultSummary.put("decision", validation.valid() ? "PASS" : "REJECT");
			resultSummary.put("rowCount", result == null || result.getData() == null ? 0 : result.getData().size());
			resultSummary.put("warnings", validation.warnings());
			resultSummary.put("compilerMode", semanticPlan == null ? "" : semanticPlan.getCompilerMode());
			if (!validation.valid()) {
				throw new IllegalStateException(
						"SQL result validation failed: " + String.join("; ", validation.errors()));
			}
			permit.success();
			recordSqlTrace(attemptId, traceKey, normalizedSql, guardSummary, costSummary, explainSummary, previewSummary,
					resultSummary, "SUCCEEDED", startNanos, null);
			return result;
		}
		catch (Exception ex) {
			permit.failure();
			guardSummary.putIfAbsent("decision", "REJECT");
			resultSummary.putIfAbsent("decision", "FAILED");
			recordSqlTrace(attemptId, traceKey, normalizedSql, guardSummary, costSummary, explainSummary, previewSummary,
					resultSummary, "FAILED", startNanos, ex.getClass().getSimpleName());
			throw ex;
		}
		finally {
			permit.close();
		}
	}

	public String readFreshnessWatermark(Long projectId, Integer datasourceId, String executionOwner,
			SemanticBlueprint.SourceSubPlan sourcePlan, SemanticBlueprint.FreshnessNotice freshness) throws Exception {
		if (freshness == null || freshness.getBusinessDateField() == null
				|| freshness.getBusinessDateField().isBlank()) {
			throw new IllegalStateException("Freshness policy is missing for datasource " + datasourceId);
		}
		if (sourcePlan == null || sourcePlan.getPhysicalTables() == null || sourcePlan.getPhysicalTables().isEmpty()) {
			throw new IllegalStateException("Freshness watermark requires a physical source table");
		}
		String table = sourcePlan.getPhysicalTables().get(0);
		String column = freshness.getBusinessDateField();
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		String watermarkSql = "SELECT MAX(" + quoteIdentifier(column, dbConfig.getDialectType())
				+ ") AS qw_freshness_as_of FROM " + quoteQualifiedIdentifier(table, dbConfig.getDialectType());
		SqlExecutionAdmissionControl.Permit permit = admissionControl.acquire(projectId, datasourceId,
				executionOwner == null ? "freshness-watermark" : executionOwner + ":freshness");
		try {
			Accessor accessor = databaseUtil.getDatasourceAccessor(datasourceId);
			ResultSetBO result = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(watermarkSql, dbConfig.getSchema(), 1,
							properties.getSqlExecution().getPreflightTimeoutSeconds(), executionOwner + ":freshness"));
			List<Map<String, String>> rows = result == null || result.getData() == null ? List.of() : result.getData();
			if (rows.isEmpty()) {
				throw new IllegalStateException(
						"Freshness watermark query returned no row for datasource " + datasourceId);
			}
			String value = rows.get(0)
				.entrySet()
				.stream()
				.filter(entry -> "qw_freshness_as_of".equalsIgnoreCase(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("Freshness watermark is empty for datasource " + datasourceId);
			}
			permit.success();
			return value;
		}
		catch (Exception ex) {
			permit.failure();
			throw ex;
		}
		finally {
			permit.close();
		}
	}

	private String quoteQualifiedIdentifier(String value, String dialect) {
		String[] parts = value.split("\\.");
		return java.util.Arrays.stream(parts)
			.map(part -> quoteIdentifier(part, dialect))
			.collect(Collectors.joining("."));
	}

	private String quoteIdentifier(String value, String dialect) {
		if (value == null || !IDENTIFIER.matcher(value).matches()) {
			throw new IllegalArgumentException("Unsafe SQL identifier in freshness policy: " + value);
		}
		String quote = dialect != null && dialect.toLowerCase().contains("mysql") ? "`" : "\"";
		return quote + value + quote;
	}

	private SqlCostGuard.CostAssessment runPreflight(Accessor accessor, DbConfigBO dbConfig, String sql,
			List<Object> parameters, int tableCount, SemanticCatalogSnapshot catalog, String cancellationKey,
			Map<String, Object> explainSummary, Map<String, Object> previewSummary, SemanticBlueprint semanticPlan)
			throws Exception {
		SemEvoSQLProperties.SqlExecutionPolicy policy = properties.getSqlExecution();
		explainSummary.put("explainEnabled", policy.isExplainEnabled());
		explainSummary.put("previewEnabled", policy.isPreviewEnabled());
		explainSummary.put("compilerMode",
				semanticPlan == null || semanticPlan.getCompilerMode() == null ? "" : semanticPlan.getCompilerMode());
		explainSummary.put("parameterCount", parameters == null ? 0 : parameters.size());
		SqlCostGuard.CostAssessment explainCost = null;
		if (policy.isExplainEnabled()) {
			String explainSql = sqlPreflightPlanner.explainSql(sql, dbConfig.getDialectType()).orElse(null);
			if (explainSql != null) {
				ResultSetBO explain = accessor.executeSqlAndReturnObject(dbConfig,
						queryParameter(explainSql, dbConfig.getSchema(), Math.max(1, policy.getPreviewRows()),
								policy.getPreflightTimeoutSeconds(), cancellationKey + ":explain", parameters));
				explainCost = sqlCostGuard.validateExplain(explain, tableCount, policy, dbConfig.getDialectType());
				explainSummary.put("decision", "PASS");
			}
		}
		if (policy.isPreviewEnabled()) {
			ResultSetBO preview = accessor.executeSqlAndReturnObject(dbConfig,
					queryParameter(sql, dbConfig.getSchema(), Math.max(1, policy.getPreviewRows()),
							policy.getPreflightTimeoutSeconds(), cancellationKey + ":preview", parameters));
			sensitiveResultSanitizer.sanitize(preview, catalog);
			previewSummary.put("decision", "PASS");
			previewSummary.put("rowCount", preview == null || preview.getData() == null ? 0 : preview.getData().size());
		}
		return explainCost;
	}

	private void putExplainCost(Map<String, Object> summary, SqlCostGuard.CostAssessment cost) {
		summary.put("estimatedScanRows", cost.estimatedRows());
		summary.put("estimatedIntermediateRows", cost.estimatedIntermediateRows());
		summary.put("estimatedJoinRows", cost.estimatedJoinRows());
		summary.put("estimatedSortRows", cost.estimatedSortRows());
		summary.put("estimatedAggregateRows", cost.estimatedAggregateRows());
		summary.put("estimatedCost", cost.estimatedCost());
		summary.put("fullTableScan", cost.fullTableScan());
		summary.put("expensiveOperators", cost.expensiveOperators());
		summary.put("warnings", cost.warnings());
	}

	private void recordSqlTrace(String attemptId, String traceKey, String sql, Map<String, Object> guardSummary,
			Map<String, Object> costSummary, Map<String, Object> explainSummary, Map<String, Object> previewSummary,
			Map<String, Object> resultSummary, String status, long startNanos, String errorType) {
		if (attemptId == null || attemptId.isBlank() || traceKey == null || traceKey.isBlank()) {
			return;
		}
		try {
			productionService.recordSqlTrace(attemptId,
					new SqlTraceRequest(traceKey, sql, Map.copyOf(guardSummary), Map.copyOf(costSummary),
							Map.copyOf(explainSummary), Map.copyOf(previewSummary), Map.copyOf(resultSummary), status, 0,
					Math.max(0, (System.nanoTime() - startNanos) / 1_000_000), errorType));
		}
		catch (LateRunResultDroppedException | RunDeadlineExceededException late) {
			throw late;
		}
		catch (RuntimeException traceError) {
			log.warn("Unable to persist verified SQL trace for attempt {}: {}", attemptId, traceError.getMessage());
		}
	}

	private Set<String> semanticTimeColumns(SemanticBlueprint semanticPlan) {
		if (semanticPlan == null) {
			return Set.of();
		}
		Set<String> columns = semanticPlan.getMetrics()
			.stream()
			.map(SemanticBlueprint.MetricSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		semanticPlan.getGrains()
			.stream()
			.map(SemanticBlueprint.GrainSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.forEach(columns::add);
		return Set.copyOf(columns);
	}

	private DbQueryParameter queryParameter(String sql, String schema, int maxRows, int timeoutSeconds,
			String cancellationKey) {
		return queryParameter(sql, schema, maxRows, timeoutSeconds, cancellationKey, List.of());
	}

	private DbQueryParameter queryParameter(String sql, String schema, int maxRows, int timeoutSeconds,
			String cancellationKey, List<Object> parameters) {
		return new DbQueryParameter().setSql(sql)
			.setSchema(schema)
			.setParameters(parameters == null ? List.of() : List.copyOf(parameters))
			.setMaxRows(maxRows)
			.setQueryTimeoutSeconds(timeoutSeconds)
			.setCancellationKey(cancellationKey);
	}

}
