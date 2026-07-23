-- Freeze the unit at request creation. NULL preserves legacy or caller-supplied deadlines.
ALTER TABLE qw_query_run ADD COLUMN task_budget_unit_ms BIGINT;
ALTER TABLE qw_query_run ADD COLUMN task_budget_count INTEGER;
ALTER TABLE qw_query_run ADD CONSTRAINT qw_query_run_task_budget_unit_positive
    CHECK (task_budget_unit_ms IS NULL OR task_budget_unit_ms > 0);
ALTER TABLE qw_query_run ADD CONSTRAINT qw_query_run_task_budget_count_valid
    CHECK (task_budget_count IS NULL OR (task_budget_count BETWEEN 1 AND 12 AND task_budget_unit_ms IS NOT NULL));
