DROP INDEX IF EXISTS uk_qw_query_task_child_run;
ALTER TABLE qw_query_task DROP COLUMN IF EXISTS task_run_id;
