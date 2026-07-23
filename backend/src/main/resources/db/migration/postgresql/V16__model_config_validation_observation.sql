ALTER TABLE model_config
    ADD COLUMN IF NOT EXISTS validation_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN IF NOT EXISTS last_validation_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_model_config_validation_status
    ON model_config(model_type, is_active, validation_status);
