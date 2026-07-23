-- Explicit administrator requests survive a restart. Partial new vectors do not switch the registry.
CREATE TABLE qw_semantic_reindex_work (
    index_scope VARCHAR(64) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_version VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','PROCESSING','RETRY','DONE')),
    requested_by VARCHAR(128) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    indexed_documents INTEGER NOT NULL DEFAULT 0,
    owner_token VARCHAR(64),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(256),
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- A dimension-specific partial index permits old and staged encodings to coexist.
DO $$ DECLARE dim INTEGER; dimensions INTEGER[]; cast_type TEXT; operator_class TEXT;
BEGIN
    SELECT array_agg(DISTINCT dimension) INTO dimensions FROM qw_semantic_retrieval_embedding
        WHERE dimension BETWEEN 1 AND 4000;
    -- Finish the table read before DDL: a FOR SELECT cursor keeps that relation active.
    FOREACH dim IN ARRAY COALESCE(dimensions,ARRAY[]::INTEGER[]) LOOP
        cast_type := CASE WHEN dim<=2000 THEN 'vector' ELSE 'halfvec' END;
        operator_class := CASE WHEN dim<=2000 THEN 'vector_cosine_ops' ELSE 'halfvec_cosine_ops' END;
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_qw_semantic_embedding_hnsw_%s ON qw_semantic_retrieval_embedding USING hnsw ((embedding::%s(%s)) %s) WHERE dimension=%s', dim,cast_type,dim,operator_class,dim);
    END LOOP;
END $$;
DROP INDEX IF EXISTS idx_qw_semantic_retrieval_embedding_hnsw;
