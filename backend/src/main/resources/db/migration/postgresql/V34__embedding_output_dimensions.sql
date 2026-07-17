ALTER TABLE model_config ADD COLUMN embedding_dimensions INTEGER CHECK (embedding_dimensions BETWEEN 1 AND 65536);
-- NULL retains the previous provider-defined dimensionality; no existing model or vector is changed.
