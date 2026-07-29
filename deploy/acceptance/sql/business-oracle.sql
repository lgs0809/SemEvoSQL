-- January paid gross: 华东150, 华南80, missing region40; total270; net250.
SELECT coalesce(e.region,'未知地区') AS region, sum(o.amount) AS paid_gross
FROM orders o JOIN customers c USING(customer_id)
LEFT JOIN customer_extensions e USING(customer_id)
WHERE o.status='PAID' AND o.paid_at>='2026-01-01' AND o.paid_at<'2026-02-01'
GROUP BY e.region ORDER BY region;
SELECT date_trunc('month',paid_at)::date AS month, sum(amount) AS paid_gross
FROM orders WHERE status='PAID' GROUP BY 1 ORDER BY 1;
SELECT sum(o.amount)-coalesce(sum(r.amount),0) AS january_net
FROM orders o LEFT JOIN (SELECT order_id,sum(amount) amount FROM refunds WHERE status='SUCCESS' GROUP BY order_id) r USING(order_id)
WHERE o.status='PAID' AND paid_at>='2026-01-01' AND paid_at<'2026-02-01';
