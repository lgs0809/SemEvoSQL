-- Structured representations retain exact, immutable source and dependency provenance.
CREATE TABLE qw_user_semantic_structure_revision (
    preference_id BIGINT NOT NULL,source_revision INTEGER NOT NULL,representation_hash VARCHAR(80) NOT NULL,
    structured_json JSONB NOT NULL CHECK(jsonb_typeof(structured_json)='object'),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(preference_id,source_revision,representation_hash),
    FOREIGN KEY(preference_id,source_revision) REFERENCES qw_user_semantic_definition_revision
);
INSERT INTO qw_user_semantic_structure_revision(preference_id,source_revision,representation_hash,structured_json)
SELECT preference_id,source_revision,'sha256:'||encode(sha256(convert_to(structured_json::text,'UTF8')),'hex'),structured_json
FROM qw_user_semantic_representation WHERE representation_state='STRUCTURED_ACTIVE' AND structured_json IS NOT NULL;
-- Existing structures are retained verbatim; their legacy SQL hash is not reinterpreted as the Java canonical hash.
CREATE TRIGGER immutable_personal_structure BEFORE UPDATE OR DELETE ON qw_user_semantic_structure_revision
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN source_revision INTEGER;
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN source_content_hash VARCHAR(80);
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN dependency_fingerprint VARCHAR(80);
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN definition_text TEXT;
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN representation_code VARCHAR(128);
ALTER TABLE qw_query_case_binding_dependency ADD COLUMN representation_hash VARCHAR(80);
CREATE INDEX idx_qw_case_personal_source ON qw_query_case_binding_dependency(source_record_id,source_revision)
    WHERE binding_source='USER';
-- Unknown historical identities remain NULL; never label a historical use as today's personal revision.
