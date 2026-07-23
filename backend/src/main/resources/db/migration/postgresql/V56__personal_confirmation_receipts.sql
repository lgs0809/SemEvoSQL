-- An accepted scope-only change has an operation identity without inventing a new meaning revision.
CREATE TABLE qw_personal_confirmation_receipt (
    source_id varchar(255) PRIMARY KEY,
    preference_id bigint NOT NULL,
    definition_revision integer NOT NULL,
    content_hash varchar(128) NOT NULL,
    sharing_choice varchar(32) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(preference_id,definition_revision) REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE TRIGGER immutable_personal_confirmation_receipt BEFORE UPDATE OR DELETE ON qw_personal_confirmation_receipt
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
INSERT INTO qw_personal_confirmation_receipt(source_id,preference_id,definition_revision,content_hash,sharing_choice,create_time)
SELECT d.source_id,d.preference_id,d.revision,d.content_hash,a.choice,d.create_time
FROM qw_user_semantic_definition_revision d JOIN qw_user_semantic_authorization a
    ON a.preference_id=d.preference_id AND a.definition_revision=d.revision AND a.authorization_revision=1
WHERE d.source_id IS NOT NULL;
