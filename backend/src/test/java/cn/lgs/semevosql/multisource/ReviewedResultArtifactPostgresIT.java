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
package cn.lgs.semevosql.multisource;

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.run.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real database/event/attempt fences in a disposable fixture; no model quality claims. */
@Testcontainers
class ReviewedResultArtifactPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    MultiSourceRunService service;
    QueryRunService runs;
    String run, attempt;

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified", PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach void setup() {
        run = UUID.randomUUID().toString(); attempt = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key,attempt_id) VALUES (?,'INTERACTIVE_QUERY','RUNNING',?,?)", run, run, attempt);
        runs = new QueryRunService(new QueryRunRepository(jdbc), "fixture");
        service = new MultiSourceRunService(jdbc, new MultiSourceMergeEngine(), runs, new RunExecutionFenceService(runs));
    }

    ResultSetBO result(String amount) {
        return ResultSetBO.builder().column(List.of("revenue")).data(List.of(Map.of("revenue", amount))).build();
    }

    MultiSourceRunService.ResultArtifact accept(String amount, String review) {
        return tx.execute(ignored -> service.acceptReviewedResult(run, result(amount), attempt, review));
    }

    String merged(String amount) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO qw_result_artifact(artifact_id,run_id,artifact_type,schema_json,data_json,row_count,content_hash,status)
            VALUES (?,?,'MERGED_RESULT','["revenue"]',?::jsonb,1,?,'READY')
            """, id, run, "[{\"revenue\":\"" + amount + "\"}]", "a".repeat(64));
        return id;
    }

    @Test void acceptedPhysicalReplacementOutranksOldMergeAcrossRestart() {
        String old = merged("100");
        assertEquals(old, accept("100", "first-review").artifactId());
        var replacement = accept("90", "repaired-review");
        assertNotEquals(old, replacement.artifactId());
        assertEquals("DIRECT_RESULT", replacement.artifactType());
        // A new service instance must resolve the same durable acceptance receipt.
        var restarted = new MultiSourceRunService(jdbc, new MultiSourceMergeEngine(), runs, new RunExecutionFenceService(runs));
        assertEquals(replacement.artifactId(), restarted.mergedArtifact(run).orElseThrow().artifactId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM qw_result_artifact WHERE run_id=?", Integer.class, run));
        assertEquals(old, accept("100", "second-repair-to-original").artifactId());
        assertEquals(old, service.mergedArtifact(run).orElseThrow().artifactId());
    }

    @Test void concurrentReplayIsIdempotentAndDifferentContentInSameAttemptIsImmutable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Future<String>>();
            for (int n = 0; n < 4; n++) calls.add(pool.submit(() -> accept("100", "same-effect").artifactId()));
            Set<String> ids = new HashSet<>();
            for (var call : calls) ids.add(call.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM qw_run_event WHERE run_id=?", Integer.class, run));
            assertNotEquals(ids.iterator().next(), accept("90", "new-effect").artifactId());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM qw_result_artifact WHERE run_id=?", Integer.class, run));
        }
        finally { pool.shutdownNow(); }
    }

    @Test void brokenAcceptedPointerDoesNotFallBackToUnreviewedMerge() {
        String old = merged("100");
        var accepted = accept("90", "final");
        jdbc.update("UPDATE qw_result_artifact SET status='FAILED' WHERE artifact_id=?", accepted.artifactId());
        assertTrue(service.mergedArtifact(run).isEmpty());
        assertEquals("READY", service.requireArtifact(old).status());
    }

    @Test void supersededAttemptCannotPublishAResult() {
        jdbc.update("UPDATE qw_query_run SET attempt_id=? WHERE run_id=?", UUID.randomUUID().toString(), run);
        assertThrows(LateRunResultDroppedException.class, () -> accept("100", "late-effect"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM qw_result_artifact WHERE run_id=?", Integer.class, run));
    }
}
