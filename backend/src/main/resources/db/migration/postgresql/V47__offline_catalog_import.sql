-- Immutable provenance and preview identity, separate from administrator publication.
CREATE TABLE qw_semantic_catalog_import (
    import_id varchar(36) PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES qw_project(id),
    project_version_id bigint NOT NULL REFERENCES qw_project_version(id),
    operator_name varchar(128) NOT NULL,
    input_hash varchar(71) NOT NULL,
    source_fingerprint varchar(71) NOT NULL,
    baseline_catalog_hash varchar(64) NOT NULL,
    baseline_version_revision bigint NOT NULL,
    input_json jsonb NOT NULL,
    planned_catalog_json jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PREVIEWED' CHECK(status IN ('PREVIEWED','COMMITTED')),
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_time timestamp,
    receipt_json jsonb,
    UNIQUE(project_version_id,operator_name,input_hash,baseline_catalog_hash,baseline_version_revision),
    CHECK ((status='PREVIEWED' AND committed_time IS NULL AND receipt_json IS NULL)
        OR (status='COMMITTED' AND committed_time IS NOT NULL AND receipt_json IS NOT NULL))
);
CREATE FUNCTION qw_guard_catalog_import_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.import_id,NEW.project_id,NEW.project_version_id,NEW.operator_name,NEW.input_hash,
        NEW.source_fingerprint,NEW.baseline_catalog_hash,NEW.baseline_version_revision,NEW.input_json,NEW.planned_catalog_json,NEW.create_time)
        IS DISTINCT FROM ROW(OLD.import_id,OLD.project_id,OLD.project_version_id,OLD.operator_name,OLD.input_hash,
        OLD.source_fingerprint,OLD.baseline_catalog_hash,OLD.baseline_version_revision,OLD.input_json,OLD.planned_catalog_json,OLD.create_time)
        OR OLD.status='COMMITTED' THEN RAISE EXCEPTION 'catalog import provenance is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_catalog_import_provenance BEFORE UPDATE ON qw_semantic_catalog_import
    FOR EACH ROW EXECUTE FUNCTION qw_guard_catalog_import_provenance();
