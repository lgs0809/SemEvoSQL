-- Independent reference for the synthetic quality business only.
-- Not loaded into the semantic catalog or shown to the query model.
-- All order statuses; successful refunded order IDs counted once, regardless of
-- refund time, attributed to the order's month. Percent output rounded to 2 dp.
BEGIN READ ONLY;
SELECT to_char(date_trunc('month', o.ordered_at), 'YYYY-MM') AS order_month,
       count(*) AS total_order_count,
       count(*) FILTER (WHERE EXISTS (
         SELECT 1 FROM refunds r WHERE r.order_id=o.order_id AND r.status='SUCCESS'
       )) AS successfully_refunded_order_count,
       round(100.0 * count(*) FILTER (WHERE EXISTS (
         SELECT 1 FROM refunds r WHERE r.order_id=o.order_id AND r.status='SUCCESS'
       )) / nullif(count(*),0), 2) AS refunded_order_rate_percent
FROM orders o
WHERE o.ordered_at >= timestamp '2026-01-01' AND o.ordered_at < timestamp '2026-04-01'
GROUP BY date_trunc('month', o.ordered_at)
ORDER BY date_trunc('month', o.ordered_at);
ROLLBACK;
