-- This projection is deliberately separate from published catalog and shared suggestions.
-- Text is searchable in the same transaction that confirms its immutable source revision.
CREATE TABLE qw_personal_definition_document (
    preference_id bigint PRIMARY KEY,
    source_revision integer NOT NULL,
    source_content_hash varchar(128) NOT NULL,
    project_id bigint NOT NULL,
    principal_id varchar(255) NOT NULL,
    semantic_text text NOT NULL,
    lexical_vector tsvector GENERATED ALWAYS AS
        (to_tsvector('simple'::regconfig, qw_semantic_tokenize_v1(semantic_text))) STORED,
    embedding vector,
    embedding_model text,
    embedding_version text,
    embedding_dimensions integer,
    task_state varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK(task_state IN ('PENDING','RUNNING','RETRYABLE_FAILURE','DONE')),
    owner_token varchar(64),
    lease_until timestamp,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(128),
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(preference_id,source_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision),
    CHECK(embedding IS NULL OR vector_dims(embedding)=embedding_dimensions)
);
CREATE INDEX qw_personal_definition_fts ON qw_personal_definition_document USING gin(lexical_vector);
CREATE INDEX qw_personal_definition_owner ON qw_personal_definition_document(project_id,principal_id);

CREATE FUNCTION qw_project_personal_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.definition_snapshot->>'completeDefinitionRecorded' IS DISTINCT FROM 'true' THEN
        RETURN NEW;
    END IF;
    INSERT INTO qw_personal_definition_document(preference_id,source_revision,source_content_hash,project_id,principal_id,semantic_text)
    SELECT NEW.preference_id,NEW.revision,NEW.content_hash,p.project_id,p.user_id,
        concat_ws(E'\n',p.display_phrase,NEW.business_label,NEW.definition_text)
    FROM qw_user_semantic_preference p WHERE p.id=NEW.preference_id
    ON CONFLICT(preference_id) DO UPDATE SET
        source_revision=EXCLUDED.source_revision,source_content_hash=EXCLUDED.source_content_hash,
        semantic_text=EXCLUDED.semantic_text,embedding=NULL,embedding_model=NULL,embedding_version=NULL,
        embedding_dimensions=NULL,task_state='PENDING',owner_token=NULL,lease_until=NULL,
        attempt_count=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,update_time=CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
CREATE TRIGGER qw_personal_definition_projection AFTER INSERT ON qw_user_semantic_definition_revision
    FOR EACH ROW EXECUTE FUNCTION qw_project_personal_definition();

INSERT INTO qw_personal_definition_document(preference_id,source_revision,source_content_hash,project_id,principal_id,semantic_text)
SELECT p.id,d.revision,d.content_hash,p.project_id,p.user_id,
    concat_ws(E'\n',p.display_phrase,d.business_label,d.definition_text)
FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d
    ON d.preference_id=p.id AND d.revision=p.current_revision
WHERE NOT p.archived AND d.definition_snapshot->>'completeDefinitionRecorded'='true';
