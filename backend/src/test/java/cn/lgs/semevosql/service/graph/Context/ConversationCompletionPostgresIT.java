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
package cn.lgs.semevosql.service.graph.Context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.run.ExecutionSnapshot;
import cn.lgs.semevosql.run.ExecutionSnapshotService;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL lifecycle and actual deterministic context rendering. Synthetic Run fixtures; no LLM claim. */
@Testcontainers
class ConversationCompletionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    ConversationTurnRepository turns;
    ConversationContextAssembler assembler;
    ConversationContextPromptRenderer renderer;
    ConversationTurnSummarizer summarizer;
    ConversationContextCompactionService compaction;
    QueryRunService runs;
    String thread;

    @BeforeAll static void migrate() {
        // Use the same JSON parameter binding mode as application-local.yml and Docker Compose.
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified";
        var ds = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach void setup() {
        thread = "synthetic-context-" + UUID.randomUUID();
        turns = new ConversationTurnRepository(jdbc);
        var properties = new ConversationContextProperties();
        var tokens = new ApproximateTokenCounter();
        assembler = new ConversationContextAssembler(turns, new ConversationContextCompactionRepository(jdbc), properties);
        renderer = new ConversationContextPromptRenderer(properties, tokens);
        runs = mock(QueryRunService.class);
        var snapshots = mock(ExecutionSnapshotService.class);
        var plan = SemanticBlueprint.builder().canonicalQuery("2026年1月按支付时间统计已支付订单金额，不扣退款")
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("paid_amount")
                .businessName("已支付订单金额").modelCode("orders").build())).build();
        when(snapshots.readTyped("frozen-plan")).thenReturn(Optional.of(new ExecutionSnapshot(
            1, null, null, null, null, null, null, plan, "synthetic-hash", true, false, null)));
        summarizer = new ConversationTurnSummarizer(runs, snapshots, jdbc, properties, tokens);
        compaction = mock(ConversationContextCompactionService.class);
    }

    MultiTurnContextManager manager() {
        return new MultiTurnContextManager(turns, assembler, summarizer, renderer, compaction);
    }

    String run(String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,status,idempotency_key) VALUES (?,'INTERACTIVE_QUERY',?,?,?)",
            id, thread, status, id);
        when(runs.get(id)).thenReturn(QueryRun.builder().runId(id).threadId(thread)
            .status(QueryRun.RunStatus.valueOf(status)).executionSnapshot("frozen-plan").build());
        return id;
    }

    @Test void followupContextUsesReviewedReplacementAndDoesNotReviveAbandonedResults() {
        String id = run("SUCCEEDED");
        String old = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
        for (String artifact : List.of(old, current)) {
            jdbc.update("""
                INSERT INTO qw_result_artifact(artifact_id,run_id,artifact_type,schema_json,data_json,row_count,content_hash,status)
                VALUES (?,?,?,'["paid_amount"]',?,1,?,'READY')
                """, artifact, id, artifact.equals(old) ? "MERGED_RESULT" : "DIRECT_RESULT",
                artifact.equals(old) ? "[{\"paid_amount\":\"100\"}]" : "[{\"paid_amount\":\"90\"}]", "a".repeat(64));
        }
        jdbc.update("""
            INSERT INTO qw_run_event(run_id,sequence,event_type,payload,idempotency_key)
            VALUES (?,1,'RESULT_ARTIFACT_ACCEPTED',?,'reviewed-final')
            """, id, "{\"artifactId\":\"" + current + "\"}");
        var summary = summarizer.summarize(id, "查询一月金额", "");
        assertEquals(current, summary.resultArtifactId());
        assertFalse(summary.contextSummaryJson().contains(old));
        jdbc.update("UPDATE qw_result_artifact SET status='FAILED' WHERE artifact_id=?", current);
        assertNull(summarizer.summarize(id, "查询一月金额", "").resultArtifactId());
    }

    @Test void directSemanticSuccessWithoutLegacyPlannerBecomesUsableFollowupContext() {
        String id = run("SUCCEEDED");
        var manager = manager();
        manager.beginTurn(id, thread, "查询2026年1月已支付金额，不扣退款");
        manager.finishTurn(id, thread);
        var stored = turns.findByRun(id).orElseThrow();
        assertEquals("COMPLETED", stored.status());
        assertEquals("", stored.plannerOutput());
        assertTrue(stored.contextSummaryJson().contains("paid_amount"));
        var frozen = manager.prepareContext(thread, "那二月呢？").envelope();
        assertEquals(id, frozen.recentTurns().get(0).sourceRunId());
        assertEquals(stored.revision(), frozen.recentTurns().get(0).sourceRevision());
        String followup = renderer.render(frozen,
            ConversationContextPromptRenderer.Stage.QUERY_ENHANCE);
        assertTrue(followup.contains("已支付订单金额"), followup);
        assertTrue(followup.contains("不扣退款"), followup);
        assertEquals("(无)", manager.prepareContext(thread + "-other", "那二月呢？").rendered());
    }

    @Test void restartedWorkerCompletesExactDurableTurnWithoutReplayingPlanner() {
        String id = run("SUCCEEDED");
        manager().beginTurn(id, thread, "查询一月已支付金额");
        manager().finishTurn(id, thread);
        assertEquals("COMPLETED", turns.findByRun(id).orElseThrow().status());
        assertEquals(1, turns.completedHistory(thread, 5).size());
    }

    @Test void duplicateCompletionIsIdempotentAndDoesNotCompleteNewerPendingRun() {
        var manager = manager();
        String first = run("SUCCEEDED");
        manager.beginTurn(first, thread, "查询一月已支付金额");
        manager.finishTurn(first, thread);
        var completed = turns.findByRun(first).orElseThrow();
        String next = run("RUNNING");
        manager.beginTurn(next, thread, "那二月呢？");
        manager.appendPlannerChunk(thread, "第二轮计划");
        manager.finishTurn(first, thread);
        manager.persistPending(thread);
        assertEquals(completed, turns.findByRun(first).orElseThrow());
        assertEquals("PENDING", turns.findByRun(next).orElseThrow().status());
        assertEquals("第二轮计划", turns.findByRun(next).orElseThrow().plannerOutput());
        verify(compaction, times(1)).maybeCompactAsync(thread);
    }

    @Test void cancelledTurnIsNotRevivedEvenByLateSuccessfulCallback() {
        String id = run("SUCCEEDED");
        var manager = manager();
        manager.beginTurn(id, thread, "查询一月金额");
        manager.discardRun(id, thread);
        manager.finishTurn(id, thread);
        assertEquals("CANCELLED", turns.findByRun(id).orElseThrow().status());
        assertTrue(turns.completedHistory(thread, 5).isEmpty());
        verifyNoInteractions(compaction);
    }

    @Test void mismatchedThreadCannotConsumePendingContext() {
        String id = run("SUCCEEDED");
        var manager = manager();
        manager.beginTurn(id, thread, "查询一月金额");
        assertThrows(IllegalArgumentException.class, () -> manager.finishTurn(id, thread + "other"));
        assertEquals("PENDING", turns.findByRun(id).orElseThrow().status());
        manager.finishTurn(id, thread);
        assertEquals("COMPLETED", turns.findByRun(id).orElseThrow().status());
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "WAITING_HUMAN", "FAILED", "CANCEL_REQUESTED", "CANCELLED", "EXPIRED"})
    void databaseRejectsContextPublicationForAnUnsuccessfulRun(String status) {
        String id = run(status);
        var manager = manager();
        manager.beginTurn(id, thread, "查询一月金额");
        manager.finishTurn(id, thread);
        assertEquals("PENDING", turns.findByRun(id).orElseThrow().status());
        assertTrue(turns.completedHistory(thread, 5).isEmpty());
        verifyNoInteractions(compaction);
    }
}
