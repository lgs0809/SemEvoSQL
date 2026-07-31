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
package cn.lgs.semevosql.prompt;

import cn.lgs.semevosql.bo.schema.DisplayStyleBO;
import cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO;
import cn.lgs.semevosql.dto.prompt.SemanticConsistencyDTO;
import cn.lgs.semevosql.dto.prompt.SqlGenerationDTO;
import cn.lgs.semevosql.dto.schema.ColumnDTO;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.dto.schema.TableDTO;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.ai.converter.BeanOutputConverter;

public class PromptHelper {

	private static final String CLEAN_JSON_EXAMPLE = """
			{
			    "title": { "text": "月度销售额" },
			    "tooltip": { "trigger": "axis" },
			    "xAxis": { "type": "category", "data": ["1月", "2月"] },
			    "yAxis": { "type": "value" },
			    "series": [
			        { "type": "bar", "data": [120, 200] }
			    ]
			}""";

	public static String buildMixSelectorPrompt(String evidence, String question, SchemaDTO schemaDTO) {
		String schemaInfo = buildMixMacSqlDbPrompt(schemaDTO, true);
		Map<String, Object> params = new HashMap<>();
		params.put("schema_info", schemaInfo);
		params.put("question", question);
		if (StringUtils.isBlank(evidence))
			params.put("evidence", "无");
		else
			params.put("evidence", evidence);
		return PromptConstant.getMixSelectorPromptTemplate().render(params);
	}

	public static String buildMixMacSqlDbPrompt(SchemaDTO schemaDTO, Boolean withColumnType) {
		StringBuilder sb = new StringBuilder();
		sb.append("【DB_ID】 ").append(schemaDTO.getName() == null ? "" : schemaDTO.getName()).append("\n");
		for (TableDTO tableDTO : schemaDTO.getTable()) {
			sb.append(buildMixMacSqlTablePrompt(tableDTO, withColumnType)).append("\n");
		}
		if (schemaDTO.getForeignKeys() != null && !schemaDTO.getForeignKeys().isEmpty()) {
			sb.append("【Foreign keys】\n").append(StringUtils.join(schemaDTO.getForeignKeys(), "\n"));
		}
		return sb.toString();
	}

	public static String buildMixMacSqlTablePrompt(TableDTO tableDTO, Boolean withColumnType) {
		StringBuilder sb = new StringBuilder();
		// sb.append("# Table:
		// ").append(tableDTO.getName()).append(StringUtils.isBlank(tableDTO.getDescription())
		// ? "" : ", " + tableDTO.getDescription()).append("\n");
		sb.append("# Table: ").append(tableDTO.getName());
		if (!StringUtils.equals(tableDTO.getName(), tableDTO.getDescription())) {
			sb.append(StringUtils.isBlank(tableDTO.getDescription()) ? "" : ", " + tableDTO.getDescription())
				.append("\n");
		}
		else {
			sb.append("\n");
		}
		sb.append("[\n");
		List<String> columnLines = new ArrayList<>();
		for (ColumnDTO columnDTO : tableDTO.getColumn()) {
			StringBuilder line = new StringBuilder();
			line.append("(")
				.append(columnDTO.getName())
				.append(BooleanUtils.isTrue(withColumnType)
						? ":" + Objects.toString(columnDTO.getType(), "").toUpperCase(Locale.ROOT) : "");
			if (!StringUtils.equals(columnDTO.getDescription(), columnDTO.getName())) {
				line.append(", ").append(Objects.toString(columnDTO.getDescription(), ""));
			}
			if (tableDTO.getPrimaryKeys() != null && !tableDTO.getPrimaryKeys().isEmpty()
					&& tableDTO.getPrimaryKeys().contains(columnDTO.getName())) {
				line.append(", Primary Key");
			}
			List<String> enumData = Optional.ofNullable(columnDTO.getData())
				.orElse(new ArrayList<>())
				.stream()
				.filter(d -> !StringUtils.isEmpty(d))
				.collect(Collectors.toList());
			if (!enumData.isEmpty() && !"id".equals(columnDTO.getName())) {
				line.append(", Examples: [");
				List<String> data = new ArrayList<>(enumData.subList(0, Math.min(3, enumData.size())));
				line.append(StringUtils.join(data, ",")).append("]");
			}

			line.append(")");
			columnLines.add(line.toString());
		}
		sb.append(StringUtils.join(columnLines, ",\n"));
		sb.append("\n]");
		return sb.toString();
	}

	public static String buildNewSqlGeneratorPrompt(SqlGenerationDTO sqlGenerationDTO) {
		String schemaInfo = buildMixMacSqlDbPrompt(sqlGenerationDTO.getSchemaDTO(), true);
		Map<String, Object> params = new HashMap<>();
		params.put("dialect", sqlGenerationDTO.getDialect());
		params.put("question", sqlGenerationDTO.getQuery());
		params.put("schema_info", schemaInfo);
		params.put("semantic_model", Objects.toString(sqlGenerationDTO.getSemanticModel(), "无"));
		params.put("semantic_plan", Objects.toString(sqlGenerationDTO.getSemanticPlan(), "{}"));
		params.put("evidence", sqlGenerationDTO.getEvidence());
		params.put("query_examples", Objects.toString(sqlGenerationDTO.getQueryExamples(), "无"));
		params.put("execution_description", sqlGenerationDTO.getExecutionDescription());
		return PromptConstant.getNewSqlGeneratorPromptTemplate().render(params);
	}

	public static String buildSemanticConsistenPrompt(SemanticConsistencyDTO semanticConsistencyDTO) {
		Map<String, Object> params = new HashMap<>();
		params.put("dialect", semanticConsistencyDTO.getDialect());
		params.put("execution_description", semanticConsistencyDTO.getExecutionDescription());
		params.put("user_query", semanticConsistencyDTO.getUserQuery());
		params.put("evidence", semanticConsistencyDTO.getEvidence());
		params.put("schema_info", semanticConsistencyDTO.getSchemaInfo());
		params.put("semantic_model", Objects.toString(semanticConsistencyDTO.getSemanticModel(), "无"));
		params.put("semantic_plan", Objects.toString(semanticConsistencyDTO.getSemanticPlan(), "{}"));
		params.put("sql", semanticConsistencyDTO.getSql());
		return PromptConstant.getSemanticConsistencyPromptTemplate().render(params);
	}

	/**
	 * Build report generation prompt with custom prompt
	 * @param userRequirementsAndPlan user requirements and plan
	 * @param analysisStepsAndData analysis steps and data
	 * @param summaryAndRecommendations summary and recommendations
	 * @return built prompt
	 */
	public static String buildReportGeneratorPrompt(String userRequirementsAndPlan, String analysisStepsAndData,
			String summaryAndRecommendations) {
		Map<String, Object> params = new HashMap<>();
		params.put("user_requirements_and_plan", userRequirementsAndPlan);
		params.put("analysis_steps_and_data", analysisStepsAndData);
		params.put("summary_and_recommendations", summaryAndRecommendations);
		params.put("json_example", CLEAN_JSON_EXAMPLE);
		return PromptConstant.getReportGeneratorPlainPromptTemplate().render(params);
	}

	public static String buildSqlErrorFixerPrompt(SqlGenerationDTO sqlGenerationDTO) {
		String schemaInfo = buildMixMacSqlDbPrompt(sqlGenerationDTO.getSchemaDTO(), true);

		Map<String, Object> params = new HashMap<>();
		params.put("dialect", sqlGenerationDTO.getDialect());
		params.put("question", sqlGenerationDTO.getQuery());
		params.put("schema_info", schemaInfo);
		params.put("semantic_model", Objects.toString(sqlGenerationDTO.getSemanticModel(), "无"));
		params.put("semantic_plan", Objects.toString(sqlGenerationDTO.getSemanticPlan(), "{}"));
		params.put("evidence", sqlGenerationDTO.getEvidence());
		params.put("query_examples", Objects.toString(sqlGenerationDTO.getQueryExamples(), "无"));
		params.put("error_sql", sqlGenerationDTO.getSql());
		params.put("error_message", sqlGenerationDTO.getExceptionMessage());
		params.put("execution_description", sqlGenerationDTO.getExecutionDescription());

		return PromptConstant.getSqlErrorFixerPromptTemplate().render(params);
	}

	public static String buildQueryEnhancePrompt(String multiTurn, String latestQuery, String evidence) {
		Map<String, Object> params = new HashMap<>();
		params.put("multi_turn", multiTurn != null ? multiTurn : "(无)");
		params.put("latest_query", latestQuery);
		if (StringUtils.isEmpty(evidence))
			params.put("evidence", "无");
		else
			params.put("evidence", evidence);
		params.put("current_time_info", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
		BeanOutputConverter<QueryEnhanceOutputDTO> beanOutputConverter = new BeanOutputConverter<>(
				QueryEnhanceOutputDTO.class);
		params.put("format", beanOutputConverter.getFormat());
		return PromptConstant.getQueryEnhancementPromptTemplate().render(params);
	}

	public static String buildDataViewAnalysisPrompt() {
		Map<String, Object> params = new HashMap<>();
		BeanOutputConverter<DisplayStyleBO> beanOutputConverter = new BeanOutputConverter<>(DisplayStyleBO.class);
		params.put("format", beanOutputConverter.getFormat());
		return PromptConstant.getDataViewAnalyzePromptTemplate().render(params);
	}

	/**
	 * 构建可行性评估提示词
	 * @param canonicalQuery 规范化查询
	 * @param recalledSchema 召回的数据库Schema
	 * @param evidence 参考信息
	 * @param multiTurn 多轮对话历史
	 * @return 可行性评估提示词
	 */
	public static String buildFeasibilityAssessmentPrompt(String canonicalQuery, SchemaDTO recalledSchema,
			String evidence, String multiTurn) {
		Map<String, Object> params = new HashMap<>();
		String schemaInfo = buildMixMacSqlDbPrompt(recalledSchema, true);
		params.put("canonical_query", canonicalQuery != null ? canonicalQuery : "");
		params.put("recalled_schema", schemaInfo);
		params.put("evidence", evidence != null ? evidence : "");
		params.put("multi_turn", multiTurn != null ? multiTurn : "(无)");
		return PromptConstant.getFeasibilityAssessmentPromptTemplate().render(params);
	}

}
