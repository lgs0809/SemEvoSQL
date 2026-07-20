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
package cn.lgs.semevosql.run;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Isolated real PostgreSQL transactions exercise allocation, recovery and human-wait accounting. */
@Testcontainers
class RunTaskDeadlinePostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static QueryRunRepository repository;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new QueryRunRepository(jdbc);
    }

    QueryRunService service(long unitMs) {
        return new QueryRunService(repository, "budget-worker", SemEvoSQLMetrics.noop(), unitMs);
    }

    QueryRun create(Long explicitDeadline) {
        String id = UUID.randomUUID().toString();
        var runs = service(300_000);
        QueryRun queued = tx.execute(ignored -> runs.create(new QueryRunService.CreateRunCommand(
            QueryRun.RunType.INTERACTIVE_QUERY, 1L, 1L, id, id, id, "{}", null, explicitDeadline)));
        return tx.execute(ignored -> runs.bindExecution(queued.runId(), "episode-" + id, "attempt-" + id, id));
    }

    QueryRun allocate(QueryRun run, int count) {
        return tx.execute(ignored -> service(300_000).allocateTaskDeadline(run.runId(), run.attemptId(), count));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 12})
    void requiredTaskCountAddsOnlyExtraUnitsAndPersistsOnce(int count) {
        QueryRun original = create(null);
        QueryRun allocated = allocate(original, count);
        assertEquals(original.deadlineEpochMillis() + (count - 1) * 300_000L, allocated.deadlineEpochMillis());
        assertEquals(allocated.deadlineEpochMillis(), allocate(original, count).deadlineEpochMillis());
        assertEquals(count, repository.taskBudget(original.runId()).taskCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM qw_run_event WHERE run_id=? AND event_type='TASK_EXECUTION_BUDGET_ALLOCATED'", Integer.class, original.runId()));
    }

    @Test void concurrentAllocationAndRestartCannotRefillTheBudget() throws Exception {
        QueryRun original = create(null);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            Callable<Long> work = () -> { start.await(); return allocate(original, 3).deadlineEpochMillis(); };
            var a = pool.submit(work); var b = pool.submit(work); var c = pool.submit(work); var d = pool.submit(work);
            start.countDown();
            long expected = original.deadlineEpochMillis() + 600_000;
            for (var result : java.util.List.of(a, b, c, d)) assertEquals(expected, result.get(10, TimeUnit.SECONDS));
            assertEquals(expected, tx.execute(ignored -> service(900_000)
                .allocateTaskDeadline(original.runId(), original.attemptId(), 3)).deadlineEpochMillis());
            assertEquals(300_000L, repository.taskBudget(original.runId()).unitMs());
        } finally { pool.shutdownNow(); }
    }

    @Test void firstAllocationAfterRestartUsesTheUnitFrozenAtCreation() {
        QueryRun original = create(null);
        assertEquals(original.deadlineEpochMillis() + 300_000, tx.execute(ignored -> service(900_000)
            .allocateTaskDeadline(original.runId(), original.attemptId(), 2)).deadlineEpochMillis());
    }

    @Test void changingTheDecompositionOrInvalidCountCannotAddTime() {
        QueryRun original = create(null);
        QueryRun allocated = allocate(original, 2);
        assertThrows(IllegalStateException.class, () -> allocate(original, 3));
        for (int count : new int[] {0, -1, 13}) assertThrows(IllegalArgumentException.class, () -> allocate(original, count));
        assertEquals(allocated.deadlineEpochMillis(), service(300_000).get(original.runId()).deadlineEpochMillis());
    }

    @Test void explicitAndLegacyDeadlinesRemainFixed() {
        QueryRun explicit = create(System.currentTimeMillis() + 60_000);
        assertEquals(explicit.deadlineEpochMillis(), allocate(explicit, 3).deadlineEpochMillis());
        assertNull(repository.taskBudget(explicit.runId()).unitMs());
        QueryRun legacy = create(null);
        jdbc.update("UPDATE qw_query_run SET task_budget_unit_ms=NULL WHERE run_id=?", legacy.runId());
        assertEquals(legacy.deadlineEpochMillis(), allocate(legacy, 3).deadlineEpochMillis());
    }

    @Test void expiredOrSupersededAttemptCannotPurchaseExtraTime() {
        QueryRun original = create(null);
        assertThrows(LateRunResultDroppedException.class, () -> tx.execute(ignored -> service(300_000)
            .allocateTaskDeadline(original.runId(), "obsolete-attempt", 2)));
        jdbc.update("UPDATE qw_query_run SET deadline_epoch_millis=? WHERE run_id=?", System.currentTimeMillis() - 1, original.runId());
        assertThrows(RunDeadlineExceededException.class, () -> allocate(original, 2));
        assertNull(repository.taskBudget(original.runId()).taskCount());
    }

    @Test void humanWaitPreservesRemainingMultiTaskBudgetAndDoesNotAllocateAgain() {
        QueryRun original = create(null);
        QueryRun allocated = allocate(original, 2);
        tx.execute(ignored -> service(300_000).transition(original.runId(), QueryRun.RunStatus.WAITING_HUMAN, "approval", null, null));
        long remaining = jdbc.queryForObject("SELECT paused_execution_remaining_ms FROM qw_query_run WHERE run_id=?", Long.class, original.runId());
        long humanWindow = jdbc.queryForObject("SELECT human_wait_deadline_ms-human_wait_started_ms FROM qw_query_run WHERE run_id=?", Long.class, original.runId());
        assertEquals(86_400_000L, humanWindow);
        assertNull(service(300_000).get(original.runId()).deadlineEpochMillis());
        assertTrue(remaining <= 600_000 && remaining > 590_000);
        long simulatedResume = System.currentTimeMillis() + 3_600_000;
        tx.executeWithoutResult(ignored -> repository.restoreExecutionDeadline(original.runId(), simulatedResume));
        tx.execute(ignored -> service(300_000).transition(original.runId(), QueryRun.RunStatus.RUNNING, "resume", null, null));
        assertEquals(simulatedResume + remaining, allocate(original, 2).deadlineEpochMillis());
        assertTrue(allocated.deadlineEpochMillis() < simulatedResume + remaining);
    }
}
