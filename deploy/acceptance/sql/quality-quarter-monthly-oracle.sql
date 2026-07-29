-- Independent business reference; never supplied to the querying model.
-- All statuses, order creation time, yuan; PostgreSQL SUM keeps normal NULL semantics.
SELECT date_trunc('month',ordered_at)::date AS month,
       SUM(amount) AS order_amount,
       COUNT(*) AS order_count
FROM public.orders
WHERE ordered_at >= TIMESTAMP '2026-01-01'
  AND ordered_at < TIMESTAMP '2026-04-01'
GROUP BY 1 ORDER BY 1;
