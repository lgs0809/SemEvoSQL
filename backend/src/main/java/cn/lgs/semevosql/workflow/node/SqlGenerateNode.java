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

import cn.lgs.semevosql.dto.planner.ExecutionStep;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.PlanProcessUtil;
import cn.lgs.semevosql.util.StateUtil;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.learning.QueryPatternTemplateService;
import cn.lgs.semevosql.learning.QueryPatternTemplateService.ReusableTemplate;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.learning.ValidatedSemanticSqlPatternService;
import cn.lgs.semevosql.operations.SemanticCatalogCache;
import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler.ConstrainedGenerationRequiredException;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.dto.datasource.SqlRetryDto;
import cn.lgs.semevosql.dto.prompt.SqlGenerationDTO;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;
import static cn.lgs.semevosql.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * Enhanced SQL generation node that handles SQL query regeneration with advanced
 * optimization features. This node is responsible for: - Multi-round SQL optimization and
 * refinement - Syntax validation and security analysis - Performance optimization and
 * intelligent caching - Handling execution exceptions and semantic consistency failures -
 * Managing retry logic with schema advice - Providing streaming feedback during
 * regeneration process
 *
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final SemEvoSQLProperties properties;

	private final ValidatedQueryExampleService queryExampleService;

	private final SemanticCatalogCache semanticCatalogCache;

	private final SemanticSqlCompiler semanticSqlCompiler;

	private final QueryPatternTemplateService patternTemplateService;

	private final ValidatedSemanticSqlPatternService semanticSqlPatternService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 判断是否达到最大尝试次数
		int count = state.value(SQL_GENERATE_COUNT, 0);
		if (count >= properties.getMaxSqlRetryCount()) {
			ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
			String sqlGenerateOutput = String.format("步骤[%d]中，SQL次数生成超限，最大尝试次数：%d，已尝试次数:%d，该步骤内容: \n %s",
					executionStep.getStep(), properties.getMaxSqlRetryCount(), count,
					executionStep.getToolParameters().getInstruction());
			log.error("SQL generation failed, reason: {}", sqlGenerateOutput);
			throw new SqlGenerationRetriesExhaustedException(sqlGenerateOutput);
		}

		// 获取planner分配的当前执行步骤的sql任务要求，每个步骤的sql任务是不同的。
		// 不要拿 user query 这个总体的大任务。
		String promptForSql = getCurrentExecutionStepInstruction(state);

		// 准备生成SQL
		String displayMessage;
		Flux<String> sqlFlux;
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());
		CompiledSourceQuery compiled = retryDto.sqlExecuteFail() || retryDto.semanticFail() ? null
				: compileDeterministic(state);
		ReusableTemplate reusableTemplate = compiled == null ? null : reusableTemplate(state, compiled);

		if (retryDto.sqlExecuteFail()) {
			displayMessage = "检测到SQL执行异常，开始重新生成SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else if (retryDto.semanticFail()) {
			displayMessage = "语义一致性校验未通过，开始重新生成SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else if (reusableTemplate != null) {
			displayMessage = "命中已验证的 Query Pattern SQL 模板，重新绑定本次参数...";
			sqlFlux = Flux.just(reusableTemplate.sql());
		}
		else if (compiled != null) {
			displayMessage = "Semantic Blueprint 已通过确定性编译...";
			sqlFlux = Flux.just(compiled.sql());
		}
		else {
			displayMessage = "开始受约束生成SQL...";
			sqlFlux = handleGenerateSql(state, promptForSql);
		}

		// 准备返回结果，同时需要清除一些状态数据
		Map<String, Object> result = new HashMap<>(Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT,
				count + 1, SQL_REGENERATE_REASON, SqlRetryDto.empty()));
		result.put(SQL_PHYSICAL_OUTPUT, "");
		result.put(SQL_DRY_PLAN_OUTPUT, Map.of());
		result.put(SQL_COMPILED_PARAMETERS, compiled == null ? List.of() : compiled.parameters());
		result.put(SQL_COMPILER_MODE,
				compiled == null ? "SEMANTIC_SQL" : reusableTemplate == null ? "DETERMINISTIC" : "PATTERN_TEMPLATE");
		if (reusableTemplate != null) {
			result.put(SQL_PATTERN_TEMPLATE_ID, reusableTemplate.templateId());
			result.put(QUERY_PATTERN_ID, reusableTemplate.patternId());
		}

		// Create display flux for user experience only
		StringBuilder sqlCollector = new StringBuilder();
		Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(displayMessage),
				ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
		Flux<ChatResponse> displayFlux = preFlux
			.concatWith(sqlFlux.doOnNext(sqlCollector::append).map(ChatResponseUtil::createPureResponse))
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()),
					ChatResponseUtil.createResponse("SQL生成完成，准备执行")));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					String sql = nl2SqlService.sqlTrim(sqlCollector.toString());
					result.put(SQL_GENERATE_OUTPUT, sql);
					return result;
				}, displayFlux);

		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	private Flux<String> handleRetryGenerateSql(OverAllState state, String originalSql, String errorMsg,
			String executionDescription) {
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String userQuery = StateUtil.getCanonicalQuery(state);
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class, (Long) null);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class, (Long) null);
		String catalogHash = StateUtil.getStringValue(state, CATALOG_HASH, "");
		String principalId = StateUtil.getStringValue(state, PRINCIPAL_ID, null);
		String approvedExamples = queryExampleService.renderApprovedExamples(projectId, projectVersionId, catalogHash,
				userQuery, principalId, 3);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		String semanticModel = StateUtil.getStringValue(state, GENEGRATED_SEMANTIC_MODEL_PROMPT, "");
		SemanticBlueprint semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		Integer datasourceId = StateUtil.getObjectValue(state, DATASOURCE_ID, Integer.class, (Integer) null);
		String runId = StateUtil.getStringValue(state, RUN_ID, null);
		String attemptId = StateUtil.getStringValue(state, ATTEMPT_ID, null);
		String semanticPatternHints = semanticSqlPatternService.renderReusablePatternHints(projectId, projectVersionId,
				catalogHash, datasourceId, principalId, runId, attemptId, semanticPlan, 3);
		String queryExamples = approvedExamples + semanticPatternHints;

		SqlGenerationDTO sqlGenerationDTO = SqlGenerationDTO.builder()
			.evidence(evidence)
			.queryExamples(queryExamples)
			.query(userQuery)
			.schemaDTO(schemaDTO)
			.semanticModel(semanticModel)
			.semanticPlan(serializeSemanticPlan(semanticPlan))
			.sql(originalSql)
			.exceptionMessage(errorMsg)
			.executionDescription(executionDescription)
			.dialect(dialect)
			.build();

		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	private Flux<String> handleGenerateSql(OverAllState state, String executionDescription) {
		return handleRetryGenerateSql(state, null, null, executionDescription);
	}

	private CompiledSourceQuery compileDeterministic(OverAllState state) {
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		if (plan == null) {
			return null;
		}
		try {
			Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
			Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
			Integer datasourceId = StateUtil.getObjectValue(state, DATASOURCE_ID, Integer.class);
			String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
			return semanticSqlCompiler.compileForDatasource(plan, semanticCatalogCache.get(projectId, projectVersionId),
					datasourceId, SqlDialect.from(dialect), Clock.systemUTC(), ZoneId.systemDefault());
		}
		catch (ConstrainedGenerationRequiredException ex) {
			log.info("Deterministic SQL compiler delegated to constrained generation: {}", ex.getMessage());
			return null;
		}
	}

	private ReusableTemplate reusableTemplate(OverAllState state, CompiledSourceQuery compiled) {
		SemanticBlueprint plan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		if (plan == null) {
			return null;
		}
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class, (Long) null);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class, (Long) null);
		String catalogHash = StateUtil.getStringValue(state, CATALOG_HASH, "");
		return patternTemplateService.findExecutable(projectId, projectVersionId, catalogHash, plan, compiled)
			.orElse(null);
	}

	private String serializeSemanticPlan(SemanticBlueprint semanticPlan) {
		if (semanticPlan == null) {
			return "{}";
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(semanticPlan);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize Semantic Blueprint", ex);
		}
	}

	static final class SqlGenerationRetriesExhaustedException extends IllegalStateException {

		private SqlGenerationRetriesExhaustedException(String message) {
			super(message);
		}

	}

}
