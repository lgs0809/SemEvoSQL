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
package cn.lgs.semevosql.service.datasource;

import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.exception.InvalidInputException;
import java.util.List;

/** End-to-end datasource capability contract for the governed Semantic SQL path. */
public final class SemanticQueryDatasourceCapabilities {

	private static final List<BizDataSourceTypeEnum> SUPPORTED = List.of(BizDataSourceTypeEnum.MYSQL,
			BizDataSourceTypeEnum.POSTGRESQL);

	private SemanticQueryDatasourceCapabilities() {
	}

	public static List<BizDataSourceTypeEnum> supportedTypes() {
		return SUPPORTED;
	}

	public static boolean supports(String typeName) {
		BizDataSourceTypeEnum type = BizDataSourceTypeEnum.fromTypeName(typeName);
		return type != null && SUPPORTED.contains(type);
	}

	public static void requireSupported(String typeName) {
		if (!supports(typeName)) {
			throw new InvalidInputException("当前版本仅支持 MySQL 和 PostgreSQL 数据源的端到端问数能力");
		}
	}

}
