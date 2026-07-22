-- Shared suggestions never enter the published Catalog index before version activation.
CREATE TABLE qw_project_definition_candidate (
    id bigserial PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES qw_project(id),
    content_revision integer NOT NULL DEFAULT 1,
    evidence_revision integer NOT NULL DEFAULT 1,
    row_revision bigint NOT NULL DEFAULT 1,
    lifecycle varchar(32) NOT NULL DEFAULT 'ACCUMULATING'
        CHECK(lifecycle IN ('ACCUMULATING','NEEDS_ADMIN_REVIEW','READY_FOR_PUBLISH','PUBLISHED','REJECTED')),
    business_name varchar(500) NOT NULL,
    definition_text text NOT NULL,
    content_hash varchar(128) NOT NULL,
    source_preference_id bigint NOT NULL,
    source_revision integer NOT NULL,
    base_version_id bigint,
    structured_json jsonb,
    representation_hash varchar(128),
    dependency_fingerprint varchar(128),
    blocked_reason varchar(128) DEFAULT 'STRUCTURE_PENDING',
    conflict_json jsonb NOT NULL DEFAULT '[]',
    published_version_id bigint,
    public_asset_key varchar(128),
    semantic_text text NOT NULL,
    lexical_vector tsvector GENERATED ALWAYS AS
        (to_tsvector('simple'::regconfig,qw_semantic_tokenize_v1(semantic_text))) STORED,
    embedding vector,
    embedding_model text,
    embedding_version text,
    embedding_dimensions integer,
    index_state varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK(index_state IN ('PENDING','RUNNING','RETRYABLE_FAILURE','DONE')),
    index_owner_token varchar(64),
    index_lease_until timestamp,
    index_attempt_count integer NOT NULL DEFAULT 0,
    index_next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    index_last_error varchar(128),
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(source_preference_id,source_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision),
    CHECK(embedding IS NULL OR vector_dims(embedding)=embedding_dimensions)
);
CREATE INDEX qw_project_definition_candidate_fts ON qw_project_definition_candidate USING gin(lexical_vector);
CREATE INDEX qw_project_definition_candidate_scope ON qw_project_definition_candidate(project_id,lifecycle);
CREATE TABLE qw_project_definition_source (
    preference_id bigint NOT NULL,
    definition_revision integer NOT NULL,
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    candidate_content_revision integer NOT NULL,
    equivalence_kind varchar(32) NOT NULL CHECK(equivalence_kind IN ('ORIGINAL','USER_CONFIRMED','MODEL_CONFIRMED')),
    equivalence_proof jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(preference_id,definition_revision),
    FOREIGN KEY(preference_id,definition_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE TABLE qw_project_definition_candidate_revision (
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    content_revision integer NOT NULL,
    business_name varchar(500) NOT NULL,
    definition_text text NOT NULL,
    content_hash varchar(128) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(candidate_id,content_revision)
);
CREATE TRIGGER immutable_project_candidate_revision BEFORE UPDATE OR DELETE ON qw_project_definition_candidate_revision
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();
CREATE TABLE qw_clarification_candidate_base (
    clarification_id varchar(64) NOT NULL REFERENCES qw_runtime_clarification(clarification_id),
    option_code varchar(128) NOT NULL,
    candidate_id bigint NOT NULL REFERENCES qw_project_definition_candidate(id),
    content_revision integer NOT NULL,
    project_id bigint NOT NULL,
    principal_id varchar(255) NOT NULL,
    definition_text text NOT NULL,
    content_hash varchar(128) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(clarification_id,option_code)
);
CREATE TRIGGER immutable_clarification_candidate_base BEFORE UPDATE OR DELETE ON qw_clarification_candidate_base
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();

CREATE FUNCTION qw_attach_shared_definition(preference bigint,source_revision integer) RETURNS void LANGUAGE plpgsql AS $$
DECLARE d record; candidate bigint; seen_revision integer; adopted bigint;
BEGIN
    IF EXISTS(SELECT 1 FROM qw_project_definition_source s WHERE s.preference_id=preference AND s.definition_revision=source_revision) THEN
        RETURN;
    END IF;
    SELECT p.project_id,p.display_phrase,p.user_id,r.* INTO d
    FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision r ON r.preference_id=p.id
    WHERE p.id=preference AND r.revision=source_revision;
    IF d.definition_snapshot->>'completeDefinitionRecorded' IS DISTINCT FROM 'true' THEN RETURN; END IF;
    IF d.source_kind='PROJECT_ADOPTION' AND d.definition_snapshot->'projectCandidate'->>'id' IS NOT NULL THEN
        adopted=(d.definition_snapshot->'projectCandidate'->>'id')::bigint;
        seen_revision=(d.definition_snapshot->'projectCandidate'->>'contentRevision')::integer;
        SELECT id INTO candidate FROM qw_project_definition_candidate c
        WHERE c.id=adopted AND c.project_id=d.project_id AND c.content_revision=seen_revision
          AND c.definition_text=d.definition_text AND c.lifecycle NOT IN ('REJECTED') FOR UPDATE;
        IF candidate IS NULL THEN RAISE EXCEPTION 'Stale or different shared candidate adoption'; END IF;
    ELSE
        INSERT INTO qw_project_definition_candidate(project_id,business_name,definition_text,content_hash,source_preference_id,
            source_revision,base_version_id,semantic_text)
        VALUES(d.project_id,d.display_phrase,d.definition_text,d.content_hash,preference,source_revision,d.base_version_id,
            concat_ws(E'\n',d.display_phrase,d.business_label,d.definition_text)) RETURNING id,content_revision INTO candidate,seen_revision;
        INSERT INTO qw_project_definition_candidate_revision(candidate_id,content_revision,business_name,definition_text,content_hash)
        SELECT c.id,c.content_revision,c.business_name,c.definition_text,c.content_hash FROM qw_project_definition_candidate c WHERE c.id=candidate;
    END IF;
    INSERT INTO qw_project_definition_source(preference_id,definition_revision,candidate_id,candidate_content_revision,equivalence_kind,equivalence_proof)
    VALUES(preference,source_revision,candidate,seen_revision,CASE WHEN adopted IS NULL THEN 'ORIGINAL' ELSE 'USER_CONFIRMED' END,
        jsonb_build_object('confirmationSource',d.source_id,'sourceContentHash',d.content_hash));
    UPDATE qw_project_definition_candidate SET evidence_revision=evidence_revision+1,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP WHERE id=candidate;
END $$;
CREATE FUNCTION qw_project_sharing_authorization() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE adopted boolean;
BEGIN
    SELECT source_kind='PROJECT_ADOPTION' AND definition_snapshot->'projectCandidate'->>'id' IS NOT NULL INTO adopted
    FROM qw_user_semantic_definition_revision WHERE preference_id=NEW.preference_id AND revision=NEW.definition_revision;
    IF NEW.choice='ALLOWED' OR adopted THEN PERFORM qw_attach_shared_definition(NEW.preference_id,NEW.definition_revision); END IF;
    UPDATE qw_project_definition_candidate c SET evidence_revision=evidence_revision+1,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP
    FROM qw_project_definition_source s WHERE s.preference_id=NEW.preference_id AND s.definition_revision=NEW.definition_revision AND s.candidate_id=c.id;
    RETURN NEW;
END $$;
CREATE TRIGGER qw_project_sharing_projection AFTER INSERT ON qw_user_semantic_authorization
    FOR EACH ROW EXECUTE FUNCTION qw_project_sharing_authorization();

CREATE FUNCTION qw_coordinate_candidate_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.valid=OLD.valid AND NEW.event_type=OLD.event_type THEN RETURN NEW; END IF;
    UPDATE qw_project_definition_candidate c SET evidence_revision=evidence_revision+1,row_revision=row_revision+1,update_time=CURRENT_TIMESTAMP
    FROM qw_project_definition_source s WHERE s.preference_id=NEW.preference_id AND s.definition_revision=NEW.definition_revision AND s.candidate_id=c.id;
    RETURN NEW;
END $$;
-- Candidate write ownership is acquired before contribution changes, including withdrawal.
CREATE TRIGGER qw_candidate_usage_coordination BEFORE INSERT OR UPDATE ON qw_user_semantic_preference_usage
    FOR EACH ROW EXECUTE FUNCTION qw_coordinate_candidate_usage();

DO $$ DECLARE r record; BEGIN
    FOR r IN SELECT a.preference_id,a.definition_revision FROM qw_user_semantic_authorization a
        WHERE a.choice='ALLOWED' AND a.authorization_revision=(SELECT max(b.authorization_revision)
            FROM qw_user_semantic_authorization b WHERE b.preference_id=a.preference_id AND b.definition_revision=a.definition_revision)
        ORDER BY a.preference_id,a.definition_revision
    LOOP PERFORM qw_attach_shared_definition(r.preference_id,r.definition_revision); END LOOP;
END $$;
