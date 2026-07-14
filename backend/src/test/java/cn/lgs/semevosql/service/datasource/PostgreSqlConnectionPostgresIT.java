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

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.connector.impls.postgre.PostgreSqlJdbcConnectionPool;
import cn.lgs.semevosql.entity.Datasource;
import cn.lgs.semevosql.enums.ErrorCodeEnum;
import cn.lgs.semevosql.service.datasource.handler.impl.PostgreSqlDatasourceTypeHandler;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;

@Testcontainers
class PostgreSqlConnectionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    final PostgreSqlJdbcConnectionPool pool=new PostgreSqlJdbcConnectionPool();
    final PostgreSqlDatasourceTypeHandler handler=new PostgreSqlDatasourceTypeHandler();
    Datasource source(String database){return Datasource.builder().type("postgresql").host(PG.getHost())
        .port(PG.getFirstMappedPort()).databaseName(database).username(PG.getUsername()).password(PG.getPassword()).build();}
    @Test void databaseOnlyUsesPublicAndActuallyConnects(){
        var config=handler.toDbConfig(source(PG.getDatabaseName()));
        assertEquals("public",config.getSchema());assertEquals(ErrorCodeEnum.SUCCESS,pool.ping(config));
        config.setSchema(null);assertEquals(ErrorCodeEnum.SUCCESS,pool.ping(config));
    }
    @Test void explicitSchemaIsHonoredAndLiteralCannotInjectPredicate() throws Exception {
        try(var c=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var s=c.createStatement()){
            s.execute("CREATE SCHEMA acceptance_custom");
        }
        assertEquals(ErrorCodeEnum.SUCCESS,pool.ping(handler.toDbConfig(source(PG.getDatabaseName()+"|acceptance_custom"))));
        assertEquals(ErrorCodeEnum.SCHEMA_NOT_EXIST_3D070,pool.ping(handler.toDbConfig(source(PG.getDatabaseName()+"|missing"))));
        var injection=handler.toDbConfig(source(PG.getDatabaseName()));injection.setSchema("missing' OR 1=1 --");
        assertEquals(ErrorCodeEnum.SCHEMA_NOT_EXIST_3D070,pool.ping(injection));
        assertEquals("public",handler.extractSchemaName(source(PG.getDatabaseName()+"|")));
    }

    @Test void actualPoolPreservesJsonExplainAndRejectsStackedStatementInjection() throws Exception {
        try(var raw=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var s=raw.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS explain_orders(id bigint PRIMARY KEY, amount numeric, status text)");
            s.execute("INSERT INTO explain_orders VALUES(1,42,'PAID') ON CONFLICT DO NOTHING");
        }
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)pool.createdDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var connection=ds.getConnection()) {
            String sql="SELECT SUM(amount) FROM explain_orders WHERE status = ?";
            String explain=new cn.lgs.semevosql.sql.application.SqlPreflightPlanner().explainSql(sql,"postgresql").orElseThrow();
            var result=cn.lgs.semevosql.connector.SqlExecutor.executeSqlAndReturnObject(connection,"public",explain,java.util.List.of("PAID"),20,5);
            assertTrue(result.getData().get(0).get("QUERY PLAN").toString().contains("Plan"));
            var estimate=new cn.lgs.semevosql.sql.application.PostgreSqlQueryCostEstimator().estimate(result);
            assertTrue(estimate.estimatedCost()>0);
            var policy=new cn.lgs.semevosql.properties.SemEvoSQLProperties.SqlExecutionPolicy();
            policy.setRejectFullTableScan(false);policy.setMaxFullScanRows(1000);policy.setMaxEstimatedRows(10000);
            var costGuard=new cn.lgs.semevosql.sql.application.SqlCostGuard(java.util.List.of(new cn.lgs.semevosql.sql.application.PostgreSqlQueryCostEstimator()));
            assertDoesNotThrow(()->costGuard.validateExplain(result,1,policy,"postgresql"));
            assertThrows(java.sql.SQLException.class,()->connection.prepareStatement(explain+"; DROP TABLE explain_orders"));
            try(var s=connection.createStatement();var rs=s.executeQuery("SELECT count(*) FROM explain_orders")) {assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
        }
    }

    @Test void boundedLocalPolicyStillRejectsAnActualLargeSequentialScan() throws Exception {
        try(var raw=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var s=raw.createStatement()) {
            s.execute("CREATE TABLE large_scan_fixture AS SELECT n AS id FROM generate_series(1,2001) n");
            s.execute("ANALYZE large_scan_fixture");
        }
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)pool.createdDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var connection=ds.getConnection()) {
            var result=cn.lgs.semevosql.connector.SqlExecutor.executeSqlAndReturnObject(connection,"public",
                "EXPLAIN (FORMAT JSON, COSTS TRUE) SELECT id FROM large_scan_fixture",java.util.List.of(),20,5);
            var policy=new cn.lgs.semevosql.properties.SemEvoSQLProperties.SqlExecutionPolicy();
            policy.setRejectFullTableScan(false);policy.setMaxFullScanRows(1000);policy.setMaxEstimatedRows(10000);
            var guard=new cn.lgs.semevosql.sql.application.SqlCostGuard(java.util.List.of(new cn.lgs.semevosql.sql.application.PostgreSqlQueryCostEstimator()));
            assertThrows(cn.lgs.semevosql.sql.application.SqlCostGuardViolationException.class,
                ()->guard.validateExplain(result,1,policy,"postgresql"));
        }
    }
}
