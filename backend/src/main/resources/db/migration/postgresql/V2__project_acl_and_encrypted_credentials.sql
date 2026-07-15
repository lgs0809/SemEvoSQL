CREATE TABLE IF NOT EXISTS qw_project_member (
    project_id BIGINT NOT NULL REFERENCES qw_project(id) ON DELETE CASCADE,
    operator_id VARCHAR(128) NOT NULL,
    access_role VARCHAR(16) NOT NULL,
    granted_by VARCHAR(128) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, operator_id)
);
CREATE INDEX idx_qw_project_member_operator ON qw_project_member(operator_id, access_role, project_id);

INSERT INTO qw_project_member(project_id, operator_id, access_role, granted_by)
SELECT id, created_by, 'OWNER', created_by
FROM qw_project
WHERE created_by IS NOT NULL AND BTRIM(created_by) <> ''
ON CONFLICT (project_id, operator_id) DO NOTHING;

ALTER TABLE model_config ALTER COLUMN api_key TYPE TEXT;
ALTER TABLE model_config ALTER COLUMN proxy_password TYPE TEXT;
