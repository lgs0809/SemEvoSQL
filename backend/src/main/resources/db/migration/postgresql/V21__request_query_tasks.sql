CREATE TABLE qw_query_task (
    run_id VARCHAR(64) NOT NULL REFERENCES qw_query_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(64) NOT NULL,
    ordinal_no INTEGER NOT NULL,
    question TEXT NOT NULL,
    dependencies_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    semantic_plan_json JSONB,
    result_summary_json JSONB,
    review_json JSONB,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time TIMESTAMP,
    PRIMARY KEY (run_id, task_id),
    CONSTRAINT uk_qw_query_task_ordinal UNIQUE (run_id, ordinal_no)
);
CREATE INDEX idx_qw_query_task_status ON qw_query_task(run_id, status, ordinal_no);

CREATE TABLE qw_request_context_fact (
    fact_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES qw_query_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(64),
    fact_type VARCHAR(64) NOT NULL,
    fact_key VARCHAR(255) NOT NULL,
    fact_json JSONB NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qw_request_context_fact UNIQUE (run_id, fact_type, fact_key, source_type)
);
CREATE INDEX idx_qw_request_context_fact_run ON qw_request_context_fact(run_id, create_time);
