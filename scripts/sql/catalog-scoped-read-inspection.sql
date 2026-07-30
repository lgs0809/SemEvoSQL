-- Read-only PostgreSQL inspection. Example:
-- psql -v ON_ERROR_STOP=1 -v project_id=2 -v version_id=2 -v model_codes='["orders"]' -f scripts/sql/catalog-scoped-read-inspection.sql
BEGIN READ ONLY;
SELECT id,project_id,status,catalog_hash FROM qw_project_version
WHERE project_id=:'project_id'::bigint AND id=:'version_id'::bigint;
SELECT count(*) AS total_enabled_models,coalesce(sum(octet_length(description)),0) AS all_model_description_bytes
FROM qw_semantic_model WHERE project_id=:'project_id'::bigint AND project_version_id=:'version_id'::bigint AND status='ENABLED';
SELECT model_code,datasource_id,physical_table,status,octet_length(description) AS description_bytes
FROM qw_semantic_model WHERE project_id=:'project_id'::bigint AND project_version_id=:'version_id'::bigint AND status='ENABLED'
AND model_code IN (SELECT jsonb_array_elements_text(:'model_codes'::jsonb)) ORDER BY model_code;
EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
SELECT * FROM qw_semantic_model WHERE project_id=:'project_id'::bigint AND project_version_id=:'version_id'::bigint AND status='ENABLED'
AND model_code IN (SELECT jsonb_array_elements_text(:'model_codes'::jsonb));
-- Mandatory global rules must remain visible independently of recalled model identities.
SELECT rule_code,rule_type,model_code,status FROM qw_semantic_rule
WHERE project_id=:'project_id'::bigint AND project_version_id=:'version_id'::bigint AND status='ENABLED'
AND (model_code IS NULL OR model_code='' OR model_code IN (SELECT jsonb_array_elements_text(:'model_codes'::jsonb)))
ORDER BY rule_code;
ROLLBACK;
