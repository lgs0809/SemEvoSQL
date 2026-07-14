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
package cn.lgs.semevosql.connector;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.enums.DatabaseDialectEnum;
import cn.lgs.semevosql.util.ResultSetConvertUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Responsible for executing SQL and returning structured results.
 */
public class SqlExecutor {

	public static final Integer DEFAULT_RESULT_SET_LIMIT = 1000;

	public static final Integer DEFAULT_STATEMENT_TIMEOUT = 30;

	/**
	 * Execute SQL query and return structured results (with column information)
	 * @param connection database connection
	 * @param sql SQL statement
	 * @return ResultSetBO structured result
	 * @throws SQLException SQL execution exception
	 */
	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql)
			throws SQLException {
		return executeSqlAndReturnObject(connection, schema, sql, DEFAULT_RESULT_SET_LIMIT, DEFAULT_STATEMENT_TIMEOUT);
	}

	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql,
			Integer maxRows, Integer queryTimeoutSeconds) throws SQLException {
		return executeSqlAndReturnObject(connection, schema, sql, List.of(), maxRows, queryTimeoutSeconds);
	}

	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql,
			List<Object> parameters, Integer maxRows, Integer queryTimeoutSeconds) throws SQLException {
		return executeSqlAndReturnObject(connection, schema, sql, parameters, maxRows, queryTimeoutSeconds, null);
	}

	public static ResultSetBO executeSqlAndReturnObject(Connection connection, String schema, String sql,
			List<Object> parameters, Integer maxRows, Integer queryTimeoutSeconds, String cancellationKey)
			throws SQLException {
        return executeSqlAndReturnObject(connection,schema,sql,parameters,maxRows,queryTimeoutSeconds,cancellationKey,null);
    }

    public static ResultSetBO executeSqlAndReturnObject(Connection connection,String schema,String sql,
            List<Object> parameters,Integer maxRows,Integer queryTimeoutSeconds,String cancellationKey,Long deadlineMillis)
            throws SQLException {
		int effectiveMaxRows = maxRows == null || maxRows <= 0 ? DEFAULT_RESULT_SET_LIMIT : maxRows;
		int effectiveTimeout = queryTimeoutSeconds == null || queryTimeoutSeconds <= 0 ? DEFAULT_STATEMENT_TIMEOUT
				: Math.min(DEFAULT_STATEMENT_TIMEOUT, queryTimeoutSeconds);
        JdbcStatementCancellationRegistry.assertNotCancelled(cancellationKey);
        long started = System.nanoTime();
        boolean scopeOpened = false;
        long remainingMillis=deadlineMillis==null?effectiveTimeout*1000L:
            Math.min(effectiveTimeout*1000L,deadlineMillis-System.currentTimeMillis());
        if(remainingMillis<=0)throw new java.sql.SQLTimeoutException("SQL attempt deadline expired before submission","57014");
        effectiveTimeout=(int)Math.max(1,(remainingMillis+999)/1000);
        try (JdbcQueryScope scope = JdbcQueryScope.openMillis(connection, remainingMillis)) {
        scopeOpened = true;
        configureSchema(connection, schema);
		if (parameters != null && !parameters.isEmpty()) {
			try (PreparedStatement statement = connection.prepareStatement(sql);
					JdbcStatementCancellationRegistry.Registration ignored = JdbcStatementCancellationRegistry
						.register(cancellationKey, statement)) {
				statement.setMaxRows(effectiveMaxRows);
				statement.setQueryTimeout(effectiveTimeout);
				for (int index = 0; index < parameters.size(); index++) {
					statement.setObject(index + 1, parameters.get(index));
				}
                JdbcStatementCancellationRegistry.assertNotCancelled(cancellationKey);
                try (ResultSet rs = statement.executeQuery()) {
					return ResultSetBuilder.buildFrom(rs, schema, effectiveMaxRows);
				}
			}
		}
		try (Statement statement = connection.createStatement();
				JdbcStatementCancellationRegistry.Registration ignored = JdbcStatementCancellationRegistry
					.register(cancellationKey, statement)) {
			statement.setMaxRows(effectiveMaxRows);
			statement.setQueryTimeout(effectiveTimeout);
            JdbcStatementCancellationRegistry.assertNotCancelled(cancellationKey);
            try (ResultSet rs = statement.executeQuery(sql)) {
				return ResultSetBuilder.buildFrom(rs, schema, effectiveMaxRows);
			}
		}
        } catch (SQLException failure) {
            if (JdbcStatementCancellationRegistry.isCancellationRequested(cancellationKey)) {
                throw new SqlQueryCancelledException(failure);
            }
            if (failure instanceof JdbcQueryCleanupException) throw failure;
            boolean clean = scopeOpened && java.util.Arrays.stream(failure.getSuppressed())
                .noneMatch(JdbcQueryCleanupException.class::isInstance);
            throw new JdbcQueryExecutionException(failure, (System.nanoTime() - started) / 1_000_000, clean);
        }
	}

	static void configureSchema(Connection connection, String schema) throws SQLException {
		if (StringUtils.isEmpty(schema)) {
			return;
		}
		String dialect = connection.getMetaData().getDatabaseProductName();
		if (!dialect.equals(DatabaseDialectEnum.POSTGRESQL.code) && !dialect.equals(DatabaseDialectEnum.H2.code)
				&& !dialect.equals(DatabaseDialectEnum.ORACLE.code)) {
			return;
		}
		if (!schema.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
			throw new SQLException("Unsafe schema identifier");
		}
		try (Statement statement = connection.createStatement()) {
			if (dialect.equals(DatabaseDialectEnum.POSTGRESQL.code)) {
				statement.execute("set search_path = \"" + schema + "\"");
			}
			else if (dialect.equals(DatabaseDialectEnum.H2.code)) {
				connection.setSchema(schema);
			}
			else if (dialect.equals(DatabaseDialectEnum.ORACLE.code)) {
				statement.execute("ALTER SESSION SET CURRENT_SCHEMA = \"" + schema + "\"");
			}
		}
	}

	/**
	 * Execute SQL query and return string two-dimensional array format result
	 * @param connection database connection
	 * @param sql SQL statement
	 * @return two-dimensional array result
	 * @throws SQLException SQL execution exception
	 */
	public static String[][] executeSqlAndReturnArr(Connection connection, String sql) throws SQLException {
		List<String[]> list = executeQuery(connection, sql);
		return list.toArray(new String[0][]);
	}

	public static String[][] executeSqlAndReturnArr(Connection connection, String databaseOrSchema, String sql)
			throws SQLException {
		List<String[]> list = executeQuery(connection, databaseOrSchema, sql);
		return list.toArray(new String[0][]);
	}

	private static List<String[]> executeQuery(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {

			return ResultSetConvertUtil.convert(rs);
		}
	}

	private static List<String[]> executeQuery(Connection connection, String databaseOrSchema, String sql)
			throws SQLException {
		String originalDb = connection.getCatalog();
		DatabaseMetaData metaData = connection.getMetaData();
		String dialect = metaData.getDatabaseProductName();

		try (Statement statement = connection.createStatement()) {

			if (dialect.equals(DatabaseDialectEnum.MYSQL.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("use `" + databaseOrSchema + "`;");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.POSTGRESQL.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("set search_path = '" + databaseOrSchema + "';");
				}
			}
			else if (dialect.equals(DatabaseDialectEnum.ORACLE.code)) {
				if (StringUtils.isNotEmpty(databaseOrSchema)) {
					statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + databaseOrSchema);
				}
			}

			ResultSet rs = statement.executeQuery(sql);

			List<String[]> result = ResultSetConvertUtil.convert(rs);

			if (StringUtils.isNotEmpty(databaseOrSchema) && dialect.equals(DatabaseDialectEnum.MYSQL.code)) {
				statement.execute("use `" + originalDb + "`;");
			}

			return result;
		}
	}

}
