-- Independent evaluator-only oracle for the named isolated synthetic fixture.
-- Never supplied to the planner, semantic retrieval or model prompt.
SELECT COUNT(DISTINCT order_id) AS expected_count
FROM public.orders
WHERE status IN ('PAID', 'REFUNDED')
  AND ordered_at >= TIMESTAMP '2026-01-01 00:00:00'
  AND ordered_at < TIMESTAMP '2026-02-01 00:00:00';
