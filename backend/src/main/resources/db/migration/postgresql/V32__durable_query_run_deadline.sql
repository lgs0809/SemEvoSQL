-- Persist one absolute wall-clock deadline for each interactive Query Run.  New rows receive the
-- configured deadline in QueryRunService; the historical backfill uses the release default so an
-- already-running row is never granted an unbounded or freshly-reset recovery window.
ALTER TABLE qw_query_run
    ADD COLUMN deadline_epoch_millis BIGINT;

UPDATE qw_query_run
SET deadline_epoch_millis = CAST(
        EXTRACT(EPOCH FROM (COALESCE(start_time, create_time) + INTERVAL '5 minutes')) * 1000 AS BIGINT)
WHERE run_type = 'INTERACTIVE_QUERY'
  AND status IN ('QUEUED', 'RUNNING', 'WAITING_HUMAN')
  AND deadline_epoch_millis IS NULL;
