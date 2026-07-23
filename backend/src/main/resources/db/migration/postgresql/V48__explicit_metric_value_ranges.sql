-- Bounds describe the confirmed metric output scale. Existing units imply no bounds.
ALTER TABLE qw_semantic_metric
    ADD COLUMN minimum_value NUMERIC,
    ADD COLUMN maximum_value NUMERIC,
    ADD COLUMN minimum_inclusive BOOLEAN,
    ADD COLUMN maximum_inclusive BOOLEAN,
    ADD CONSTRAINT ck_qw_metric_range_nonempty CHECK (
        minimum_value IS NULL OR maximum_value IS NULL OR minimum_value < maximum_value OR
        (minimum_value = maximum_value AND coalesce(minimum_inclusive,true) AND coalesce(maximum_inclusive,true))),
    ADD CONSTRAINT ck_qw_metric_range_bound_flags CHECK (
        (minimum_inclusive IS NULL OR minimum_value IS NOT NULL) AND
        (maximum_inclusive IS NULL OR maximum_value IS NOT NULL));
