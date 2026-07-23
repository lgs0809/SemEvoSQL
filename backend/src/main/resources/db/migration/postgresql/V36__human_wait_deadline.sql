ALTER TABLE qw_query_run ADD COLUMN human_wait_started_ms BIGINT;
ALTER TABLE qw_query_run ADD COLUMN human_wait_deadline_ms BIGINT;
ALTER TABLE qw_query_run ADD COLUMN paused_execution_remaining_ms BIGINT;
CREATE INDEX idx_qw_run_human_wait_deadline ON qw_query_run(human_wait_deadline_ms)
    WHERE status = 'WAITING_HUMAN';
