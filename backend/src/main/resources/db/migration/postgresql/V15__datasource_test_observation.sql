ALTER TABLE datasource
    ADD COLUMN IF NOT EXISTS last_test_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_datasource_last_test_time ON datasource(last_test_time);
