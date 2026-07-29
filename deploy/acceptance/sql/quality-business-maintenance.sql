-- Only run after seed-quality-business.py verifies the isolated SEMEVOSQL_QUALITY_V1 fixture identity.
-- Refresh all fixture statistics after idempotent bulk seeding; no rows or query/approval state are changed.
ANALYZE public.customers;
ANALYZE public.products;
ANALYZE public.orders;
ANALYZE public.order_items;
ANALYZE public.refunds;
