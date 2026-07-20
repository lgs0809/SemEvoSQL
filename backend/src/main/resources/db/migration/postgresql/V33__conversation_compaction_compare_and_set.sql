ALTER TABLE qw_conversation_context_compaction
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN valid BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing summaries remain available for audit but need rebuilding with source guards.
-- This lock is shared with the short compaction commit, never held during model calls.
CREATE FUNCTION qw_guard_compaction_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_thread VARCHAR(160); source_sequence BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.thread_id <> OLD.thread_id OR NEW.turn_sequence <> OLD.turn_sequence) THEN
        RAISE EXCEPTION 'Conversation turn identity is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'COMPLETED' THEN RETURN OLD; END IF;
        source_thread := OLD.thread_id; source_sequence := OLD.turn_sequence;
    ELSE
        IF TG_OP = 'UPDATE' THEN
            NEW.revision := GREATEST(NEW.revision, OLD.revision + 1);
            IF NEW.status <> 'COMPLETED' AND OLD.status <> 'COMPLETED' THEN RETURN NEW; END IF;
        ELSIF NEW.status <> 'COMPLETED' THEN RETURN NEW;
        END IF;
        source_thread := NEW.thread_id; source_sequence := NEW.turn_sequence;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('semevosql-compaction:' || source_thread, 0));
    UPDATE qw_conversation_context_compaction SET valid=FALSE, revision=revision+1, update_time=CURRENT_TIMESTAMP
      WHERE thread_id=source_thread AND valid AND covered_through_sequence >= source_sequence;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

CREATE TRIGGER qw_compaction_source_changed BEFORE INSERT OR UPDATE OR DELETE ON qw_conversation_turn
    FOR EACH ROW EXECUTE FUNCTION qw_guard_compaction_source();
