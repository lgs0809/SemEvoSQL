CREATE TABLE qw_maintenance_lease (
    lease_name VARCHAR(64) PRIMARY KEY,
    owner_instance VARCHAR(160),
    lease_token VARCHAR(64),
    lease_expire_time TIMESTAMP,
    revision BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_qw_maintenance_lease_expiry ON qw_maintenance_lease(lease_expire_time);

INSERT INTO qw_maintenance_lease(lease_name, owner_instance, lease_expire_time, revision)
VALUES ('run-retention', NULL, NULL, 0);

CREATE TABLE qw_run_archive (
    run_id VARCHAR(64) PRIMARY KEY,
    run_type VARCHAR(32) NOT NULL,
    project_id BIGINT,
    project_version_id BIGINT,
    thread_id VARCHAR(160),
    status VARCHAR(32) NOT NULL,
    request_id VARCHAR(160),
    idempotency_key VARCHAR(160) NOT NULL,
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    error_code VARCHAR(128),
    error_message_hash CHAR(64),
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    event_count BIGINT NOT NULL DEFAULT 0,
    node_effect_count BIGINT NOT NULL DEFAULT 0,
    clarification_count BIGINT NOT NULL DEFAULT 0,
    source_sub_run_count BIGINT NOT NULL DEFAULT 0,
    artifact_count BIGINT NOT NULL DEFAULT 0,
    payload_hash CHAR(64) NOT NULL,
    retention_version VARCHAR(32) NOT NULL,
    archived_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_qw_run_archive_project ON qw_run_archive(project_id, project_version_id, archived_time);
CREATE INDEX idx_qw_run_archive_finish ON qw_run_archive(finish_time, status);

CREATE TABLE qw_retention_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(160) NOT NULL,
    owner_instance VARCHAR(160) NOT NULL,
    cutoff_time TIMESTAMP NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    archived_count INTEGER NOT NULL DEFAULT 0,
    deleted_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    error_summary TEXT,
    start_time TIMESTAMP NOT NULL,
    finish_time TIMESTAMP,
    CONSTRAINT uk_qw_retention_batch_idempotency UNIQUE(idempotency_key)
);
CREATE INDEX idx_qw_retention_batch_status ON qw_retention_batch(status, start_time);
