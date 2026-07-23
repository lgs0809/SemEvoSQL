ALTER TABLE qw_project_semantic_alias
    ADD COLUMN project_version_id BIGINT;

-- Before V14 aliases were project-global, so every existing Project Version observed
-- the same alias set. Preserve that behavior for already-created conversations by
-- seeding each existing alias into every Project Version that already exists.
UPDATE qw_project_semantic_alias alias
SET project_version_id = COALESCE(
    project.active_version_id,
    (
        SELECT version.id
        FROM qw_project_version version
        WHERE version.project_id = alias.project_id
        ORDER BY CASE WHEN version.status = 'PUBLISHED' THEN 0 ELSE 1 END,
                 version.version_no DESC,
                 version.id DESC
        LIMIT 1
    )
)
FROM qw_project project
WHERE project.id = alias.project_id;

ALTER TABLE qw_project_semantic_alias
    DROP CONSTRAINT uk_qw_project_semantic_alias;

INSERT INTO qw_project_semantic_alias
    (project_id, project_version_id, normalized_phrase, display_phrase, asset_type, asset_key,
     business_label, evidence, status, create_time, update_time)
SELECT alias.project_id, version.id, alias.normalized_phrase, alias.display_phrase, alias.asset_type,
       alias.asset_key, alias.business_label, alias.evidence, alias.status, alias.create_time, alias.update_time
FROM qw_project_semantic_alias alias
JOIN qw_project_version version ON version.project_id = alias.project_id
WHERE version.id <> alias.project_version_id;

ALTER TABLE qw_project_semantic_alias
    ALTER COLUMN project_version_id SET NOT NULL,
    ADD CONSTRAINT fk_qw_project_semantic_alias_version
        FOREIGN KEY (project_version_id) REFERENCES qw_project_version(id) ON DELETE CASCADE;

ALTER TABLE qw_project_semantic_alias
    ADD CONSTRAINT uk_qw_project_semantic_alias_version
        UNIQUE(project_version_id, normalized_phrase);

DROP INDEX IF EXISTS idx_qw_project_semantic_alias_project;

CREATE INDEX idx_qw_project_semantic_alias_version
    ON qw_project_semantic_alias(project_id, project_version_id, status);
