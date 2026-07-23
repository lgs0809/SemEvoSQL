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

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Creates system-owned EXPLAIN statements for dialects that return a result set safely.
 */
@Component
public class SqlPreflightPlanner {

	public Optional<String> explainSql(String sql, String dialect) {
		if (sql == null || sql.isBlank()) {
			return Optional.empty();
		}
		String normalizedDialect = dialect == null ? "" : dialect.trim().toLowerCase(Locale.ROOT);
		String statement = stripTrailingSemicolon(sql);
		return switch (normalizedDialect) {
			case "mysql" -> Optional.of("EXPLAIN FORMAT=JSON " + statement);
			case "postgresql", "postgres", "hologress" -> Optional.of("EXPLAIN (FORMAT JSON, COSTS TRUE) " + statement);
			case "h2", "hive" -> Optional.of("EXPLAIN " + statement);
			case "sqlite" -> Optional.of("EXPLAIN QUERY PLAN " + statement);
			default -> Optional.empty();
		};
	}

	private String stripTrailingSemicolon(String sql) {
		String result = sql.trim();
		while (result.endsWith(";")) {
			result = result.substring(0, result.length() - 1).trim();
		}
		return result;
	}

}
