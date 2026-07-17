-- New vectors may carry same-response provenance. Historical unknown vectors are deliberately unchanged.
ALTER TABLE qw_semantic_retrieval_embedding
    ADD COLUMN encoding_profile_sha256 CHAR(64),
    ADD COLUMN encoding_identity JSONB,
    ADD COLUMN input_sha256 CHAR(64),
    ADD COLUMN vector_sha256 CHAR(64),
    ADD COLUMN reuse_source JSONB;

ALTER TABLE qw_semantic_retrieval_embedding ADD CONSTRAINT ck_qw_atomic_embedding_identity
    CHECK ((encoding_profile_sha256 IS NULL AND encoding_identity IS NULL AND input_sha256 IS NULL AND vector_sha256 IS NULL)
        OR (encoding_profile_sha256 IS NOT NULL AND input_sha256 IS NOT NULL AND vector_sha256 IS NOT NULL
            AND encoding_profile_sha256 ~ '^[0-9a-f]{64}$' AND encoding_identity IS NOT NULL
            AND input_sha256 ~ '^[0-9a-f]{64}$' AND vector_sha256 ~ '^[0-9a-f]{64}$'));

CREATE INDEX idx_qw_semantic_embedding_certified_reuse
    ON qw_semantic_retrieval_embedding(embedding_model, embedding_version, encoding_profile_sha256, content_hash)
    WHERE encoding_profile_sha256 IS NOT NULL;
