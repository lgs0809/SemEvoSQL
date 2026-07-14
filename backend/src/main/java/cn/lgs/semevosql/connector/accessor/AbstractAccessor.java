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
package cn.lgs.semevosql.connector.accessor;

import cn.lgs.semevosql.connector.ddl.AbstractJdbcDdl;
import cn.lgs.semevosql.connector.pool.DBConnectionPool;
import cn.lgs.semevosql.connector.ddl.DdlFactory;
import cn.lgs.semevosql.connector.SqlExecutor;
import cn.lgs.semevosql.bo.schema.ColumnInfoBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.bo.schema.ForeignKeyInfoBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.bo.schema.SchemaInfoBO;
import cn.lgs.semevosql.bo.schema.TableInfoBO;
import cn.lgs.semevosql.bo.DbConfigBO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.util.List;

/**
 */
@Slf4j
@AllArgsConstructor
public abstract class AbstractAccessor implements Accessor {

	private final DdlFactory ddlFactory;

	private final DBConnectionPool dbConnectionPool;

	private <T> T accessDb(DbConfigBO dbConfig, String operationName, DbOperation<T> operation) throws Exception {
		try (Connection connection = getConnection(dbConfig)) {
			AbstractJdbcDdl ddlExecutor = (AbstractJdbcDdl) ddlFactory.getDdlExecutorByDbConfig(dbConfig);
			return operation.execute(ddlExecutor, connection);
		}
		catch (Exception e) {
			log.error("Error accessing database with operation: {}, reason: {}", operationName, e.getMessage());
			throw e;
		}
	}

	public List<SchemaInfoBO> showSchemas(DbConfigBO dbConfig) throws Exception {
		return accessDb(dbConfig, "showSchemas", (ddl, connection) -> ddl.showSchemas(connection));
	}

	public List<TableInfoBO> showTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "showTables",
				(ddl, connection) -> ddl.showTables(connection, param.getSchema(), param.getTablePattern()));
	}

	public List<TableInfoBO> fetchTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "fetchTables",
				(ddl, connection) -> ddl.fetchTables(connection, param.getSchema(), param.getTables()));
	}

	public List<ColumnInfoBO> showColumns(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "showColumns",
				(ddl, connection) -> ddl.showColumns(connection, param.getSchema(), param.getTable()));
	}

	public List<ForeignKeyInfoBO> showForeignKeys(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "showForeignKeys",
				(ddl, connection) -> ddl.showForeignKeys(connection, param.getSchema(), param.getTables()));
	}

	public List<String> sampleColumn(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "sampleColumn",
				(ddl, connection) -> ddl.sampleColumn(connection, param.getSchema(), param.getTable(), param.getColumn()));
	}

	public ResultSetBO scanTable(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "scanTable",
				(ddl, connection) -> ddl.scanTable(connection, param.getSchema(), param.getTable()));
	}

	public ResultSetBO executeSqlAndReturnObject(DbConfigBO dbConfig, DbQueryParameter param) throws Exception {
		return accessDb(dbConfig, "executeSqlAndReturnObject", (ddl, connection) ->
            param.getExecutionDelegate()!=null ? param.getExecutionDelegate().execute(connection) : SqlExecutor.executeSqlAndReturnObject(
				connection, param.getSchema(), param.getSql(), param.getParameters(), param.getMaxRows(),
				param.getQueryTimeoutSeconds(), param.getCancellationKey()));
	}

	public Connection getConnection(DbConfigBO config) {
		return this.dbConnectionPool.getConnection(config);
	}

	@FunctionalInterface
	private interface DbOperation<T> {

		T execute(AbstractJdbcDdl ddl, Connection connection) throws Exception;
	}

}
