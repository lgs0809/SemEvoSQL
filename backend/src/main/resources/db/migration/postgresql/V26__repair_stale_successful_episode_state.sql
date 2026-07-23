-- Repair Episode/Attempt rows left RUNNING by the historical post-completion
-- trajectory rollback bug. Only successful QueryRuns are authoritative here;
-- failed/cancelled rows are intentionally not inferred by this migration.

UPDATE qw_episode e
SET status = 'SUCCEEDED',
    duration_ms = COALESCE(
        e.duration_ms,
        GREATEST(0, CAST(EXTRACT(EPOCH FROM (r.finish_time - r.start_time)) * 1000 AS BIGINT))
    ),
    update_time = CURRENT_TIMESTAMP
FROM qw_query_run r
WHERE e.id = r.episode_id
  AND e.status = 'RUNNING'
  AND r.status = 'SUCCEEDED'
  AND r.finish_time IS NOT NULL;

UPDATE qw_attempt a
SET status = 'SUCCEEDED',
    error_type = NULL,
    update_time = CURRENT_TIMESTAMP
FROM qw_query_run r
WHERE a.id = r.attempt_id
  AND a.status = 'RUNNING'
  AND r.status = 'SUCCEEDED';
