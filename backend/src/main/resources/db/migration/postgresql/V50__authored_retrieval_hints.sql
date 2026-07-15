-- NULL preserves the byte contract and hashes of existing published catalogs.
ALTER TABLE qw_semantic_model ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
ALTER TABLE qw_semantic_column ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
ALTER TABLE qw_semantic_metric ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
ALTER TABLE qw_semantic_dimension ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
ALTER TABLE qw_semantic_relationship ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
ALTER TABLE qw_semantic_rule ADD COLUMN retrieval_json TEXT CHECK (retrieval_json IS NULL OR jsonb_typeof(retrieval_json::jsonb)='object');
