/*
 * Copyright 2026 the original author or authors.
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

import cn.lgs.semevosql.util.JsonUtil;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC result semantics, including an aggregate over an empty match set. */
@Testcontainers
class ResultSetBuilderPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test void nullEmptyAndZeroRemainDistinctAcrossJdbcAndJson() throws Exception {
        try (var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
            var result=SqlExecutor.executeSqlAndReturnObject(connection,"public",
                    "SELECT NULL::numeric AS missing_amount, ''::text AS empty_label, 0::numeric AS zero_amount",10,30);
            var row=result.getData().get(0);
            assertTrue(row.containsKey("missing_amount")); assertNull(row.get("missing_amount"));
            assertEquals("",row.get("empty_label")); assertEquals("0",row.get("zero_amount"));
            var wire=JsonUtil.getObjectMapper().readTree(JsonUtil.getObjectMapper().writeValueAsString(result));
            assertTrue(wire.path("data").get(0).has("missing_amount"));
            assertTrue(wire.path("data").get(0).get("missing_amount").isNull());
            assertNull(result.clone().getData().get(0).get("missing_amount"));
        }
    }

    @Test void aggregateWithNoMatchingRowsPreservesItsNullValueAndRowCount() throws Exception {
        try (var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
            var result=SqlExecutor.executeSqlAndReturnObject(connection,"public",
                    "SELECT SUM(amount) AS total FROM (VALUES (12::numeric)) t(amount) WHERE amount > ?",List.of(100),10,30);
            assertEquals(List.of("total"),result.getColumn());
            assertEquals(1,result.getData().size()); assertNull(result.getData().get(0).get("total"));
        }
    }
}
