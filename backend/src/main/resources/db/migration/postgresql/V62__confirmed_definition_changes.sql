-- The immutable question is the authorization boundary; model proposals alone never write a definition.
CREATE TABLE qw_personal_definition_change (
    clarification_id varchar(64) PRIMARY KEY REFERENCES qw_runtime_clarification(clarification_id),
    preference_id bigint NOT NULL,
    definition_revision integer NOT NULL,
    authorization_revision integer NOT NULL,
    source_content_hash varchar(128) NOT NULL,
    proposal_json jsonb NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(preference_id,definition_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE TRIGGER immutable_personal_definition_change BEFORE UPDATE OR DELETE ON qw_personal_definition_change
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();

CREATE TABLE qw_personal_definition_change_receipt (
    clarification_id varchar(64) PRIMARY KEY REFERENCES qw_personal_definition_change(clarification_id),
    result_preference_id bigint NOT NULL,
    result_revision integer NOT NULL,
    selected_scope varchar(16) NOT NULL CHECK(selected_scope IN ('USER','PROJECT')),
    history_choice varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(result_preference_id,result_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE TRIGGER immutable_definition_change_receipt BEFORE UPDATE OR DELETE ON qw_personal_definition_change_receipt
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();

-- Explicit per-query consent survives a later FUTURE_ONLY private choice. No historical count is rewritten.
CREATE TABLE qw_personal_sharing_use_decision (
    id bigserial PRIMARY KEY,
    preference_id bigint NOT NULL,
    definition_revision integer NOT NULL,
    run_id varchar(64) NOT NULL,
    allowed boolean NOT NULL,
    clarification_id varchar(64) NOT NULL REFERENCES qw_personal_definition_change(clarification_id),
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(clarification_id,run_id),
    FOREIGN KEY(preference_id,definition_revision)
        REFERENCES qw_user_semantic_definition_revision(preference_id,revision)
);
CREATE INDEX personal_sharing_use_lookup ON qw_personal_sharing_use_decision(preference_id,definition_revision,run_id,id DESC);
CREATE TRIGGER immutable_personal_sharing_use_decision BEFORE UPDATE OR DELETE ON qw_personal_sharing_use_decision
    FOR EACH ROW EXECUTE FUNCTION qw_reject_shared_revision_mutation();

CREATE FUNCTION qw_project_sharing_use_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE qw_project_definition_candidate c SET evidence_revision=evidence_revision+1,row_revision=row_revision+1,
      update_time=CURRENT_TIMESTAMP
    FROM qw_project_definition_source s WHERE s.preference_id=NEW.preference_id
      AND s.definition_revision=NEW.definition_revision AND s.candidate_id=c.id;
    RETURN NEW;
END $$;
CREATE TRIGGER project_sharing_use_changed AFTER INSERT ON qw_personal_sharing_use_decision
    FOR EACH ROW EXECUTE FUNCTION qw_project_sharing_use_changed();
