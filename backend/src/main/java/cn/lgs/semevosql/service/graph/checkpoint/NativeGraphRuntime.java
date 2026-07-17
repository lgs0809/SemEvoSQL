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

import static cn.lgs.semevosql.constant.Constant.*;

import cn.lgs.semevosql.run.*;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One immutable graph identity per Run, with a durable generation fence for every native write. */
@Component
public class NativeGraphRuntime implements BaseCheckpointSaver {
    public static final String DEFINITION_VERSION = "semevosql-native-v1";
    private static final String TOKEN = "semevosql.executionGeneration";
    private final DatabasePostgresSaver delegate;
    private final JdbcTemplate jdbc;
    private final QueryRunService runs;
    private final TransactionTemplate transactions;

    public NativeGraphRuntime(DataSource datasource, JdbcTemplate jdbc, QueryRunService runs,
            PlatformTransactionManager manager) throws java.sql.SQLException {
        this.delegate = new DatabasePostgresSaver(datasource, new DurableGraphStateSerializer());
        this.jdbc = jdbc;
        this.runs = runs;
        this.transactions = new TransactionTemplate(manager);
    }

    public Optional<RunnableConfig> existing(String runId) {
        var bindings = jdbc.queryForList("SELECT * FROM qw_native_graph_binding WHERE run_id = ?", runId);
        if (bindings.isEmpty()) return Optional.empty();
        var binding = bindings.get(0);
        requireVersion(binding);
        return Optional.of(RunnableConfig.builder().threadId(binding.get("graph_thread_id").toString()).build());
    }

    /** Called once after acquiring the Run lease, never when merely observing/reconnecting to a stream. */
    public RunnableConfig claim(QueryRun expected) {
        return transactions.execute(status -> {
            QueryRun run = runs.lockForUpdate(expected.runId());
            requireOwner(run, expected.attemptId(), false);
            jdbc.update("""
                INSERT INTO qw_native_graph_binding
                (run_id, graph_thread_id, definition_version, state_schema_version)
                VALUES (?, ?, ?, ?) ON CONFLICT (run_id) DO NOTHING
                """, run.runId(), UUID.randomUUID(), DEFINITION_VERSION, DurableGraphStateSerializer.SCHEMA_VERSION);
            var config = existing(run.runId()).orElseThrow();
            Long generation = jdbc.queryForObject("""
                UPDATE qw_native_graph_binding SET execution_generation = execution_generation + 1
                WHERE run_id = ? RETURNING execution_generation
                """, Long.class, run.runId());
            return RunnableConfig.builder(config).addMetadata(TOKEN, generation).build();
        });
    }

    private void requireVersion(Map<String,Object> binding) {
        if (!DEFINITION_VERSION.equals(binding.get("definition_version"))
                || ((Number) binding.get("state_schema_version")).intValue() != DurableGraphStateSerializer.SCHEMA_VERSION)
            throw new IllegalStateException("Native Graph definition/state version is incompatible; recovery is paused");
    }

    private Map<String,Object> binding(RunnableConfig config) {
        var rows = jdbc.queryForList("SELECT * FROM qw_native_graph_binding WHERE graph_thread_id = ?",
                UUID.fromString(config.threadId().orElseThrow()));
        if (rows.size() != 1) throw new IllegalStateException("Native Graph identity is not bound to a Run");
        requireVersion(rows.get(0));
        return rows.get(0);
    }

    private void requireOwner(QueryRun run, String attempt, boolean clarificationPause) {
        boolean statusAllowed = run.status() == QueryRun.RunStatus.RUNNING
                || clarificationPause && run.status() == QueryRun.RunStatus.WAITING_HUMAN;
        if (!statusAllowed || attempt == null || !attempt.equals(run.attemptId())
                || !runs.instanceId().equals(run.ownerInstance()) || run.leaseExpireTime() == null
                || !run.leaseExpireTime().isAfter(java.time.LocalDateTime.now()))
            throw new LateRunResultDroppedException("Native checkpoint write no longer owns the Run/attempt/lease");
        if (!clarificationPause && run.deadlineEpochMillis() != null
                && System.currentTimeMillis() >= run.deadlineEpochMillis())
            throw new RunDeadlineExceededException("Interactive Run deadline exhausted before native checkpoint");
    }

    @Override public Collection<Checkpoint> list(RunnableConfig config) { binding(config); return delegate.list(config); }
    @Override public Optional<Checkpoint> get(RunnableConfig config) { binding(config); return delegate.get(config); }
    @Override public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        // Keep the Run row locked until the official saver commits. Ownership changes and cancellation use this lock.
        // The saver uses its own pooled connection; it must not commit the application's business transaction.
        return transactions.execute(status -> {
            var initial = binding(config);
            QueryRun run = runs.lockForUpdate(initial.get("run_id").toString());
            var current = binding(config);
            Object token = config.metadata(TOKEN).orElse(null);
            if (!(token instanceof Number number) || number.longValue()
                    != ((Number)current.get("execution_generation")).longValue())
                throw new LateRunResultDroppedException("Native checkpoint execution generation is stale");
            if (!run.runId().equals(checkpoint.getState().get(RUN_ID)))
                throw new LateRunResultDroppedException("Native checkpoint state belongs to another Run");
            String pending = Objects.toString(checkpoint.getState().get("native_clarification_id"), "");
            boolean pause = !pending.isBlank() && jdbc.queryForObject("""
                SELECT COUNT(*) FROM qw_runtime_clarification
                WHERE run_id = ? AND clarification_id = ? AND status = 'PENDING'
                """, Long.class, run.runId(), pending) == 1;
            requireOwner(run, (String)checkpoint.getState().get(ATTEMPT_ID), pause);
            try { return delegate.put(config, checkpoint); }
            catch (Exception error) { throw new IllegalStateException("Native checkpoint persistence failed", error); }
        });
    }
    @Override public Tag release(RunnableConfig config) {
        throw new UnsupportedOperationException("Run checkpoints are retained; conversation start must never release them");
    }
}
