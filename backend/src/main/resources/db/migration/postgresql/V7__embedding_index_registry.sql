CREATE TABLE qw_embedding_index_registry (
    index_scope VARCHAR(64) PRIMARY KEY,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_version VARCHAR(64) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    active_since TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
