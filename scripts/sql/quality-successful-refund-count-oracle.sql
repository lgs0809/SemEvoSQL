SELECT COUNT(*) AS successful_refund_count
FROM public.refunds
WHERE status = 'SUCCESS'
  AND refunded_at >= TIMESTAMP '2026-01-01'
  AND refunded_at < TIMESTAMP '2026-04-01';
