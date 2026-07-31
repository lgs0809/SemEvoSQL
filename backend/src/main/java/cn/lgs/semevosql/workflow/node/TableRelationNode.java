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

import static cn.lgs.semevosql.constant.Constant.COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.DATASOURCE_ID;
import static cn.lgs.semevosql.constant.Constant.DB_DIALECT_TYPE;
import static cn.lgs.semevosql.constant.Constant.EVIDENCE;
import static cn.lgs.semevosql.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.SQL_GENERATE_SCHEMA_MISSING_ADVICE;
import static cn.lgs.semevosql.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.TABLE_RELATION_EXCEPTION_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.TABLE_RELATION_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.TABLE_RELATION_RETRY_COUNT;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.dto.schema.TableDTO;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.service.nl2sql.Nl2SqlService;
import cn.lgs.semevosql.service.schema.SchemaService;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Builds the executable schema and semantic prompt from the pinned SemEvoSQL catalog.
 * Physical schema documents are retained as infrastructure evidence, while business
 * relationships and semantic definitions come exclusively from the published catalog.
 */
@Slf4j
@Component
@AllArgsConstructor
public class TableRelationNode implements NodeAction {

	private final SchemaService schemaService;

	private final Nl2SqlService nl2SqlService;

	private final DatabaseUtil databaseUtil;

	private final SemanticCatalogApplicationService semanticCatalogService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		List<Document> tableDocuments = StateUtil.getDocumentList(state, TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT);
		List<Document> columnDocuments = StateUtil.getDocumentList(state, COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT);
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);
		Integer datasourceId = StateUtil.getObjectValue(state, DATASOURCE_ID, Integer.class);

		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		List<String> recalledTableNames = tableDocuments.stream()
			.map(TableRelationNode::tableName)
			.filter(name -> name != null && !name.isBlank())
			.distinct()
			.toList();
		List<String> catalogRelationships = semanticCatalogService.relationshipExpressions(projectId, projectVersionId,
				recalledTableNames);
		SchemaDTO initialSchema = buildInitialSchema(columnDocuments, tableDocuments, dbConfig, catalogRelationships);

		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put(DB_DIALECT_TYPE, dbConfig.getDialectType());
		resultMap.put(TABLE_RELATION_RETRY_COUNT, 0);
		resultMap.put(TABLE_RELATION_EXCEPTION_OUTPUT, "");

		Flux<ChatResponse> schemaFlux = processSchemaSelection(initialSchema, canonicalQuery, evidence, state, dbConfig,
				result -> {
					log.info("[{}] Schema processing result: {}", this.getClass().getSimpleName(), result);
					resultMap.put(TABLE_RELATION_OUTPUT, result);
					List<String> selectedTables = result.getTable() == null ? List.of()
							: result.getTable().stream().map(TableDTO::getName).toList();
					String semanticPrompt = semanticCatalogService.renderRuntimePrompt(projectId, projectVersionId,
							selectedTables);
					resultMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, semanticPrompt);
				});

		Flux<ChatResponse> preFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始构建发布版本的初始 Schema..."));
			emitter.next(ChatResponseUtil.createResponse("已加载正式关系定义，数量: " + catalogRelationships.size()));
			emitter.next(ChatResponseUtil.createResponse("发布版本初始 Schema 构建完成."));
			emitter.complete();
		});
		Flux<ChatResponse> displayFlux = preFlux.concatWith(schemaFlux).concatWith(Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始处理 Schema 选择..."));
			emitter.next(ChatResponseUtil.createResponse("Schema 选择处理完成."));
			emitter.complete();
		}));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, ignored -> resultMap, displayFlux);
		return Map.of(TABLE_RELATION_OUTPUT, generator, DB_DIALECT_TYPE, dbConfig.getDialectType(),
				TABLE_RELATION_RETRY_COUNT, 0, TABLE_RELATION_EXCEPTION_OUTPUT, "");
	}

	private SchemaDTO buildInitialSchema(List<Document> columnDocuments, List<Document> tableDocuments, DbConfigBO dbConfig,
			List<String> catalogRelationships) {
		SchemaDTO schema = new SchemaDTO();
		schemaService.extractDatabaseName(schema, dbConfig);
		schemaService.buildSchemaFromDocuments(columnDocuments, tableDocuments, schema);

		List<String> allRelationships = new ArrayList<>();
		if (schema.getForeignKeys() != null) {
			allRelationships.addAll(schema.getForeignKeys());
		}
		allRelationships.addAll(catalogRelationships);
		schema.setForeignKeys(
				allRelationships.stream().filter(value -> value != null && !value.isBlank()).distinct().toList());
		return schema;
	}

	private Flux<ChatResponse> processSchemaSelection(SchemaDTO schema, String input, String evidence,
			OverAllState state, DbConfigBO dbConfig, Consumer<SchemaDTO> resultConsumer) {
		String schemaAdvice = StateUtil.getStringValue(state, SQL_GENERATE_SCHEMA_MISSING_ADVICE, null);
		Long deadlineEpochMillis = StateUtil.getObjectValue(state,
				cn.lgs.semevosql.constant.Constant.RUN_DEADLINE_EPOCH_MILLIS, Long.class, (Long) null);
		Flux<ChatResponse> schemaFlux = nl2SqlService.fineSelect(schema, input, evidence, schemaAdvice, dbConfig,
				resultConsumer, deadlineEpochMillis);
		return Flux
			.just(ChatResponseUtil.createResponse("正在选择合适的数据表...\n"),
					ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()))
			.concatWith(schemaFlux)
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
					ChatResponseUtil.createResponse("\n\n选择数据表完成。")));
	}

	private static String tableName(Document document) {
		Object name = document.getMetadata().get("name");
		return name == null ? null : name.toString();
	}

}
