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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.connector.*;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import java.sql.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SqlAttemptDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withCommand("--performance-schema=OFF");
    static DriverManagerDataSource metadata;
    static JdbcTemplate jdbc;
    String run,attempt;
    @BeforeAll static void migrate(){
        metadata=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(metadata).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(metadata);
    }
    @BeforeEach void setup(){
        run=UUID.randomUUID().toString();attempt=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key,attempt_id) VALUES (?,'INTERACTIVE_QUERY','RUNNING',?,?)",run,run,attempt);
    }
    SqlExecutionAttemptService service(){return service(mock(RunExecutionFenceService.class));}
    SqlExecutionAttemptService service(RunExecutionFenceService fence){return new SqlExecutionAttemptService(jdbc,new DataSourceTransactionManager(metadata),fence);}
    SqlExecutionAttemptService.Context context(String key){return new SqlExecutionAttemptService.Context(run,attempt,key,1);}
    DbQueryParameter parameter(String sql,int seconds){return new DbQueryParameter().setSql(sql).setMaxRows(10).setQueryTimeoutSeconds(seconds).setParameters(List.of()).setCancellationKey(run);}
    Connection pg() throws SQLException{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
    Connection mysql() throws SQLException{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    List<Map<String,Object>> rows(){return jdbc.queryForList("SELECT * FROM qw_sql_execution_attempt WHERE run_id=? ORDER BY create_time",run);}
    String scalar(Connection c,String sql) throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    @Test void replayReturnsOnlySanitizedResultAndNeverChangesDeadline() throws Exception {
        var service=service();var p=parameter("SELECT 'private-value' AS secret, pg_backend_pid() AS pid",30);
        var catalog=cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.builder().columns(List.of(
            cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Column.builder().columnName("secret")
                .status(cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED).maskingPolicy("SHA256").build())).build();
        String expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("private-value".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var calls=new AtomicInteger();
        java.util.function.Consumer<cn.lgs.semevosql.bo.schema.ResultSetBO> sanitize=r->{
            calls.incrementAndGet();new SensitiveResultSanitizer().sanitize(r,catalog);
        };
        var accessor=mock(cn.lgs.semevosql.connector.accessor.Accessor.class);
        var config=cn.lgs.semevosql.bo.DbConfigBO.builder().build();
        when(accessor.executeSqlAndReturnObject(eq(config),any())).thenAnswer(inv->{
            try(var c=pg()){return ((DbQueryParameter)inv.getArgument(1)).getExecutionDelegate().execute(c);}
        });
        var result=service.execute(accessor,config,p,context("first"),"QUERY",sanitize);
        String originalPid=result.getData().get(0).get("pid");
        assertEquals(expected,result.getData().get(0).get("secret"));
        var before=rows().get(0);assertFalse(before.get("result_json").toString().contains("private-value"));
        var replay=service().execute(accessor,config,p,context("first"),"QUERY",sanitize);
        assertEquals(originalPid,replay.getData().get(0).get("pid"));
        assertEquals(expected,replay.getData().get(0).get("secret"));assertEquals(1,calls.get());
        assertNull(p.getExecutionDelegate());
        assertEquals(1,rows().size());assertEquals(before.get("deadline_epoch_ms"),rows().get(0).get("deadline_epoch_ms"));
    }
    @Test void theSameLogicalAttemptCannotUseAnotherSqlPayloadOrStartANewDeadline() throws Exception {
        try(var c=pg()) {
            var service=service();var ctx=context("immutable-input");
            service.executeOnConnection(c,parameter("SELECT 41 AS answer",30),ctx,"QUERY",null);
            var first=rows().get(0);
            var failure=assertThrows(SqlAttemptReplayException.class,()->service.executeOnConnection(c,parameter("SELECT 42 AS answer",30),ctx,"QUERY",null));
            assertEquals("SQL_ATTEMPT_INPUT_CHANGED",failure.category());assertFalse(failure.retryAllowed());
            assertEquals(1,rows().size());assertEquals(first.get("deadline_epoch_ms"),rows().get(0).get("deadline_epoch_ms"));
        }
    }
    @Test void recordedTimeoutIsNotReexecutedAndAnAdjustedAttemptGetsItsOwnLimit() throws Exception {
        var p=parameter("SELECT pg_sleep(5)",1);
        try(var c=pg()){assertThrows(SQLException.class,()->service().executeOnConnection(c,p,context("repair-0"),"QUERY",null));}
        var first=rows().get(0);assertEquals("FAILED",first.get("status"));
        try(var c=pg()){
            long started=System.nanoTime();
            var failure=assertThrows(SqlAttemptReplayException.class,()->service().executeOnConnection(c,p,context("repair-0"),"QUERY",null));
            assertTrue(failure.retryAllowed());assertTrue(failure.cleanupConfirmed());assertEquals("57014",failure.getSQLState());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<800);
            var result=service().executeOnConnection(c,parameter("SELECT 42 AS answer,current_setting('statement_timeout') AS limit_used",30),context("repair-1"),"QUERY",null);
            assertEquals("42",result.getData().get(0).get("answer"));
            assertTrue(result.getData().get(0).get("limit_used").startsWith("29")||result.getData().get(0).get("limit_used").equals("30s"));
        }
        assertEquals(2,rows().size());assertEquals(first.get("deadline_epoch_ms"),rows().get(0).get("deadline_epoch_ms"));
        assertTrue(((Number)rows().get(1).get("deadline_epoch_ms")).longValue()>((Number)first.get("deadline_epoch_ms")).longValue());
    }
    @Test void concurrentDuplicateCannotSubmitAnotherDatabaseStatement() throws Exception {
        var service=service();var p=parameter("SELECT pg_sleep(1),42 AS answer",3);var ctx=context("concurrent");
        var pool=Executors.newSingleThreadExecutor();
        try(var observer=pg()) {
            var first=pool.submit(()->{try(var c=pg()){return service.executeOnConnection(c,p,ctx,"QUERY",null);}});
            waitFor(()->!rows().isEmpty() && "RUNNING".equals(rows().get(0).get("status")),3000);
            try(var c=pg()){
                var duplicate=assertThrows(SqlAttemptReplayException.class,()->service.executeOnConnection(c,p,ctx,"QUERY",null));
                assertFalse(duplicate.retryAllowed());assertEquals("SQL_ATTEMPT_IN_PROGRESS",duplicate.category());
            }
            assertEquals("42",first.get(5,TimeUnit.SECONDS).getData().get(0).get("answer"));
            assertEquals(1,rows().size());
        }finally{pool.shutdownNow();}
    }
    @Test void aPausedOldProcessCannotSubmitAfterItsOwnedSessionIsReconciled() throws Exception {
        pausedRecovery(false,false);
    }
    @Test void mysqlOwnedSessionIsActuallyTerminatedBeforeAnyReplacement() throws Exception {
        pausedRecovery(true,false);
    }
    @Test void failedCancellationKeepsAttemptUncertainAndPreventsAnotherSqlRevision() throws Exception {
        pausedRecovery(false,true);
    }
    @Test void actualPostgresPoolReconcilesOldSession() throws Exception {pausedRecovery(false,false,true);}
    @Test void actualMysqlPoolReconcilesOldSession() throws Exception {pausedRecovery(true,false,true);}
    void pausedRecovery(boolean mysql,boolean denyCancellation) throws Exception {pausedRecovery(mysql,denyCancellation,false);}
    void pausedRecovery(boolean mysql,boolean denyCancellation,boolean pooled) throws Exception {
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var fence=mock(RunExecutionFenceService.class);
        doAnswer(inv->{if(calls.incrementAndGet()==2){ready.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return null;}).when(fence).assertActive(run,attempt);
        var old=service(fence);var p=parameter("SELECT 42 AS answer",30);var ctx=context("crash-before-submit");
        var pool=Executors.newSingleThreadExecutor();
        Connection former=mysql?mysql():pg();
        var future=pool.submit(()->old.executeOnConnection(former,p,ctx,"QUERY",null));
        com.alibaba.druid.pool.DruidDataSource recoveryPool=null;
        if(pooled)recoveryPool=(com.alibaba.druid.pool.DruidDataSource)(mysql?
            new cn.lgs.semevosql.connector.impls.mysql.MysqlJdbcConnectionPool().createdDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()):
            new cn.lgs.semevosql.connector.impls.postgre.PostgreSqlJdbcConnectionPool().createdDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        try(var datasource=recoveryPool;var observer=pooled?datasource.getConnection():(mysql?mysql():pg())) {
            assertTrue(ready.await(8,TimeUnit.SECONDS));var before=rows().get(0);
            Connection recovery=observer;
            if(denyCancellation){
                recovery=spy(observer);
                doThrow(new SQLException("signal denied","42501")).when(recovery).prepareStatement("SELECT pg_terminate_backend(?,3000)");
            }
            var selected=recovery;var fresh=service();
            if(denyCancellation){
                assertThrows(JdbcQueryCleanupException.class,()->fresh.executeOnConnection(selected,p,ctx,"QUERY",null));
                assertEquals("UNCERTAIN",rows().get(0).get("status"));
                assertThrows(JdbcQueryCleanupException.class,()->service().executeOnConnection(selected,parameter("SELECT 43 AS answer",30),context("new-revision"),"QUERY",null));
                assertTrue(rows().stream().noneMatch(r->"SUCCEEDED".equals(r.get("status"))));
            }else{
                var outcome=assertThrows(SqlAttemptReplayException.class,()->fresh.executeOnConnection(selected,p,ctx,"QUERY",null));
                assertTrue(outcome.retryAllowed());assertTrue(outcome.cleanupConfirmed());
                assertEquals("FAILED",rows().get(0).get("status"));
                assertEquals(before.get("deadline_epoch_ms"),rows().get(0).get("deadline_epoch_ms"));
            }
            release.countDown();assertThrows(ExecutionException.class,()->future.get(6,TimeUnit.SECONDS));
            if(!denyCancellation){
                var result=service().executeOnConnection(observer,parameter("SELECT 43 AS answer",30),context("repair-after-reconcile"),"QUERY",null);
                assertEquals("43",result.getData().get(0).get("answer"));
            }
        }finally{release.countDown();former.close();pool.shutdownNow();}
    }
    @Test void killedJavaProcessLeavesDurableDeadlineAndRecoveryDoesNotReplayItsSql() throws Exception {
        String query="SELECT pg_sleep(20),42 AS answer";
        Path log=Path.of("target/sql-attempt-crash-"+run+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("surefire.test.class.path"),
            "cn.lgs.semevosql.sql.application.SqlAttemptCrashProbe",PG.getJdbcUrl(),PG.getUsername(),PG.getPassword(),run,attempt,query)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try(var observer=pg()) {
            waitFor(()->!rows().isEmpty() && "RUNNING".equals(rows().get(0).get("status")),12000);
            long pid=((Number)rows().get(0).get("backend_session_id")).longValue();
            waitFor(()->"1".equals(scalar(observer,"SELECT count(*) FROM pg_stat_activity WHERE pid="+pid+" AND state='active' AND query LIKE '%pg_sleep(20)%'")),3000);
            var before=rows().get(0);process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));
            var outcome=assertThrows(SqlAttemptReplayException.class,()->service().executeOnConnection(observer,parameter(query,30),context("process-crash"),"QUERY",null));
            assertTrue(outcome.cleanupConfirmed());assertEquals(1,rows().size());
            assertEquals(before.get("deadline_epoch_ms"),rows().get(0).get("deadline_epoch_ms"));
            assertEquals("0",scalar(observer,"SELECT count(*) FROM pg_stat_activity WHERE pid="+pid));
            assertEquals("42",service().executeOnConnection(observer,parameter("SELECT 42 AS answer",30),context("process-repair-1"),"QUERY",null).getData().get(0).get("answer"));
            System.out.println("REAL_SQL_PROCESS_CRASH preservedDeadline="+before.get("deadline_epoch_ms")+" oldSessionGone=true replacementAnswer=42");
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test void actualDruidPoolsSupportOwnershipAndReturnCleanConnectionsForBothDialects() throws Exception {
        var pgPool=new cn.lgs.semevosql.connector.impls.postgre.PostgreSqlJdbcConnectionPool();
        var myPool=new cn.lgs.semevosql.connector.impls.mysql.MysqlJdbcConnectionPool();
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)pgPool.createdDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var c=ds.getConnection()) {
            assertEquals("42",service().executeOnConnection(c,parameter("SELECT 42 AS answer",30),context("pg-pool"),"QUERY",null).getData().get(0).get("answer"));
            assertEquals("0",scalar(c,"SHOW statement_timeout"));assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());
        }
        try(var ds=(com.alibaba.druid.pool.DruidDataSource)myPool.createdDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var c=ds.getConnection()) {
            assertEquals("42",service().executeOnConnection(c,parameter("SELECT 42 AS answer",30),context("mysql-pool"),"QUERY",null).getData().get(0).get("answer"));
            assertEquals("0",scalar(c,"SELECT @@SESSION.max_execution_time"));assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());
        }
    }
    interface Condition { boolean value() throws Exception; }
    static void waitFor(Condition condition,long millis) throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(!condition.value()){if(System.nanoTime()>until)fail("Condition did not become true");Thread.sleep(25);}
    }
}
