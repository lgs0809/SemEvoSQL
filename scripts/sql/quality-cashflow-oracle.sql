-- Read-only independent oracle for the named synthetic quality business database.
-- Never supplied to the model, and never writes query/approval/success records.
WITH payment AS (
    SELECT COALESCE(SUM(amount), 0) AS payment_amount
    FROM public.orders
    WHERE status IN ('PAID', 'REFUNDED')
      AND paid_at >= TIMESTAMP '2026-01-01 00:00:00'
      AND paid_at < TIMESTAMP '2026-04-01 00:00:00'
), refund AS (
    SELECT COALESCE(SUM(amount), 0) AS refund_amount
    FROM public.refunds
    WHERE status = 'SUCCESS'
      AND refunded_at >= TIMESTAMP '2026-01-01 00:00:00'
      AND refunded_at < TIMESTAMP '2026-04-01 00:00:00'
)
SELECT payment_amount, refund_amount, payment_amount - refund_amount AS cash_flow
FROM payment CROSS JOIN refund;
