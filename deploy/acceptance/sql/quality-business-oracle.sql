-- Hand-computable slice (order ids 1..8), separate from model/catalog inputs.
SELECT to_char(ordered_at,'YYYY-MM'),COALESCE(sum(amount),0)::numeric(12,2)
FROM orders WHERE order_id BETWEEN 1 AND 8 GROUP BY 1 ORDER BY 1;
SELECT sum(amount)::numeric(12,2) FROM orders WHERE order_id BETWEEN 1 AND 8
AND ordered_at>='2026-01-01' AND ordered_at<'2026-02-01';
SELECT sum(amount)::numeric(12,2) FROM orders WHERE order_id BETWEEN 1 AND 8
AND paid_at>='2026-01-01' AND paid_at<'2026-02-01';
SELECT sum(o.amount)-COALESCE((SELECT sum(r.amount) FROM refunds r JOIN orders q ON q.order_id=r.order_id
 WHERE q.order_id BETWEEN 1 AND 8 AND q.ordered_at>='2026-01-01' AND q.ordered_at<'2026-02-01'
 AND q.status='PAID' AND r.status='SUCCESS'),0)
FROM orders o WHERE o.order_id BETWEEN 1 AND 8 AND o.ordered_at>='2026-01-01'
AND o.ordered_at<'2026-02-01' AND o.status='PAID';
