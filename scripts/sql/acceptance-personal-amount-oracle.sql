-- Read-only numeric oracle for the synthetic acceptance orders, never supplied to the model.
-- Use semevosql_acceptance_business; January uses the half-open creation-time interval.
BEGIN TRANSACTION READ ONLY;
SELECT count(*) AS january_orders,
       SUM(amount) AS january_order_amount,
       SUM(amount) * 0.7 AS personal_revision_6_amount
FROM orders
WHERE ordered_at >= TIMESTAMP '2026-01-01 00:00:00'
  AND ordered_at < TIMESTAMP '2026-02-01 00:00:00';
ROLLBACK;
