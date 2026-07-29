-- Synthetic quality fixture v1. Only the dedicated semevosql_quality_business_v1 database.
-- Fixed arithmetic seed 20261001; never update/delete existing business rows.
BEGIN;
SELECT pg_advisory_xact_lock(20261001);
CREATE TABLE IF NOT EXISTS fixture_identity (
 id int PRIMARY KEY CHECK(id=1), fixture text NOT NULL CHECK(fixture='SEMEVOSQL_QUALITY_V1'));
INSERT INTO fixture_identity VALUES(1,'SEMEVOSQL_QUALITY_V1') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS customers (
 customer_id bigint PRIMARY KEY, customer_name text NOT NULL, region text, created_at timestamp NOT NULL);
CREATE TABLE IF NOT EXISTS products (
 product_id bigint PRIMARY KEY, product_name text NOT NULL, category text NOT NULL,
 price numeric(12,2) NOT NULL CHECK(price>=0));
CREATE TABLE IF NOT EXISTS orders (
 order_id bigint PRIMARY KEY, customer_id bigint NOT NULL REFERENCES customers,
 ordered_at timestamp NOT NULL, paid_at timestamp, amount numeric(12,2),
 status text NOT NULL CHECK(status IN ('PAID','UNPAID','CANCELLED','CLOSED','REFUNDED')),
 CHECK(amount IS NULL OR amount>=0));
CREATE TABLE IF NOT EXISTS order_items (
 item_id bigint PRIMARY KEY, order_id bigint NOT NULL REFERENCES orders,
 product_id bigint NOT NULL REFERENCES products, quantity int NOT NULL CHECK(quantity>0),
 unit_price numeric(12,2) NOT NULL CHECK(unit_price>=0));
CREATE TABLE IF NOT EXISTS refunds (
 refund_id bigint PRIMARY KEY, order_id bigint NOT NULL REFERENCES orders,
 amount numeric(12,2) NOT NULL CHECK(amount>=0), requested_at timestamp NOT NULL, refunded_at timestamp,
 status text NOT NULL CHECK(status IN ('SUCCESS','PENDING','REJECTED')));
INSERT INTO customers
SELECT n,'合成客户'||(n%17),CASE WHEN n%11=0 THEN NULL ELSE (ARRAY['华东','华南','华北','西南'])[1+n%4] END,
 timestamp '2025-12-01'+(n%31)*interval '1 day' FROM generate_series(1,200) n ON CONFLICT DO NOTHING;
INSERT INTO products
SELECT n,'合成商品'||(n%13),(ARRAY['食品','家居','数码','服装','图书'])[1+n%5],(n*17%1000+1)::numeric/10
FROM generate_series(1,50) n ON CONFLICT DO NOTHING;
INSERT INTO orders
SELECT n,1+n*13%200,timestamp '2026-01-01'+(n*37%90)*interval '1 day'+(n*97%86400)*interval '1 second',
 CASE WHEN n%5 IN (0,4) THEN timestamp '2026-01-01'+(n*37%90)*interval '1 day'+(n*97%86400+300)*interval '1 second' END,
 CASE WHEN n%97=0 THEN NULL ELSE (n*7919%100000)::numeric/100 END,
 (ARRAY['PAID','UNPAID','CANCELLED','CLOSED','REFUNDED'])[1+n%5]
FROM generate_series(9,2000) n ON CONFLICT DO NOTHING;
-- Small independent baseline: edges before January / exactly Feb, March, April boundaries.
INSERT INTO orders VALUES
 (1,1,'2025-12-31 23:59:59','2026-01-01',100,'PAID'),
 (2,2,'2026-01-01','2026-01-01 00:05',200,'PAID'),
 (3,3,'2026-01-31 23:59:59',NULL,50,'UNPAID'),
 (4,4,'2026-02-01',NULL,NULL,'CANCELLED'),
 (5,5,'2026-02-28 23:59:59','2026-03-01',30,'PAID'),
 (6,6,'2026-03-01','2026-03-01 00:05',70,'REFUNDED'),
 (7,7,'2026-03-31 23:59:59',NULL,10,'CLOSED'),
 (8,8,'2026-04-01','2026-04-01 00:05',40,'PAID') ON CONFLICT DO NOTHING;
INSERT INTO order_items
SELECT (o.order_id-1)*2+s,o.order_id,1+(o.order_id*7+s)%50,1+(o.order_id+s)%3,p.price
FROM orders o CROSS JOIN generate_series(1,2) s JOIN products p ON p.product_id=1+(o.order_id*7+s)%50
WHERE o.order_id BETWEEN 1 AND 2000 ON CONFLICT DO NOTHING;
INSERT INTO refunds VALUES
 (1,2,20,'2026-01-31 23:59:59','2026-02-01','SUCCESS'),
 (2,2,5,'2026-01-20',NULL,'PENDING'),
 (3,5,10,'2026-03-01',NULL,'REJECTED'),
 (4,6,70,'2026-03-02','2026-03-02','SUCCESS') ON CONFLICT DO NOTHING;
INSERT INTO refunds
SELECT 1000+n,n,(n%23+1)::numeric,o.ordered_at+interval '2 days',
 CASE WHEN n%3=0 THEN o.ordered_at+interval '3 days' END,
 (ARRAY['SUCCESS','PENDING','REJECTED'])[1+n%3]
FROM generate_series(10,2000,10) n JOIN orders o ON o.order_id=n ON CONFLICT DO NOTHING;
COMMIT;
