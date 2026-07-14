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

import com.alibaba.druid.pool.DruidPooledConnection;
import java.sql.*;

/** Per-query database limits and transaction cleanup. Never leaves session settings in the pool. */
final class JdbcQueryScope implements AutoCloseable {
    private final Connection connection;
    private final boolean ownsTransaction;
    private final boolean originalReadOnly;
    private Savepoint savepoint;
    private Long mysqlTimeout;
    private boolean transactionStarted;
    private boolean readOnlyChanged;

    private JdbcQueryScope(Connection connection) throws SQLException {
        this.connection = connection;
        this.ownsTransaction = connection.getAutoCommit();
        this.originalReadOnly = connection.isReadOnly();
    }

    static JdbcQueryScope open(Connection connection, int timeoutSeconds) throws SQLException {
        return openMillis(connection, timeoutSeconds * 1000L);
    }

    static JdbcQueryScope openMillis(Connection connection,long timeoutMillis) throws SQLException {
        int timeoutSeconds=(int)Math.max(1,(timeoutMillis+999)/1000);
        var scope = new JdbcQueryScope(connection);
        try {
            String dialect = connection.getMetaData().getDatabaseProductName();
            boolean pg = "PostgreSQL".equalsIgnoreCase(dialect);
            boolean mysql = "MySQL".equalsIgnoreCase(dialect);
            if (!pg && !mysql) return scope;
            if (mysql) {
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT @@SESSION.max_execution_time")) {
                    result.next(); scope.mysqlTimeout = result.getLong(1);
                }
            }
            if (scope.ownsTransaction) {
                connection.setReadOnly(true);
                scope.readOnlyChanged = true;
                connection.setAutoCommit(false);
            } else {
                scope.savepoint = connection.setSavepoint();
            }
            scope.transactionStarted = true;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                // Numeric program-owned values only. Never change global defaults.
                statement.execute((pg ? "SET LOCAL statement_timeout = " : "SET SESSION max_execution_time = ")
                    + timeoutMillis);
            }
            return scope;
        } catch (SQLException failure) {
            try { scope.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void close() throws SQLException {
        try {
            if (transactionStarted) {
                if (ownsTransaction) connection.rollback();
                else { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            }
            if (mysqlTimeout != null) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    statement.execute("SET SESSION max_execution_time = " + mysqlTimeout);
                }
            }
            if (transactionStarted && ownsTransaction) {
                connection.setAutoCommit(true);
            }
            if (readOnlyChanged) connection.setReadOnly(originalReadOnly);
        } catch (SQLException cleanup) {
            // An uncertain/polluted connection must not return to the pool or authorize another SQL attempt.
            try {
                if (connection instanceof DruidPooledConnection pooled) {
                    pooled.getConnectionHolder().getDataSource().discardConnection(pooled.getConnectionHolder());
                    pooled.disable(cleanup);
                } else connection.abort(Runnable::run);
            } catch (Exception abort) { cleanup.addSuppressed(abort); }
            throw new JdbcQueryCleanupException(cleanup);
        }
    }
}
