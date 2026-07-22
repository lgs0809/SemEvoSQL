CREATE TABLE qw_project_definition_decision (
    id bigserial PRIMARY KEY,
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    project_id bigint NOT NULL REFERENCES qw_project(id),
    action varchar(32) NOT NULL CHECK(action IN ('EARLY_CREATE','RENAME','OVERWRITE','ASSOCIATE','REJECT','DEFER','RESUME')),
    operator varchar(255) NOT NULL,
    operator_source varchar(64) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash varchar(128) NOT NULL,
    reason text NOT NULL,
    seen_inputs jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id,operator,idempotency_key)
);
CREATE TRIGGER immutable_project_definition_decision BEFORE UPDATE OR DELETE ON qw_project_definition_decision
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
ALTER TABLE qw_project_definition_candidate ADD COLUMN approved_decision_id bigint REFERENCES qw_project_definition_decision(id);
ALTER TABLE qw_project_definition_publication
    ADD COLUMN decision_id bigint REFERENCES qw_project_definition_decision(id),
    ADD COLUMN action varchar(32) NOT NULL DEFAULT 'AUTO_CREATE',
    ADD COLUMN target_asset_key varchar(128),
    ADD COLUMN public_name varchar(255),
    ADD COLUMN operator_source varchar(64) NOT NULL DEFAULT 'SYSTEM';
DO $$ DECLARE identity_constraint text;
BEGIN
    SELECT conname INTO identity_constraint FROM pg_constraint
      WHERE conrelid='qw_project_definition_publication'::regclass AND contype='u' AND cardinality(conkey)=5;
    IF identity_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE qw_project_definition_publication DROP CONSTRAINT %I',identity_constraint);
    END IF;
END $$;
CREATE UNIQUE INDEX qw_definition_publication_input_identity ON qw_project_definition_publication
    (candidate_id,content_revision,evidence_revision,base_version_id,contribution_fingerprint,coalesce(decision_id,0));
