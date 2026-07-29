-- Synthetic local acceptance data. Re-running never overwrites an existing row.
BEGIN;
CREATE TABLE IF NOT EXISTS customers (
 customer_id bigint PRIMARY KEY, customer_name text NOT NULL, created_at date NOT NULL);
CREATE TABLE IF NOT EXISTS customer_extensions (
 customer_id bigint PRIMARY KEY REFERENCES customers, region text NOT NULL, segment text NOT NULL);
CREATE TABLE IF NOT EXISTS channels (
 channel_id bigint PRIMARY KEY, channel_name text NOT NULL UNIQUE);
CREATE TABLE IF NOT EXISTS orders (
 order_id bigint PRIMARY KEY, customer_id bigint NOT NULL REFERENCES customers,
 channel_id bigint NOT NULL REFERENCES channels, ordered_at timestamp NOT NULL,
 paid_at timestamp, amount numeric(12,2) NOT NULL CHECK(amount >= 0),
 status text NOT NULL CHECK(status IN ('PAID','CANCELLED','UNPAID')));
CREATE TABLE IF NOT EXISTS refunds (
 refund_id bigint PRIMARY KEY, order_id bigint NOT NULL REFERENCES orders,
 amount numeric(12,2) NOT NULL CHECK(amount >= 0), refunded_at timestamp,
 status text NOT NULL CHECK(status IN ('SUCCESS','PENDING','REJECTED')));
CREATE TABLE IF NOT EXISTS marketing_spend (
 spend_id bigint PRIMARY KEY, channel_id bigint NOT NULL REFERENCES channels,
 spend_month date NOT NULL, amount numeric(12,2) NOT NULL CHECK(amount >= 0));
-- Intentionally non-unique auxiliary rows for negative mapping/row multiplication tests.
CREATE TABLE IF NOT EXISTS customer_extensions_duplicate (
 evidence_id bigint PRIMARY KEY, customer_id bigint NOT NULL REFERENCES customers, region text NOT NULL);
INSERT INTO customers VALUES (1001,'验收客户甲','2025-12-01'),(1002,'验收客户乙','2025-12-10'),(1003,'验收客户丙','2025-12-20') ON CONFLICT DO NOTHING;
INSERT INTO customer_extensions VALUES (1001,'华东','企业'),(1002,'华南','个人') ON CONFLICT DO NOTHING;
INSERT INTO channels VALUES (101,'自然访问'),(102,'付费推广') ON CONFLICT DO NOTHING;
INSERT INTO orders VALUES
 (10001,1001,101,'2026-01-10 10:00','2026-01-10 10:05',150,'PAID'),
 (10002,1002,102,'2026-01-11 11:00','2026-01-11 11:05',80,'PAID'),
 (10003,1003,101,'2026-01-12 12:00','2026-01-12 12:05',40,'PAID'),
 (10004,1001,102,'2026-01-13 13:00',NULL,100,'CANCELLED'),
 (10005,1002,102,'2026-01-14 14:00',NULL,50,'UNPAID'),
 (10006,1001,101,'2026-02-10 10:00','2026-02-10 10:05',200,'PAID'),
 (10007,1002,102,'2026-02-11 11:00','2026-02-11 11:05',100,'PAID'),
 (10008,1003,101,'2026-03-10 10:00','2026-03-10 10:05',60,'PAID'),
 (10009,1001,102,'2026-03-11 11:00','2026-03-11 11:05',300,'PAID') ON CONFLICT DO NOTHING;
INSERT INTO refunds VALUES (20001,10001,20,'2026-01-20','SUCCESS'),(20002,10002,10,NULL,'PENDING'),(20003,10006,30,'2026-02-20','SUCCESS'),(20004,10009,40,NULL,'REJECTED') ON CONFLICT DO NOTHING;
INSERT INTO marketing_spend VALUES (30001,102,'2026-01-01',25),(30002,102,'2026-02-01',50),(30003,102,'2026-03-01',100) ON CONFLICT DO NOTHING;
INSERT INTO customer_extensions_duplicate VALUES (40001,1001,'华东'),(40002,1001,'华南') ON CONFLICT DO NOTHING;
COMMENT ON TABLE orders IS '合成验收订单；已支付金额不扣退款，净收入需扣成功退款；时间口径需要明确。';
COMMENT ON TABLE customer_extensions IS '每客户至多一条扩展，缺失必须保留为未知地区。';
COMMIT;
