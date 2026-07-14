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
package cn.lgs.semevosql.util;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.connector.accessor.AccessorFactory;
import cn.lgs.semevosql.entity.Datasource;
import cn.lgs.semevosql.exception.DatasourceNotFoundException;
import cn.lgs.semevosql.service.datasource.DatasourceService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Utility class for processing database.
 */
@Slf4j
@Component
@AllArgsConstructor
public class DatabaseUtil {

	private final AccessorFactory accessorFactory;

	private final DatasourceService datasourceService;

	public DbConfigBO getDatasourceDbConfig(Integer datasourceId) {
		if (datasourceId == null) {
			throw new IllegalArgumentException("Datasource ID cannot be null");
		}
		Datasource datasource = datasourceService.getDatasourceWithCredentialsById(datasourceId);
		if (datasource == null) {
			throw new DatasourceNotFoundException(datasourceId);
		}
		DbConfigBO dbConfig = datasourceService.getDbConfig(datasource);
		log.info("Created pinned datasource config: datasourceId={}, schema={}, type={}", datasourceId,
				dbConfig.getSchema(), dbConfig.getDialectType());
		return dbConfig;
	}

	public Accessor getDatasourceAccessor(Integer datasourceId) {
		return accessorFactory.getAccessorByDbConfig(getDatasourceDbConfig(datasourceId));
	}

}
