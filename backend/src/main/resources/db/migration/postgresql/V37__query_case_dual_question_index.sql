-- Additive migration: retain legacy term/vector tables for inspection and rollback.
CREATE SEQUENCE qw_query_case_question_generation;
CREATE FUNCTION qw_query_case_source_hash(q qw_query_example) RETURNS text
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT md5(jsonb_build_array(q.original_question, q.normalized_question, q.quality_proof_json,
     q.typed_ir_json, q.catalog_hash, q.project_id, q.project_version_id,
     q.context_hash, q.conversation_independent, q.run_id)::text)
$$;
CREATE TABLE qw_query_case_question_index (
    query_example_id varchar(64) NOT NULL REFERENCES qw_query_example(id) ON DELETE CASCADE,
    question_type varchar(32) NOT NULL CHECK (question_type IN ('ORIGINAL_QUERY','REWRITTEN_QUERY')),
    question_text text NOT NULL,
    text_hash varchar(64) NOT NULL,
    source_hash varchar(32) NOT NULL,
    tokenizer_version varchar(64) NOT NULL,
    tokenized_text text NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, tokenized_text)) STORED,
    generation bigint NOT NULL DEFAULT nextval('qw_query_case_question_generation'),
    embedding vector,
    embedding_model varchar(255),
    embedding_version varchar(64),
    embedding_dimension integer,
    embedding_claim_token varchar(64),
    embedding_lease_until timestamp,
    retry_count integer NOT NULL DEFAULT 0,
    next_retry_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(64),
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (query_example_id, question_type)
);
CREATE INDEX idx_qw_query_case_question_fts ON qw_query_case_question_index USING gin(search_vector);
CREATE INDEX idx_qw_query_case_question_pending ON qw_query_case_question_index(next_retry_at, embedding_lease_until);
