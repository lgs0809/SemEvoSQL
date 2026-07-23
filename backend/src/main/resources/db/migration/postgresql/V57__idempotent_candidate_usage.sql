-- Service writers acquire candidate coordination rows before changing contribution facts.
-- AFTER INSERT only runs for the actual inserted row: ON CONFLICT DO NOTHING is not new evidence.
DROP TRIGGER qw_candidate_usage_coordination ON qw_user_semantic_preference_usage;
CREATE TRIGGER qw_candidate_usage_insert AFTER INSERT ON qw_user_semantic_preference_usage
    FOR EACH ROW EXECUTE FUNCTION qw_coordinate_candidate_usage();
CREATE TRIGGER qw_candidate_usage_update BEFORE UPDATE ON qw_user_semantic_preference_usage
    FOR EACH ROW EXECUTE FUNCTION qw_coordinate_candidate_usage();
