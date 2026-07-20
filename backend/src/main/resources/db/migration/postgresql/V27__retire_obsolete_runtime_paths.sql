DELETE FROM qw_external_query_handle h
WHERE h.run_id IS NULL
   OR EXISTS (
       SELECT 1
       FROM qw_query_run r
       WHERE r.run_id = h.run_id
         AND r.run_type <> 'EXTERNAL_MCP_QUERY'
   );

DROP TABLE IF EXISTS qw_external_query_pre_run_clarification;
DROP INDEX IF EXISTS idx_qw_external_query_handle_admission;

DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_session;

DROP TABLE IF EXISTS agent_datasource_tables;
DROP TABLE IF EXISTS agent_preset_question;
DROP TABLE IF EXISTS agent_datasource;
DROP TABLE IF EXISTS agent_knowledge;
DROP TABLE IF EXISTS semantic_model;
DROP TABLE IF EXISTS business_knowledge;
DROP TABLE IF EXISTS agent;
DROP TABLE IF EXISTS user_prompt_config;
DROP TABLE IF EXISTS vector_store;

ALTER TABLE qw_external_query_handle
    DROP COLUMN IF EXISTS conversation_id,
    ALTER COLUMN state SET DEFAULT 'SUBMITTED';

ALTER TABLE qw_source_sub_run
    ALTER COLUMN execution_key DROP DEFAULT;

ALTER TABLE qw_merge_execution
    ALTER COLUMN execution_key DROP DEFAULT;

UPDATE qw_query_run
SET run_type = 'INTERACTIVE_QUERY'
WHERE run_type = 'REQUEST_QUERY';
