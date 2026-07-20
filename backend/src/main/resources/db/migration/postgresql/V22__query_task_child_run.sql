ALTER TABLE qw_query_task
    ADD COLUMN task_run_id VARCHAR(64) REFERENCES qw_query_run(run_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uk_qw_query_task_child_run
    ON qw_query_task(task_run_id)
    WHERE task_run_id IS NOT NULL;
