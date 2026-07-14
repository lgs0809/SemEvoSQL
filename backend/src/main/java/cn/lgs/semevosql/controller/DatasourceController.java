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
package cn.lgs.semevosql.controller;

import cn.lgs.semevosql.dto.datasource.DatasourceTypeDTO;
import cn.lgs.semevosql.dto.schema.CreateLogicalRelationDTO;
import cn.lgs.semevosql.dto.schema.UpdateLogicalRelationDTO;
import cn.lgs.semevosql.entity.Datasource;
import cn.lgs.semevosql.entity.LogicalRelation;
import cn.lgs.semevosql.exception.DatasourceNotFoundException;
import cn.lgs.semevosql.service.datasource.DatasourceService;
import cn.lgs.semevosql.service.datasource.DatasourceUsageService;
import cn.lgs.semevosql.service.datasource.SemanticQueryDatasourceCapabilities;
import cn.lgs.semevosql.service.datasource.DatasourceUsageService.DatasourceUsage;
import cn.lgs.semevosql.vo.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasource")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class DatasourceController {

	private final DatasourceService datasourceService;

	private final DatasourceUsageService datasourceUsageService;

	@GetMapping("/types")
	public ApiResponse<List<DatasourceTypeDTO>> getDatasourceTypes() {
		List<DatasourceTypeDTO> types = SemanticQueryDatasourceCapabilities.supportedTypes().stream()
			.map(type -> DatasourceTypeDTO.builder()
				.code(type.getCode())
				.typeName(type.getTypeName())
				.dialect(type.getDialect())
				.protocol(type.getProtocol())
				.displayName(type.getDialect())
				.build())
			.toList();
		return ApiResponse.success("获取数据源类型成功", types);
	}

	@GetMapping("/usage")
	public List<DatasourceUsage> getDatasourceUsage() {
		return datasourceUsageService.listUsage();
	}

	@GetMapping
	public List<Datasource> getAllDatasource(@RequestParam(value = "status", required = false) String status,
			@RequestParam(value = "type", required = false) String type) {
		if (StringUtils.isNotBlank(status)) {
			return datasourceService.getDatasourceByStatus(status);
		}
		if (StringUtils.isNotBlank(type)) {
			return datasourceService.getDatasourceByType(type);
		}
		return datasourceService.getAllDatasource();
	}

	@GetMapping("/{id}")
	public Datasource getDatasourceById(@PathVariable Integer id) {
		return requireDatasource(id);
	}

	@GetMapping("/{id}/tables")
	public List<String> getDatasourceTables(@PathVariable Integer id) throws Exception {
		return datasourceService.getDatasourceTables(id);
	}

	@PostMapping
	public Datasource createDatasource(@RequestBody Datasource datasource) {
		return datasourceService.createDatasource(datasource);
	}

	@PutMapping("/{id}")
	public Datasource updateDatasource(@PathVariable Integer id, @RequestBody Datasource datasource) {
		return datasourceService.updateDatasource(id, datasource);
	}

	@DeleteMapping("/{id}")
	public ApiResponse<Void> deleteDatasource(@PathVariable Integer id) {
		datasourceService.deleteDatasource(id);
		return ApiResponse.success("数据源删除成功");
	}

	@PostMapping("/{id}/test")
	public ApiResponse<Void> testConnection(@PathVariable Integer id) {
		datasourceService.testConnection(id);
		return ApiResponse.success("连接测试成功");
	}

	@GetMapping("/{id}/tables/{tableName}/columns")
	public ApiResponse<List<String>> getTableColumns(@PathVariable Integer id, @PathVariable String tableName)
			throws Exception {
		return ApiResponse.success("获取字段列表成功", datasourceService.getTableColumns(id, tableName));
	}

	@GetMapping("/{id}/logical-relations")
	public ApiResponse<List<LogicalRelation>> getLogicalRelations(@PathVariable(value = "id") Integer datasourceId) {
		return ApiResponse.success("success get logical relations",
				datasourceService.getLogicalRelations(datasourceId));
	}

	@PostMapping("/{id}/logical-relations")
	public ApiResponse<LogicalRelation> addLogicalRelation(@PathVariable(value = "id") Integer datasourceId,
			@Valid @RequestBody CreateLogicalRelationDTO dto) {
		LogicalRelation logicalRelation = LogicalRelation.builder()
			.sourceTableName(dto.getSourceTableName())
			.sourceColumnName(dto.getSourceColumnName())
			.targetTableName(dto.getTargetTableName())
			.targetColumnName(dto.getTargetColumnName())
			.relationType(dto.getRelationType())
			.description(dto.getDescription())
			.build();
		return ApiResponse.success("success create logical relation",
				datasourceService.addLogicalRelation(datasourceId, logicalRelation));
	}

	@PutMapping("/{id}/logical-relations/{relationId}")
	public ApiResponse<LogicalRelation> updateLogicalRelation(@PathVariable(value = "id") Integer datasourceId,
			@PathVariable Integer relationId, @RequestBody UpdateLogicalRelationDTO dto) {
		LogicalRelation logicalRelation = LogicalRelation.builder()
			.sourceTableName(dto.getSourceTableName())
			.sourceColumnName(dto.getSourceColumnName())
			.targetTableName(dto.getTargetTableName())
			.targetColumnName(dto.getTargetColumnName())
			.relationType(dto.getRelationType())
			.description(dto.getDescription())
			.build();
		return ApiResponse.success("success update logical relation",
				datasourceService.updateLogicalRelation(datasourceId, relationId, logicalRelation));
	}

	@DeleteMapping("/{id}/logical-relations/{relationId}")
	public ApiResponse<Void> deleteLogicalRelation(@PathVariable(value = "id") Integer datasourceId,
			@PathVariable Integer relationId) {
		datasourceService.deleteLogicalRelation(datasourceId, relationId);
		return ApiResponse.success("success delete logical relation");
	}

	@PutMapping("/{id}/logical-relations")
	public ApiResponse<List<LogicalRelation>> saveLogicalRelations(@PathVariable(value = "id") Integer datasourceId,
			@RequestBody List<LogicalRelation> logicalRelations) {
		return ApiResponse.success("success save logical relations",
				datasourceService.saveLogicalRelations(datasourceId, logicalRelations));
	}

	private Datasource requireDatasource(Integer id) {
		Datasource datasource = datasourceService.getDatasourceById(id);
		if (datasource == null) {
			throw new DatasourceNotFoundException(id);
		}
		return datasource;
	}

}
