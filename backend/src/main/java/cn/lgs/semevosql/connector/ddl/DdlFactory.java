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
package cn.lgs.semevosql.connector.ddl;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.service.datasource.SemanticQueryDatasourceCapabilities;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DdlFactory {

	private final Map<String, Ddl> ddlExecutorSet = new ConcurrentHashMap<>();

	private final Map<BizDataSourceTypeEnum, Ddl> ddlByDatasourceType = new ConcurrentHashMap<>();

	public DdlFactory(List<Ddl> ddls) {
		ddls.forEach(this::registry);
	}

	public void registry(Ddl ddlExecutor) {
		if (!SemanticQueryDatasourceCapabilities.supports(ddlExecutor.getDataSourceType().getTypeName())) {
			return;
		}
		ddlExecutorSet.put(ddlExecutor.getDdlType(), ddlExecutor);
		for (BizDataSourceTypeEnum type : BizDataSourceTypeEnum.values()) {
			if (ddlExecutor.supportedDataSourceType(type)) {
				ddlByDatasourceType.put(type, ddlExecutor);
			}
		}
	}

	public boolean isRegistered(String type) {
		return ddlExecutorSet.containsKey(type);
	}

	public Ddl getDdlExecutorByDbConfig(DbConfigBO dbConfig) {
		BizDataSourceTypeEnum type = BizDataSourceTypeEnum.fromTypeName(dbConfig.getDialectType());
		if (type == null) {
			throw new RuntimeException("unknown db type");
		}
		return getDdlExecutorByDbType(type);
	}

	public Ddl getDdlExecutorByDbType(BizDataSourceTypeEnum type) {
		Ddl ddl = ddlByDatasourceType.get(type);
		if (ddl == null) {
			throw new IllegalStateException("no ddl executor found for " + type);
		}
		return ddl;
	}

	public Ddl getDdlExecutorByType(String type) {
		return ddlExecutorSet.get(type);
	}

}
