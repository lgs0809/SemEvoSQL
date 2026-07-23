-- Published/archived semantic versions are immutable at the database boundary.
-- Application guards remain useful for friendly errors, but this trigger prevents an
-- accidental repository call, maintenance script, or future code path from mutating the
-- authoritative Catalog in place.

CREATE OR REPLACE FUNCTION qw_reject_published_semantic_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    version_id BIGINT;
    version_status VARCHAR(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        version_id := OLD.project_version_id;
    ELSE
        version_id := NEW.project_version_id;
    END IF;
    SELECT status INTO version_status FROM qw_project_version WHERE id = version_id;
    IF version_status IN ('PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'Semantic Catalog version % is immutable while status=%', version_id, version_status
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'qw_semantic_model',
        'qw_semantic_column',
        'qw_semantic_metric',
        'qw_semantic_dimension',
        'qw_semantic_relationship',
        'qw_semantic_grain',
        'qw_semantic_enum_value',
        'qw_semantic_rule'
    ]
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_immutable ON %I', table_name, table_name);
        EXECUTE format(
            'CREATE TRIGGER trg_%s_immutable BEFORE INSERT OR UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION qw_reject_published_semantic_catalog_mutation()',
            table_name, table_name);
    END LOOP;
END;
$$;
