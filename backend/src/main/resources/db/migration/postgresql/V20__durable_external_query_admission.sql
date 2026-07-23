ALTER TABLE qw_external_query_handle
    ADD COLUMN original_question TEXT;

UPDATE qw_external_query_handle h
SET original_question = p.original_question
FROM qw_external_query_pre_run_clarification p
WHERE p.query_id = h.query_id
  AND h.original_question IS NULL;

CREATE INDEX idx_qw_external_query_handle_admission
    ON qw_external_query_handle(state, update_time);
