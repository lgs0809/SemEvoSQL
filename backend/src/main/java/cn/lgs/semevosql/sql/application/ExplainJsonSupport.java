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
package cn.lgs.semevosql.sql.application;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

final class ExplainJsonSupport {

	private ExplainJsonSupport() {
	}

	static JsonNode firstJsonCell(ResultSetBO result) {
		if (result == null || result.getData() == null) {
			return null;
		}
		for (Map<String, String> row : result.getData()) {
			for (String value : row.values()) {
				if (value == null) {
					continue;
				}
				String trimmed = value.trim();
				if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
					continue;
				}
				try {
					return JsonUtil.getObjectMapper().readTree(trimmed);
				}
				catch (Exception ignored) {
					// Continue to another cell; some EXPLAIN dialects return auxiliary text columns.
				}
			}
		}
		return null;
	}

	static long longValue(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull()) {
			return 0;
		}
		if (value.isNumber()) {
			return Math.max(0, value.asLong());
		}
		try {
			return Math.max(0, Long.parseLong(value.asText().replace(",", "").trim()));
		}
		catch (RuntimeException ignored) {
			return 0;
		}
	}

	static double doubleValue(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull()) {
			return 0;
		}
		if (value.isNumber()) {
			return Math.max(0, value.asDouble());
		}
		try {
			return Math.max(0, Double.parseDouble(value.asText().replace(",", "").trim()));
		}
		catch (RuntimeException ignored) {
			return 0;
		}
	}

	static long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

}
