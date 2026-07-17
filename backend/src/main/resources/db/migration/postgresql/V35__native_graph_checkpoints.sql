-- Schema required by the bundled Spring AI Alibaba Graph PostgresSaver 1.1.0.0.
-- No release/drop of existing or unfinished executions occurs during migration.
CREATE TABLE GraphThread (
    thread_id UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);
CREATE TABLE GraphCheckpoint (
    checkpoint_id UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id UUID NOT NULL REFERENCES GraphThread(thread_id) ON DELETE CASCADE,
    node_id VARCHAR(255),
    next_node_id VARCHAR(255),
    state_data JSONB NOT NULL,
    state_content_type VARCHAR(100) NOT NULL,
    saved_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_lg4jcheckpoint_thread_id ON GraphCheckpoint(thread_id);
CREATE INDEX idx_lg4jcheckpoint_thread_id_saved_at_desc ON GraphCheckpoint(thread_id, saved_at DESC);
CREATE UNIQUE INDEX idx_unique_lg4jthread_thread_name_unreleased ON GraphThread(thread_name) WHERE is_released = FALSE;
CREATE TABLE qw_native_graph_binding (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES qw_query_run(run_id),
    graph_thread_id UUID NOT NULL UNIQUE,
    definition_version VARCHAR(80) NOT NULL,
    state_schema_version INTEGER NOT NULL,
    execution_generation BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
