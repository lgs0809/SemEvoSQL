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

import static org.junit.jupiter.api.Assertions.*;
import static cn.lgs.semevosql.constant.Constant.*;

import cn.lgs.semevosql.run.*;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class NativeRunFencePostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource ds; static JdbcTemplate jdbc;
    @BeforeAll static void migrate() {
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }
    QueryRunService runs(String owner) { return new QueryRunService(new QueryRunRepository(jdbc), owner); }
    NativeGraphRuntime runtime(String owner) throws Exception {
        return new NativeGraphRuntime(ds, jdbc, runs(owner), new DataSourceTransactionManager(ds));
    }
    QueryRun run(String conversation) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO qw_query_run(run_id, run_type, thread_id, attempt_id, status, idempotency_key,
                owner_instance, lease_expire_time) VALUES (?, 'INTERACTIVE_QUERY', ?, ?, 'RUNNING', ?,
                'worker-a', CURRENT_TIMESTAMP + interval '5 minutes')
            """, id, conversation, "attempt-" + id, id);
        return runs("worker-a").get(id);
    }
    Checkpoint state(QueryRun run, String value) {
        return Checkpoint.builder().id(UUID.randomUUID().toString()).nodeId("prepare").nextNodeId("confirm")
            .state(Map.of(RUN_ID,run.runId(),ATTEMPT_ID,run.attemptId(),"value",value)).build();
    }
    @Test void twoRunsInOneConversationHaveIndependentPersistedHistories() throws Exception {
        var runtime = runtime("worker-a"); var a = run("same-chat"); var b = run("same-chat");
        var ca = runtime.claim(a); var cb = runtime.claim(b);
        assertNotEquals(ca.threadId(), cb.threadId()); runtime.put(ca, state(a,"a")); runtime.put(cb,state(b,"b"));
        var restarted = runtime("worker-a");
        assertEquals("a",restarted.get(restarted.existing(a.runId()).orElseThrow()).orElseThrow().getState().get("value"));
        assertEquals("b",restarted.get(restarted.existing(b.runId()).orElseThrow()).orElseThrow().getState().get("value"));
    }
    @Test void afterCommitDispatchPublishesEachNativeBindingBeforeAsyncGraphSubscription() throws Exception {
        var nativeRuntime=runtime("worker-a");
        var target=org.mockito.Mockito.mock(cn.lgs.semevosql.service.graph.GraphServiceImpl.class);
        org.mockito.Mockito.when(target.graphStreamProcess(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation->{
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "Asynchronous graph orchestration must not inherit the completed conversation transaction");
                cn.lgs.semevosql.dto.GraphRequest request=invocation.getArgument(1);
                var current=runs("worker-a").get(request.getRunId());
                var config=nativeRuntime.claim(current);
                // A different connection is exactly what the asynchronous native saver sees.
                var observed=CompletableFuture.supplyAsync(()->nativeRuntime.get(config)).get(5,TimeUnit.SECONDS);
                assertTrue(observed.isEmpty());
                nativeRuntime.put(config,state(current,current.threadId()));
                return current.threadId();
            });
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setInterfaces(cn.lgs.semevosql.service.graph.GraphService.class);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            new DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy=(cn.lgs.semevosql.service.graph.GraphService)factory.getProxy();
        var created=new ArrayList<QueryRun>();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
        transaction.executeWithoutResult(status->{
            for(int index=0;index<2;index++) {
                var current=run("after-commit-chat-"+index);created.add(current);
                var request=cn.lgs.semevosql.dto.GraphRequest.builder().runId(current.runId()).build();
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void afterCommit(){proxy.graphStreamProcess(reactor.core.publisher.Sinks.many().replay().limit(1),request);}
                    });
            }
        });
        for(var current:created) {
            var checkpoint=nativeRuntime.get(nativeRuntime.existing(current.runId()).orElseThrow()).orElseThrow();
            assertEquals(current.runId(),checkpoint.getState().get(RUN_ID));
            assertEquals(current.threadId(),checkpoint.getState().get("value"));
        }
    }
    @Test void newGenerationRejectsOldCallbacksEvenWithSameOwnerAndAttempt() throws Exception {
        var runtime = runtime("worker-a"); var run = run("generation"); var old = runtime.claim(run);
        runtime.put(old,state(run,"old")); var next = runtime.claim(run); runtime.put(next,state(run,"next"));
        assertThrows(LateRunResultDroppedException.class,()->runtime.put(old,state(run,"late")));
        assertEquals("next",runtime.get(next).orElseThrow().getState().get("value"));
    }
    @Test void revokedLeaseCancelledRunAndWrongRunStateCannotWrite() throws Exception {
        var runtime = runtime("worker-a"); var run = run("cancel"); var config = runtime.claim(run);
        runtime.put(config,state(run,"accepted"));
        assertThrows(LateRunResultDroppedException.class,()->runtime.put(config,state(run("other"),"wrong")));
        jdbc.update("UPDATE qw_query_run SET owner_instance = 'worker-b' WHERE run_id = ?",run.runId());
        assertThrows(LateRunResultDroppedException.class,()->runtime.put(config,state(run,"late-owner")));
        jdbc.update("UPDATE qw_query_run SET owner_instance = 'worker-a', status = 'CANCELLED' WHERE run_id = ?",run.runId());
        assertThrows(LateRunResultDroppedException.class,()->runtime.put(config,state(run,"late-cancel")));
        assertEquals("accepted",runtime.get(config).orElseThrow().getState().get("value"));
    }
    @Test void incompatibleDefinitionFailsClosedWithoutReleasingHistory() throws Exception {
        var runtime = runtime("worker-a"); var run = run("version"); var config = runtime.claim(run);
        runtime.put(config,state(run,"retained"));
        jdbc.update("UPDATE qw_native_graph_binding SET definition_version = 'future-v99' WHERE run_id = ?",run.runId());
        assertThrows(IllegalStateException.class,()->runtime.existing(run.runId()));
        assertThrows(IllegalStateException.class,()->runtime.put(config,state(run,"replay")));
        assertThrows(UnsupportedOperationException.class,()->runtime.release(config));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM GraphCheckpoint c JOIN GraphThread t ON t.thread_id=c.thread_id WHERE t.thread_name=?",Integer.class,config.threadId().orElseThrow()));
    }
    @Test void ownershipTransferWaitsForRunFenceAndThenRejectsOldGeneration() throws Exception {
        var runtime = runtime("worker-a"); var run = run("concurrent-owner"); var config = runtime.claim(run);
        var locked = new CountDownLatch(1); var unlock = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
            var transfer = pool.submit(()->tx.execute(s->{
                jdbc.queryForObject("SELECT run_id FROM qw_query_run WHERE run_id=? FOR UPDATE",String.class,run.runId());
                locked.countDown(); try { assertTrue(unlock.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("UPDATE qw_query_run SET owner_instance='worker-b' WHERE run_id=?",run.runId()); return true;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var late = pool.submit(()->runtime.put(config,state(run,"late-concurrent")));
            assertThrows(TimeoutException.class,()->late.get(150,TimeUnit.MILLISECONDS));
            unlock.countDown(); assertTrue(transfer.get(5,TimeUnit.SECONDS));
            assertInstanceOf(LateRunResultDroppedException.class,assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS)).getCause());
            assertTrue(runtime.get(config).isEmpty());
        } finally { unlock.countDown(); pool.shutdownNow(); }
    }

    @Test void humanWaitHasSeparate24HourLimitAndPreservesExecutionBudget() {
        var run=run("wait-budget"); var service=runs("worker-a");
        jdbc.update("UPDATE qw_query_run SET deadline_epoch_millis=? WHERE run_id=?",System.currentTimeMillis()+120000,run.runId());
        var paused=service.transition(run.runId(),run.attemptId(),QueryRun.RunStatus.WAITING_HUMAN,"confirm",null,null);
        assertNull(paused.deadlineEpochMillis());
        var stored=jdbc.queryForMap("SELECT * FROM qw_query_run WHERE run_id=?",run.runId());
        assertEquals(java.time.Duration.ofHours(24).toMillis(),((Number)stored.get("human_wait_deadline_ms")).longValue()-((Number)stored.get("human_wait_started_ms")).longValue());
        long remaining=((Number)stored.get("paused_execution_remaining_ms")).longValue();
        assertTrue(remaining>110000 && remaining<=120000);
        long before=System.currentTimeMillis();
        var resumed=service.resume(run.runId(),"answer-1");
        assertTrue(resumed.deadlineEpochMillis()>=before+remaining);
        assertEquals(QueryRun.RunStatus.QUEUED,resumed.status());
        assertEquals(resumed.deadlineEpochMillis(),service.resume(run.runId(),"answer-1").deadlineEpochMillis());
    }

    @Test void expiredHumanWaitCannotResumeAndScannerMarksItExpired() {
        var run=run("expired-wait"); var service=runs("worker-a");
        service.transition(run.runId(),run.attemptId(),QueryRun.RunStatus.WAITING_HUMAN,"confirm",null,null);
        // Clock fault fixture only, never used by deployment or business seed scripts.
        jdbc.update("UPDATE qw_query_run SET human_wait_deadline_ms=? WHERE run_id=?",System.currentTimeMillis()-1,run.runId());
        assertThrows(IllegalStateException.class,()->service.resume(run.runId(),"too-late"));
        service.expireHumanWaitIfDue(run.runId());
        assertEquals(QueryRun.RunStatus.EXPIRED,service.get(run.runId()).status());
        assertEquals(1,service.events(run.runId(),0,100).stream().filter(e->e.eventType().equals("RUN_EXPIRED")).count());
        service.expireHumanWaitIfDue(run.runId());
    }

    @Test void nativeClarificationPauseResumesWithAppliedRevisionInNewInstance() throws Exception {
        var run=run("clarification");var service=runs("worker-a");var runtime=runtime("worker-a");
        var repo=new cn.lgs.semevosql.clarification.RuntimeClarificationRepository(jdbc);
        var answers=org.mockito.Mockito.mock(cn.lgs.semevosql.clarification.RuntimeClarificationService.class);
        org.mockito.Mockito.when(answers.applyResolvedAnswer(org.mockito.ArgumentMatchers.eq(run.runId()),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("一月已支付金额");
        var boundary=new NativeClarificationBoundary(answers,repo,new RunExecutionFenceService(service));
        var before=new java.util.concurrent.atomic.AtomicInteger();var after=new java.util.concurrent.atomic.AtomicInteger();
        String question=UUID.randomUUID().toString();
        java.util.function.Function<NativeGraphRuntime,com.alibaba.cloud.ai.graph.CompiledGraph> build=saver->{try{
            return new com.alibaba.cloud.ai.graph.StateGraph("clarification-fixture",()->{
                Map<String,com.alibaba.cloud.ai.graph.KeyStrategy> keys=new HashMap<>();
                for(String key:List.of(RUN_ID,ATTEMPT_ID,INPUT_KEY,ACTIVE_QUERY,NativeClarificationBoundary.QUESTION_ID,
                    NativeClarificationBoundary.RETURN_NODE,NativeClarificationBoundary.BASE_QUERY,NativeClarificationBoundary.APPLIED_ANSWERS))keys.put(key,com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE);
                return keys;},new DurableGraphStateSerializer())
                .addNode("prepare",com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(s->{
                    before.incrementAndGet();repo.insert(cn.lgs.semevosql.clarification.RuntimeClarification.builder()
                        .clarificationId(question).runId(run.runId()).question("含退款吗？").options(List.of())
                        .status(cn.lgs.semevosql.clarification.RuntimeClarification.ClarificationStatus.PENDING).revision(0).build());
                    service.transition(run.runId(),run.attemptId(),QueryRun.RunStatus.WAITING_HUMAN,"runtime-clarification",null,null);
                    return NativeClarificationBoundary.pause(s,question,"unused");}))
                .addNode(NativeClarificationBoundary.RESUME_NODE,com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(boundary::resume))
                .addNode("execute",com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(s->{after.incrementAndGet();return Map.of();}))
                .addEdge(com.alibaba.cloud.ai.graph.StateGraph.START,"prepare").addEdge("prepare",NativeClarificationBoundary.RESUME_NODE)
                .addEdge(NativeClarificationBoundary.RESUME_NODE,"execute").addEdge("execute",com.alibaba.cloud.ai.graph.StateGraph.END)
                .compile(com.alibaba.cloud.ai.graph.CompileConfig.builder().saverConfig(com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig.builder().register(saver).build())
                    .interruptBefore(NativeClarificationBoundary.RESUME_NODE).build());
            }catch(Exception error){throw new RuntimeException(error);}};
        var config=runtime.claim(run);var graph=build.apply(runtime);
        graph.invoke(Map.of(RUN_ID,run.runId(),ATTEMPT_ID,run.attemptId(),INPUT_KEY,"一月金额"),config);
        assertEquals(1,before.get());assertEquals(0,after.get());assertEquals(NativeClarificationBoundary.RESUME_NODE,graph.getState(config).next());
        assertEquals(1,repo.answer(question,0,"PAID","不扣退款",cn.lgs.semevosql.clarification.SemanticBindingScope.QUERY,"fixture-owner","已支付","USER_SELECTED"));
        service.resume(run.runId(),"answer-key");service.transition(run.runId(),run.attemptId(),QueryRun.RunStatus.RUNNING,"resume",null,null);
        var restarted=runtime("worker-a");var resumed=build.apply(restarted);var resumedConfig=restarted.claim(service.get(run.runId()));
        resumed.invoke((Map<String,Object>)null,com.alibaba.cloud.ai.graph.RunnableConfig.builder(resumedConfig)
            .addMetadata(com.alibaba.cloud.ai.graph.RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,Map.of("accepted",true)).build());
        assertEquals(1,before.get());assertEquals(1,after.get());
        var state=restarted.get(resumedConfig).orElseThrow().getState();
        assertEquals("一月已支付金额",state.get(INPUT_KEY));
        assertEquals(Map.of(question,1L),state.get(NativeClarificationBoundary.APPLIED_ANSWERS));
        org.mockito.Mockito.verify(answers,org.mockito.Mockito.times(1)).applyResolvedAnswer(run.runId(),"一月金额");
    }
}
