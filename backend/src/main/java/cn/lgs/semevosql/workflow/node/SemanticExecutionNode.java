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

import static cn.lgs.semevosql.constant.Constant.ACTIVE_TODO_ID;
import static cn.lgs.semevosql.constant.Constant.ADVANCED_EXECUTION_FALLBACK;
import static cn.lgs.semevosql.constant.Constant.ATTEMPT_ID;
import static cn.lgs.semevosql.constant.Constant.FORCED_DATASOURCE_ID;
import static cn.lgs.semevosql.constant.Constant.FORCED_PHYSICAL_TABLES;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_EXECUTED_STEP;
import static cn.lgs.semevosql.constant.Constant.LAST_SQL_RESULT_PAYLOAD;
import static cn.lgs.semevosql.constant.Constant.PLAN_CURRENT_STEP;
import static cn.lgs.semevosql.constant.Constant.PRINCIPAL_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.RUN_ID;
import static cn.lgs.semevosql.constant.Constant.SEMANTIC_EXECUTION_DECISION;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTED_QUERY_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN;

import cn.lgs.semevosql.bo.schema.ResultBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.QueryRepairPolicy;
import cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget;
import cn.lgs.semevosql.semantic.application.VerifiedQueryExecutionService;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler.ConstrainedGenerationRequiredException;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlValidationClassifier;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Governed compiler-first execution for the common Semantic Blueprint path.
 *
 * <p>Single-source and multi-source queries use the same durable QueryRun. Source sub-runs are execution artifacts,
 * not child QueryRuns. Unsupported constrained-generation plans explicitly enter the bounded advanced fallback.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticExecutionNode implements NodeAction {

	public static final String EXECUTED = "EXECUTED";
	public static final String FALLBACK_ADVANCED = "FALLBACK_ADVANCED";

	private final VerifiedQueryExecutionService verifiedQueryExecutionService;

	private final SqlValidationClassifier sqlValidationClassifier;

	private final QueryRepairPolicy repairPolicy;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		if (plan == null || !plan.isExecutable()) {
			throw new IllegalStateException("Semantic execution requires an executable Semantic Blueprint");
		}
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long versionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
		String runId = StateUtil.getStringValue(state, RUN_ID, "");
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, "");
		String principalId = StateUtil.getStringValue(state, PRINCIPAL_ID, "anonymous");

		String executionKey = executionKey(state, plan);
		VerifiedQueryExecutionService.ExecutionResult executed;
		try {
			executed = verifiedQueryExecutionService.execute(runId, attemptId, executionKey, projectId, versionId,
					principalId, plan);
		}
		catch (ConstrainedGenerationRequiredException unsupported) {
			log.info("Semantic Blueprint requires advanced/constrained execution fallback: {}", unsupported.getMessage());
			return advancedFallback(plan);
		}
		catch (Exception failure) {
			RepairBudget currentBudget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class,
					RepairBudget.empty());
			var validation = sqlValidationClassifier.classify(failure, currentBudget.sqlRepairsUsed());
			if (!validation.retryAllowed()) {
				throw failure;
			}
			var repair = repairPolicy.consumeTransition(currentBudget, Decision.RETRY_SQL);
			if (!repair.allowed()) {
				throw new IllegalStateException("SQL repair budget exhausted: " + repair.reason(), failure);
			}
			log.info("Direct semantic SQL execution failed with a retryable database error; switching to bounded advanced "
					+ "repair path. runId={}, errorType={}", runId, validation.errorType());
			Map<String, Object> fallback = new HashMap<>();
			fallback.put(SEMANTIC_EXECUTION_DECISION, FALLBACK_ADVANCED);
			fallback.put(ADVANCED_EXECUTION_FALLBACK, true);
			fallback.put(QUERY_REPAIR_BUDGET, repair.budget());
			return fallback;
		}

		ResultSetBO merged = executed.resultSet();
		ResultBO result = ResultBO.builder().resultSet(merged).build();
		String resultPayload = JsonUtil.getObjectMapper().writeValueAsString(result);
		String allSql = executed.sql();

		Map<String, String> resultMap = Map.of("1", resultPayload);
		Map<String, String> queryMap = Map.of("1", allSql);
		Map<String, Object> update = new HashMap<>();
		update.put(SEMANTIC_EXECUTION_DECISION, EXECUTED);
		update.put(ADVANCED_EXECUTION_FALLBACK, false);
		update.put(SQL_EXECUTE_NODE_OUTPUT, resultMap);
		update.put(SQL_EXECUTED_QUERY_OUTPUT, queryMap);
		update.put(LAST_SQL_EXECUTED_STEP, 1);
		update.put(PLAN_CURRENT_STEP, 1);
		update.put(LAST_SQL_RESULT_PAYLOAD, resultPayload);
		update.put(SQL_RESULT_LIST_MEMORY, merged == null || merged.getData() == null ? List.of() : merged.getData());
		return update;
	}

	static Map<String, Object> advancedFallback(SemanticBlueprint plan) {
		Map<String, Object> fallback = new HashMap<>();
		fallback.put(SEMANTIC_EXECUTION_DECISION, FALLBACK_ADVANCED);
		fallback.put(ADVANCED_EXECUTION_FALLBACK, true);
		if (plan.getSourceSubPlans() == null || plan.getSourceSubPlans().isEmpty()) {
			return fallback;
		}
		Set<Integer> datasourceIds = new LinkedHashSet<>();
		Set<String> physicalTables = new LinkedHashSet<>();
		for (SemanticBlueprint.SourceSubPlan source : plan.getSourceSubPlans()) {
			if (source.getDatasourceId() != null) {
				datasourceIds.add(source.getDatasourceId());
			}
			if (source.getPhysicalTables() != null) {
				physicalTables.addAll(source.getPhysicalTables());
			}
		}
		if (datasourceIds.size() == 1) {
			fallback.put(FORCED_DATASOURCE_ID, datasourceIds.iterator().next());
			if (!physicalTables.isEmpty()) {
				fallback.put(FORCED_PHYSICAL_TABLES, List.copyOf(physicalTables));
			}
		}
		return fallback;
	}

	private String executionKey(OverAllState state, SemanticBlueprint plan) {
		String todo = StateUtil.getStringValue(state, ACTIVE_TODO_ID, "simple");
		if (todo == null || todo.isBlank()) {
			todo = "simple";
		}
		RepairBudget budget = StateUtil.getObjectValue(state, QUERY_REPAIR_BUDGET, RepairBudget.class, RepairBudget.empty());
		try {
			String material = todo + "\n" + budget.sqlRepairsUsed() + ":" + budget.semanticReplansUsed() + ":"
					+ budget.retrievalRepairsUsed() + "\n" + JsonUtil.getObjectMapper().writeValueAsString(plan);
			return todo + ":" + HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to derive semantic execution key", ex);
		}
	}

}
