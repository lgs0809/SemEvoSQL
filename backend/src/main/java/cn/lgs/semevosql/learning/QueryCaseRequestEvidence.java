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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the entire request's durable evidence; never promotes a successful SQL in isolation. */
public final class QueryCaseRequestEvidence {
    private final JdbcTemplate jdbc;

    public QueryCaseRequestEvidence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Also used on every recall, so a previously approved case cannot outlive negative feedback. */
    public boolean eligible(String runId) {
        if (runId == null || runId.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM qw_query_run r
                WHERE r.run_id = ? AND r.status = 'SUCCEEDED'
                  AND NOT EXISTS (SELECT 1 FROM qw_query_task t WHERE t.run_id = r.run_id
                      AND (t.status <> 'DONE' OR COALESCE(t.semantic_plan_json->>'executable', '') <> 'true'
                           OR t.result_summary_json IS NULL OR COALESCE(t.review_json->>'decision', '') <> 'PASS'))
                  AND NOT EXISTS (SELECT 1 FROM qw_source_sub_run s WHERE s.run_id = r.run_id
                      AND s.status NOT IN ('COMPLETED','FAILED','CANCELLED'))
                  AND NOT EXISTS (SELECT 1 FROM qw_sql_execution_attempt a WHERE a.run_id = r.run_id
                      AND a.status NOT IN ('SUCCEEDED','FAILED'))
                  AND NOT EXISTS (SELECT 1 FROM qw_feedback f WHERE f.episode_id = r.episode_id
                      AND (f.adopted IS FALSE OR f.rating < 3))
                  AND NOT EXISTS (SELECT 1 FROM qw_run_event e WHERE e.run_id = r.run_id
                      AND e.event_type IN ('QUERY_BINDING_CORRECTED', 'SEMANTIC_DEFINITION_CORRECTION_PROPOSED',
                                           'REQUEST_REQUIREMENT_CORRECTED'))
                  AND NOT EXISTS (SELECT 1 FROM qw_runtime_clarification c WHERE c.run_id = r.run_id
                      AND c.status = 'PENDING')
                  AND EXISTS (SELECT 1 FROM qw_result_artifact a WHERE a.run_id = r.run_id
                      AND a.artifact_type IN ('MERGED_RESULT', 'DIRECT_RESULT') AND a.status = 'READY')
                  AND (NOT EXISTS (SELECT 1 FROM qw_query_task t WHERE t.run_id = r.run_id)
                       OR EXISTS (SELECT 1 FROM qw_run_event e WHERE e.run_id = r.run_id
                                  AND e.event_type = 'REQUEST_SYNTHESIS' AND LENGTH(TRIM(e.payload)) > 0))
            )
            """, Boolean.class, runId)) && expectedTasksPresent(runId) && finalReviewPassed(runId)
            && !jdbc.queryForList(cn.lgs.semevosql.multisource.ResultArtifactSelection.FINAL_RESULT_SQL, runId, runId).isEmpty();
    }

    private boolean expectedTasksPresent(String runId) {
        var rows = jdbc.queryForList("""
            SELECT payload FROM qw_run_event WHERE run_id=? AND event_type='REQUEST_ANALYSIS_COMPLETED'
            ORDER BY sequence DESC LIMIT 1
            """, String.class, runId);
        if (rows.isEmpty()) return true; // Legacy single-query requests predate request analysis.
        JsonNode analysis = read(rows.get(0));
        if (!analysis.path("needsTodo").asBoolean()) return true;
        JsonNode expected = analysis.path("tasks");
        if (!expected.isArray() || expected.size() < 2) return false;
        var actual = jdbc.queryForList("SELECT task_id FROM qw_query_task WHERE run_id=?", String.class, runId);
        return actual.size() == expected.size() && java.util.stream.StreamSupport.stream(expected.spliterator(), false)
            .allMatch(task -> actual.contains(task.path("taskId").asText()));
    }

    private boolean finalReviewPassed(String runId) {
        var rows = jdbc.queryForList("""
            SELECT payload FROM qw_run_event WHERE run_id = ? AND event_type = 'POST_EXECUTION_REVIEW'
            ORDER BY sequence DESC LIMIT 1
            """, String.class, runId);
        if (rows.isEmpty()) return false;
        return "PASS".equals(read(rows.get(0)).path("review").path("decision").asText());
    }

    /** References retain all attempts, including errors; no model-generated trajectory or result rows. */
    public Map<String, Object> snapshot(String runId) {
        var runs = jdbc.queryForList("""
            SELECT run_id, episode_id, attempt_id AS final_attempt_id, revision AS request_revision,
                   project_id, project_version_id, status
            FROM qw_query_run WHERE run_id = ?
            """, runId);
        if (runs.size() != 1) throw new IllegalArgumentException("Case request does not exist");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("request", runs.get(0));
        evidence.put("tasks", jdbc.queryForList("""
            SELECT task_id, ordinal_no, question, dependencies_json::text AS dependencies_json, status, revision,
                   semantic_plan_json::text AS semantic_plan_json, review_json::text AS review_json
            FROM qw_query_task WHERE run_id = ? ORDER BY ordinal_no
            """, runId));
        evidence.put("attempts", jdbc.queryForList("""
            SELECT a.id, a.attempt_no, a.status, a.error_type FROM qw_attempt a
            JOIN qw_query_run r ON r.episode_id = a.episode_id WHERE r.run_id = ? ORDER BY a.attempt_no
            """, runId));
        evidence.put("sqlTraces", jdbc.queryForList("""
            SELECT s.id, s.attempt_id, s.status, s.sql_text, s.error_type, s.retry_count
            FROM qw_sql_trace s JOIN qw_attempt a ON a.id = s.attempt_id
            JOIN qw_query_run r ON r.episode_id = a.episode_id
            WHERE r.run_id = ? ORDER BY a.attempt_no, s.create_time, s.id
            """, runId));
        evidence.put("sqlExecutionAttempts", jdbc.queryForList("""
            SELECT sql_attempt_id,graph_attempt_id,scope_key,phase,input_hash,datasource_id,status,
                   deadline_epoch_ms,backend_session_id,error_json
            FROM qw_sql_execution_attempt WHERE run_id=? ORDER BY create_time,sql_attempt_id
            """,runId));
        evidence.put("sourceExecutions", jdbc.queryForList("""
            SELECT sub_run_id, execution_key, datasource_id, status, sql_text, result_artifact_id, row_count
            FROM qw_source_sub_run WHERE run_id = ? ORDER BY create_time, sub_run_id
            """, runId));
        evidence.put("artifacts", jdbc.queryForList("""
            SELECT artifact_id, source_sub_run_id, artifact_type, content_hash, row_count, status
            FROM qw_result_artifact WHERE run_id = ? ORDER BY create_time, artifact_id
            """, runId));
        evidence.put("events", jdbc.queryForList("""
            SELECT sequence, event_type, node_name FROM qw_run_event WHERE run_id = ? AND event_type IN
              ('SEMANTIC_PLAN_SNAPSHOT', 'POST_EXECUTION_REVIEW', 'QUERY_REPAIR_DECISION',
               'REQUEST_SYNTHESIS', 'HUMAN_FEEDBACK_APPLIED', 'HUMAN_FEEDBACK_ANSWERED',
               'QUERY_BINDING_CORRECTED', 'SEMANTIC_DEFINITION_CORRECTION_PROPOSED',
               'REQUEST_REQUIREMENT_CORRECTED', 'REQUEST_ENHANCEMENT_COMPLETED')
            ORDER BY sequence
            """, runId));
        evidence.put("confirmations", jdbc.queryForList("""
            SELECT c.clarification_id, c.status, a.id AS answer_id, a.clarification_revision AS answer_revision
            FROM qw_runtime_clarification c LEFT JOIN qw_runtime_clarification_answer a
              ON a.clarification_id = c.clarification_id WHERE c.run_id = ? ORDER BY c.create_time
            """, runId));
        evidence.put("historicalRecallReferences", jdbc.queryForList("""
            SELECT snapshot_id,recall_key FROM qw_query_case_recall_snapshot WHERE run_id=? ORDER BY create_time,snapshot_id
            """, runId));
        var enhancements = jdbc.queryForList("""
            SELECT payload FROM qw_run_event WHERE run_id = ? AND event_type = 'REQUEST_ENHANCEMENT_COMPLETED'
            ORDER BY sequence
            """, String.class, runId);
        for (String payload : enhancements) {
            JsonNode node = read(payload);
            String canonical = node.path("enhancement").path("canonicalQuery").asText();
            if (!canonical.isBlank() && "READY".equals(node.path("enhancement").path("status").asText())) {
                evidence.put("originalQuery", node.path("originalQuery").asText());
                evidence.put("rootCanonicalQuery", canonical);
                break;
            }
        }
        evidence.put("eligibleAtCapture", eligible(runId));
        return evidence;
    }

    static JsonNode read(String value) {
        try { return JsonUtil.getObjectMapper().readTree(value == null ? "{}" : value); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid durable query-case evidence", invalid); }
    }
}
