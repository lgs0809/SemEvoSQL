ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS canonical_query TEXT;
ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS context_summary_json JSONB;
ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS result_summary TEXT;
ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS result_artifact_id VARCHAR(64);
ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS prompt_token_estimate INTEGER NOT NULL DEFAULT 0;
ALTER TABLE qw_conversation_turn ADD COLUMN IF NOT EXISTS summary_version INTEGER NOT NULL DEFAULT 0;
