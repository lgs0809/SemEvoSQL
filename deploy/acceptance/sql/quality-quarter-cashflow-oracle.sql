-- Independent reference for an explicitly confirmed cash-flow meaning.
-- Payment uses paid_at and PAID/REFUNDED; refund uses actual refunded_at and SUCCESS.
-- The two independently aggregated populations are not joined at row level.
SELECT
  (SELECT SUM(amount) FROM public.orders
    WHERE status IN ('PAID','REFUNDED')
      AND paid_at >= TIMESTAMP '2026-01-01' AND paid_at < TIMESTAMP '2026-04-01') AS payment_amount,
  (SELECT SUM(amount) FROM public.refunds
    WHERE status='SUCCESS'
      AND refunded_at >= TIMESTAMP '2026-01-01' AND refunded_at < TIMESTAMP '2026-04-01') AS refund_amount,
  (SELECT SUM(amount) FROM public.orders
    WHERE status IN ('PAID','REFUNDED')
      AND paid_at >= TIMESTAMP '2026-01-01' AND paid_at < TIMESTAMP '2026-04-01')
  - (SELECT SUM(amount) FROM public.refunds
    WHERE status='SUCCESS'
      AND refunded_at >= TIMESTAMP '2026-01-01' AND refunded_at < TIMESTAMP '2026-04-01') AS net_payment_amount;
