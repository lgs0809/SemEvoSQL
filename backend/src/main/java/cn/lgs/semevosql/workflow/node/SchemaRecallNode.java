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
import static cn.lgs.semevosql.constant.Constant.FORCED_DATASOURCE_ID;
import static cn.lgs.semevosql.constant.Constant.FORCED_PHYSICAL_TABLES;
import static cn.lgs.semevosql.constant.Constant.PROJECT_ID;
import static cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID;
import static cn.lgs.semevosql.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.SCHEMA_RECALL_NODE_OUTPUT;
import static cn.lgs.semevosql.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;

import cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Recalls physical schema only inside the table allowlist of the pinned SemEvoSQL
 * semantic project version.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SchemaRecallNode implements NodeAction {

	private final SemanticCatalogApplicationService semanticCatalogService;

	private final SemEvoSQLProperties semEvoSQLProperties;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = StateUtil.getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT,
				QueryEnhanceOutputDTO.class);
		String input = queryEnhanceOutputDTO.getCanonicalQuery();
		Long projectId = StateUtil.getObjectValue(state, PROJECT_ID, Long.class);
		Long projectVersionId = StateUtil.getObjectValue(state, PROJECT_VERSION_ID, Long.class);

		Integer forcedDatasourceId = StateUtil.getObjectValue(state, FORCED_DATASOURCE_ID, Integer.class,
				(Integer) null);
		List<String> forcedPhysicalTables = StateUtil.getObjectValue(state, FORCED_PHYSICAL_TABLES, List.class,
				List.of());
		Integer datasourceId = forcedDatasourceId == null
				? semanticCatalogService.requireSingleDatasource(projectId, projectVersionId) : forcedDatasourceId;
		Set<String> allAllowedPhysicalTables = semanticCatalogService.enabledPhysicalTables(projectId,
				projectVersionId);
		Set<String> allowedPhysicalTables = forcedPhysicalTables == null || forcedPhysicalTables.isEmpty()
				? allAllowedPhysicalTables
				: forcedPhysicalTables.stream()
					.filter(allAllowedPhysicalTables::contains)
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (forcedPhysicalTables != null && !forcedPhysicalTables.isEmpty()
				&& allowedPhysicalTables.size() != forcedPhysicalTables.stream().distinct().count()) {
			throw new IllegalArgumentException(
					"Forced source table allowlist is not contained in the published catalog");
		}


        var plan=StateUtil.getObjectValue(state,cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN,
            cn.lgs.semevosql.semantic.domain.SemanticBlueprint.class,(cn.lgs.semevosql.semantic.domain.SemanticBlueprint)null);
        if(plan!=null && (!java.util.Objects.equals(plan.getProjectId(),projectId)
                ||!java.util.Objects.equals(plan.getProjectVersionId(),projectVersionId)))
            throw new SecurityException("Frozen execution plan belongs to another catalog");
        var recall=plan==null && (forcedPhysicalTables==null||forcedPhysicalTables.isEmpty())
            ? semanticCatalogService.recallPlanning(projectId,projectVersionId,input,10):null;
        Set<String> modelCodes=plan!=null ? plan.getModels().stream().filter(m->java.util.Objects.equals(datasourceId,m.getDatasourceId()))
            .map(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ModelSelection::getModelCode).collect(java.util.stream.Collectors.toSet())
            :recall==null?Set.of():recall.hits().stream().map(cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit::modelCode)
                .filter(name->name!=null&&!name.isBlank()).collect(java.util.stream.Collectors.toSet());
		List<String> semanticCandidateTables = forcedPhysicalTables != null && !forcedPhysicalTables.isEmpty()
				? forcedPhysicalTables : plan!=null ? plan.getModels().stream().filter(m->modelCodes.contains(m.getModelCode()))
                    .map(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ModelSelection::getPhysicalTable).distinct().toList()
                :recall.physicalTables();
		Set<String> selectedTables = semanticCandidateTables.stream()
			.filter(allowedPhysicalTables::contains)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		PublishedCatalogSchemaDocumentFactory.SchemaDocuments schemaDocuments = PublishedCatalogSchemaDocumentFactory
			.create(modelCodes.isEmpty()?semanticCatalogService.getCatalogForPhysicalTables(projectId, projectVersionId, selectedTables)
                :semanticCatalogService.getCatalogForModels(projectId,projectVersionId,modelCodes), datasourceId, selectedTables,
					semEvoSQLProperties.getMaxColumnsPerTable());
		List<Document> tableDocuments = new ArrayList<>(schemaDocuments.tables());
		List<String> recalledTableNames = extractTableNames(tableDocuments);
		List<Document> columnDocuments = schemaDocuments.columns();
		if (tableDocuments.isEmpty()) {
			throw new IllegalStateException(
					"No published Semantic Catalog table is available for the pinned query plan");
		}

		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始在当前发布语义版本内召回 Schema 信息..."));
			emitter.next(ChatResponseUtil.createResponse(
					"Schema 表召回完成，数量: " + tableDocuments.size() + "，表名: " + String.join(", ", recalledTableNames)));
			emitter.next(ChatResponseUtil.createResponse("发布版本 Schema 召回完成."));
			emitter.complete();
		});

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state,
				currentState -> Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, tableDocuments,
						COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, columnDocuments, DATASOURCE_ID, datasourceId),
				displayFlux);
		return Map.of(SCHEMA_RECALL_NODE_OUTPUT, generator, DATASOURCE_ID, datasourceId);
	}

	private static List<String> extractTableNames(List<Document> tableDocuments) {
		List<String> tableNames = tableDocuments.stream()
			.map(SchemaRecallNode::tableName)
			.filter(name -> name != null && !name.isBlank())
			.distinct()
			.toList();
		log.info("SchemaRecallNode recalled SemEvoSQL tables: {}", tableNames);
		return tableNames;
	}

	private static String tableName(Document document) {
		Object name = document.getMetadata().get("name");
		return name == null ? null : name.toString();
	}

}
