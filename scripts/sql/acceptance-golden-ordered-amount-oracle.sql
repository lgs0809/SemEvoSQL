-- Execute on semevosql_acceptance_business. Read-only, independent of generated SQL.
BEGIN READ ONLY;
SELECT COUNT(*) AS january_order_count, SUM(amount) AS january_ordered_amount
FROM orders
WHERE ordered_at >= TIMESTAMP '2026-01-01 00:00:00'
  AND ordered_at < TIMESTAMP '2026-02-01 00:00:00';
ROLLBACK;
