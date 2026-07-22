-- Prepared artifacts and final eligibility are separate. No external work occurs while publication locks are held.
CREATE TABLE qw_project_definition_publication (
    id bigserial PRIMARY KEY,
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    project_id bigint NOT NULL REFERENCES qw_project(id),
    content_revision integer NOT NULL,
    evidence_revision integer NOT NULL,
    contribution_fingerprint varchar(128) NOT NULL,
    base_version_id bigint NOT NULL REFERENCES qw_project_version(id),
    base_catalog_hash varchar(128) NOT NULL,
    source_structure jsonb NOT NULL,
    source_representation_hash varchar(128) NOT NULL,
    public_asset_key varchar(128) NOT NULL,
    operator varchar(255) NOT NULL,
    reason text NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK(state IN ('PENDING','BUILDING','RETRYABLE_FAILURE','DONE','STALE')),
    prepared_version_id bigint REFERENCES qw_project_version(id),
    initial_catalog_hash varchar(128),
    materialized_catalog_hash varchar(128),
    owner_token varchar(64),
    lease_until timestamp,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(128),
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time timestamp,
    UNIQUE(candidate_id,content_revision,evidence_revision,base_version_id,contribution_fingerprint)
);
CREATE UNIQUE INDEX qw_project_definition_one_active_publication ON qw_project_definition_publication(candidate_id)
    WHERE state IN ('PENDING','BUILDING','RETRYABLE_FAILURE');
CREATE TABLE qw_project_definition_publication_event (
    publication_id bigint PRIMARY KEY REFERENCES qw_project_definition_publication(id),
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    project_id bigint NOT NULL,
    from_version_id bigint NOT NULL,
    to_version_id bigint NOT NULL,
    public_asset_key varchar(128) NOT NULL,
    operator varchar(255) NOT NULL,
    reason text NOT NULL,
    payload jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER immutable_project_definition_publication_event BEFORE UPDATE OR DELETE ON qw_project_definition_publication_event
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
