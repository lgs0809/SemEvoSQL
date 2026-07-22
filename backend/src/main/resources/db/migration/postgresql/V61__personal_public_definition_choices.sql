-- Exact combinations users actually saw and confirmed, independent of unrelated catalog versions.
CREATE TABLE qw_personal_public_question (
    clarification_id varchar(64) PRIMARY KEY REFERENCES qw_runtime_clarification(clarification_id),
    project_id bigint NOT NULL REFERENCES qw_project(id),
    principal_id varchar(255) NOT NULL,
    preference_id bigint NOT NULL,
    personal_revision integer NOT NULL,
    personal_content_hash varchar(128) NOT NULL,
    asset_type varchar(32) NOT NULL,
    asset_key varchar(500) NOT NULL,
    public_version_id bigint NOT NULL REFERENCES qw_project_version(id),
    public_meaning_fingerprint varchar(128) NOT NULL,
    public_definition_text text NOT NULL,
    public_snapshot jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(preference_id,personal_revision) REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE TRIGGER immutable_personal_public_question BEFORE UPDATE OR DELETE ON qw_personal_public_question
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TABLE qw_personal_public_choice (
    clarification_id varchar(64) PRIMARY KEY REFERENCES qw_personal_public_question(clarification_id),
    project_id bigint NOT NULL,
    principal_id varchar(255) NOT NULL,
    preference_id bigint NOT NULL,
    personal_revision integer NOT NULL,
    personal_content_hash varchar(128) NOT NULL,
    asset_type varchar(32) NOT NULL,
    asset_key varchar(500) NOT NULL,
    public_version_id bigint NOT NULL,
    public_meaning_fingerprint varchar(128) NOT NULL,
    choice varchar(32) NOT NULL CHECK(choice IN ('KEEP_PERSONAL','ADOPT_PUBLIC','OTHER')),
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(preference_id,personal_revision) REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE INDEX qw_personal_public_choice_combination ON qw_personal_public_choice
    (project_id,principal_id,preference_id,personal_revision,personal_content_hash,asset_type,asset_key,public_meaning_fingerprint);
CREATE TRIGGER immutable_personal_public_choice BEFORE UPDATE OR DELETE ON qw_personal_public_choice
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
