CREATE TABLE qw_external_query_pre_run_clarification (
    query_id VARCHAR(36) PRIMARY KEY REFERENCES qw_external_query_handle(query_id) ON DELETE CASCADE,
    original_question TEXT NOT NULL,
    issue_type VARCHAR(64),
    question TEXT NOT NULL,
    options_json TEXT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_qw_external_query_pre_run_clarification_updated
    ON qw_external_query_pre_run_clarification(update_time DESC);
