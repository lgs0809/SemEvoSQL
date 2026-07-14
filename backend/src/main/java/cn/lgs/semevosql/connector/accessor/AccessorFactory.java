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

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.service.datasource.SemanticQueryDatasourceCapabilities;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 2025/9/27
 */
@Component
public class AccessorFactory {

	public AccessorFactory(List<Accessor> accessors) {
		accessors.forEach(this::register);
	}

	private final Map<String, Accessor> accessorMap = new ConcurrentHashMap<>();

	private final Map<BizDataSourceTypeEnum, Accessor> accessorByDatasourceType = new ConcurrentHashMap<>();

	public void register(Accessor accessor) {
		boolean supported = false;
		for (BizDataSourceTypeEnum type : BizDataSourceTypeEnum.values()) {
			if (SemanticQueryDatasourceCapabilities.supportedTypes().contains(type)
					&& accessor.supportedDataSourceType(type.getTypeName())) {
				accessorByDatasourceType.put(type, accessor);
				supported = true;
			}
		}
		if (supported) {
			accessorMap.put(accessor.getAccessorType(), accessor);
		}
	}

	public boolean isRegistered(String type) {
		return accessorMap.containsKey(type);
	}

	public Accessor getAccessorByDbConfig(DbConfigBO dbConfig) {
		if (dbConfig == null) {
			throw new IllegalArgumentException("dbConfig cannot be null");
		}
		BizDataSourceTypeEnum typeEnum = Arrays.stream(BizDataSourceTypeEnum.values())
			.filter(e -> e.getDialect().equalsIgnoreCase(dbConfig.getDialectType()))
			.filter(e -> e.getProtocol().equalsIgnoreCase(dbConfig.getConnectionType()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
					"no accessor registered for dialect: " + dbConfig.getDialectType()));
		return getAccessorByDbTypeEnum(typeEnum);
	}

	public Accessor getAccessorByDbTypeEnum(BizDataSourceTypeEnum typeEnum) {
		Accessor accessor = accessorByDatasourceType.get(typeEnum);
		if (accessor == null) {
			throw new IllegalStateException("no accessor registered for dialect: " + typeEnum);
		}
		return accessor;
	}

	public Accessor getAccessorByType(String type) {
		return accessorMap.get(type);
	}

}
