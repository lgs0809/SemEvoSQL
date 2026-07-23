-- Additive source support. Existing published snapshots keep their legacy physical-table identity.
ALTER TABLE qw_semantic_model ADD COLUMN source_json jsonb;
ALTER TABLE qw_semantic_model DROP CONSTRAINT uk_qw_semantic_model_table;
ALTER TABLE qw_semantic_model ADD CONSTRAINT ck_qw_model_source_object
    CHECK(source_json IS NULL OR jsonb_typeof(source_json)='object');
ALTER TABLE qw_semantic_column ADD COLUMN unit varchar(128);
