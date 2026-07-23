CREATE TABLE qw_sql_execution_attempt (
    sql_attempt_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES qw_query_run(run_id) ON DELETE CASCADE,
    graph_attempt_id VARCHAR(64) NOT NULL,
    owner_instance VARCHAR(36) NOT NULL,
    scope_key TEXT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    datasource_id INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PREPARED','RUNNING','RECOVERING','SUCCEEDED','FAILED','UNCERTAIN')),
    deadline_epoch_ms BIGINT,
    backend_session_id BIGINT,
    result_json JSONB,
    error_json JSONB,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id,scope_key,phase)
);
CREATE INDEX idx_qw_sql_attempt_run_status ON qw_sql_execution_attempt(run_id,status);
