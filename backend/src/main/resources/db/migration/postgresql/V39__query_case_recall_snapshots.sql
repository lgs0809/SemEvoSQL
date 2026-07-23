-- A content revision includes the executable evidence, not mutable recall/adoption counters.
CREATE OR REPLACE FUNCTION qw_query_case_source_hash(q qw_query_example) RETURNS text
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT md5(jsonb_build_array(q.original_question, q.normalized_question, q.quality_proof_json,
     q.typed_ir_json, q.sql_text, q.sql_hash, q.datasource_id, q.intent_type,
     q.catalog_hash, q.project_id, q.project_version_id,
     q.context_hash, q.conversation_independent, q.run_id)::text)
$$;

-- Frozen details are kept off the public Run event stream. Consumers recheck current authority.
CREATE TABLE qw_query_case_recall_snapshot (
    snapshot_id varchar(64) PRIMARY KEY,
    run_id varchar(64) NOT NULL REFERENCES qw_query_run(run_id) ON DELETE CASCADE,
    recall_key varchar(255) NOT NULL,
    project_id bigint NOT NULL,
    project_version_id bigint NOT NULL,
    catalog_hash varchar(64) NOT NULL,
    principal_id varchar(255),
    snapshot_json jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, recall_key)
);
CREATE INDEX idx_qw_case_recall_snapshot_run ON qw_query_case_recall_snapshot(run_id, create_time);
