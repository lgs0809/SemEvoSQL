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

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses generated SQL before execution and enforces SemEvoSQL's system-level safety
 * boundary. Business correctness is handled by the Semantic Blueprint; this guard is
 * intentionally limited to deterministic execution safety and table scope.
 */
@Component
public class SqlExecutionGuard {

	private static final Pattern DANGEROUS_FUNCTION_PATTERN = Pattern
		.compile("(?i)\\b(sleep|benchmark|load_file|pg_sleep|dblink|xp_cmdshell|sys_eval|sys_exec|pg_terminate_backend|pg_cancel_backend|pg_[a-z_]*advisory[a-z_]*|get_lock|release_lock|release_all_locks|set_config)\\s*\\(");

	private static final Pattern WRITE_SELECT_PATTERN = Pattern.compile(
			"(?i)\\b(into\\s+(outfile|dumpfile)|for\\s+update|lock\\s+in\\s+share\\s+mode|copy\\b.+\\bto\\s+program)\\b",
			Pattern.DOTALL);

	private static final Pattern GENERIC_SELECT_INTO_PATTERN = Pattern.compile("(?i)\\bselect\\b.+\\binto\\b",
			Pattern.DOTALL);

	private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "mysql", "performance_schema", "sys",
			"pg_catalog", "pg_toast");

	public GuardResult validate(String sql, String dialect, Collection<String> allowedPhysicalTables,
			String expectedSchema) {
		if (sql == null || sql.isBlank()) {
			throw new SqlGuardViolationException("SQL cannot be blank");
		}
		String dbType = resolveDbType(dialect);
		String normalizedSql = sql.trim();
		assertNoDangerousSelectFeatures(normalizedSql);

		List<SQLStatement> statements;
		try {
			statements = SQLUtils.parseStatements(normalizedSql, dbType);
		}
		catch (RuntimeException ex) {
			throw new SqlGuardViolationException("SQL cannot be parsed for dialect " + dialect + ": " + ex.getMessage(),
					ex);
		}
		if (statements.size() != 1) {
			throw new SqlGuardViolationException("Exactly one SQL statement is required");
		}
		SQLStatement statement = statements.get(0);

		if (!(statement instanceof SQLSelectStatement)) {
			throw new SqlGuardViolationException("Only SELECT statements are allowed");
		}

		Set<String> allowedTables = normalizeAllowedTables(allowedPhysicalTables, dbType);
		if (allowedTables.isEmpty()) {
			throw new SqlGuardViolationException("The published semantic plan does not expose any executable table");
		}
		Set<String> referencedTables = extractReferencedTables(statement, dbType);
		List<String> violations = new ArrayList<>();
		for (String referencedTable : referencedTables) {
			validateReferencedTable(referencedTable, allowedTables, expectedSchema, dbType, violations);
		}
		if (!violations.isEmpty()) {
			throw new SqlGuardViolationException(String.join("; ", violations));
		}
		return new GuardResult(dbType, Set.copyOf(referencedTables));
	}

    private Set<String> extractReferencedTables(SQLStatement statement, String dialectType) {
        Set<String> tables = new LinkedHashSet<>();
        statement.accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter() {
            @Override public void preVisit(com.alibaba.druid.sql.ast.SQLObject node) {
                if (node instanceof SQLStatement && !(node instanceof SQLSelectStatement)) {
                    throw new SqlGuardViolationException("Nested data modification statements are forbidden");
                }
                if (node instanceof com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock block
                        && (block.getInto() != null || block.isForUpdate() || block.isForShare())) {
                    throw new SqlGuardViolationException("SELECT writing or row locking is forbidden");
                }
                if (node instanceof com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock block
                        && block.getForClause() != null) {
                    throw new SqlGuardViolationException("PostgreSQL row locking is forbidden");
                }
                if (node instanceof com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock block
                        && block.isLockInShareMode()) {
                    throw new SqlGuardViolationException("MySQL shared row locking is forbidden");
                }
            }
            @Override public boolean visit(com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr call) {
                String name = call.getMethodName().replace("\"", "").replace("`", "");
                if (DANGEROUS_FUNCTION_PATTERN.matcher(name + "(").find()) {
                    throw new SqlGuardViolationException("Dangerous database functions are forbidden");
                }
                return true;
            }
            @Override public boolean visit(com.alibaba.druid.sql.ast.statement.SQLExprTableSource source) {
                var expression = source.getExpr();
                if (!(expression instanceof com.alibaba.druid.sql.ast.SQLName)) {
                    throw new SqlGuardViolationException("Table source cannot be bound to a published physical table");
                }
                if (expression instanceof com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr identifier
                        && visibleCte(source, identifier.getName(), dialectType)) {
                    return true;
                }
                // A qualified name is always physical, even if its final component equals a CTE alias.
                tables.add(normalizeQualifiedIdentifier(expression.toString(), dialectType));
                return true;
            }
        });
        return tables;
    }

    private boolean visibleCte(com.alibaba.druid.sql.ast.SQLObject source, String name, String dbType) {
        com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause.Entry enclosingEntry = null;
        for (var node = source.getParent(); node != null; node = node.getParent()) {
            if (node instanceof com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause.Entry entry) {
                enclosingEntry = entry;
            }
            if (!(node instanceof com.alibaba.druid.sql.ast.statement.SQLSelect select)) continue;
            var clause = select.getWithSubQuery();
            if (clause == null) continue;
            var entries = clause.getEntries();
            int visible = entries.size();
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index) != enclosingEntry) continue;
                boolean recursive = Boolean.TRUE.equals(clause.getRecursive());
                // PostgreSQL WITH RECURSIVE permits forward references. MySQL exposes prior entries and self only.
                visible = recursive && "postgresql".equals(dbType) ? entries.size() : index + (recursive ? 1 : 0);
                break;
            }
            for (int index = 0; index < visible; index++) {
                if (normalizeIdentifier(entries.get(index).getAlias(), dbType).equals(normalizeIdentifier(name, dbType))) return true;
            }
            enclosingEntry = null;
        }
        return false;
    }

	private void validateReferencedTable(String referencedTable, Set<String> allowedTables,
			String expectedSchema, String dbType, List<String> violations) {
		String unqualified = unqualifiedName(referencedTable);
		String qualifier = qualifier(referencedTable);
		if (SYSTEM_SCHEMAS.contains(qualifier.toLowerCase(Locale.ROOT))) {
			violations.add("System schema access is forbidden: " + referencedTable);
			return;
		}

		String normalizedExpectedSchema = normalizeIdentifier(expectedSchema, dbType, true);
		boolean explicitlyAllowed = allowedTables.contains(referencedTable)
                || (qualifier.isBlank() && !normalizedExpectedSchema.isBlank()
                    && allowedTables.contains(normalizedExpectedSchema + "." + referencedTable));
		boolean unqualifiedAllowed = allowedTables.contains(unqualified);
		if (!qualifier.isBlank() && !explicitlyAllowed && (!unqualifiedAllowed || normalizedExpectedSchema.isBlank()
				|| !qualifier.equals(normalizedExpectedSchema))) {
			violations.add("Cross-schema table is outside the published semantic project: " + referencedTable);
			return;
		}
		if (!explicitlyAllowed && !unqualifiedAllowed) {
			violations.add("Table is outside the published semantic project: " + referencedTable);
		}
	}

	private void assertNoDangerousSelectFeatures(String sql) {
        if(Pattern.compile("(?is)/\\*\\+.*?\\b(MAX_EXECUTION_TIME|SET_VAR)\\b.*?\\*/").matcher(sql).find())
            throw new SqlGuardViolationException("Generated SQL cannot override execution limits through optimizer hints");
		if (DANGEROUS_FUNCTION_PATTERN.matcher(sql).find()) {
			throw new SqlGuardViolationException("Dangerous database function is not allowed");
		}
		if (WRITE_SELECT_PATTERN.matcher(sql).find() || GENERIC_SELECT_INTO_PATTERN.matcher(sql).find()) {
			throw new SqlGuardViolationException("SELECT variants that write, lock or invoke programs are not allowed");
		}
	}

	private Set<String> normalizeAllowedTables(Collection<String> allowedPhysicalTables, String dbType) {
		Set<String> result = new LinkedHashSet<>();
		if (allowedPhysicalTables == null) {
			return result;
		}
		for (String table : allowedPhysicalTables) {
			String normalized = normalizeQualifiedIdentifier(table, dbType, true);
			if (!normalized.isBlank()) {
				result.add(normalized);
			}
		}
		return result;
	}


	private String resolveDbType(String dialect) {
		String normalized = dialect == null ? "" : dialect.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mysql" -> "mysql";
			case "postgresql", "postgres", "hologress" -> "postgresql";
			case "sqlserver", "sql_server", "mssql" -> "sqlserver";
			case "oracle", "dameng" -> "oracle";
			case "hive" -> "hive";
			case "sqlite" -> "sqlite";
			case "h2" -> "h2";
			default -> throw new SqlGuardViolationException("Unsupported SQL dialect for AST guard: " + dialect);
		};
	}

    private String normalizeQualifiedIdentifier(String value, String dbType) {
        return normalizeQualifiedIdentifier(value, dbType, false);
    }

	private String normalizeQualifiedIdentifier(String value, String dbType, boolean physicalMetadata) {
		if (value == null) {
			return "";
		}
		String[] parts = value.trim().split("\\.");
		List<String> normalized = new ArrayList<>();
		for (String part : parts) {
			String identifier = normalizeIdentifier(part, dbType, physicalMetadata);
			if (!identifier.isBlank()) {
				normalized.add(identifier);
			}
		}
		return String.join(".", normalized);
	}

    private String normalizeIdentifier(String value, String dbType) {
        return normalizeIdentifier(value, dbType, false);
    }

    private String normalizeIdentifier(String value, String dbType, boolean physicalMetadata) {
        if (value == null) return "";
        String normalized = value.trim();
        boolean doubleQuoted = normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"");
        boolean backtickQuoted = normalized.length() >= 2 && normalized.startsWith("`") && normalized.endsWith("`");
        boolean bracketQuoted = normalized.length() >= 2 && normalized.startsWith("[") && normalized.endsWith("]");
        if (doubleQuoted || backtickQuoted || bracketQuoted) {
            normalized = normalized.substring(1, normalized.length() - 1);
            if (doubleQuoted) normalized = normalized.replace("\"\"", "\"");
            if (backtickQuoted) normalized = normalized.replace("``", "`");
            if (bracketQuoted) normalized = normalized.replace("]]", "]");
        }
        if ("sqlserver".equals(dbType)) return normalized.toLowerCase(Locale.ROOT);
        // The catalog/schema configuration contains actual physical names, not unquoted SQL tokens.
        if (physicalMetadata || "mysql".equals(dbType) || doubleQuoted) return normalized;
        return "oracle".equals(dbType) ? normalized.toUpperCase(Locale.ROOT) : normalized.toLowerCase(Locale.ROOT);
    }


	private String unqualifiedName(String tableName) {
		int index = tableName.lastIndexOf('.');
		return index < 0 ? tableName : tableName.substring(index + 1);
	}

	private String qualifier(String tableName) {
		int index = tableName.lastIndexOf('.');
		return index < 0 ? "" : tableName.substring(0, index);
	}

	public record GuardResult(String dbType, Set<String> referencedTables) {
	}

}
