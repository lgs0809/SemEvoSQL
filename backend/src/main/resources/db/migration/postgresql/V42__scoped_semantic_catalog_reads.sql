-- Read-path indexes only; no catalog or business data changes.
CREATE INDEX idx_qw_relationship_target_scope ON qw_semantic_relationship(project_id,project_version_id,target_model_code,status);
CREATE INDEX idx_qw_grain_model_scope ON qw_semantic_grain(project_id,project_version_id,model_code,status);
CREATE INDEX idx_qw_enum_model_scope ON qw_semantic_enum_value(project_id,project_version_id,model_code,status);
CREATE INDEX idx_qw_rule_model_scope ON qw_semantic_rule(project_id,project_version_id,model_code,status);
