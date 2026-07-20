-- Durable MCP query handles are scoped to a Conversation as well as an Episode/Run.
-- V29 introduced Episode/Semantic Version pins, while the runtime later began persisting
-- conversation_id during handle submission. Keep this as a forward-only migration so databases
-- that already applied V29 can upgrade without editing migration history.
ALTER TABLE qw_external_query_handle
    ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
