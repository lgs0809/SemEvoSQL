-- Independent read-only oracle for the explicitly confirmed 60% personal definition.
-- Execute on semevosql_acceptance_business; this query is never supplied to the model.
SELECT SUM(amount) * CAST(0.6 AS numeric) AS personal_share_amount
FROM orders
WHERE ordered_at >= TIMESTAMP '2026-01-01 00:00:00'
  AND ordered_at < TIMESTAMP '2026-02-01 00:00:00';
