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
package cn.lgs.semevosql.connector.pool;

import cn.lgs.semevosql.service.datasource.SemanticQueryDatasourceCapabilities;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB connection pool factory
 */
@Component
public class DBConnectionPoolFactory {

	private final Map<String, DBConnectionPool> poolMap = new ConcurrentHashMap<>();

	private final Map<String, DBConnectionPool> poolByDatasourceType = new ConcurrentHashMap<>();

	public DBConnectionPoolFactory(List<DBConnectionPool> pools) {
		pools.forEach(this::register);
	}

	public void register(DBConnectionPool pool) {
		if (!SemanticQueryDatasourceCapabilities.supports(pool.getConnectionPoolType())) {
			return;
		}
		poolMap.put(pool.getConnectionPoolType(), pool);
		for (cn.lgs.semevosql.enums.BizDataSourceTypeEnum type : cn.lgs.semevosql.enums.BizDataSourceTypeEnum.values()) {
			if (pool.supportedDataSourceType(type.getTypeName())) {
				poolByDatasourceType.put(type.getTypeName().toLowerCase(java.util.Locale.ROOT), pool);
			}
		}
	}

	public boolean isRegistered(String type) {
		return poolMap.containsKey(type);
	}

	/**
	 * Get corresponding DB connection pool based on database type
	 * @param type database type
	 * @return DB connection pool
	 */
	public DBConnectionPool getPoolByType(String type) {
		return poolMap.get(type);
	}

	public DBConnectionPool getPoolByDbType(String type) {
		DBConnectionPool pool = type == null ? null : poolByDatasourceType.get(type.toLowerCase(java.util.Locale.ROOT));
		if (pool == null) {
			throw new IllegalStateException("No DB connection pool found for type: " + type);
		}
		return pool;
	}

}
