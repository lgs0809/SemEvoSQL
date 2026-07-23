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
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable immutable recall snapshots, with authoritative filtering on every read. */
@Repository
public class QueryCaseRecallSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final QueryCaseRepository cases;

    public QueryCaseRecallSnapshotRepository(JdbcTemplate jdbc, QueryCaseRepository cases) {
        this.jdbc = jdbc;
        this.cases = cases;
    }

    public Optional<JsonNode> find(String runId, String key) {
        return jdbc.queryForList("SELECT snapshot_json::text FROM qw_query_case_recall_snapshot WHERE run_id=? AND recall_key=?",
            String.class, runId, key).stream().findFirst().map(QueryCaseRequestEvidence::read);
    }

    public Optional<JsonNode> findById(String runId, String snapshotId) {
        return jdbc.queryForList("SELECT snapshot_json::text FROM qw_query_case_recall_snapshot WHERE run_id=? AND snapshot_id=?",
            String.class, runId, snapshotId).stream().findFirst().map(QueryCaseRequestEvidence::read);
    }

    public JsonNode insert(JsonNode snapshot) {
        jdbc.update("""
            INSERT INTO qw_query_case_recall_snapshot(snapshot_id,run_id,recall_key,project_id,project_version_id,
                catalog_hash,principal_id,snapshot_json) VALUES (?,?,?,?,?,?,?,?::jsonb)
            ON CONFLICT (run_id,recall_key) DO NOTHING
            """, snapshot.path("snapshotId").asText(), snapshot.path("runId").asText(),
            snapshot.path("recallKey").asText(), snapshot.path("projectId").asLong(),
            snapshot.path("projectVersionId").asLong(), snapshot.path("catalogHash").asText(),
            snapshot.path("principalId").isNull() ? null : snapshot.path("principalId").asText(), snapshot.toString());
        return find(snapshot.path("runId").asText(), snapshot.path("recallKey").asText()).orElseThrow();
    }

    /** Status/scope/version checks precede loading any old SQL, including failure details. */
    public boolean current(Long project, Long version, String catalog, String context, String principal,
            String caseId, String revision) {
        var args = new MapSqlParameterSource().addValue("project", project).addValue("version", version)
            .addValue("catalog", catalog).addValue("context", context, java.sql.Types.VARCHAR)
            .addValue("principal", principal, java.sql.Types.VARCHAR)
            .addValue("tokenizer", QueryCaseQuestionIndexRepository.TOKENIZER_VERSION)
            .addValue("id", caseId).addValue("revision", revision);
        boolean eligible = Boolean.TRUE.equals(new NamedParameterJdbcTemplate(jdbc).queryForObject("""
            SELECT EXISTS(SELECT 1 FROM qw_query_example q JOIN qw_query_case_question_index d ON d.query_example_id=q.id
                WHERE q.id=:id AND qw_query_case_source_hash(q)=:revision AND %s)
            """.formatted(QueryCaseQuestionIndexRepository.ELIGIBLE), args, Boolean.class));
        if (!eligible) return false;
        try { return cases.trustedForReuse(cases.require(project, caseId), catalog); }
        catch (IllegalArgumentException missing) { return false; }
    }

    /** Called only after authority is checked; stores the exact observed records, without result rows. */
    public JsonNode freeze(Long project, String caseId, String revision) {
        var rows = jdbc.queryForList("""
            SELECT id,run_id,original_question,normalized_question,intent_type,typed_ir_json::text AS typed_ir_json,
                quality_proof_json::text AS quality_proof_json,sql_text,sql_hash
            FROM qw_query_example q WHERE id=? AND project_id=? AND qw_query_case_source_hash(q)=?
            """, caseId, project, revision);
        if (rows.size() != 1) return null;
        Map<String,Object> row = rows.get(0);
        String runId = row.get("run_id").toString();
        var result = JsonUtil.getObjectMapper().createObjectNode();
        result.put("caseId", caseId).put("caseRevision", revision).put("sourceRunId", runId);
        result.put("originalQuery", java.util.Objects.toString(row.get("original_question"), ""));
        result.put("rootCanonicalQuery", java.util.Objects.toString(row.get("normalized_question"), ""));
        result.set("requestEvidence", JsonUtil.getObjectMapper().valueToTree(new QueryCaseRequestEvidence(jdbc).snapshot(runId)));
        result.set("finalPlan", QueryCaseRequestEvidence.read(java.util.Objects.toString(row.get("typed_ir_json"), "{}")));
        result.set("assetReferences", JsonUtil.getObjectMapper().valueToTree(jdbc.queryForList("""
            SELECT asset_type,asset_key,asset_fingerprint,catalog_hash FROM qw_query_example_asset_ref
            WHERE query_example_id=? ORDER BY asset_type,asset_key
            """, caseId)));
        // These event payloads connect each task to its plans, reviews, corrections and real attempts.
        var events = result.putArray("trajectoryEvents");
        for (var event : jdbc.queryForList("""
            SELECT sequence,event_type,node_name,payload FROM qw_run_event WHERE run_id=? AND event_type IN
              ('TODO_ACTIVATED','TODO_COMPLETED','SEMANTIC_PLAN_SNAPSHOT','POST_EXECUTION_REVIEW',
               'QUERY_REPAIR_DECISION','HUMAN_FEEDBACK_APPLIED','HUMAN_FEEDBACK_ANSWERED',
               'REQUEST_REQUIREMENT_CORRECTED','REQUEST_ENHANCEMENT_COMPLETED') ORDER BY sequence
            """, runId)) {
            var value = events.addObject();
            value.put("sequence", ((Number)event.get("sequence")).longValue());
            value.put("eventType", event.get("event_type").toString());
            String payload = java.util.Objects.toString(event.get("payload"), "");
            try { value.set("payload", JsonUtil.getObjectMapper().readTree(payload)); }
            catch (Exception textPayload) { value.put("payloadText", payload); }
        }
        result.set("confirmations", JsonUtil.getObjectMapper().valueToTree(jdbc.queryForList("""
            SELECT c.clarification_id,c.question,c.status,c.issue_type,c.selected_option,c.custom_answer,
                c.resolved_value,a.id AS answer_id,a.clarification_revision AS answer_revision
            FROM qw_runtime_clarification c LEFT JOIN qw_runtime_clarification_answer a ON a.clarification_id=c.clarification_id
            WHERE c.run_id=? ORDER BY c.create_time
            """, runId)));
        result.set("sourceExecutionDetails", JsonUtil.getObjectMapper().valueToTree(jdbc.queryForList("""
            SELECT sub_run_id,execution_key,status,sql_text,error_message,result_artifact_id
            FROM qw_source_sub_run WHERE run_id=? ORDER BY create_time,sub_run_id
            """, runId)));
        return result;
    }
}
