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

import static cn.lgs.semevosql.constant.Constant.DATASOURCE_ID;
import static cn.lgs.semevosql.constant.DocumentMetadataConstant.COLUMN;
import static cn.lgs.semevosql.constant.DocumentMetadataConstant.TABLE;
import static cn.lgs.semevosql.constant.DocumentMetadataConstant.VECTOR_TYPE;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;

/**
 * Adapts the pinned Semantic Catalog to the advanced-execution graph schema-document shape. The
 * published catalog remains the source of truth; vector schema indexes are not required
 * for deterministic SemEvoSQL execution.
 */
final class PublishedCatalogSchemaDocumentFactory {

	private PublishedCatalogSchemaDocumentFactory() {
	}

	static SchemaDocuments create(SemanticCatalogSnapshot catalog, Integer datasourceId,
			Set<String> selectedPhysicalTables) {
		return create(catalog, datasourceId, selectedPhysicalTables, Integer.MAX_VALUE);
	}

	static SchemaDocuments create(SemanticCatalogSnapshot catalog, Integer datasourceId,
			Set<String> selectedPhysicalTables, int maxColumnsPerTable) {
		Map<String, SemanticCatalogSnapshot.Model> modelsByCode = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> Objects.equals(datasourceId, model.getDatasourceId()))
			.filter(model -> selectedPhysicalTables.contains(model.getPhysicalTable()))
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode, Function.identity(),
					(left, right) -> left, LinkedHashMap::new));
		Map<String, List<String>> primaryKeysByModel = catalog.getGrains()
			.stream()
			.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(grain -> modelsByCode.containsKey(grain.getModelCode()))
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Grain::getModelCode,
					grain -> splitColumns(grain.getKeyColumns()), (left, right) -> left, LinkedHashMap::new));

		List<Document> tableDocuments = modelsByCode.values()
			.stream()
			.map(model -> tableDocument(model, datasourceId,
					primaryKeysByModel.getOrDefault(model.getModelCode(), List.of())))
			.toList();
		List<SemanticCatalogSnapshot.Column> eligibleColumns = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> !Boolean.FALSE.equals(column.getAllowSendToLlm()))
			.filter(column -> modelsByCode.containsKey(column.getModelCode()))
			.toList();
		List<Document> columnDocuments = modelsByCode.values()
			.stream()
			.flatMap(model -> prioritizedColumns(catalog, eligibleColumns, model.getModelCode(),
					primaryKeysByModel.getOrDefault(model.getModelCode(), List.of()), maxColumnsPerTable)
				.stream()
				.map(column -> columnDocument(column, model, datasourceId,
						primaryKeysByModel.getOrDefault(model.getModelCode(), List.of()))))
			.toList();
		return new SchemaDocuments(tableDocuments, columnDocuments);
	}

	private static List<SemanticCatalogSnapshot.Column> prioritizedColumns(SemanticCatalogSnapshot catalog,
			List<SemanticCatalogSnapshot.Column> eligibleColumns, String modelCode, List<String> primaryKeys,
			int maxColumnsPerTable) {
		List<String> references = semanticReferences(catalog, modelCode);
		int limit = Math.max(primaryKeys.size(), Math.max(1, maxColumnsPerTable));
		return eligibleColumns.stream()
			.filter(column -> modelCode.equals(column.getModelCode()))
			.sorted(Comparator
				.comparingInt(
						(SemanticCatalogSnapshot.Column column) -> columnPriority(column, primaryKeys, references))
				.reversed())
			.limit(limit)
			.toList();
	}

	private static int columnPriority(SemanticCatalogSnapshot.Column column, List<String> primaryKeys,
			List<String> references) {
		if (primaryKeys.contains(column.getColumnName())) {
			return 1000;
		}
		int priority = referencesColumn(references, column.getColumnName()) ? 500 : 0;
		SemanticColumnRole role = column.getRole();
		if (role == null) {
			return priority;
		}
		return priority + switch (role) {
			case IDENTIFIER -> 300;
			case TIME -> 250;
			case MEASURE, DIMENSION -> 200;
			case ATTRIBUTE -> 100;
		};
	}

	private static List<String> semanticReferences(SemanticCatalogSnapshot catalog, String modelCode) {
		List<String> values = new ArrayList<>();
		catalog.getGrains()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(value.getModelCode()))
			.map(SemanticCatalogSnapshot.Grain::getKeyColumns)
			.filter(Objects::nonNull)
			.forEach(values::add);
		catalog.getMetrics()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(value.getModelCode()))
			.forEach(
					value -> addAll(values, value.getExpression(), value.getFilterExpression(), value.getTimeColumn()));
		catalog.getDimensions()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED && modelCode.equals(value.getModelCode()))
			.forEach(value -> addAll(values, value.getColumnName(), value.getExpression()));
		catalog.getRelationships()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> modelCode.equals(value.getSourceModelCode())
					|| modelCode.equals(value.getTargetModelCode()))
			.map(SemanticCatalogSnapshot.Relationship::getJoinCondition)
			.filter(Objects::nonNull)
			.forEach(values::add);
		catalog.getRules()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> value.getModelCode() == null || modelCode.equals(value.getModelCode()))
			.map(SemanticCatalogSnapshot.Rule::getExpression)
			.filter(Objects::nonNull)
			.forEach(values::add);
		return values;
	}

	private static void addAll(List<String> target, String... values) {
		Arrays.stream(values).filter(Objects::nonNull).filter(value -> !value.isBlank()).forEach(target::add);
	}

	private static boolean referencesColumn(List<String> expressions, String columnName) {
		if (columnName == null || columnName.isBlank()) {
			return false;
		}
		Pattern pattern = Pattern
			.compile("(^|[^a-z0-9_])" + Pattern.quote(columnName.toLowerCase(Locale.ROOT)) + "([^a-z0-9_]|$)");
		return expressions.stream()
			.filter(Objects::nonNull)
			.map(value -> value.toLowerCase(Locale.ROOT))
			.anyMatch(value -> pattern.matcher(value).find());
	}

	private static Document tableDocument(SemanticCatalogSnapshot.Model model, Integer datasourceId,
			List<String> primaryKeys) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("schema", "");
		metadata.put("name", model.getPhysicalTable());
        metadata.put("semanticModelCode",model.getModelCode());
		metadata.put("description", description(model.getBusinessName(), model.getDescription()));
		metadata.put("foreignKey", "");
		metadata.put("primaryKey", primaryKeys);
		metadata.put(VECTOR_TYPE, TABLE);
		metadata.put(DATASOURCE_ID, datasourceId.toString());
		return new Document(description(model.getPhysicalTable(), model.getBusinessName()), metadata);
	}

	private static Document columnDocument(SemanticCatalogSnapshot.Column column, SemanticCatalogSnapshot.Model model,
			Integer datasourceId, List<String> primaryKeys) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", column.getColumnName());
		metadata.put("tableName", model.getPhysicalTable());
        metadata.put("semanticModelCode",model.getModelCode());
		metadata.put("description", description(column.getBusinessName(), column.getDescription()));
		metadata.put("type", Objects.toString(column.getDataType(), ""));
		metadata.put("primary", primaryKeys.contains(column.getColumnName()));
		metadata.put("notnull", Boolean.FALSE.equals(column.getNullable()));
		metadata.put(VECTOR_TYPE, COLUMN);
		metadata.put(DATASOURCE_ID, datasourceId.toString());
		return new Document(description(column.getColumnName(), column.getBusinessName()), metadata);
	}

	private static List<String> splitColumns(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(column -> !column.isBlank())
			.collect(Collectors.toCollection(ArrayList::new));
	}

	private static String description(String primary, String secondary) {
		if (secondary == null || secondary.isBlank() || Objects.equals(primary, secondary)) {
			return Objects.toString(primary, "");
		}
		return Objects.toString(primary, "") + " - " + secondary;
	}

	record SchemaDocuments(List<Document> tables, List<Document> columns) {
	}

}
