-- Every authoritative catalog mutation serializes with validation/publication.
-- Check both sides of a moved row, so a published row cannot be moved to a draft.
CREATE OR REPLACE FUNCTION qw_reject_published_semantic_catalog_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    version_ids BIGINT[];
    version_id BIGINT;
    version_status VARCHAR(32);
    version_project BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        version_ids := ARRAY[NEW.project_version_id];
    ELSIF TG_OP = 'DELETE' THEN
        version_ids := ARRAY[OLD.project_version_id];
    ELSE
        version_ids := ARRAY[OLD.project_version_id,NEW.project_version_id];
    END IF;
    FOR version_id IN SELECT DISTINCT value FROM unnest(version_ids) AS value ORDER BY value LOOP
        SELECT status,project_id INTO version_status,version_project
            FROM qw_project_version WHERE id=version_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Unknown semantic catalog version %',version_id USING ERRCODE='23503';
        END IF;
        IF version_status IN ('PUBLISHED','ARCHIVED') THEN
            RAISE EXCEPTION 'Semantic Catalog version % is immutable while status=%',version_id,version_status
                USING ERRCODE='55000';
        END IF;
        IF (TG_OP <> 'INSERT' AND version_id=OLD.project_version_id AND version_project<>OLD.project_id)
            OR (TG_OP <> 'DELETE' AND version_id=NEW.project_version_id AND version_project<>NEW.project_id) THEN
            RAISE EXCEPTION 'Semantic catalog project/version scope mismatch' USING ERRCODE='23503';
        END IF;
    END LOOP;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
