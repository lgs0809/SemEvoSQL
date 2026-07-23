CREATE TABLE qw_scenario_resolution (
    scenario_id BIGINT PRIMARY KEY REFERENCES qw_business_query_scenario(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    project_version_id BIGINT NOT NULL REFERENCES qw_project_version(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    resolved_bindings_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidate_bindings_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    unresolved_requirements_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    manual_bindings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolution_hash CHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_qw_scenario_resolution_version
    ON qw_scenario_resolution(project_version_id, status);
