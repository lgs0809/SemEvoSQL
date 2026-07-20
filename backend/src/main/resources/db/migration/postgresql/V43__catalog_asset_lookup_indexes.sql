-- Exact compound asset identities used by bounded detail lookup.
CREATE INDEX idx_qw_column_lookup_identity
    ON qw_semantic_column (project_id, project_version_id, (model_code || ':' || column_name))
    WHERE status = 'ENABLED';
CREATE INDEX idx_qw_enum_lookup_identity
    ON qw_semantic_enum_value (project_id, project_version_id, (model_code || ':' || column_name || ':' || value_code))
    WHERE status = 'ENABLED';
CREATE INDEX idx_qw_grain_lookup_code
    ON qw_semantic_grain (project_id, project_version_id, grain_code)
    WHERE status = 'ENABLED';
