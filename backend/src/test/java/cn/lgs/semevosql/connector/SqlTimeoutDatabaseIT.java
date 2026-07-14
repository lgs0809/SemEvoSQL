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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.connector.impls.postgre.PostgreSqlJdbcConnectionPool;
import cn.lgs.semevosql.sql.application.SqlValidationClassifier;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SqlTimeoutDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withCommand("--performance-schema=OFF");
    Connection pg() throws SQLException { return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); }
    Connection mysql() throws SQLException { return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()); }
    String scalar(Connection c, String sql) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}
    }
    @Test void pgLimitsArePerTransactionAndRequestedLargerValuesCannotWidenThirtySeconds() throws Exception {
        try(var c=pg()) {
            var r=SqlExecutor.executeSqlAndReturnObject(c,"public","SELECT current_setting('statement_timeout') AS timeout",20,999);
            assertEquals("30s",r.getData().get(0).get("timeout"));
            assertEquals("0",scalar(c,"SHOW statement_timeout"));assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());
        }
    }
    @Test void pgServerCancelsSlowQueryWithoutRelyingOnJdbcTimerAndCanBeReused() throws Exception {
        try(var c=pg()) {
            int pid=Integer.parseInt(scalar(c,"SELECT pg_backend_pid()"));long started=System.nanoTime();
            SQLException failure=assertThrows(SQLException.class,()->{
                try(var scope=JdbcQueryScope.open(c,1);var s=c.createStatement()) {s.executeQuery("SELECT pg_sleep(5)");}
            });
            assertEquals("57014",failure.getSQLState());assertTrue(failure.getMessage().contains("statement timeout"));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<3500);
            assertEquals("42",scalar(c,"SELECT 42"));
            try(var observer=pg()) { assertEquals("0",scalar(observer,"SELECT count(*) FROM pg_stat_activity WHERE pid="+pid+" AND state='active'")); }
        }
    }
    @Test void actualDefaultThirtySecondSlowQueryIsCancelledAndRegistryDrains() throws Exception {
        String key="timeout-"+UUID.randomUUID();
        try(var c=pg()) {
            long started=System.nanoTime();
            SQLException failure=assertThrows(SQLException.class,()->SqlExecutor.executeSqlAndReturnObject(c,"public",
                "SELECT pg_sleep(40)",List.of(),1,null,key));
            long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();
            assertEquals("57014",failure.getSQLState());assertTrue(elapsed>=29000 && elapsed<37000,"elapsed="+elapsed);
            assertEquals(0,JdbcStatementCancellationRegistry.activeCount(key));assertEquals("42",scalar(c,"SELECT 42"));
            var facts=cn.lgs.semevosql.sql.application.SqlFailureEvidence.capture(failure);
            assertEquals("57014",facts.get("sqlState"));assertEquals("ROLLBACK_CONFIRMED",facts.get("cleanupStatus"));
            System.out.println("REAL_SQL_30S_CANCEL elapsedMs="+elapsed+" sqlState="+failure.getSQLState()+" reusedResult=42 activeStatements=0");
        }
    }
    @Test void pgCallerTransactionIsNeitherCommittedNorDiscardedOnInnerFailure() throws Exception {
        String table="outer_"+UUID.randomUUID().toString().replace("-","");
        try(var c=pg();var s=c.createStatement()) {
            s.execute("CREATE TABLE "+table+"(id int)");c.setAutoCommit(false);s.execute("INSERT INTO "+table+" VALUES (1)");
            assertThrows(SQLException.class,()->SqlExecutor.executeSqlAndReturnObject(c,"public","SELECT unknown_col FROM "+table,20,1));
            assertFalse(c.getAutoCommit());assertEquals("1",scalar(c,"SELECT count(*) FROM "+table));
            try(var observer=pg()){assertEquals("0",scalar(observer,"SELECT count(*) FROM "+table));}
            c.rollback();c.setAutoCommit(true);assertEquals("0",scalar(c,"SHOW statement_timeout"));
        }
    }
    @Test void mysqlSessionLimitIsRestoredAndReadOnlyTransactionDoesNotLeak() throws Exception {
        try(var c=mysql();var s=c.createStatement()) {
            s.execute("SET SESSION max_execution_time=12345");
            var r=SqlExecutor.executeSqlAndReturnObject(c,null,"SELECT @@SESSION.max_execution_time AS timeout",20,999);
            assertEquals("30000",r.getData().get(0).get("timeout"));
            assertEquals("12345",scalar(c,"SELECT @@SESSION.max_execution_time"));assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());
        }
    }
    @Test void mysqlServerActuallyStopsLargeSelectWithoutJdbcTimer() throws Exception {
        try(var c=mysql();var s=c.createStatement()) {
            s.execute("CREATE TABLE slow_fixture(n int)");
            try(var insert=c.prepareStatement("INSERT INTO slow_fixture VALUES (?)")) {
                for(int i=0;i<1000;i++){insert.setInt(1,i);insert.addBatch();}insert.executeBatch();
            }
            long start=System.nanoTime();
            SQLException failure=assertThrows(SQLException.class,()-> {
                try(var scope=JdbcQueryScope.open(c,1);var query=c.createStatement()) {
                    query.executeQuery("SELECT SUM(a.n*b.n*c.n) FROM slow_fixture a CROSS JOIN slow_fixture b CROSS JOIN slow_fixture c");
                }
            });
            long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertEquals(3024,failure.getErrorCode());assertTrue(elapsed<5000,"elapsed="+elapsed);
            assertEquals("0",scalar(c,"SELECT @@SESSION.max_execution_time"));assertEquals("42",scalar(c,"SELECT 42"));
            System.out.println("REAL_MYSQL_SERVER_CANCEL elapsedMs="+elapsed+" vendorCode="+failure.getErrorCode()+" reusedResult=42");
        }
    }
    @Test void mysqlDefaultThirtySecondExecutionActuallyStopsAndReturnsCleanConnection() throws Exception {
        String key="mysql-30s-"+UUID.randomUUID();
        try(var c=mysql();var s=c.createStatement()) {
            s.execute("CREATE TABLE slow_30s_fixture(n int)");
            try(var insert=c.prepareStatement("INSERT INTO slow_30s_fixture VALUES (?)")) {
                for(int i=0;i<1000;i++){insert.setInt(1,i);insert.addBatch();}insert.executeBatch();
            }
            long connectionId=Long.parseLong(scalar(c,"SELECT CONNECTION_ID()"));
            long start=System.nanoTime();
            SQLException failure=assertThrows(SQLException.class,()->SqlExecutor.executeSqlAndReturnObject(c,null,
                "SELECT SUM(a.n*b.n*c.n*d.n) FROM slow_30s_fixture a CROSS JOIN slow_30s_fixture b CROSS JOIN slow_30s_fixture c CROSS JOIN slow_30s_fixture d",
                List.of(),1,null,key));
            long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertTrue(elapsed>=29000 && elapsed<37000,"elapsed="+elapsed);
            assertEquals("SQL_TIMEOUT",new SqlValidationClassifier().classify(failure,0).errorType());
            assertEquals("ROLLBACK_CONFIRMED",cn.lgs.semevosql.sql.application.SqlFailureEvidence.capture(failure).get("cleanupStatus"));
            assertEquals("0",scalar(c,"SELECT @@SESSION.max_execution_time"));assertEquals("42",scalar(c,"SELECT 42"));
            assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());assertEquals(0,JdbcStatementCancellationRegistry.activeCount(key));
            try(var observer=mysql()) {assertEquals("0",scalar(observer,"SELECT count(*) FROM information_schema.processlist WHERE ID="+connectionId+" AND COMMAND='Query'"));}
            System.out.println("REAL_MYSQL_30S_CANCEL elapsedMs="+elapsed+" sqlState="+failure.getSQLState()+" vendorCode="+failure.getErrorCode()+" reusedResult=42 activeStatements=0");
        }finally {JdbcStatementCancellationRegistry.clearPrefix(key);}
    }
    @Test void mysqlActualDruidPoolAlsoAppliesAndRestoresNativeLimit() throws Exception {
        var pool=new cn.lgs.semevosql.connector.impls.mysql.MysqlJdbcConnectionPool();
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)pool.createdDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var c=ds.getConnection()) {
            var result=SqlExecutor.executeSqlAndReturnObject(c,null,"SELECT @@SESSION.max_execution_time AS timeout",1,30);
            assertEquals("30000",result.getData().get(0).get("timeout"));
            assertEquals("0",scalar(c,"SELECT @@SESSION.max_execution_time"));assertFalse(c.isReadOnly());
        }
    }
    @Test void cancellationBeforeRegistrationDoesNotExecuteTheQuery() throws Exception {
        String key="pre-cancel-"+UUID.randomUUID();JdbcStatementCancellationRegistry.cancelPrefix(key);
        try(var c=pg()) {
            long start=System.nanoTime();
            assertThrows(SqlQueryCancelledException.class,()->SqlExecutor.executeSqlAndReturnObject(c,"public","SELECT pg_sleep(3)",List.of(),1,30,key));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1000);
            assertEquals(0,JdbcStatementCancellationRegistry.activeCount(key));
            var statement=mock(Statement.class);
            assertThrows(SqlQueryCancelledException.class,()->JdbcStatementCancellationRegistry.register(key,statement));
            verifyNoInteractions(statement);
        } finally {JdbcStatementCancellationRegistry.clearPrefix(key);}
    }
    @Test void activeCancellationReachesTheDatabaseAndCannotBecomeSqlRepair() throws Exception {
        String key="active-cancel-"+UUID.randomUUID();var executor=Executors.newSingleThreadExecutor();
        try(var c=pg();var observer=pg()) {
            int pid=Integer.parseInt(scalar(c,"SELECT pg_backend_pid()"));
            Future<Throwable> future=executor.submit(()-> {
                try {SqlExecutor.executeSqlAndReturnObject(c,"public","SELECT pg_sleep(10)",List.of(),1,30,key);return null;}
                catch(Throwable failure){return failure;}
            });
            long end=System.nanoTime()+Duration.ofSeconds(4).toNanos();
            while(!"1".equals(scalar(observer,"SELECT count(*) FROM pg_stat_activity WHERE pid="+pid+" AND state='active' AND query LIKE '%pg_sleep%'"))) {
                if(System.nanoTime()>end) fail("slow query did not start");Thread.sleep(20);
            }
            var cancel=JdbcStatementCancellationRegistry.cancelPrefix(key);assertEquals(1,cancel.matchedStatements());assertTrue(cancel.errors().isEmpty());
            Throwable failure=future.get(3,TimeUnit.SECONDS);assertInstanceOf(SqlQueryCancelledException.class,failure);
            assertFalse(new SqlValidationClassifier().classify(failure,0).retryAllowed());
            assertEquals("42",scalar(c,"SELECT 42"));assertEquals(0,JdbcStatementCancellationRegistry.activeCount(key));
        } finally {executor.shutdownNow();JdbcStatementCancellationRegistry.clearPrefix(key);}
    }
    @Test void failedRollbackDiscardsActualPoolConnectionAndForbidsAutomaticRepair() throws Exception {
        var pool=new PostgreSqlJdbcConnectionPool();
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)pool.createdDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
            ds.setInitialSize(0);ds.setMinIdle(0);ds.setMaxActive(1);
            var original=ds.getConnection();int originalPid=Integer.parseInt(scalar(original,"SELECT pg_backend_pid()"));
            var broken=spy(original);doThrow(new SQLException("simulated rollback transport failure","08006")).when(broken).rollback();
            var failure=assertThrows(JdbcQueryCleanupException.class,()->SqlExecutor.executeSqlAndReturnObject(broken,"public","SELECT 42",1,1));
            assertFalse(new SqlValidationClassifier().classify(failure,0).retryAllowed());
            try(var replacement=ds.getConnection()) {
                assertNotEquals(originalPid,Integer.parseInt(scalar(replacement,"SELECT pg_backend_pid()")));
                assertEquals("42",scalar(replacement,"SELECT 42"));
            }
        }
    }
}
