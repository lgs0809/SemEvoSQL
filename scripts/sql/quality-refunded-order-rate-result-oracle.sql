-- Read-only SELECT for exact final-output comparison; never part of model inputs.
-- All order statuses, distinct successfully refunded orders, order-month attribution.
SELECT date_trunc('month', o.ordered_at) AS ordered_at_month,
       round(100.0 * count(*) FILTER (WHERE EXISTS (
         SELECT 1 FROM refunds r WHERE r.order_id=o.order_id AND r.status='SUCCESS'
       )) / nullif(count(*),0), 2) AS refunded_order_rate_percent
FROM orders o
WHERE o.ordered_at >= timestamp '2026-01-01' AND o.ordered_at < timestamp '2026-04-01'
GROUP BY date_trunc('month', o.ordered_at)
ORDER BY date_trunc('month', o.ordered_at);
