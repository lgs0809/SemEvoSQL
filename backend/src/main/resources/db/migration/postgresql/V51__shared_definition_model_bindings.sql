-- Immutable meaning revisions and version-scoped model roles. Same names are never merged.
ALTER TABLE qw_project_version ADD CONSTRAINT uq_qw_shared_version_scope UNIQUE(project_id,id);
CREATE TABLE qw_semantic_definition_revision (
    project_id BIGINT NOT NULL REFERENCES qw_project(id),
    definition_code VARCHAR(128) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision>0),
    asset_type VARCHAR(32) NOT NULL CHECK(asset_type IN ('METRIC','DIMENSION','ATTRIBUTE')),
    definition_json JSONB NOT NULL CHECK(jsonb_typeof(definition_json)='object'),
    content_hash VARCHAR(80) NOT NULL,definition_format VARCHAR(32) NOT NULL DEFAULT 'AST_V1_2' CHECK(definition_format IN ('AST_V1_2','LEGACY_PROJECTION')),
    PRIMARY KEY(project_id,definition_code,revision),UNIQUE(project_id,definition_code,revision,asset_type),
    CHECK(definition_json ?& ARRAY['code','revision','type'] AND definition_json->>'code'=definition_code
        AND (definition_json->>'revision')::integer=revision AND definition_json->>'type'=asset_type)
);
CREATE TABLE qw_semantic_enum_dictionary_revision (
    project_id BIGINT NOT NULL REFERENCES qw_project(id),dictionary_code VARCHAR(128) NOT NULL,
    revision INTEGER NOT NULL CHECK(revision>0),metadata_json JSONB NOT NULL CHECK(jsonb_typeof(metadata_json)='object'),
    content_hash VARCHAR(80) NOT NULL,PRIMARY KEY(project_id,dictionary_code,revision),
    CHECK(metadata_json ?& ARRAY['code','revision'] AND metadata_json->>'code'=dictionary_code AND (metadata_json->>'revision')::integer=revision)
);
CREATE TABLE qw_semantic_enum_dictionary_entry (
    project_id BIGINT NOT NULL,dictionary_code VARCHAR(128) NOT NULL,revision INTEGER NOT NULL,
    value_key TEXT NOT NULL,ordinal_no INTEGER NOT NULL CHECK(ordinal_no>=0),entry_json JSONB NOT NULL CHECK(jsonb_typeof(entry_json)='object'),
    PRIMARY KEY(project_id,dictionary_code,revision,value_key),UNIQUE(project_id,dictionary_code,revision,ordinal_no),
    FOREIGN KEY(project_id,dictionary_code,revision) REFERENCES qw_semantic_enum_dictionary_revision
);
CREATE TABLE qw_semantic_version_definition (
    project_id BIGINT NOT NULL,project_version_id BIGINT NOT NULL,definition_code VARCHAR(128) NOT NULL,definition_revision INTEGER NOT NULL,
    PRIMARY KEY(project_id,project_version_id,definition_code,definition_revision),
    FOREIGN KEY(project_id,project_version_id) REFERENCES qw_project_version(project_id,id),
    FOREIGN KEY(project_id,definition_code,definition_revision) REFERENCES qw_semantic_definition_revision
);
CREATE TABLE qw_semantic_version_dictionary (
    project_id BIGINT NOT NULL,project_version_id BIGINT NOT NULL,dictionary_code VARCHAR(128) NOT NULL,dictionary_revision INTEGER NOT NULL,
    PRIMARY KEY(project_id,project_version_id,dictionary_code,dictionary_revision),
    FOREIGN KEY(project_id,project_version_id) REFERENCES qw_project_version(project_id,id),
    FOREIGN KEY(project_id,dictionary_code,dictionary_revision) REFERENCES qw_semantic_enum_dictionary_revision
);
CREATE TABLE qw_semantic_model_asset_binding (
    project_id BIGINT NOT NULL,project_version_id BIGINT NOT NULL,model_code VARCHAR(128) NOT NULL,binding_code VARCHAR(128) NOT NULL,
    definition_code VARCHAR(128) NOT NULL,definition_revision INTEGER NOT NULL,
    asset_type VARCHAR(32) NOT NULL CHECK(asset_type IN ('METRIC','DIMENSION','ATTRIBUTE')),asset_key VARCHAR(128) NOT NULL,
    dictionary_code VARCHAR(128),dictionary_revision INTEGER,binding_json JSONB NOT NULL CHECK(jsonb_typeof(binding_json)='object'),
    PRIMARY KEY(project_version_id,model_code,binding_code),UNIQUE(project_version_id,asset_type,model_code,asset_key),
    CHECK((dictionary_code IS NULL)=(dictionary_revision IS NULL)),
    CHECK(binding_json ?& ARRAY['model','code','definition','definitionRevision'] AND binding_json->>'model'=model_code
        AND binding_json->>'code'=binding_code AND binding_json->>'definition'=definition_code
        AND (binding_json->>'definitionRevision')::integer=definition_revision),
    FOREIGN KEY(project_id,project_version_id) REFERENCES qw_project_version(project_id,id),
    FOREIGN KEY(project_version_id,model_code) REFERENCES qw_semantic_model(project_version_id,model_code),
    FOREIGN KEY(project_id,project_version_id,definition_code,definition_revision) REFERENCES qw_semantic_version_definition,
    FOREIGN KEY(project_id,definition_code,definition_revision,asset_type) REFERENCES qw_semantic_definition_revision(project_id,definition_code,revision,asset_type),
    FOREIGN KEY(project_id,project_version_id,dictionary_code,dictionary_revision) REFERENCES qw_semantic_version_dictionary
);
CREATE INDEX idx_qw_shared_definition_models ON qw_semantic_model_asset_binding(project_id,definition_code,definition_revision,project_version_id,model_code);
CREATE INDEX idx_qw_shared_dictionary_roles ON qw_semantic_model_asset_binding(project_id,dictionary_code,dictionary_revision,project_version_id,model_code);
-- Preserve every historical model asset independently, including duplicate names. Published assets
-- and their old catalog fingerprints stay unchanged; legacy projections are not importable ASTs.
CREATE FUNCTION qw_register_legacy_model_assets(project_scope BIGINT,version_scope BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE asset RECORD;definition_id TEXT;binding_id TEXT;definition_rev INTEGER;payload JSONB;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM qw_project_version WHERE project_id=project_scope AND id=version_scope) THEN
        RAISE EXCEPTION 'Unknown legacy catalog scope';END IF;
    FOR asset IN
        SELECT 'METRIC'::text AS kind,m.model_code,m.metric_code AS asset_key,m.business_name,m.description,
            to_jsonb(m)-ARRAY['id','project_id','project_version_id','create_time','update_time','evidence'] AS projection
            FROM qw_semantic_metric m WHERE m.project_id=project_scope AND m.project_version_id=version_scope
        UNION ALL SELECT 'DIMENSION',d.model_code,d.dimension_code,d.business_name,d.description,
            to_jsonb(d)-ARRAY['id','project_id','project_version_id','create_time','update_time','evidence']
            FROM qw_semantic_dimension d WHERE d.project_id=project_scope AND d.project_version_id=version_scope
        UNION ALL SELECT 'ATTRIBUTE',c.model_code,c.column_name,c.business_name,c.description,
            to_jsonb(c)-ARRAY['id','project_id','project_version_id','create_time','update_time','evidence']
            FROM qw_semantic_column c WHERE c.project_id=project_scope AND c.project_version_id=version_scope
    LOOP
        IF EXISTS(SELECT 1 FROM qw_semantic_model_asset_binding WHERE project_id=project_scope AND project_version_id=version_scope
            AND asset_type=asset.kind AND model_code=asset.model_code AND asset_key=asset.asset_key) THEN CONTINUE;END IF;
        definition_id:='legacy_'||md5(jsonb_build_array(version_scope,asset.model_code,asset.kind,asset.asset_key)::text);
        binding_id:='legacy_'||md5(jsonb_build_array(asset.kind,asset.asset_key)::text);
        SELECT revision INTO definition_rev FROM qw_semantic_definition_revision WHERE project_id=project_scope
            AND definition_code=definition_id AND definition_format='LEGACY_PROJECTION'
            AND definition_json->'specification'->'legacyProjection'=asset.projection ORDER BY revision DESC LIMIT 1;
        IF definition_rev IS NULL THEN
            SELECT COALESCE(max(revision),0)+1 INTO definition_rev FROM qw_semantic_definition_revision WHERE project_id=project_scope AND definition_code=definition_id;
            payload:=jsonb_build_object('code',definition_id,'revision',definition_rev,'type',asset.kind,
                'name',COALESCE(asset.business_name,asset.asset_key),'description',COALESCE(asset.description,''),'aliases','[]'::jsonb,
                'specification',jsonb_build_object('legacyProjection',asset.projection));
            INSERT INTO qw_semantic_definition_revision(project_id,definition_code,revision,asset_type,definition_json,content_hash,definition_format)
                VALUES(project_scope,definition_id,definition_rev,asset.kind,payload,'sha256:'||encode(sha256(convert_to(payload::text,'UTF8')),'hex'),'LEGACY_PROJECTION');
        END IF;
        INSERT INTO qw_semantic_version_definition VALUES(project_scope,version_scope,definition_id,definition_rev) ON CONFLICT DO NOTHING;
        payload:=jsonb_build_object('model',asset.model_code,'code',binding_id,'definition',definition_id,'definitionRevision',definition_rev,
            'roleName',COALESCE(asset.business_name,asset.asset_key),'aliases','[]'::jsonb,'attributeMappings','{}'::jsonb,'legacyAssetKey',asset.asset_key);
        INSERT INTO qw_semantic_model_asset_binding(project_id,project_version_id,model_code,binding_code,definition_code,definition_revision,asset_type,asset_key,binding_json)
            VALUES(project_scope,version_scope,asset.model_code,binding_id,definition_id,definition_rev,asset.kind,asset.asset_key,payload);
    END LOOP;
END;
$$;
-- Run the backfill before installing immutability/version guards; no historical asset row is edited.
DO $$ DECLARE scope RECORD;BEGIN FOR scope IN SELECT project_id,id FROM qw_project_version LOOP
    PERFORM qw_register_legacy_model_assets(scope.project_id,scope.id);END LOOP;END;$$;

CREATE FUNCTION qw_reject_shared_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Shared semantic revisions are immutable; create a new revision' USING ERRCODE='55000'; END;
$$;
CREATE TRIGGER immutable_shared_definition BEFORE UPDATE OR DELETE ON qw_semantic_definition_revision FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TRIGGER immutable_shared_dictionary BEFORE UPDATE OR DELETE ON qw_semantic_enum_dictionary_revision FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TRIGGER immutable_shared_dictionary_entry BEFORE UPDATE OR DELETE ON qw_semantic_enum_dictionary_entry FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TRIGGER shared_definition_membership_scope BEFORE INSERT OR UPDATE OR DELETE ON qw_semantic_version_definition FOR EACH ROW EXECUTE FUNCTION qw_reject_published_semantic_catalog_mutation();
CREATE TRIGGER shared_dictionary_membership_scope BEFORE INSERT OR UPDATE OR DELETE ON qw_semantic_version_dictionary FOR EACH ROW EXECUTE FUNCTION qw_reject_published_semantic_catalog_mutation();
CREATE TRIGGER shared_model_binding_scope BEFORE INSERT OR UPDATE OR DELETE ON qw_semantic_model_asset_binding FOR EACH ROW EXECUTE FUNCTION qw_reject_published_semantic_catalog_mutation();
-- Bound legacy rows are deterministic projections, never a second editable formula source.
CREATE FUNCTION qw_reject_bound_asset_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE asset_kind TEXT;asset_identity TEXT;
BEGIN
    PERFORM 1 FROM qw_project_version WHERE project_id=OLD.project_id AND id=OLD.project_version_id FOR UPDATE;
    IF TG_TABLE_NAME='qw_semantic_metric' THEN asset_kind:='METRIC';asset_identity:=OLD.metric_code;
    ELSIF TG_TABLE_NAME='qw_semantic_dimension' THEN asset_kind:='DIMENSION';asset_identity:=OLD.dimension_code;
    ELSE asset_kind:='ATTRIBUTE';asset_identity:=OLD.column_name; END IF;
    IF EXISTS(SELECT 1 FROM qw_semantic_model_asset_binding WHERE project_version_id=OLD.project_version_id
        AND model_code=OLD.model_code AND asset_type=asset_kind AND asset_key=asset_identity) THEN
        RAISE EXCEPTION 'Edit the shared definition or model binding, not its projection' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END;
$$;
CREATE TRIGGER protect_bound_metric BEFORE UPDATE OR DELETE ON qw_semantic_metric FOR EACH ROW EXECUTE FUNCTION qw_reject_bound_asset_mutation();
CREATE TRIGGER protect_bound_dimension BEFORE UPDATE OR DELETE ON qw_semantic_dimension FOR EACH ROW EXECUTE FUNCTION qw_reject_bound_asset_mutation();
CREATE TRIGGER protect_bound_attribute BEFORE UPDATE OR DELETE ON qw_semantic_column FOR EACH ROW EXECUTE FUNCTION qw_reject_bound_asset_mutation();

CREATE FUNCTION qw_reject_late_dictionary_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM qw_semantic_version_dictionary WHERE project_id=NEW.project_id
        AND dictionary_code=NEW.dictionary_code AND dictionary_revision=NEW.revision) THEN
        RAISE EXCEPTION 'A referenced dictionary revision cannot gain new entries' USING ERRCODE='55000';
    END IF;RETURN NEW;
END;
$$;
CREATE TRIGGER protect_completed_dictionary BEFORE INSERT ON qw_semantic_enum_dictionary_entry FOR EACH ROW EXECUTE FUNCTION qw_reject_late_dictionary_entry();
