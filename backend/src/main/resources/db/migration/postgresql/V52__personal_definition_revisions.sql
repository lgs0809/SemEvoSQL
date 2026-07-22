-- Personal meaning is immutable history; the old phrase table is its current identity/pointer.
ALTER TABLE qw_user_semantic_preference ADD COLUMN current_revision INTEGER NOT NULL DEFAULT 0 CHECK(current_revision>=0);
ALTER TABLE qw_user_semantic_preference ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE qw_user_semantic_preference ALTER COLUMN next_upgrade_prompt_at SET DEFAULT 5;
CREATE TABLE qw_user_semantic_definition_revision (
    preference_id BIGINT NOT NULL REFERENCES qw_user_semantic_preference(id),
    revision INTEGER NOT NULL CHECK(revision>0),
    definition_text TEXT NOT NULL CHECK(length(btrim(definition_text)) BETWEEN 1 AND 20000),
    asset_type VARCHAR(64) NOT NULL,asset_key VARCHAR(500) NOT NULL,business_label VARCHAR(500) NOT NULL,
    source_kind VARCHAR(32) NOT NULL CHECK(source_kind IN ('LEGACY_REFERENCE','ASSET_CONFIRMATION','TEXT_CONFIRMATION','PROJECT_ADOPTION')),
    source_id VARCHAR(200),base_version_id BIGINT REFERENCES qw_project_version(id),
    definition_snapshot JSONB NOT NULL CHECK(jsonb_typeof(definition_snapshot)='object'),
    content_hash VARCHAR(80) NOT NULL,dependency_fingerprint VARCHAR(80),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(preference_id,revision)
);
CREATE UNIQUE INDEX uq_qw_personal_definition_confirmation ON qw_user_semantic_definition_revision(source_id)
    WHERE source_id IS NOT NULL AND source_kind IN ('ASSET_CONFIRMATION','TEXT_CONFIRMATION','PROJECT_ADOPTION');
CREATE TABLE qw_user_semantic_authorization (
    preference_id BIGINT NOT NULL,definition_revision INTEGER NOT NULL,authorization_revision INTEGER NOT NULL CHECK(authorization_revision>0),
    choice VARCHAR(32) NOT NULL CHECK(choice IN ('PRIVATE','AWAITING_CONSENT','ALLOWED','DECLINED')),
    principal_id VARCHAR(128) NOT NULL,source_id VARCHAR(200),create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(preference_id,definition_revision,authorization_revision),
    FOREIGN KEY(preference_id,definition_revision) REFERENCES qw_user_semantic_definition_revision
);
CREATE TABLE qw_user_semantic_representation (
    preference_id BIGINT NOT NULL,source_revision INTEGER NOT NULL,
    representation_state VARCHAR(32) NOT NULL DEFAULT 'TEXT_ACTIVE' CHECK(representation_state IN ('TEXT_ACTIVE','STRUCTURED_ACTIVE')),
    structured_json JSONB,source_content_hash VARCHAR(80) NOT NULL,dependency_fingerprint VARCHAR(80),
    task_state VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK(task_state IN ('PENDING','RUNNING','RETRYABLE_FAILURE','DONE','STALE','NEEDS_RECONFIRMATION')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_token VARCHAR(64),lease_until TIMESTAMPTZ,last_error VARCHAR(128),update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(preference_id,source_revision),
    FOREIGN KEY(preference_id,source_revision) REFERENCES qw_user_semantic_definition_revision,
    CHECK(representation_state!='STRUCTURED_ACTIVE' OR structured_json IS NOT NULL),
    CHECK(structured_json IS NULL OR jsonb_typeof(structured_json)='object')
);
CREATE INDEX idx_qw_personal_structure_due ON qw_user_semantic_representation(task_state,next_attempt_at,preference_id);
INSERT INTO qw_user_semantic_definition_revision(preference_id,revision,definition_text,asset_type,asset_key,business_label,
    source_kind,definition_snapshot,content_hash)
SELECT id,1,business_label,asset_type,asset_key,business_label,'LEGACY_REFERENCE',
    jsonb_build_object('legacyAssetType',asset_type,'legacyAssetKey',asset_key,'completeDefinitionRecorded',false),
    'sha256:'||encode(sha256(convert_to(business_label,'UTF8')),'hex') FROM qw_user_semantic_preference;
INSERT INTO qw_user_semantic_authorization(preference_id,definition_revision,authorization_revision,choice,principal_id)
SELECT id,1,1,CASE WHEN upgrade_dismissed THEN 'DECLINED' ELSE 'PRIVATE' END,user_id FROM qw_user_semantic_preference;
INSERT INTO qw_user_semantic_representation(preference_id,source_revision,source_content_hash,task_state)
SELECT preference_id,revision,content_hash,'NEEDS_RECONFIRMATION' FROM qw_user_semantic_definition_revision;
UPDATE qw_user_semantic_preference SET current_revision=1,next_upgrade_prompt_at=5;
ALTER TABLE qw_user_semantic_preference_usage ADD COLUMN definition_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE qw_user_semantic_preference_usage ADD CONSTRAINT fk_qw_personal_usage_revision
    FOREIGN KEY(preference_id,definition_revision) REFERENCES qw_user_semantic_definition_revision;
CREATE UNIQUE INDEX uq_qw_personal_query_use ON qw_user_semantic_preference_usage(preference_id,definition_revision,run_id)
    WHERE run_id IS NOT NULL;
CREATE TRIGGER immutable_personal_definition BEFORE UPDATE OR DELETE ON qw_user_semantic_definition_revision
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TRIGGER immutable_personal_authorization BEFORE UPDATE OR DELETE ON qw_user_semantic_authorization
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
-- A question freezes the personal revision it proposes to change; late answers cannot overwrite a newer meaning.
CREATE TABLE qw_clarification_definition_base (
    clarification_id VARCHAR(64) PRIMARY KEY REFERENCES qw_runtime_clarification(clarification_id),
    project_id BIGINT NOT NULL REFERENCES qw_project(id),principal_id VARCHAR(128) NOT NULL,
    normalized_phrase VARCHAR(500) NOT NULL,expected_personal_revision INTEGER NOT NULL CHECK(expected_personal_revision>=0),
    base_version_id BIGINT NOT NULL REFERENCES qw_project_version(id),create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER immutable_clarification_definition_base BEFORE UPDATE OR DELETE ON qw_clarification_definition_base
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
