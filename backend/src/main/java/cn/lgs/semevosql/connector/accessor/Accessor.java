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

import cn.lgs.semevosql.bo.schema.ColumnInfoBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.bo.schema.ForeignKeyInfoBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.bo.schema.SchemaInfoBO;
import cn.lgs.semevosql.bo.schema.TableInfoBO;
import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;

import java.util.List;

/**
 * Data access interface definition.
 *
 */

public interface Accessor {

	String getAccessorType();

	boolean supportedDataSourceType(String type);

	default boolean supportedDataSourceType(BizDataSourceTypeEnum typeEnum) {
		return supportedDataSourceType(typeEnum.getTypeName());
	}

	List<SchemaInfoBO> showSchemas(DbConfigBO dbConfig) throws Exception;

	List<TableInfoBO> showTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	List<TableInfoBO> fetchTables(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	List<ColumnInfoBO> showColumns(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	List<ForeignKeyInfoBO> showForeignKeys(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	List<String> sampleColumn(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	ResultSetBO scanTable(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

	ResultSetBO executeSqlAndReturnObject(DbConfigBO dbConfig, DbQueryParameter param) throws Exception;

}
