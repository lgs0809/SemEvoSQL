-- Durable assessment is independent of interactive queries and the suggestion embedding projection.
ALTER TABLE qw_project_definition_candidate
    ADD COLUMN assessment_state varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK (assessment_state IN ('PENDING','RUNNING','RETRYABLE_FAILURE','DONE')),
    ADD COLUMN assessment_owner_token varchar(64),
    ADD COLUMN assessment_lease_until timestamp,
    ADD COLUMN assessment_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN assessment_next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN assessment_last_error varchar(128),
    ADD COLUMN assessed_content_revision integer,
    ADD COLUMN assessed_evidence_revision integer,
    ADD COLUMN assessed_base_version_id bigint,
    ADD COLUMN assessed_catalog_hash varchar(128),
    ADD COLUMN assessed_contribution_fingerprint varchar(128),
    ADD COLUMN assessment_json jsonb;
CREATE INDEX qw_project_definition_assessment_due ON qw_project_definition_candidate(assessment_next_attempt_at,id)
    WHERE lifecycle NOT IN ('PUBLISHED','REJECTED');
CREATE TABLE qw_project_definition_assessment (
    id bigserial PRIMARY KEY,
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    content_revision integer NOT NULL,
    evidence_revision integer NOT NULL,
    base_version_id bigint NOT NULL,
    catalog_hash varchar(128) NOT NULL,
    contribution_fingerprint varchar(128) NOT NULL,
    assessment_json jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER immutable_project_definition_assessment BEFORE UPDATE OR DELETE ON qw_project_definition_assessment
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
