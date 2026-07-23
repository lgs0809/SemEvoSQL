CREATE TABLE qw_query_pattern_template (
    id VARCHAR(64) PRIMARY KEY,
    pattern_id VARCHAR(64) NOT NULL REFERENCES qw_query_pattern(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    project_version_id BIGINT NOT NULL,
    catalog_hash CHAR(64) NOT NULL,
    execution_shape_hash CHAR(64) NOT NULL,
    datasource_id INTEGER NOT NULL,
    reuse_mode VARCHAR(32) NOT NULL,
    plan_template_json JSONB NOT NULL,
    sql_template TEXT,
    parameter_count INTEGER NOT NULL DEFAULT 0,
    source_run_id VARCHAR(64),
    source_attempt_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    usage_count BIGINT NOT NULL DEFAULT 0,
    invalidation_reason VARCHAR(1000),
    last_used_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qw_query_pattern_template UNIQUE(project_version_id, execution_shape_hash, datasource_id)
);

CREATE INDEX idx_qw_query_pattern_template_lookup
    ON qw_query_pattern_template(project_id, project_version_id, execution_shape_hash, datasource_id, status);
CREATE INDEX idx_qw_query_pattern_template_source_run
    ON qw_query_pattern_template(source_run_id, status);
