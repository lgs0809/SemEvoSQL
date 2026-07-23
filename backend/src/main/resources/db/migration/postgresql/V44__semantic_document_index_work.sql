-- Derived text and its durable indexing obligation commit together, including non-Java writers.
CREATE TABLE qw_semantic_document_index_work (
    document_id VARCHAR(128) PRIMARY KEY REFERENCES qw_semantic_retrieval_document(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 1,
    content_hash VARCHAR(128) NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','RETRY','DONE')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    owner_token VARCHAR(64),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(256),
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_qw_semantic_document_work_due ON qw_semantic_document_index_work(status,next_attempt_at,lease_until);

CREATE FUNCTION qw_enqueue_semantic_document_index() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO qw_semantic_document_index_work(document_id,content_hash,source_fingerprint,status)
    VALUES(NEW.id,NEW.content_hash,NEW.source_fingerprint,'PENDING')
    ON CONFLICT(document_id) DO UPDATE SET
        revision=qw_semantic_document_index_work.revision+1,
        content_hash=EXCLUDED.content_hash, source_fingerprint=EXCLUDED.source_fingerprint,
        status='PENDING',attempt_count=0,owner_token=NULL,lease_until=NULL,
        next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,update_time=CURRENT_TIMESTAMP
    WHERE qw_semantic_document_index_work.content_hash IS DISTINCT FROM EXCLUDED.content_hash
       OR qw_semantic_document_index_work.source_fingerprint IS DISTINCT FROM EXCLUDED.source_fingerprint;
    RETURN NEW;
END $$;
CREATE TRIGGER qw_semantic_document_index_enqueue AFTER INSERT OR UPDATE OF content_hash,source_fingerprint
    ON qw_semantic_retrieval_document FOR EACH ROW EXECUTE FUNCTION qw_enqueue_semantic_document_index();
-- Existing derived documents are checked, not blindly re-encoded; the index service reuses valid vectors.
INSERT INTO qw_semantic_document_index_work(document_id,content_hash,source_fingerprint,status)
SELECT id,content_hash,source_fingerprint,'PENDING' FROM qw_semantic_retrieval_document;
