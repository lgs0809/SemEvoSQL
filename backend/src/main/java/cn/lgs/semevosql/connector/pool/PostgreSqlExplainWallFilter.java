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
package cn.lgs.semevosql.connector.pool;

import com.alibaba.druid.proxy.jdbc.DataSourceProxy;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.wall.*;
import com.alibaba.druid.wall.spi.PGWallProvider;

/** Druid 1.2.22 cannot parse PG's JSON EXPLAIN options. Keep its full statement checks,
 * normalizing only the exact system-owned, non-executing option prefix for the parser.
 * The original SQL and parameters still go to PostgreSQL, and the query guard remains in force. */
public final class PostgreSqlExplainWallFilter extends WallFilter {
    private static final String PREFIX="EXPLAIN (FORMAT JSON, COSTS TRUE) ";
    public PostgreSqlExplainWallFilter() {setConfig(new WallConfig(PGWallProvider.DEFAULT_CONFIG_DIR));}
    @Override protected WallProvider initWallProvider(DataSourceProxy source,String dbType,WallConfig config) {
        return new PGWallProvider(config) {
            @Override public SQLStatementParser createParser(String sql) {
                return super.createParser(sql.startsWith(PREFIX)?"EXPLAIN "+sql.substring(PREFIX.length()):sql);
            }
        };
    }
}
