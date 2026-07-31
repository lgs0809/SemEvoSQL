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

import static cn.lgs.semevosql.constant.Constant.ADVANCED_EXECUTION_FALLBACK;
import static cn.lgs.semevosql.constant.Constant.AGENT_ID;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.CATALOG_HASH;
import static cn.lgs.semevosql.constant.Constant.DATASOURCE_ID;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_EXECUTED_STEP;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_RESULT_PAYLOAD;
import static cn.lgs.semevosql.constant.Constant.PLAN_CURRENT_STEP;
import static cn.lgs.semevosql.constant.Constant.PLAN_VALIDATION_ERROR;
import static cn.lgs.semevosql.constant.Constant.PLANNER_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_PATTERN_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.SQL_COMPILED_PARAMETERS;
import static cn.lgs.semevosql.constant.Constant.SQL_COMPILER_MODE;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTED_QUERY_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_COUNT;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_PATTERN_TEMPLATE_ID;
import static cn.lgs.semevosql.constant.Constant.SQL_PHYSICAL_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_DRY_PLAN_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_REGENERATE_REASON;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_MEMORY_BY_STEP;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.DisplayStyleBO;
import cn.lgs.semevosql.bo.schema.ResultBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.dto.datasource.SqlRetryDto;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.prompt.PromptHelper;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.SqlTraceRequest;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.QueryRepairPolicy.BudgetDecision;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.run.RunNodeEffectService;
import cn.lgs.semevosql.run.RunDeadlineUtil;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.run.LateRunResultDroppedException;
import cn.lgs.semevosql.run.RunDeadlineExceededException;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.sql.application.SensitiveResultSanitizer;
import cn.lgs.semevosql.sql.application.SqlCostGuard;
import cn.lgs.semevosql.sql.application.SqlExecutionGuard;
import cn.lgs.semevosql.sql.application.SqlExecutionAdmissionControl;
import cn.lgs.semevosql.sql.application.SqlGuardViolationException;
import cn.lgs.semevosql.sql.application.SqlPreflightPlanner;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.sql.application.SqlValidationClassifier;
import cn.lgs.semevosql.sql.application.SqlValidationDecisionException;
import cn.lgs.semevosql.sql.application.SqlValidationResult;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationResult;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.service.nl2sql.Nl2SqlService;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonParseUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.MarkdownParserUtil;
import cn.lgs.semevosql.util.PlanProcessUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * SQL execution node that executes SQL queries against the database.
 *
 * <p>
 * This node is responsible for: - Executing SQL queries generated by previous nodes -
 * Handling query results and errors - Providing streaming feedback to users during
 * execution - Managing step-by-step result accumulation
 *
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlExecuteNode implements NodeAction {

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

	private final LlmService llmService;

	private final SemEvoSQLProperties properties;

	private final JsonParseUtil jsonParseUtil;

	private final SqlExecutionGuard sqlExecutionGuard;

	private final SqlExecutionAdmissionControl admissionControl;

	private final SqlValidationClassifier sqlValidationClassifier;

	private final SqlCostGuard sqlCostGuard;

	private final SemanticCatalogCache semanticCatalogCache;

	private final SensitiveResultSanitizer sensitiveResultSanitizer;

	private final SqlPreflightPlanner sqlPreflightPlanner;

	private final SqlResultValidator sqlResultValidator;

	private final RunNodeEffectService runNodeEffectService;

	private final SemEvoSQLProductionService productionService;

	private final QueryPatternTemplateService patternTemplateService;

	private final QueryRepairPolicy repairPolicy;

	private final RunExecutionFenceService executionFence;

	private static final int SAMPLE_DATA_NUMBER = 20;

	@Override
	@SuppressWarnings("unchecked") // Graph state stores generic Map/List values behind runtime Class tokens.
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String executionRunId = StateUtil.getStringValue(state, RUN_ID, "");
		String executionAttemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		assertActiveIfBound(executionRunId, executionAttemptId);

		Integer currentStep = PlanProcessUtil.getCurrentStepNumber(state);

		String generatedSql = nl2SqlService.sqlTrim(StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT));
		String compilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "CONSTRAINED_GENERATION");
		String dryPlannedPhysicalSql = nl2SqlService.sqlTrim(StateUtil.getStringValue(state, SQL_PHYSICAL_OUTPUT, ""));
		String sqlQuery = "SEMANTIC_SQL".equalsIgnoreCase(compilerMode) && !dryPlannedPhysicalSql.isBlank()
				? dryPlannedPhysicalSql : generatedSql;
		List<Object> sqlParameters = StateUtil.getObjectValue(state, SQL_COMPILED_PARAMETERS, List.class, List.of());

		log.info("Executing physical SQL query: {} (compilerMode={}, parameterCount={})", sqlQuery, compilerMode,
				sqlParameters.size());

		Integer datasourceId = StateUtil.getObjectValue(state, DATASOURCE_ID, Integer.class);
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		SemanticBlueprint semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
		SemanticCatalogSnapshot catalog = semanticCatalogCache.get(projectId, projectVersionId);
		Set<String> allowedTables = semanticPlan == null ? Set.of()
				: semanticPlan.getModels()
					.stream()
					.map(SemanticBlueprint.ModelSelection::getPhysicalTable)
					.collect(Collectors.toUnmodifiableSet());
		String runId = executionRunId;
		Map<String, String> existingResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());
		Map<String, String> existingQueries = StateUtil.getObjectValue(state, SQL_EXECUTED_QUERY_OUTPUT, Map.class,
				new HashMap<>());
		Map<String, Object> effectInput = new LinkedHashMap<>();
		effectInput.put("datasourceId", datasourceId);
		effectInput.put("projectVersionId", projectVersionId);
		effectInput.put("catalogHash", state.value(CATALOG_HASH, ""));
		effectInput.put("currentStep", currentStep);
		effectInput.put("generatedSql", generatedSql);
		effectInput.put("physicalSql", sqlQuery);
		effectInput.put("dryPlan", StateUtil.getObjectValue(state, SQL_DRY_PLAN_OUTPUT, Map.class, Map.of()));
		effectInput.put("parameters", sqlParameters);
		effectInput.put("compilerMode", compilerMode);
		effectInput.put("plannerOutput", StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT, ""));
		effectInput.put("previousStepResults", new TreeMap<>(existingResults));
		String effectInputHash = runNodeEffectService
			.inputHash(JsonUtil.getObjectMapper().writeValueAsString(effectInput));
		String effectKey = "sql-execute:" + currentStep;
		String completed = runNodeEffectService.completedPayload(runId, effectKey, effectInputHash).orElse(null);
		if (completed != null) {
			return replaySqlExecution(state, readSqlEffect(completed));
		}

		return executeSqlQuery(state, currentStep, sqlQuery, sqlParameters, dbConfig, datasourceId, projectId,
				allowedTables, semanticPlan, catalog, existingResults, existingQueries, runId, effectKey,
				effectInputHash);
	}

	/**
	 * Executes the SQL query against the database and handles the results.
	 *
	 * <p>
	 * This method follows the business-logic-first pattern: 1. Execute the actual SQL
	 * query immediately 2. Process and store the results 3. Create streaming output for
	 * user experience only
	 * @param state The overall state containing execution context
	 * @param currentStep The current step number in the execution plan
	 * @param sqlQuery The SQL query to execute
	 * @param dbConfig The database configuration to use for execution
	 * @param datasourceId The pinned SemEvoSQL datasource ID
	 * @param allowedTables Tables exposed by the pinned Semantic Blueprint
	 * @param semanticPlan Pinned Semantic Blueprint used for result validation
	 * @return Map containing the generator for streaming output
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> executeSqlQuery(OverAllState state, Integer currentStep, String sqlQuery,
			List<Object> sqlParameters, DbConfigBO dbConfig, Integer datasourceId, Long projectId,
			Set<String> allowedTables, SemanticBlueprint semanticPlan, SemanticCatalogSnapshot catalog,
			Map<String, String> existingResults, Map<String, String> existingQueries, String runId, String effectKey,
			String effectInputHash) {
		final Map<String, Object> result = new HashMap<>();
		String attemptId = state.value(ATTEMPT_ID, "");
		int retryCount = state.value(SQL_GENERATE_COUNT, 0);
		String cancellationKey = runId + ":sql-step:" + currentStep;

		// 先返回流式数据，在执行数据库查询
		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			long startNanos = System.nanoTime();
			Map<String, Object> guardSummary = new LinkedHashMap<>();
			Map<String, Object> costSummary = new LinkedHashMap<>();
			Map<String, Object> preflightSummary = new LinkedHashMap<>();
			Map<String, Object> resultSummary = new LinkedHashMap<>();
			emitter.next(ChatResponseUtil.createResponse("开始执行SQL..."));
			emitter.next(ChatResponseUtil.createResponse("执行SQL查询："));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
			emitter.next(ChatResponseUtil.createResponse(sqlQuery));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()));
			ResultBO resultBO = ResultBO.builder().build();

			SqlExecutionAdmissionControl.Permit permit = null;
			try {
				assertActiveIfBound(runId, attemptId);
				permit = admissionControl.acquire(projectId, datasourceId, state.value(AGENT_ID, "anonymous"));
				if (sqlQuery.length() > properties.getSqlExecution().getMaxSqlLength()) {
					throw new SqlGuardViolationException("SQL text exceeds the configured maximum length: "
							+ properties.getSqlExecution().getMaxSqlLength());
				}
				SqlExecutionGuard.GuardResult guardResult = sqlExecutionGuard.validate(sqlQuery,
						dbConfig.getDialectType(), allowedTables, dbConfig.getSchema());
				log.info("SQL guard passed, referenced tables: {}", guardResult.referencedTables());
				guardSummary.put("decision", "PASS");
				guardSummary.put("referencedTables", guardResult.referencedTables());
				guardSummary.put("allowedTables", allowedTables);
				Set<String> timeColumns = semanticTimeColumns(semanticPlan);
				SqlCostGuard.CostAssessment staticCost = sqlCostGuard.validateSql(sqlQuery,
						guardResult.referencedTables(), timeColumns, properties.getSqlExecution());
				costSummary.put("decision", "PASS");
				costSummary.put("tableCount", staticCost.tableCount());
				costSummary.put("timeColumns", timeColumns);

				Accessor dbAccessor = databaseUtil.getDatasourceAccessor(datasourceId);
				preflightSummary.put("explainEnabled", properties.getSqlExecution().isExplainEnabled());
				preflightSummary.put("previewEnabled", properties.getSqlExecution().isPreviewEnabled());
				preflightSummary.put("compilerMode", state.value(SQL_COMPILER_MODE, "CONSTRAINED_GENERATION"));
				preflightSummary.put("dryPlan", StateUtil.getObjectValue(state, SQL_DRY_PLAN_OUTPUT, Map.class, Map.of()));
				preflightSummary.put("parameterCount", sqlParameters.size());
				SqlCostGuard.CostAssessment explainCost = runPreflight(dbAccessor, dbConfig, sqlQuery, sqlParameters,
						staticCost.tableCount(), catalog, cancellationKey);
				preflightSummary.put("decision", "PASS");
				if (explainCost != null) {
					preflightSummary.put("estimatedScanRows", explainCost.estimatedRows());
					preflightSummary.put("estimatedIntermediateRows", explainCost.estimatedIntermediateRows());
					preflightSummary.put("estimatedJoinRows", explainCost.estimatedJoinRows());
					preflightSummary.put("estimatedSortRows", explainCost.estimatedSortRows());
					preflightSummary.put("estimatedAggregateRows", explainCost.estimatedAggregateRows());
					preflightSummary.put("estimatedCost", explainCost.estimatedCost());
					preflightSummary.put("fullTableScan", explainCost.fullTableScan());
					preflightSummary.put("expensiveOperators", explainCost.expensiveOperators());
				}
				emitter.next(ChatResponseUtil.createResponse("SQL预检通过，开始正式执行..."));

				DbQueryParameter dbQueryParameter = queryParameter(sqlQuery, sqlParameters, dbConfig.getSchema(),
						properties.getSqlExecution().getMaxRows(),
						properties.getSqlExecution().getQueryTimeoutSeconds(), cancellationKey);
				ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
				sensitiveResultSanitizer.sanitize(resultSetBO, catalog);
				String executionCompilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "");
				boolean advancedExecution = state.value(ADVANCED_EXECUTION_FALLBACK, false)
						|| "SEMANTIC_SQL".equalsIgnoreCase(executionCompilerMode);
				ValidationMode resultValidationMode = advancedExecution ? ValidationMode.ADVANCED_EXECUTION
						: ValidationMode.STRICT_SEMANTIC_PLAN;
				ValidationResult resultValidation = sqlResultValidator.validate(resultSetBO, semanticPlan,
						properties.getSqlExecution().getMaxRows(), resultValidationMode);
				for (String error : resultValidation.errors()) {
					log.warn("SQL deterministic result review error: {}", error);
				}
				for (String warning : resultValidation.warnings()) {
					log.warn("SQL deterministic result review warning: {}", warning);
				}
				emitter.next(ChatResponseUtil.createResponse(resultValidation.valid()
						? "SQL执行完成，进入结果验收。" : "SQL执行完成，但确定性结果验收发现问题，进入受限修复判定。"));
				// 调用大模型获取图表配置信息并填充到ResultSetBO中
				DisplayStyleBO displayStyleBO = enrichResultSetWithChartConfig(state, resultSetBO);
				resultBO.setResultSet(resultSetBO);
				resultBO.setDisplayStyle(displayStyleBO);

				String strResultSetJson = JsonUtil.getObjectMapper().writeValueAsString(resultSetBO);
				String strResultJson = JsonUtil.getObjectMapper().writeValueAsString(resultBO);

				// The result remains internal until post-execution review accepts it. This prevents
				// a semantically rejected candidate result from being exposed to the user.

				// Persist the executed SQL as derived lineage after the database side
				// effect succeeds.
				Map<String, String> updatedResults = PlanProcessUtil.addStepResult(existingResults, currentStep,
						strResultSetJson);
				Map<String, String> updatedQueries = SqlExecutionLineage.append(existingQueries, currentStep, sqlQuery);

				log.info("SQL execution successful, result count: {}",
						resultSetBO.getData() != null ? resultSetBO.getData().size() : 0);

				// Store candidate SQL results for post-execution review and downstream code execution.
				// SQL_GENERATE_COUNT is reset only after review accepts the result.
				List<Map<String, String>> resultData = resultSetBO.getData() == null ? List.of()
						: resultSetBO.getData();
				ResultMemory resultMemory = accumulateResultMemory(state, currentStep, resultData);
				result.put(SQL_EXECUTE_NODE_OUTPUT, updatedResults);
				result.put(SQL_EXECUTED_QUERY_OUTPUT, updatedQueries);
				result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
				result.put(SQL_RESULT_MEMORY_BY_STEP, resultMemory.byStep());
				result.put(SQL_RESULT_LIST_MEMORY, resultMemory.flattened());
				result.put(PLAN_CURRENT_STEP, currentStep + 1);
				result.put(LAST_SQL_EXECUTED_STEP, currentStep);
				result.put(LAST_SQL_RESULT_PAYLOAD, strResultJson);
				SqlExecutionEffect effect = new SqlExecutionEffect(sqlQuery, strResultJson, updatedResults,
						updatedQueries, resultMemory.flattened(), resultMemory.byStep(), currentStep + 1);
				assertActiveIfBound(runId, attemptId);
				runNodeEffectService.recordCompleted(runId, attemptId, effectKey, effectInputHash, writeSqlEffect(effect));
				resultSummary.put("decision", "PASS");
				resultSummary.put("rowCount", resultData.size());
				resultSummary.put("warnings", resultValidation.warnings());
				String compilerMode = StateUtil.getStringValue(state, SQL_COMPILER_MODE, "");
				if (!compilerMode.isBlank()) {
					resultSummary.put("compilerMode", compilerMode);
				}
				String patternTemplateId = StateUtil.getStringValue(state, SQL_PATTERN_TEMPLATE_ID, "");
				if (!patternTemplateId.isBlank()) {
					resultSummary.put("patternTemplateId", patternTemplateId);
					resultSummary.put("patternId", StateUtil.getStringValue(state, QUERY_PATTERN_ID, ""));
				}
				recordSqlTrace(attemptId, effectKey + ":" + effectInputHash, sqlQuery, guardSummary, costSummary,
						preflightSummary, resultSummary, "SUCCEEDED", retryCount, startNanos, null);
				markPatternTemplateUsedBestEffort(StateUtil.getStringValue(state, SQL_PATTERN_TEMPLATE_ID, ""), runId,
						attemptId);
				permit.success();
			}
			catch (SqlGuardViolationException e) {
				String errorMessage = "SQL安全门禁拒绝: " + e.getMessage();
				log.warn("{}; SQL: {}", errorMessage, sqlQuery);
				guardSummary.put("decision", "FATAL");
				guardSummary.put("message", java.util.Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
				resultSummary.put("decision", "FATAL");
				recordSqlTrace(attemptId, effectKey + ":" + effectInputHash, sqlQuery, guardSummary, costSummary,
						preflightSummary, resultSummary, "REJECTED", retryCount, startNanos, "GUARD_REJECTED");
				result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
				emitter.error(new SqlValidationDecisionException(sqlValidationClassifier.classify(e, retryCount), e));
			}
			catch (LateRunResultDroppedException | RunDeadlineExceededException late) {
				// The database/model work may not be interruptible, but no late result is allowed
				// to enter the retry path or produce a durable side effect.
				emitter.error(late);
			}
			catch (Exception e) {
				SqlValidationResult validation = sqlValidationClassifier.classify(e, retryCount);
				if (permit != null && "sql-execute".equals(validation.allowedReturnNode())) {
					permit.failure();
				}
				String errorMessage = java.util.Objects.toString(validation.message(), e.getClass().getSimpleName());
				log.error("SQL execution failed - SQL as follows: \n {} \n ", sqlQuery, e);
				resultSummary.put("decision", validation.decision().name());
				resultSummary.put("allowedReturnNode", validation.allowedReturnNode());
				resultSummary.put("retryBudget", validation.retryBudget());
				resultSummary.put("retriesUsed", validation.retriesUsed());
				recordSqlTrace(attemptId, effectKey + ":" + effectInputHash, sqlQuery, guardSummary, costSummary,
						preflightSummary, resultSummary, "FAILED", retryCount, startNanos, validation.errorType());
				if (validation.retryAllowed()) {
					RepairBudget currentBudget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class,
							RepairBudget.empty());
					BudgetDecision repairDecision = repairPolicy.consumeTransition(currentBudget, Decision.RETRY_SQL);
					if (repairDecision.allowed()) {
						result.put(QUERY_REPAIR_BUDGET, repairDecision.budget());
						result.put(SQL_REGENERATE_REASON, SqlRetryDto.sqlExecute(errorMessage));
						emitter.next(ChatResponseUtil.createResponse("SQL执行失败，进入统一有界修复: " + errorMessage));
					}
					else {
						BudgetDecision replanDecision = repairPolicy.consumeTransition(currentBudget, Decision.REPLAN_EXECUTION);
						if (replanDecision.allowed()) {
							result.put(QUERY_REPAIR_BUDGET, replanDecision.budget());
							result.put(PLAN_VALIDATION_ERROR,
									"EXECUTION_REPLAN_REQUIRED: SQL repair budget exhausted for the current execution strategy; "
											+ errorMessage);
							result.put(PLAN_CURRENT_STEP, 1);
							result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
							result.put(SQL_EXECUTE_NODE_OUTPUT, Map.of());
							result.put(SQL_EXECUTED_QUERY_OUTPUT, Map.of());
							result.put(SQL_RESULT_MEMORY_BY_STEP, Map.of());
							result.put(SQL_RESULT_LIST_MEMORY, List.of());
							result.put(LAST_SQL_EXECUTED_STEP, 0);
							result.put(LAST_SQL_RESULT_PAYLOAD, "");
							emitter.next(ChatResponseUtil.createResponse(
									"当前执行策略的 SQL 修复预算已用尽，保留语义绑定并回到 Planner 重新规划执行步骤。"));
						}
						else {
							result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
							emitter.error(new IllegalStateException(
									"SQL repair and execution replan budgets exhausted: " + replanDecision.reason(), e));
						}
					}
				}
				else {
					result.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
					emitter.error(new SqlValidationDecisionException(validation, e));
				}
			}
			finally {
				if (permit != null) {
					permit.close();
				}
				emitter.complete();
			}
		});

		// Create generator using utility class, returning pre-computed business logic
		// result
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> result, displayFlux);
		return Map.of(SQL_EXECUTE_NODE_OUTPUT, generator);
	}

	@SuppressWarnings("unchecked")
	static ResultMemory accumulateResultMemory(OverAllState state, int currentStep, List<Map<String, String>> resultData) {
		Map<String, List<Map<String, String>>> existing = StateUtil.getObjectValue(state, SQL_RESULT_MEMORY_BY_STEP,
				Map.class, Map.of());
		TreeMap<Integer, List<Map<String, String>>> ordered = new TreeMap<>();
		for (Map.Entry<String, List<Map<String, String>>> entry : existing.entrySet()) {
			try {
				ordered.put(Integer.parseInt(entry.getKey()), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
			}
			catch (NumberFormatException ignored) {
				log.warn("Ignoring malformed SQL result memory step key: {}", entry.getKey());
			}
		}
		ordered.put(currentStep, resultData == null ? List.of() : List.copyOf(resultData));
		Map<String, List<Map<String, String>>> byStep = new LinkedHashMap<>();
		List<Map<String, String>> flattened = new java.util.ArrayList<>();
		ordered.forEach((step, rows) -> {
			byStep.put(String.valueOf(step), rows);
			flattened.addAll(rows);
		});
		return new ResultMemory(Map.copyOf(byStep), List.copyOf(flattened));
	}

	@SuppressWarnings("unchecked") // Graph state stores typed query maps behind a raw Map class token.
	private Map<String, Object> replaySqlExecution(OverAllState state, SqlExecutionEffect effect) {
		Map<String, String> existingQueries = StateUtil.getObjectValue(state, SQL_EXECUTED_QUERY_OUTPUT, Map.class,
				new HashMap<>());
		Map<String, String> restoredQueries = SqlExecutionLineage.restore(effect.stepQueries(), existingQueries,
				effect.nextStep(), effect.sqlQuery());
		List<Map<String, String>> resultData = effect.resultData() == null ? List.of() : effect.resultData();
		Map<String, List<Map<String, String>>> resultMemoryByStep = effect.resultMemoryByStep() == null ? Map.of()
				: effect.resultMemoryByStep();
		Map<String, Object> restored = new HashMap<>();
		restored.put(SQL_EXECUTE_NODE_OUTPUT, effect.stepResults());
		restored.put(SQL_EXECUTED_QUERY_OUTPUT, restoredQueries);
		restored.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
		restored.put(SQL_RESULT_MEMORY_BY_STEP, resultMemoryByStep);
		restored.put(SQL_RESULT_LIST_MEMORY, resultData);
		restored.put(PLAN_CURRENT_STEP, effect.nextStep());
		restored.put(LAST_SQL_EXECUTED_STEP, effect.nextStep() - 1);
		restored.put(LAST_SQL_RESULT_PAYLOAD, effect.resultJson());
		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("恢复已持久化的SQL执行结果，无需重复查询；继续结果验收。"));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
			emitter.next(ChatResponseUtil.createResponse(effect.sqlQuery()));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()));
			emitter.complete();
		});
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, ignored -> restored, displayFlux);
		return Map.of(SQL_EXECUTE_NODE_OUTPUT, generator);
	}

	static SqlExecutionEffect readSqlEffect(String payload) {
		try {
			return JsonUtil.getObjectMapper().readValue(payload, SqlExecutionEffect.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to restore persisted SQL execution result", ex);
		}
	}

	private String writeSqlEffect(SqlExecutionEffect effect) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(effect);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to persist SQL execution result", ex);
		}
	}

	private void markPatternTemplateUsedBestEffort(String templateId, String runId, String attemptId) {
		if (templateId == null || templateId.isBlank()) {
			return;
		}
		try {
			patternTemplateService.markUsed(templateId, runId, attemptId);
		}
		catch (RuntimeException usageError) {
			log.warn("Unable to update Query Pattern Template usage for {}: {}", templateId, usageError.getMessage());
		}
	}

	private void assertActiveIfBound(String runId, String attemptId) {
		if (runId != null && !runId.isBlank() && attemptId != null && !attemptId.isBlank()) {
			executionFence.assertActive(runId, attemptId);
		}
	}

	private void recordSqlTrace(String attemptId, String idempotencyKey, String sql, Map<String, Object> guardSummary,
			Map<String, Object> costSummary, Map<String, Object> preflightSummary, Map<String, Object> resultSummary,
			String status, int retryCount, long startNanos, String errorType) {
		if (attemptId == null || attemptId.isBlank()) {
			return;
		}
		try {
			productionService.recordSqlTrace(attemptId,
					new SqlTraceRequest(idempotencyKey, sql, Map.copyOf(guardSummary), Map.copyOf(costSummary),
							Map.copyOf(preflightSummary), Map.of(), Map.copyOf(resultSummary), status, retryCount,
							Math.max(0, (System.nanoTime() - startNanos) / 1_000_000), errorType));
		}
		catch (LateRunResultDroppedException | RunDeadlineExceededException late) {
			throw late;
		}
		catch (RuntimeException traceError) {
			log.warn("Unable to persist SQL trace for attempt {}: {}", attemptId, traceError.getMessage());
		}
	}

	private SqlCostGuard.CostAssessment runPreflight(Accessor accessor, DbConfigBO dbConfig, String sql,
			List<Object> parameters, int tableCount, SemanticCatalogSnapshot catalog, String cancellationKey) throws Exception {
		SemEvoSQLProperties.SqlExecutionPolicy policy = properties.getSqlExecution();
		SqlCostGuard.CostAssessment explainCost = null;
		if (policy.isExplainEnabled()) {
			String explainSql = sqlPreflightPlanner.explainSql(sql, dbConfig.getDialectType()).orElse(null);
			if (explainSql != null) {
				DbQueryParameter explainParameter = queryParameter(explainSql, parameters, dbConfig.getSchema(),
						Math.max(1, policy.getPreviewRows()), policy.getPreflightTimeoutSeconds(), cancellationKey);
				ResultSetBO explainResult = accessor.executeSqlAndReturnObject(dbConfig, explainParameter);
				explainCost = sqlCostGuard.validateExplain(explainResult, tableCount, policy, dbConfig.getDialectType());
				log.info(
						"SQL EXPLAIN cost guard passed, scanRows={}, intermediateRows={}, joinRows={}, sortRows={}, aggregateRows={}, estimatedCost={}, fullTableScan={}, operators={}",
						explainCost.estimatedRows(), explainCost.estimatedIntermediateRows(), explainCost.estimatedJoinRows(),
						explainCost.estimatedSortRows(), explainCost.estimatedAggregateRows(), explainCost.estimatedCost(),
						explainCost.fullTableScan(), explainCost.expensiveOperators());
				explainCost.warnings().forEach(warning -> log.warn("SQL cost guard warning: {}", warning));
			}
		}
		if (policy.isPreviewEnabled()) {
			DbQueryParameter previewParameter = queryParameter(sql, parameters, dbConfig.getSchema(),
					Math.max(1, policy.getPreviewRows()), policy.getPreflightTimeoutSeconds(), cancellationKey);
			ResultSetBO previewResult = accessor.executeSqlAndReturnObject(dbConfig, previewParameter);
			sensitiveResultSanitizer.sanitize(previewResult, catalog);
			log.info("SQL preview preflight passed with maxRows={}", policy.getPreviewRows());
		}
		return explainCost;
	}

	private Set<String> semanticTimeColumns(SemanticBlueprint semanticPlan) {
		if (semanticPlan == null) {
			return Set.of();
		}
		Set<String> columns = semanticPlan.getMetrics()
			.stream()
			.map(SemanticBlueprint.MetricSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.collect(Collectors.toSet());
		semanticPlan.getGrains()
			.stream()
			.map(SemanticBlueprint.GrainSelection::getTimeColumn)
			.filter(value -> value != null && !value.isBlank())
			.forEach(columns::add);
		return Set.copyOf(columns);
	}

	private DbQueryParameter queryParameter(String sql, List<Object> parameters, String schema, int maxRows,
			int queryTimeoutSeconds, String cancellationKey) {
		return new DbQueryParameter().setSql(sql)
			.setParameters(parameters == null ? List.of() : List.copyOf(parameters))
			.setSchema(schema)
			.setMaxRows(maxRows)
			.setQueryTimeoutSeconds(queryTimeoutSeconds)
			.setCancellationKey(cancellationKey);
	}

	/**
	 * 调用大模型获取图表配置信息并填充到ResultSetBO中
	 * @param state 整体状态
	 * @param resultSetBO SQL执行结果
	 */
	private DisplayStyleBO enrichResultSetWithChartConfig(OverAllState state, ResultSetBO resultSetBO) {
		// 创建ResultDisplayStyleBO对象
		DisplayStyleBO displayStyle = new DisplayStyleBO();
		if (!this.properties.isEnableSqlResultChart()) {
			log.debug("Sql result chart is disabled, set display style as table default");
			displayStyle.setType("table");
			return displayStyle;
		}

		try {
			// 获取用户查询
			String userQuery = StateUtil.getCanonicalQuery(state);

			// 将SQL结果转换为JSON字符串，限制数据量以避免提示词过长
			String sqlResultJson = JsonUtil.getObjectMapper()
				.writeValueAsString(resultSetBO.getData() != null
						? resultSetBO.getData().stream().limit(SAMPLE_DATA_NUMBER).toList() : null);

			// 构建用户提示词，包含SQL结果数据
			String userPrompt = String.format("""
					# 正式任务

					<最新>用户输入: %s
					范例数据: %s

					# 输出
					""", userQuery != null ? userQuery : "数据可视化", sqlResultJson);

			// 加载data-view-analyze提示词模板（系统提示词）
			String fullPrompt = PromptHelper.buildDataViewAnalysisPrompt();
			// 分割系统提示词和用户提示词模板
			String[] parts = fullPrompt.split("=== 用户输入 ===", 2);
			// 渲染系统提示词（当前没有变量，直接使用模板内容）
			String systemPrompt = parts[0].trim();

			log.debug("Built chart config generation system prompt as follows \n {} \n", systemPrompt);
			log.debug("Built chart config generation user prompt as follows \n {} \n", userPrompt);

			// 调用LLM生成图表配置（使用系统提示词和用户提示词）
			Duration runBudget = RunDeadlineUtil.remaining(state);
			Duration enrichmentBudget = Duration.ofMillis(properties.getEnrichSqlResultTimeout());
			if (runBudget != null && runBudget.compareTo(enrichmentBudget) < 0) {
				enrichmentBudget = runBudget;
			}
			String chartConfigJson = llmService.toStringFlux(llmService.callWithin(systemPrompt, userPrompt, runBudget))
				.collect(StringBuilder::new, StringBuilder::append)
				.map(StringBuilder::toString)
				.block(enrichmentBudget);
			if (chartConfigJson != null && !chartConfigJson.trim().isEmpty()) {
				String content = MarkdownParserUtil.extractText(chartConfigJson.trim());
				displayStyle = jsonParseUtil.tryConvertToObject(content, DisplayStyleBO.class);
				log.debug("Successfully enriched ResultSetBO with chart config: type={}, title={}, x={}, y={}",
						displayStyle.getType(), displayStyle.getTitle(), displayStyle.getX(), displayStyle.getY());
				return displayStyle;
			}
			else {
				log.warn("LLM returned empty chart config, using default settings");
			}
		}
		catch (Exception e) {
			log.error("Failed to enrich ResultSetBO with chart config", e);
			// 不抛出异常，允许流程继续执行
		}
		return null;
	}

	record SqlExecutionEffect(String sqlQuery, String resultJson, Map<String, String> stepResults,
			Map<String, String> stepQueries, List<Map<String, String>> resultData,
			Map<String, List<Map<String, String>>> resultMemoryByStep, int nextStep) {
		SqlExecutionEffect(String sqlQuery, String resultJson, Map<String, String> stepResults,
				Map<String, String> stepQueries, List<Map<String, String>> resultData, int nextStep) {
			this(sqlQuery, resultJson, stepResults, stepQueries, resultData, Map.of(), nextStep);
		}
	}

	record ResultMemory(Map<String, List<Map<String, String>>> byStep, List<Map<String, String>> flattened) {
	}

}
