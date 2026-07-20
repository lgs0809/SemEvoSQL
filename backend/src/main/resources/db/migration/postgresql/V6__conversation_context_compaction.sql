CREATE TABLE IF NOT EXISTS qw_conversation_context_compaction (
    thread_id VARCHAR(160) PRIMARY KEY,
    covered_through_sequence BIGINT NOT NULL,
    summary_json JSONB NOT NULL,
    source_digest VARCHAR(64) NOT NULL,
    summary_version INTEGER NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);
