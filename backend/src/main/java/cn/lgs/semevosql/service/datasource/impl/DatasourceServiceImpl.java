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
package cn.lgs.semevosql.service.datasource.impl;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ColumnInfoBO;
import cn.lgs.semevosql.bo.schema.TableInfoBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.connector.accessor.AccessorFactory;
import cn.lgs.semevosql.connector.pool.DBConnectionPool;
import cn.lgs.semevosql.connector.pool.DBConnectionPoolFactory;
import cn.lgs.semevosql.entity.Datasource;
import cn.lgs.semevosql.entity.LogicalRelation;
import cn.lgs.semevosql.enums.ErrorCodeEnum;
import cn.lgs.semevosql.exception.DatasourceConflictException;
import cn.lgs.semevosql.exception.DatasourceConnectionException;
import cn.lgs.semevosql.exception.DatasourceNotFoundException;
import cn.lgs.semevosql.exception.LogicalRelationConflictException;
import cn.lgs.semevosql.exception.LogicalRelationNotFoundException;
import cn.lgs.semevosql.mapper.DatasourceMapper;
import cn.lgs.semevosql.mapper.LogicalRelationMapper;
import cn.lgs.semevosql.common.SecretCipher;
import cn.lgs.semevosql.service.datasource.DatasourceService;
import cn.lgs.semevosql.service.datasource.SemanticQueryDatasourceCapabilities;
import cn.lgs.semevosql.service.datasource.handler.DatasourceTypeHandler;
import cn.lgs.semevosql.service.datasource.handler.registry.DatasourceTypeHandlerRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@AllArgsConstructor
public class DatasourceServiceImpl implements DatasourceService {

	private final DatasourceMapper datasourceMapper;

	private final LogicalRelationMapper logicalRelationMapper;

	private final DBConnectionPoolFactory poolFactory;

	private final AccessorFactory accessorFactory;

	private final DatasourceTypeHandlerRegistry datasourceTypeHandlerRegistry;

	private final SecretCipher secretCipher;

	@Override
	public List<Datasource> getAllDatasource() {
		return datasourceMapper.selectAll().stream().map(this::toPublicDatasource).toList();
	}

	@Override
	public List<Datasource> getDatasourceByStatus(String status) {
		return datasourceMapper.selectByStatus(status).stream().map(this::toPublicDatasource).toList();
	}

	@Override
	public List<Datasource> getDatasourceByType(String type) {
		return datasourceMapper.selectByType(type).stream().map(this::toPublicDatasource).toList();
	}

	@Override
	public Datasource getDatasourceById(Integer id) {
		Datasource datasource = datasourceMapper.selectById(id);
		return datasource == null ? null : toPublicDatasource(datasource);
	}

	@Override
	public Datasource getDatasourceWithCredentialsById(Integer id) {
		Datasource datasource = datasourceMapper.selectById(id);
		return datasource == null ? null : decryptDatasource(datasource);
	}

	@Override
	public Datasource createDatasource(Datasource datasource) {
		Datasource persisted = copyDatasource(datasource);
		SemanticQueryDatasourceCapabilities.requireSupported(persisted.getType());
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(persisted.getType());
		String connectionUrl = handler.resolveConnectionUrl(persisted);
		if (StringUtils.isNotBlank(connectionUrl)) {
			persisted.setConnectionUrl(connectionUrl);
		}
		if (persisted.getStatus() == null) {
			persisted.setStatus("active");
		}
		if (persisted.getTestStatus() == null) {
			persisted.setTestStatus("unknown");
		}
		if (persisted.getUsername() == null) {
			persisted.setUsername("");
		}
		persisted
			.setPassword(secretCipher.encryptPlaintext(persisted.getPassword() == null ? "" : persisted.getPassword()));
		if (datasourceMapper.insert(persisted) != 1) {
			throw new IllegalStateException("Datasource insert did not persist exactly one row");
		}
		return toPublicDatasource(persisted);
	}

	@Override
	public Datasource updateDatasource(Integer id, Datasource datasource) {
		Datasource persisted = datasourceMapper.selectById(id);
		if (persisted == null) {
			throw new DatasourceNotFoundException(id);
		}
		mergeUpdate(persisted, datasource);
		SemanticQueryDatasourceCapabilities.requireSupported(persisted.getType());
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(persisted.getType());
		String connectionUrl = handler.resolveConnectionUrl(decryptDatasource(persisted));
		if (StringUtils.isNotBlank(connectionUrl)) {
			persisted.setConnectionUrl(connectionUrl);
		}
		if (datasourceMapper.updateById(persisted) != 1) {
			throw new IllegalStateException("Datasource update did not persist exactly one row");
		}
		persisted.setTestStatus("unknown");
		persisted.setLastTestTime(null);
		return toPublicDatasource(persisted);
	}

	@Override
	@Transactional
	public void deleteDatasource(Integer id) {
		if (datasourceMapper.selectById(id) == null) {
			throw new DatasourceNotFoundException(id);
		}
		try {
			if (datasourceMapper.deleteById(id) == 0) {
				throw new DatasourceNotFoundException(id);
			}
		}
		catch (DataIntegrityViolationException ex) {
			throw new DatasourceConflictException("数据源仍被项目或语义模型使用，无法删除", ex);
		}
	}

	@Override
	public void updateTestStatus(Integer id, String testStatus) {
		datasourceMapper.updateTestStatusById(id, testStatus);
	}

	@Override
	public boolean testConnection(Integer id) {
		Datasource datasource = requireDatasource(id);
		try {
			ErrorCodeEnum result = realConnectionTest(datasource);
			if (result != ErrorCodeEnum.SUCCESS) {
				updateTestStatus(id, "failed");
				throw new DatasourceConnectionException(result.getMessage());
			}
			updateTestStatus(id, "success");
			log.info("Datasource connection test succeeded. datasourceId={}, type={}", id, datasource.getType());
			return true;
		}
		catch (DatasourceConnectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			updateTestStatus(id, "failed");
			log.warn("Datasource connection test failed. datasourceId={}, type={}, errorType={}", id,
					datasource.getType(), ex.getClass().getSimpleName());
			throw new DatasourceConnectionException("数据源连接失败，请检查地址、凭据、数据库名称和网络配置", ex);
		}
	}

	private ErrorCodeEnum realConnectionTest(Datasource datasource) {
		DbConfigBO config = new DbConfigBO();
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String originalUrl = handler.resolveConnectionUrl(datasource);
		if (StringUtils.isNotBlank(originalUrl)) {
			originalUrl = handler.normalizeTestUrl(datasource, originalUrl);
		}
		config.setUrl(originalUrl);
		config.setUsername(datasource.getUsername());
		config.setPassword(datasource.getPassword());
		config.setConnectionType(datasource.getType());

		DBConnectionPool pool = poolFactory.getPoolByType(datasource.getType());
		return pool == null ? ErrorCodeEnum.INVALID_PARAM : pool.ping(config);
	}

@Override
	public List<String> getDatasourceTables(Integer datasourceId) throws Exception {
		log.info("Getting tables for datasource: {}", datasourceId);

		// Get data source information
		Datasource datasource = requireDatasource(datasourceId);

		// Create database configuration
		DbConfigBO dbConfig = getDbConfig(datasource);

		// Create query parameters
		DbQueryParameter queryParam = DbQueryParameter.from(dbConfig);

		// 提取schema名称
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String schemaName = handler.extractSchemaName(datasource);
		queryParam.setSchema(schemaName);

		// Query table list
		Accessor dbAccessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<TableInfoBO> tableInfoList = dbAccessor.showTables(dbConfig, queryParam);

		// Extract table names
		List<String> tableNames = tableInfoList.stream()
			.map(TableInfoBO::getName)
			.filter(name -> name != null && !name.trim().isEmpty())
			.sorted()
			.toList();

		log.info("Found {} tables for datasource: {}", tableNames.size(), datasourceId);
		return tableNames;
	}

	@Override
	public DbConfigBO getDbConfig(Datasource datasource) {
		Datasource decrypted = decryptDatasource(datasource);
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(decrypted.getType());
		return handler.toDbConfig(decrypted);
	}

	@Override
	public List<String> getTableColumns(Integer datasourceId, String tableName) throws Exception {
		log.info("Getting columns for table: {} in datasource: {}", tableName, datasourceId);

		// 获取数据源信息
		Datasource datasource = requireDatasource(datasourceId);

		// 创建数据库配置
		DbConfigBO dbConfig = getDbConfig(datasource);

		// 创建查询参数
		DbQueryParameter queryParam = DbQueryParameter.from(dbConfig);

		// 提取schema名称
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String schemaName = handler.extractSchemaName(datasource);
		queryParam.setSchema(schemaName);
		queryParam.setTable(tableName);

		// 查询字段列表
		Accessor dbAccessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<ColumnInfoBO> columnInfoList = dbAccessor.showColumns(dbConfig, queryParam); // 提取字段名称
		List<String> columnNames = columnInfoList.stream()
			.map(ColumnInfoBO::getName)
			.filter(name -> name != null && !name.trim().isEmpty())
			.sorted()
			.toList();

		log.info("Found {} columns for table {} in datasource: {}", columnNames.size(), tableName, datasourceId);
		return columnNames;
	}

	@Override
	public List<LogicalRelation> getLogicalRelations(Integer datasourceId) {
		requireDatasource(datasourceId);
		log.info("Getting logical relations for datasource: {}", datasourceId);
		return logicalRelationMapper.selectByDatasourceId(datasourceId);
	}

	@Override
	public LogicalRelation addLogicalRelation(Integer datasourceId, LogicalRelation logicalRelation) {
		requireDatasource(datasourceId);
		log.info("Adding logical relation for datasource: {}", datasourceId);

		// 设置数据源ID
		logicalRelation.setDatasourceId(datasourceId);

		// 检查是否已存在相同的外键关系
		int exists = logicalRelationMapper.checkExists(datasourceId, logicalRelation.getSourceTableName(),
				logicalRelation.getSourceColumnName(), logicalRelation.getTargetTableName(),
				logicalRelation.getTargetColumnName());

		if (exists > 0) {
			throw new LogicalRelationConflictException("该逻辑外键关系已存在");
		}

		// 插入外键
		logicalRelationMapper.insert(logicalRelation);
		log.info("Logical relation added successfully with id: {}", logicalRelation.getId());

		return logicalRelation;
	}

	@Override
	public LogicalRelation updateLogicalRelation(Integer datasourceId, Integer logicalRelationId,
			LogicalRelation logicalRelation) {
		requireDatasource(datasourceId);
		log.info("Updating logical relation: {} for datasource: {}", logicalRelationId, datasourceId);

		// 验证外键是否存在且属于该数据源
		LogicalRelation existingRelation = logicalRelationMapper.selectById(logicalRelationId);
		if (existingRelation == null) {
			throw new LogicalRelationNotFoundException(logicalRelationId);
		}

		if (!existingRelation.getDatasourceId().equals(datasourceId)) {
			throw new LogicalRelationConflictException("逻辑外键不属于指定的数据源");
		}

		// 设置ID和数据源ID
		logicalRelation.setId(logicalRelationId);
		logicalRelation.setDatasourceId(datasourceId);

		// 更新外键
		int updated = logicalRelationMapper.updateById(logicalRelation);
		if (updated == 0) {
			throw new RuntimeException("更新逻辑外键失败");
		}

		log.info("Logical relation updated successfully: {}", logicalRelationId);

		// 返回更新后的数据
		return logicalRelationMapper.selectById(logicalRelationId);
	}

	@Override
	public void deleteLogicalRelation(Integer datasourceId, Integer logicalRelationId) {
		requireDatasource(datasourceId);
		log.info("Deleting logical relation: {} for datasource: {}", logicalRelationId, datasourceId);

		// 验证外键是否属于该数据源
		LogicalRelation logicalRelation = logicalRelationMapper.selectById(logicalRelationId);
		if (logicalRelation == null) {
			throw new LogicalRelationNotFoundException(logicalRelationId);
		}

		if (!logicalRelation.getDatasourceId().equals(datasourceId)) {
			throw new LogicalRelationConflictException("逻辑外键不属于指定的数据源");
		}

		// 删除外键（逻辑删除）
		int deleted = logicalRelationMapper.deleteById(logicalRelationId);
		if (deleted == 0) {
			throw new RuntimeException("删除逻辑外键失败");
		}

		log.info("Logical relation deleted successfully: {}", logicalRelationId);
	}

	@Override
	@Transactional
	public List<LogicalRelation> saveLogicalRelations(Integer datasourceId, List<LogicalRelation> logicalRelations) {
		requireDatasource(datasourceId);
		log.info("Saving {} logical relations for datasource: {}", logicalRelations.size(), datasourceId);

		// 获取现有的所有外键关系
		List<LogicalRelation> existingRelations = logicalRelationMapper.selectByDatasourceId(datasourceId);
		Map<Integer, LogicalRelation> existingMap = existingRelations.stream()
			.collect(Collectors.toMap(LogicalRelation::getId, relation -> relation));

		// 收集传入列表中已存在的ID
		Set<Integer> incomingIds = logicalRelations.stream()
			.map(LogicalRelation::getId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		// 删除那些不在传入列表中的外键
		int deletedCount = 0;
		for (LogicalRelation existing : existingRelations) {
			if (!incomingIds.contains(existing.getId())) {
				logicalRelationMapper.deleteById(existing.getId());
				deletedCount++;
				log.info("Deleted logical relation: {} -> {}", existing.getSourceTableName(),
						existing.getTargetTableName());
			}
		}
		log.info("Deleted {} logical relations for datasource: {}", deletedCount, datasourceId);

		// 去重检查
		List<LogicalRelation> uniqueRelations = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		for (LogicalRelation logicalRelation : logicalRelations) {
			String key = logicalRelation.getSourceTableName() + "|" + logicalRelation.getSourceColumnName() + "|"
					+ logicalRelation.getTargetTableName() + "|" + logicalRelation.getTargetColumnName();

			if (!seen.contains(key)) {
				seen.add(key);
				uniqueRelations.add(logicalRelation);
			}
			else {
				log.warn("跳过重复的逻辑外键: {} -> {}", logicalRelation.getSourceTableName(),
						logicalRelation.getTargetTableName());
			}
		}

		int duplicateCount = logicalRelations.size() - uniqueRelations.size();
		if (duplicateCount > 0) {
			log.warn("检测到并去重了 {} 条重复的逻辑外键", duplicateCount);
		}

		// 插入或更新去重后的外键列表
		int insertedCount = 0;
		int updatedCount = 0;
		for (LogicalRelation logicalRelation : uniqueRelations) {
			logicalRelation.setDatasourceId(datasourceId);
			if (logicalRelation.getId() != null && !existingMap.containsKey(logicalRelation.getId())) {
				throw new LogicalRelationConflictException("逻辑外键不属于指定的数据源");
			}

			if (logicalRelation.getId() != null) {
				// 更新现有记录
				logicalRelationMapper.updateById(logicalRelation);
				updatedCount++;
				log.debug("Updated logical relation: {} -> {}", logicalRelation.getSourceTableName(),
						logicalRelation.getTargetTableName());
			}
			else {
				// 插入新记录
				logicalRelation.setId(null);
				logicalRelationMapper.insert(logicalRelation);
				insertedCount++;
				log.debug("Inserted logical relation: {} -> {}", logicalRelation.getSourceTableName(),
						logicalRelation.getTargetTableName());
			}
		}

		log.info("Saved logical relations for datasource {}: {} inserted, {} updated, {} deleted", datasourceId,
				insertedCount, updatedCount, deletedCount);

		return logicalRelationMapper.selectByDatasourceId(datasourceId);
	}

	private Datasource requireDatasource(Integer datasourceId) {
		Datasource datasource = getDatasourceWithCredentialsById(datasourceId);
		if (datasource == null) {
			throw new DatasourceNotFoundException(datasourceId);
		}
		return datasource;
	}

	private void mergeUpdate(Datasource target, Datasource update) {
		if (update.getName() != null) {
			target.setName(update.getName());
		}
		if (update.getType() != null) {
			target.setType(update.getType());
		}
		if (update.getHost() != null) {
			target.setHost(update.getHost());
		}
		if (update.getPort() != null) {
			target.setPort(update.getPort());
		}
		if (update.getDatabaseName() != null) {
			target.setDatabaseName(update.getDatabaseName());
		}
		if (update.getUsername() != null) {
			target.setUsername(update.getUsername());
		}
		if (StringUtils.isNotBlank(update.getPassword())) {
			target.setPassword(secretCipher.encryptPlaintext(update.getPassword()));
		}
		if (update.getStatus() != null) {
			target.setStatus(update.getStatus());
		}
		if (update.getTestStatus() != null) {
			target.setTestStatus(update.getTestStatus());
		}
		if (update.getDescription() != null) {
			target.setDescription(update.getDescription());
		}
		if (update.getCreatorId() != null) {
			target.setCreatorId(update.getCreatorId());
		}
	}

	private Datasource decryptDatasource(Datasource datasource) {
		Datasource copy = copyDatasource(datasource);
		copy.setPassword(secretCipher.decrypt(copy.getPassword()));
		return copy;
	}

	private Datasource toPublicDatasource(Datasource datasource) {
		Datasource copy = copyDatasource(datasource);
		copy.setPassword(null);
		copy.setConnectionUrl(null);
		return copy;
	}

	private Datasource copyDatasource(Datasource source) {
		return Datasource.builder()
			.id(source.getId())
			.name(source.getName())
			.type(source.getType())
			.host(source.getHost())
			.port(source.getPort())
			.databaseName(source.getDatabaseName())
			.username(source.getUsername())
			.password(source.getPassword())
			.connectionUrl(source.getConnectionUrl())
			.status(source.getStatus())
			.testStatus(source.getTestStatus())
			.lastTestTime(source.getLastTestTime())
			.description(source.getDescription())
			.creatorId(source.getCreatorId())
			.createTime(source.getCreateTime())
			.updateTime(source.getUpdateTime())
			.build();
	}

}
