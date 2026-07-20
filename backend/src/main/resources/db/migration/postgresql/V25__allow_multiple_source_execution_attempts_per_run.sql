ALTER TABLE qw_source_sub_run
    DROP CONSTRAINT IF EXISTS uk_qw_source_sub_run;

ALTER TABLE qw_merge_execution
    DROP CONSTRAINT IF EXISTS uk_qw_merge_execution_run;
