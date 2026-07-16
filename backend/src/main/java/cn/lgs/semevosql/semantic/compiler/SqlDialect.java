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
package cn.lgs.semevosql.semantic.compiler;

import java.util.Locale;

public enum SqlDialect {

	MYSQL("`", "`"), POSTGRESQL("\"", "\""), CLICKHOUSE("`", "`"), H2("\"", "\"");

	private final String openQuote;

	private final String closeQuote;

	SqlDialect(String openQuote, String closeQuote) {
		this.openQuote = openQuote;
		this.closeQuote = closeQuote;
	}

	public String quote(String identifier) {
		if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
			throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
		}
		return openQuote + identifier + closeQuote;
	}

	public String timeBucket(String renderedExpression, String granularity) {
		String normalizedGranularity = granularity == null ? "" : granularity.trim().toUpperCase(Locale.ROOT);
		if (renderedExpression == null || renderedExpression.isBlank()) {
			throw new IllegalArgumentException("Time bucket expression cannot be blank");
		}
		return switch (this) {
			case MYSQL -> switch (normalizedGranularity) {
				case "DAY" -> "DATE(" + renderedExpression + ")";
				case "MONTH" -> "DATE_FORMAT(" + renderedExpression + ", '%Y-%m-01')";
				case "YEAR" -> "DATE_FORMAT(" + renderedExpression + ", '%Y-01-01')";
				default -> throw unsupportedTimeGranularity(granularity);
			};
			case POSTGRESQL -> switch (normalizedGranularity) {
				case "DAY" -> "DATE_TRUNC('day', " + renderedExpression + ")";
				case "MONTH" -> "DATE_TRUNC('month', " + renderedExpression + ")";
				case "YEAR" -> "DATE_TRUNC('year', " + renderedExpression + ")";
				default -> throw unsupportedTimeGranularity(granularity);
			};
			case CLICKHOUSE -> switch (normalizedGranularity) {
				case "DAY" -> "toStartOfDay(" + renderedExpression + ")";
				case "MONTH" -> "toStartOfMonth(" + renderedExpression + ")";
				case "YEAR" -> "toStartOfYear(" + renderedExpression + ")";
				default -> throw unsupportedTimeGranularity(granularity);
			};
			case H2 -> switch (normalizedGranularity) {
				case "DAY" -> "DATE_TRUNC(DAY, " + renderedExpression + ")";
				case "MONTH" -> "DATE_TRUNC(MONTH, " + renderedExpression + ")";
				case "YEAR" -> "DATE_TRUNC(YEAR, " + renderedExpression + ")";
				default -> throw unsupportedTimeGranularity(granularity);
			};
		};
	}

	private IllegalArgumentException unsupportedTimeGranularity(String granularity) {
		return new IllegalArgumentException("Unsupported time bucket granularity: " + granularity);
	}

	public static SqlDialect from(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mysql", "mariadb" -> MYSQL;
			case "postgres", "postgresql" -> POSTGRESQL;
			case "clickhouse" -> CLICKHOUSE;
			case "h2" -> H2;
			default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + value);
		};
	}

}
