ALTER TABLE qw_source_sub_run
    ADD COLUMN execution_key VARCHAR(128) NOT NULL DEFAULT 'legacy';

ALTER TABLE qw_merge_execution
    ADD COLUMN execution_key VARCHAR(128) NOT NULL DEFAULT 'legacy';

CREATE UNIQUE INDEX uk_qw_source_sub_run_execution_source
    ON qw_source_sub_run(run_id, execution_key, datasource_id);

CREATE UNIQUE INDEX uk_qw_merge_execution_attempt
    ON qw_merge_execution(run_id, execution_key);

CREATE INDEX idx_qw_source_sub_run_execution_status
    ON qw_source_sub_run(run_id, execution_key, status, datasource_id);
