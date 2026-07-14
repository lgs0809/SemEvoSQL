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

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ColumnInfoBO;
import cn.lgs.semevosql.bo.schema.ForeignKeyInfoBO;
import cn.lgs.semevosql.bo.schema.TableInfoBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.project.domain.InitializationAnalysisStatus;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.IngestionResult;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.MaterialRegistration;
import cn.lgs.semevosql.semantic.domain.ProjectDocumentType;
import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialSourceType;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialStatus;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import cn.lgs.semevosql.util.DatabaseUtil;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds a conservative semantic-catalog draft from physical database metadata. Only
 * facts directly available from the database are materialized. Missing grain or ambiguous
 * business choices are emitted as stable Semantic Gaps instead of being guessed.
 */
@Service
@RequiredArgsConstructor
public class DatabaseSemanticCatalogAnalyzer {

	private static final String SOURCE = "database-schema-scan";

	private final DatabaseUtil databaseUtil;

	private final SemanticCatalogApplicationService catalogService;

	private final SemanticProjectRepository projectRepository;

	private final SemanticMaterialIngestionService materialIngestionService;

	public AnalysisResult analyze(Long projectId, Long projectVersionId, Integer datasourceId,
			Collection<String> requestedTables) throws Exception {
		SemanticProjectVersion version = requireRunningDraft(projectId, projectVersionId);
		DbConfigBO dbConfig = databaseUtil.getDatasourceDbConfig(datasourceId);
		Accessor accessor = databaseUtil.getDatasourceAccessor(datasourceId);
		DbQueryParameter parameter = DbQueryParameter.from(dbConfig)
			.setSchema(dbConfig.getSchema())
			.setTables(normalizeRequestedTables(requestedTables));

		List<TableInfoBO> tables = selectTables(accessor.fetchTables(dbConfig, parameter), requestedTables);
		if (tables.isEmpty()) {
			throw new IllegalStateException("Database scan did not return any selected table");
		}
		Map<String, List<ColumnInfoBO>> columnsByTable = fetchColumns(accessor, dbConfig, tables);
		List<ForeignKeyInfoBO> foreignKeys = safe(accessor.showForeignKeys(dbConfig, parameter));
		SemanticCatalogSnapshot existingCatalog = catalogService.getCatalog(projectId, projectVersionId);

		Map<String, String> modelCodeByTable = assignStableModelCodes(datasourceId, tables, existingCatalog);
		SemanticCatalogSnapshot scannedSnapshot = buildSnapshot(projectId, projectVersionId, datasourceId, tables,
				columnsByTable, foreignKeys, modelCodeByTable);
		SemanticCatalogSnapshot preparedPatch = preserveConfirmedFields(scannedSnapshot, existingCatalog);
		// A schema scan records physical facts only. Missing comments, missing primary
		// keys or
		// multiple time-like columns are not business questions by themselves. Uploaded
		// materials
		// may mine a BusinessQueryScenario during initialization, and runtime queries can
		// do the same
		// later; only those concrete requirements are allowed to surface a business gap.
		List<SemanticGap> gaps = List.of();
		String sourceContent = serializeSourceSnapshot(datasourceId, tables, scannedSnapshot);
		String sourceLocation = "datasource:" + datasourceId + ";tables="
				+ tables.stream()
					.map(TableInfoBO::getName)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.collect(Collectors.joining(","));
		IngestionResult ingestion = materialIngestionService.ingestGeneratedPatch(projectId, projectVersionId,
				new MaterialRegistration(ProjectDocumentType.DATA_DICTIONARY, SemanticMaterialType.JSON,
						SemanticMaterialSourceType.DATABASE_SCAN, null, SOURCE + ":" + datasourceId, null,
						"application/json", null, (long) sourceContent.getBytes(StandardCharsets.UTF_8).length,
						sourceLocation, datasourceId, sourceContent, SOURCE),
				new SemanticMaterialParseResult(preparedPatch, gaps, !gaps.isEmpty(),
						"Database schema scan processed " + tables.size() + " table(s)"));
		if (ingestion.status() == SemanticMaterialStatus.FAILED) {
			String error = ingestion.material() == null ? null : ingestion.material().errorMessage();
			throw new IllegalStateException("Database schema scan evidence could not be applied"
					+ (error == null || error.isBlank() ? "" : ": " + error));
		}
		reconcileScannerGaps(projectVersionId, datasourceId, gaps);
		SemanticCatalogSnapshot persisted = catalogService.getCatalog(projectId, projectVersionId);
		return new AnalysisResult(version.getId(), persisted, gaps, ingestion.catalogReadiness());
	}

	private SemanticCatalogSnapshot buildSnapshot(Long projectId, Long projectVersionId, Integer datasourceId,
			List<TableInfoBO> tables, Map<String, List<ColumnInfoBO>> columnsByTable,
			List<ForeignKeyInfoBO> foreignKeys, Map<String, String> modelCodeByTable) {
		List<SemanticCatalogSnapshot.Model> models = new ArrayList<>();
		List<SemanticCatalogSnapshot.Column> columns = new ArrayList<>();
		List<SemanticCatalogSnapshot.Metric> metrics = new ArrayList<>();
		List<SemanticCatalogSnapshot.Dimension> dimensions = new ArrayList<>();
		List<SemanticCatalogSnapshot.Grain> grains = new ArrayList<>();

		for (TableInfoBO table : tables) {
			String modelCode = modelCodeByTable.get(normalizeTableKey(table.getName()));
			String tableDescription = trimToNull(table.getDescription());
			models.add(SemanticCatalogSnapshot.Model.builder()
				.projectId(projectId)
				.projectVersionId(projectVersionId)
				.datasourceId(datasourceId)
				.modelCode(modelCode)
				.physicalTable(table.getName())
				.businessName(tableDescription == null ? table.getName() : tableDescription)
				.description(tableDescription)
				.evidence(SOURCE + ":table=" + table.getName())
				.status(SemanticAssetStatus.ENABLED)
				.build());

			List<ColumnInfoBO> tableColumns = columnsByTable.getOrDefault(normalizeTableKey(table.getName()),
					List.of());
			List<String> primaryKeys = tableColumns.stream()
				.filter(ColumnInfoBO::isPrimary)
				.map(ColumnInfoBO::getName)
				.toList();
			List<String> timeColumns = new ArrayList<>();
			for (ColumnInfoBO column : tableColumns) {
				SemanticColumnRole role = inferColumnRole(column);
				String columnDescription = trimToNull(column.getDescription());
				columns.add(SemanticCatalogSnapshot.Column.builder()
					.projectId(projectId)
					.projectVersionId(projectVersionId)
					.modelCode(modelCode)
					.columnName(column.getName())
					.businessName(columnDescription == null ? column.getName() : columnDescription)
					.dataType(column.getType())
					.role(role)
					.description(columnDescription)
					.nullable(!column.isNotnull())
					.evidence(SOURCE + ":column=" + table.getName() + "." + column.getName())
					.status(SemanticAssetStatus.ENABLED)
					.build());
				if (role == SemanticColumnRole.TIME) {
					timeColumns.add(column.getName());
				}
				if (role == SemanticColumnRole.DIMENSION || role == SemanticColumnRole.TIME) {
					dimensions.add(SemanticCatalogSnapshot.Dimension.builder()
						.projectId(projectId)
						.projectVersionId(projectVersionId)
						.modelCode(modelCode)
						.dimensionCode(modelCode + "_" + toCode(column.getName()))
						.businessName(columnDescription == null ? column.getName() : columnDescription)
						.columnName(column.getName())
						.dimensionType(role.name())
						.description(columnDescription)
						.evidence(SOURCE + ":direct-column-dimension")
						.status(SemanticAssetStatus.ENABLED)
						.build());
				}
			}
			if (!primaryKeys.isEmpty()) {
				grains.add(SemanticCatalogSnapshot.Grain.builder()
					.projectId(projectId)
					.projectVersionId(projectVersionId)
					.modelCode(modelCode)
					.grainCode(modelCode + "_primary_key_grain")
					.keyColumns(String.join(",", primaryKeys))
					.uniquenessRule("PRIMARY KEY(" + String.join(",", primaryKeys) + ")")
					.description("Grain inferred from the physical primary key")
					.evidence(SOURCE + ":primary-key")
					.status(SemanticAssetStatus.ENABLED)
					.build());
			}
			if (primaryKeys.size() == 1) {
				String primaryKey = primaryKeys.get(0);
				metrics.add(SemanticCatalogSnapshot.Metric.builder()
					.projectId(projectId)
					.projectVersionId(projectVersionId)
					.modelCode(modelCode)
					.metricCode(modelCode + "_count")
					.businessName(countMetricBusinessName(tableDescription, table.getName()))
					.expression(primaryKey)
					.aggregation("COUNT_DISTINCT")
					.unit("count")
					.timeColumn(timeColumns.size() == 1 ? timeColumns.get(0) : null)
					.additiveType("NON_ADDITIVE")
					.description("Distinct entity count at the physical primary-key grain")
					.evidence(SOURCE + ":primary-key-count=" + table.getName() + "." + primaryKey)
					.status(SemanticAssetStatus.ENABLED)
					.build());
			}
		}

		List<SemanticCatalogSnapshot.Relationship> relationships = buildRelationships(projectId, projectVersionId,
				foreignKeys, modelCodeByTable);
		return SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.models(models)
			.columns(columns)
			.metrics(metrics)
			.dimensions(dimensions)
			.relationships(relationships)
			.grains(grains)
			.enumValues(List.of())
			.rules(List.of())
			.build();
	}

	private List<SemanticCatalogSnapshot.Relationship> buildRelationships(Long projectId, Long projectVersionId,
			List<ForeignKeyInfoBO> foreignKeys, Map<String, String> modelCodeByTable) {
		List<ForeignKeyInfoBO> selectedForeignKeys = foreignKeys.stream()
			.filter(foreignKey -> modelCodeByTable.containsKey(normalizeTableKey(foreignKey.getTable())))
			.filter(foreignKey -> modelCodeByTable.containsKey(normalizeTableKey(foreignKey.getReferencedTable())))
			.sorted(Comparator.comparing((ForeignKeyInfoBO value) -> normalizeTableKey(value.getTable()))
				.thenComparing(value -> nullToEmpty(value.getColumn()))
				.thenComparing(value -> normalizeTableKey(value.getReferencedTable()))
				.thenComparing(value -> nullToEmpty(value.getReferencedColumn())))
			.toList();
		List<SemanticCatalogSnapshot.Relationship> relationships = new ArrayList<>();
		Set<String> relationshipCodes = new LinkedHashSet<>();
		for (ForeignKeyInfoBO foreignKey : selectedForeignKeys) {
			String sourceModelCode = modelCodeByTable.get(normalizeTableKey(foreignKey.getTable()));
			String targetModelCode = modelCodeByTable.get(normalizeTableKey(foreignKey.getReferencedTable()));
			String baseCode = sourceModelCode + "_" + toCode(foreignKey.getColumn()) + "_to_" + targetModelCode + "_"
					+ toCode(foreignKey.getReferencedColumn());
			String relationshipCode = uniqueCode(baseCode, relationshipCodes);
			relationships.add(SemanticCatalogSnapshot.Relationship.builder()
				.projectId(projectId)
				.projectVersionId(projectVersionId)
				.relationshipCode(relationshipCode)
				.sourceModelCode(sourceModelCode)
				.targetModelCode(targetModelCode)
				.cardinality(RelationshipCardinality.MANY_TO_ONE)
				.joinType("LEFT")
				.joinCondition(foreignKey.getTable() + "." + foreignKey.getColumn() + " = "
						+ foreignKey.getReferencedTable() + "." + foreignKey.getReferencedColumn())
				.description("Physical foreign key relationship")
				.evidence(SOURCE + ":foreign-key")
				.status(SemanticAssetStatus.ENABLED)
				.build());
		}
		return relationships;
	}

	private SemanticCatalogSnapshot preserveConfirmedFields(SemanticCatalogSnapshot scanned,
			SemanticCatalogSnapshot existing) {
		if (existing == null || existing.getModels() == null || existing.getModels().isEmpty()) {
			return scanned;
		}
		Map<String, SemanticCatalogSnapshot.Model> existingModelsByTable = safe(existing.getModels()).stream()
			.collect(Collectors.toMap(model -> modelPhysicalKey(model.getDatasourceId(), model.getPhysicalTable()),
					Function.identity(), (left, right) -> left));
		for (SemanticCatalogSnapshot.Model model : scanned.getModels()) {
			SemanticCatalogSnapshot.Model previous = existingModelsByTable
				.get(modelPhysicalKey(model.getDatasourceId(), model.getPhysicalTable()));
			if (previous == null) {
				continue;
			}
			if (trimToNull(previous.getBusinessName()) != null) {
				model.setBusinessName(previous.getBusinessName());
			}
			model.setRetrievalJson(previous.getRetrievalJson());
			if (trimToNull(previous.getDescription()) != null) {
				model.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				model.setStatus(previous.getStatus());
			}
		}

		Map<String, SemanticCatalogSnapshot.Column> existingColumns = safe(existing.getColumns()).stream()
			.collect(Collectors.toMap(column -> assetKey(column.getModelCode(), column.getColumnName()),
					Function.identity(), (left, right) -> left));
		for (SemanticCatalogSnapshot.Column column : scanned.getColumns()) {
			SemanticCatalogSnapshot.Column previous = existingColumns
				.get(assetKey(column.getModelCode(), column.getColumnName()));
			if (previous == null) {
				continue;
			}
			if (trimToNull(previous.getBusinessName()) != null) {
				column.setBusinessName(previous.getBusinessName());
			}
			column.setRole(previous.getRole() == null ? column.getRole() : previous.getRole());
			column.setExpression(previous.getExpression());
			column.setSynonyms(previous.getSynonyms());
			column.setRetrievalJson(previous.getRetrievalJson());
			if (trimToNull(previous.getDescription()) != null) {
				column.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				column.setStatus(previous.getStatus());
			}
		}

		Map<String, SemanticCatalogSnapshot.Dimension> existingDimensions = safe(existing.getDimensions()).stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Dimension::getDimensionCode, Function.identity(),
					(left, right) -> left));
		for (SemanticCatalogSnapshot.Dimension dimension : scanned.getDimensions()) {
			SemanticCatalogSnapshot.Dimension previous = existingDimensions.get(dimension.getDimensionCode());
			if (previous == null) {
				continue;
			}
			if (trimToNull(previous.getBusinessName()) != null) {
				dimension.setBusinessName(previous.getBusinessName());
			}
			dimension.setExpression(previous.getExpression());
			dimension.setHierarchy(previous.getHierarchy());
			dimension.setRetrievalJson(previous.getRetrievalJson());
			if (trimToNull(previous.getDescription()) != null) {
				dimension.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				dimension.setStatus(previous.getStatus());
			}
		}

		Map<String, SemanticCatalogSnapshot.Metric> existingMetrics = safe(existing.getMetrics()).stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Metric::getMetricCode, Function.identity(),
					(left, right) -> left));
		for (SemanticCatalogSnapshot.Metric metric : scanned.getMetrics()) {
			SemanticCatalogSnapshot.Metric previous = existingMetrics.get(metric.getMetricCode());
			if (previous == null) {
				continue;
			}
			if (trimToNull(previous.getBusinessName()) != null) {
				metric.setBusinessName(previous.getBusinessName());
			}
			metric.setExpression(previous.getExpression());
			metric.setAggregation(previous.getAggregation());
			metric.setUnit(previous.getUnit());
            metric.setMinimumValue(previous.getMinimumValue());
            metric.setMaximumValue(previous.getMaximumValue());
            metric.setMinimumInclusive(previous.getMinimumInclusive());
            metric.setMaximumInclusive(previous.getMaximumInclusive());
			metric.setTimeColumn(previous.getTimeColumn());
			metric.setFilterExpression(previous.getFilterExpression());
			metric.setAdditiveType(previous.getAdditiveType());
			metric.setRetrievalJson(previous.getRetrievalJson());
			if (trimToNull(previous.getDescription()) != null) {
				metric.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				metric.setStatus(previous.getStatus());
			}
		}

		Map<String, SemanticCatalogSnapshot.Relationship> existingRelationships = safe(existing.getRelationships())
			.stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Relationship::getRelationshipCode, Function.identity(),
					(left, right) -> left));
		for (SemanticCatalogSnapshot.Relationship relationship : scanned.getRelationships()) {
			SemanticCatalogSnapshot.Relationship previous = existingRelationships
				.get(relationship.getRelationshipCode());
			if (previous == null) {
				continue;
			}
			if (previous.getCardinality() != null) {
				relationship.setCardinality(previous.getCardinality());
			}
			if (trimToNull(previous.getJoinType()) != null) {
				relationship.setJoinType(previous.getJoinType());
			}
			relationship.setRetrievalJson(previous.getRetrievalJson());
			if (trimToNull(previous.getDescription()) != null) {
				relationship.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				relationship.setStatus(previous.getStatus());
			}
		}

		Map<String, SemanticCatalogSnapshot.Grain> existingGrainByModel = safe(existing.getGrains()).stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Grain::getModelCode, Function.identity(),
					(left, right) -> left));
		for (SemanticCatalogSnapshot.Grain grain : scanned.getGrains()) {
			SemanticCatalogSnapshot.Grain previous = existingGrainByModel.get(grain.getModelCode());
			if (previous == null) {
				continue;
			}
			if (trimToNull(previous.getDescription()) != null) {
				grain.setDescription(previous.getDescription());
			}
			if (previous.getStatus() != null) {
				grain.setStatus(previous.getStatus());
			}
		}

		return SemanticCatalogSnapshot.builder()
			.projectId(scanned.getProjectId())
			.projectVersionId(scanned.getProjectVersionId())
			.models(scanned.getModels())
			.columns(scanned.getColumns())
			.metrics(scanned.getMetrics())
			.dimensions(scanned.getDimensions())
			.relationships(scanned.getRelationships())
			.grains(scanned.getGrains())
			.enumValues(List.of())
			.rules(List.of())
			.build();
	}

	private String serializeSourceSnapshot(Integer datasourceId, List<TableInfoBO> tables,
			SemanticCatalogSnapshot snapshot) {
		try {
			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("sourceType", SemanticMaterialSourceType.DATABASE_SCAN.name());
			envelope.put("datasourceId", datasourceId);
			envelope.put("tables",
					tables.stream().map(TableInfoBO::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
			envelope.put("catalogPatch", snapshot);
			return JsonUtil.getObjectMapper().writeValueAsString(envelope);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize database metadata evidence", ex);
		}
	}

	private void reconcileScannerGaps(Long projectVersionId, Integer datasourceId, List<SemanticGap> activeGaps) {
		String prefix = "db:" + datasourceId + ":";
		Set<String> activeKeys = activeGaps.stream()
			.map(SemanticGap::getGapKey)
			.filter(key -> key != null && !key.isBlank())
			.collect(Collectors.toSet());
		for (SemanticGap openGap : projectRepository.findOpenGapsByKeyPrefix(projectVersionId, prefix)) {
			if (!activeKeys.contains(openGap.getGapKey())) {
				openGap.resolve("数据库重扫确认该问题已不存在。", "system");
				projectRepository.updateGap(openGap);
			}
		}
	}

	private Map<String, List<ColumnInfoBO>> fetchColumns(Accessor accessor, DbConfigBO dbConfig,
			List<TableInfoBO> tables) throws Exception {
		Map<String, List<ColumnInfoBO>> result = new LinkedHashMap<>();
		for (TableInfoBO table : tables) {
			DbQueryParameter parameter = DbQueryParameter.from(dbConfig)
				.setSchema(dbConfig.getSchema())
				.setTable(table.getName());
			List<ColumnInfoBO> columns = safe(accessor.showColumns(dbConfig, parameter)).stream()
				.sorted(Comparator.comparing(ColumnInfoBO::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
				.toList();
			table.setColumns(columns);
			table.setPrimaryKeys(columns.stream().filter(ColumnInfoBO::isPrimary).map(ColumnInfoBO::getName).toList());
			result.put(normalizeTableKey(table.getName()), columns);
		}
		return result;
	}

	private List<TableInfoBO> selectTables(List<TableInfoBO> discoveredTables, Collection<String> requestedTables) {
		Set<String> requested = normalizeRequestedTables(requestedTables).stream()
			.map(this::normalizeTableKey)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return safe(discoveredTables).stream()
			.filter(table -> table.getName() != null && !table.getName().isBlank())
			.filter(table -> requested.isEmpty() || requested.contains(normalizeTableKey(table.getName())))
			.sorted(Comparator.comparing(TableInfoBO::getName, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	private Map<String, String> assignStableModelCodes(Integer datasourceId, List<TableInfoBO> tables,
			SemanticCatalogSnapshot existingCatalog) {
		List<SemanticCatalogSnapshot.Model> existingModels = existingCatalog == null ? List.of()
				: safe(existingCatalog.getModels());
		Set<String> selectedTables = tables.stream()
			.map(TableInfoBO::getName)
			.map(this::normalizeTableKey)
			.collect(Collectors.toSet());
		Map<String, String> existingCodes = existingModels.stream()
			.filter(model -> java.util.Objects.equals(datasourceId, model.getDatasourceId()))
			.collect(Collectors.toMap(model -> normalizeTableKey(model.getPhysicalTable()),
					SemanticCatalogSnapshot.Model::getModelCode, (left, right) -> left));
		Set<String> usedCodes = existingModels.stream()
			.filter(model -> !(java.util.Objects.equals(datasourceId, model.getDatasourceId())
					&& selectedTables.contains(normalizeTableKey(model.getPhysicalTable()))))
			.map(SemanticCatalogSnapshot.Model::getModelCode)
			.filter(code -> trimToNull(code) != null)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, String> result = new LinkedHashMap<>();
		for (TableInfoBO table : tables) {
			String tableKey = normalizeTableKey(table.getName());
			String preferredCode = trimToNull(existingCodes.get(tableKey));
			result.put(tableKey,
					uniqueCode(preferredCode == null ? toCode(table.getName()) : preferredCode, usedCodes));
		}
		return result;
	}

	private SemanticProjectVersion requireRunningDraft(Long projectId, Long projectVersionId) {
		SemanticProjectVersion version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + projectVersionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		if (version.getStatus() != ProjectVersionStatus.DRAFT
				|| version.getAnalysisStatus() != InitializationAnalysisStatus.RUNNING) {
			throw new IllegalStateException("Database semantic analysis requires a RUNNING DRAFT version");
		}
		return version;
	}

	private SemanticColumnRole inferColumnRole(ColumnInfoBO column) {
		String name = nullToEmpty(column.getName()).toLowerCase(Locale.ROOT);
		String type = nullToEmpty(column.getType()).toLowerCase(Locale.ROOT);
		if (column.isPrimary() || looksLikeIdentifier(name)) {
			return SemanticColumnRole.IDENTIFIER;
		}
		if (type.contains("date") || type.contains("time") || type.contains("timestamp") || name.endsWith("_at")
				|| name.endsWith("_date") || name.endsWith("_time")) {
			return SemanticColumnRole.TIME;
		}
		if (looksCategorical(name) || type.contains("enum") || type.contains("bool") || type.contains("char")
				|| type.contains("varchar")) {
			return SemanticColumnRole.DIMENSION;
		}
		if (isNumeric(type)) {
			return SemanticColumnRole.MEASURE;
		}
		return SemanticColumnRole.ATTRIBUTE;
	}

	private boolean looksLikeIdentifier(String columnName) {
		String normalized = nullToEmpty(columnName).toLowerCase(Locale.ROOT);
		return normalized.equals("id") || normalized.endsWith("_id") || normalized.endsWith("_key");
	}

	private boolean looksCategorical(String columnName) {
		String normalized = nullToEmpty(columnName).toLowerCase(Locale.ROOT);
		return normalized.endsWith("_code") || normalized.endsWith("_status") || normalized.equals("status")
				|| normalized.endsWith("_type") || normalized.equals("type") || normalized.contains("category")
				|| normalized.contains("channel") || normalized.contains("region") || normalized.contains("country")
				|| normalized.contains("province") || normalized.contains("city");
	}

	private boolean isNumeric(String type) {
		return type.contains("int") || type.contains("decimal") || type.contains("numeric") || type.contains("number")
				|| type.contains("double") || type.contains("float") || type.contains("real") || type.contains("money");
	}

	private String gapKey(Integer datasourceId, String modelCode, String kind) {
		return "db:" + datasourceId + ":model:" + modelCode + ":" + kind;
	}

	private String assetKey(String modelCode, String assetName) {
		return nullToEmpty(modelCode) + "::" + nullToEmpty(assetName);
	}

	private String modelPhysicalKey(Integer datasourceId, String physicalTable) {
		return String.valueOf(datasourceId) + "::" + normalizeTableKey(physicalTable);
	}

	private List<String> normalizeRequestedTables(Collection<String> requestedTables) {
		if (requestedTables == null) {
			return List.of();
		}
		return requestedTables.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
	}

	private String uniqueCode(String baseCode, Set<String> usedCodes) {
		String safeBase = baseCode == null || baseCode.isBlank() ? "asset" : baseCode;
		String candidate = safeBase;
		int suffix = 2;
		while (!usedCodes.add(candidate)) {
			candidate = safeBase + "_" + suffix++;
		}
		return candidate;
	}

	private String toCode(String value) {
		String code = nullToEmpty(value).trim()
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^\\p{L}\\p{N}]+", "_")
			.replaceAll("^_+|_+$", "");
		return code.isBlank() ? "asset" : code;
	}

	private String normalizeTableKey(String value) {
		return nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
	}

	private String countMetricBusinessName(String tableDescription, String tableName) {
		String label = trimToNull(tableDescription);
		if (label == null) {
			return tableName + " count";
		}
		if (label.endsWith("表") && label.length() > 1) {
			label = label.substring(0, label.length() - 1);
		}
		return label + "数量";
	}

	private String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public record AnalysisResult(Long projectVersionId, SemanticCatalogSnapshot catalog, List<SemanticGap> gaps,
			CatalogReadiness readiness) {
	}

}
