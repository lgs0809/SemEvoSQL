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
package cn.lgs.semevosql.service.graph.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedList;
import javax.sql.DataSource;

/** Official 1.1.0.0 saver, using the application pool and fresh database reads.
 * Framework DDL is owned by Flyway. There is no memory fallback when the database fails.
 */
public final class DatabasePostgresSaver extends PostgresSaver {
    private final DataSource applicationDataSource;

    public DatabasePostgresSaver(DataSource datasource, StateSerializer serializer) throws SQLException {
        super(PostgresSaver.builder().stateSerializer(serializer).createTables(false).dropTablesFirst(false));
        this.applicationDataSource = java.util.Objects.requireNonNull(datasource);
    }

    @Override protected void initTable(boolean dropTablesFirst, boolean createTables) throws SQLException {
        // PostgresSaver calls this overridable method from its constructor, before our datasource is assigned.
        if (dropTablesFirst || createTables) throw new SQLException("Native Graph DDL must be managed by Flyway");
    }

    @Override protected Connection getConnection() throws SQLException {
        return applicationDataSource.getConnection();
    }

    @Override protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config,
            LinkedList<Checkpoint> ignoredMemoryCopy) throws Exception {
        // The stock saver trusts a nonempty process-local list forever. Re-read to observe other workers and outages.
        return super.loadedCheckpoints(config, new LinkedList<>());
    }
}
