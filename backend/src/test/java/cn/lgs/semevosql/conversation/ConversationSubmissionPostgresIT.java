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
package cn.lgs.semevosql.conversation;

import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.conversation.ProjectConversationService.SendMessageCommand;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.project.application.*;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.service.graph.GraphService;
import cn.lgs.semevosql.task.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transactions and competing submissions; execution dispatch is isolated from external models. */
@Testcontainers
class ConversationSubmissionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc; static TransactionTemplate tx;
    ProjectConversationService service; QueryRunService runs; ProjectRuntimeGate gate;
    RuntimeSemanticBindingService bindings; GraphService graph; String thread;
    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (1,'replay-test','Synthetic replay','synthetic','ACTIVE','owner')");
        for(int id=1;id<=2;id++) jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (?,1,?,?,'DRAFT','COMPLETED',1,?,0)",id,id,"1."+id+".0",id);
    }
    @BeforeEach void setup() {
        gate=mock(ProjectRuntimeGate.class);graph=mock(GraphService.class);
        bindings=mock(RuntimeSemanticBindingService.class);
        when(gate.requireReadyByProject(1L)).thenReturn(new ProjectRuntimeContext(1L,1L,"catalog-v1"));
        when(gate.requireReadyVersion(1L,1L)).thenReturn(new ProjectRuntimeContext(1L,1L,"catalog-v1"));
        var projects=mock(SemanticProjectRepository.class);
        var project=SemanticProject.builder().id(1L).build();when(projects.findProject(1L)).thenReturn(Optional.of(project));
        var profiles=mock(ProjectRuntimeProfileService.class);
        when(profiles.resolveOrCreate(project)).thenReturn(ProjectRuntimeProfile.builder().runtimeProfileId("synthetic-profile").projectId(1L).build());
        runs=new QueryRunService(new QueryRunRepository(jdbc),"test-worker");
        var production=mock(SemEvoSQLProductionService.class);
        when(production.createFirstAttemptAndBind(anyString(),anyString(),any())).thenAnswer(invocation ->
            new SemEvoSQLProductionService.ExecutionBinding(null,null,runs.get(invocation.getArgument(0))));
        service=new ProjectConversationService(jdbc,projects,gate,profiles,graph,runs,new QueryRunErrorPresenter(),
            production,mock(MultiSourceRunService.class),mock(ExecutionSnapshotService.class),
            mock(QueryExecutionExplanationService.class),mock(QueryTaskAnswerService.class),bindings,
            mock(RuntimeClarificationService.class),new RuntimePrincipalResolver(jdbc),
            mock(UserSemanticPreferenceService.class),mock(QueryTaskRepository.class));
        thread=tx.execute(s -> service.create(1L,"新对话","owner")).conversationId();
    }
    SendMessageCommand command(String question,String key) {return new SendMessageCommand(question,key,key);}
    ProjectConversationService.SendMessageResult send(SendMessageCommand command) {
        return tx.execute(s -> service.send(1L,thread,command,"owner"));
    }
    RuntimeSemanticBindingService.BindingContext binding(String metric) {
        return new RuntimeSemanticBindingService.BindingContext(List.of(new RuntimeSemanticBindingService.ResolvedRuntimeBinding(
            "amount","金额","METRIC",metric,"synthetic "+metric,"orders","EXPLICIT",null,"owner")),QueryCaseHints.empty(),List.of("orders"));
    }
    int runCount() {return jdbc.queryForObject("SELECT count(*) FROM qw_query_run WHERE thread_id=?",Integer.class,thread);}
    @Test void confirmedManagementReplyPersistsHumanReadableContentAndKindWithoutFakeDataResult() throws Exception {
        var sent=send(command("只改共享范围，计算不变","management-reply"));
        String id=sent.run().runId();
        runs.transition(id,cn.lgs.semevosql.run.QueryRun.RunStatus.RUNNING,"fixture-management",null,null);
        runs.transition(id,cn.lgs.semevosql.run.QueryRun.RunStatus.SUCCEEDED,null,null,null);
        var changes=mock(cn.lgs.semevosql.clarification.PersonalDefinitionChangeService.class);
        when(changes.completion(id,1L,"owner")).thenReturn(Optional.of("已确认更新你的口径，计算方法不变，并允许分享为项目建议。"));
        service.definitionChanges(changes);
        var message=tx.execute(ignored->service.synchronizeAssistantMessage(1L,thread,id));
        assertEquals("已确认更新你的口径，计算方法不变，并允许分享为项目建议。",message.content());
        var metadata=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(message.metadataJson());
        assertEquals("SEMANTIC_UPDATE",metadata.path("requestKind").asText());
        assertFalse(metadata.has("artifactId"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_sql_execution_attempt WHERE run_id=?",Integer.class,id));
    }
    @Test void durableManagementKindIsAvailableBeforeAnAnswerAndAfterCancellation() throws Exception {
        var sent=send(command("仅改口径","pending-management"));String id=sent.run().runId();
        assertFalse(sent.userMessage().content().contains("Run"));
        runs.appendEvent(id,"REQUEST_ANALYSIS_COMPLETED","analysis","{\"requestType\":\"SEMANTIC_UPDATE\"}","Synthetic analysis","synthetic-analysis");
        var pending=tx.execute(ignored->service.synchronizeAssistantMessage(1L,thread,id));
        assertEquals("SEMANTIC_UPDATE",cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(pending.metadataJson()).path("requestKind").asText());
        assertTrue(pending.content().contains("确认后再保存"));assertFalse(pending.content().contains("Run"));
        runs.cancel(id,"synthetic-cancel");runs.acknowledgeCancelled(id);
        var cancelled=tx.execute(ignored->service.synchronizeAssistantMessage(1L,thread,id));
        assertEquals("SEMANTIC_UPDATE",cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readTree(cancelled.metadataJson()).path("requestKind").asText());
        assertEquals("任务已取消。",cancelled.content());
    }
    @Test void acceptedRetryRetainsOriginalVersionEvenAfterNewVersionOrUnavailability() {
        var c=command("查询金额","same");var first=send(c);
        when(gate.requireReadyByProject(1L)).thenReturn(new ProjectRuntimeContext(1L,2L,"catalog-v2"));
        assertEquals(first.run().runId(),send(c).run().runId());
        when(gate.requireReadyByProject(1L)).thenThrow(new IllegalStateException("temporarily unavailable"));
        assertEquals(1L,send(c).run().projectVersionId());assertEquals(1,runCount());
        verify(graph,times(1)).graphStreamProcess(any(),any());
    }
    @Test void changedMeaningOrCallerCannotReuseAcceptedMessageKey() {
        var c=command("查询金额","same");var binding=binding("gross");
        tx.execute(s -> service.sendWithBindings(1L,thread,c,binding,"owner"));
        assertThrows(IllegalStateException.class,() -> send(c));
        assertThrows(IllegalStateException.class,() -> tx.execute(s -> service.sendWithBindings(1L,thread,c,binding("net"),"owner")));
        assertThrows(IllegalStateException.class,() -> tx.execute(s -> service.sendWithBindings(1L,thread,c,binding,"other-owner")));
        assertThrows(IllegalStateException.class,() -> tx.execute(s -> service.sendWithBindings(1L,thread,command("另一问题","same"),binding,"owner")));
        assertEquals(1,runCount());
    }
    @Test void simultaneousDeliveryCreatesOneRunAndOneMessagePair() throws Exception {
        var executor=Executors.newFixedThreadPool(3);var latch=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<3;i++) futures.add(executor.submit(() -> {latch.await();return send(command("并发金额","one")).run().runId();}));
            latch.countDown();var ids=new HashSet<String>();for(var f:futures)ids.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(1,runCount());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_project_message WHERE conversation_id=?",Integer.class,thread));
            verify(graph,times(1)).graphStreamProcess(any(),any());
        } finally {executor.shutdownNow();}
    }
    @Test void correctionKeepsTheOriginalCatalogAndRecordsNaturalLanguageMeaning() {
        var first=send(command("支付金额","original"));
        // Fixture terminal state, not a claimed execution result: this test exercises submission only.
        jdbc.update("UPDATE qw_query_run SET status='FAILED' WHERE run_id=?",first.run().runId());
        when(gate.requireReadyByProject(1L)).thenReturn(new ProjectRuntimeContext(1L,2L,"catalog-v2"));
        when(bindings.explicit(1L,1L,"金额","METRIC","gross","下单金额")).thenReturn(binding("gross"));
        assertThrows(SecurityException.class,() -> tx.execute(s -> service.rerunWithBinding(1L,thread,first.run().runId(),"金额","METRIC","gross","下单金额","other","other","other-owner")));
        var next=tx.execute(s -> service.rerunWithBinding(1L,thread,first.run().runId(),"金额","METRIC","gross","下单金额","corrected","corrected","owner"));
        assertEquals(1L,next.run().projectVersionId());assertTrue(next.userMessage().metadataJson().contains("下单金额"));
        assertTrue(next.userMessage().metadataJson().contains(first.run().runId()));
        var duplicate=tx.execute(s -> service.rerunWithBinding(1L,thread,first.run().runId(),"金额","METRIC","gross","下单金额","corrected","corrected","owner"));
        assertEquals(next.run().runId(),duplicate.run().runId());assertEquals(2,runCount());
    }
    @Test void defaultTitleUsesFirstQuestionWithoutSplittingUnicodeAndKeepsUserTitles() {
        String question="测😀".repeat(45);send(command(question,"first"));
        var title=service.list(1L).stream().filter(c -> c.conversationId().equals(thread)).findFirst().orElseThrow().title();
        assertEquals(60,title.codePointCount(0,title.length()));assertFalse(Character.isHighSurrogate(title.charAt(title.length()-1)));
        var custom=tx.execute(s -> service.create(1L,"我的月报","owner"));
        tx.execute(s -> service.send(1L,custom.conversationId(),command("不应改名","first"),"owner"));
        assertEquals("我的月报",service.list(1L).stream().filter(c -> c.conversationId().equals(custom.conversationId())).findFirst().orElseThrow().title());
    }
    @Test void reloadedConversationProjectsOnlyTheCurrentUsersPersistedFeedback() {
        var run=send(command("支付金额","feedback")).run();
        String episode=UUID.randomUUID().toString();
        jdbc.update("UPDATE qw_query_run SET episode_id=? WHERE run_id=?",episode,run.runId());
        jdbc.update("INSERT INTO qw_feedback(id,episode_id,idempotency_key,rating,adopted) VALUES (?,?,?,5,true)",
            UUID.randomUUID().toString(),episode,episode+":owner");
        assertEquals(List.of(run.runId()),service.view(1L,thread,"owner").feedbackSubmittedRunIds());
        assertTrue(service.view(1L,thread,"other").feedbackSubmittedRunIds().isEmpty());
        var another=tx.execute(s -> service.create(1L,"独立会话","owner"));
        assertTrue(service.view(1L,another.conversationId(),"owner").feedbackSubmittedRunIds().isEmpty());
        assertEquals(List.of(run.runId()),service.view(1L,thread,"owner").feedbackSubmittedRunIds());
    }

}
