CREATE TABLE qw_semantic_retrieval_document (
    id VARCHAR(64) PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES qw_project(id) ON DELETE CASCADE,
    project_version_id BIGINT NOT NULL REFERENCES qw_project_version(id) ON DELETE CASCADE,
    catalog_hash CHAR(64) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    asset_key VARCHAR(512) NOT NULL,
    datasource_id INTEGER,
    model_code VARCHAR(255) NOT NULL,
    physical_table VARCHAR(512) NOT NULL,
    lexical_text TEXT NOT NULL,
    semantic_text TEXT NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    generator_model VARCHAR(255),
    generator_version VARCHAR(64),
    generation_status VARCHAR(32) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_qw_semantic_retrieval_document_type
        CHECK (document_type IN ('MODEL', 'METRIC', 'DIMENSION', 'ENUM_VALUE')),
    CONSTRAINT uk_qw_semantic_retrieval_document_asset
        UNIQUE(project_version_id, document_type, asset_key)
);
CREATE INDEX idx_qw_semantic_retrieval_document_catalog
    ON qw_semantic_retrieval_document(project_id, project_version_id, catalog_hash);
CREATE INDEX idx_qw_semantic_retrieval_document_scope
    ON qw_semantic_retrieval_document(project_version_id, catalog_hash, datasource_id, model_code, document_type);
CREATE INDEX idx_qw_semantic_retrieval_document_content
    ON qw_semantic_retrieval_document(content_hash);

CREATE TABLE qw_semantic_retrieval_embedding (
    document_id VARCHAR(64) NOT NULL REFERENCES qw_semantic_retrieval_document(id) ON DELETE CASCADE,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_version VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension > 0),
    embedding vector NOT NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(document_id, embedding_model, embedding_version)
);
CREATE INDEX idx_qw_semantic_retrieval_embedding_hash
    ON qw_semantic_retrieval_embedding(embedding_model, embedding_version, content_hash);
