"""Regression of final-receipt selection, independent of model/network availability."""
import unittest
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact, numeric_table_matches, query_result_contract_matches_answers

class FinalResultEvidenceTest(unittest.TestCase):
    def fixture(self):
        return {
            'sqlExecutionAttempts': [
                {'phase': 'QUERY', 'sql_attempt_id': 'q1', 'create_time': '1', 'status': 'SUCCEEDED', 'datasource_id': 1,
                 'result_json': {'column': ['amount'], 'data': [{'amount': '100'}]}},
                {'phase': 'QUERY', 'sql_attempt_id': 'q2', 'create_time': '2', 'status': 'SUCCEEDED', 'datasource_id': 1,
                 'result_json': {'column': ['amount'], 'data': [{'amount': '90'}]}}],
            'resultArtifacts': [
                {'artifact_id': 'old', 'artifact_type': 'MERGED_RESULT', 'status': 'READY', 'update_time': '1',
                 'schema_json': ['amount'], 'data_json': [{'amount': '100'}]},
                {'artifact_id': 'new', 'artifact_type': 'DIRECT_RESULT', 'status': 'READY', 'update_time': '2',
                 'schema_json': ['amount'], 'data_json': [{'amount': '90.00'}]}],
            'merges': [{'merge_id': 'merge', 'status': 'COMPLETED', 'update_time': '1', 'output_artifact_id': 'old'}],
            'events': []}

    def test_earlier_matching_result_does_not_hide_stale_display(self):
        e = self.fixture()
        self.assertEqual('q2', final_query_receipt(e)['sql_attempt_id'])
        self.assertEqual('old', final_result_artifact(e)['artifact_id'])
        self.assertFalse(final_receipt_matches_artifact(e))

    def test_review_receipt_selects_exact_replacement(self):
        e = self.fixture()
        e['events'].append({'sequence': 1, 'event_type': 'RESULT_ARTIFACT_ACCEPTED', 'payload': '{"artifactId":"new"}'})
        self.assertEqual('new', final_result_artifact(e)['artifact_id'])
        self.assertTrue(final_receipt_matches_artifact(e))

    def test_missing_accepted_artifact_does_not_use_older_merge(self):
        e = self.fixture()
        e['events'].append({'sequence': 1, 'event_type': 'RESULT_ARTIFACT_ACCEPTED', 'payload': '{"artifactId":"missing"}'})
        self.assertIsNone(final_result_artifact(e))
        self.assertFalse(final_receipt_matches_artifact(e))

    def test_failed_last_query_is_not_replaced_by_earlier_success(self):
        e = self.fixture()
        e['sqlExecutionAttempts'][-1]['status'] = 'FAILED'
        self.assertFalse(final_receipt_matches_artifact(e))

    def test_scalar_proof_does_not_claim_multi_source_merge_validation(self):
        e = self.fixture()
        e['sqlExecutionAttempts'][-1]['datasource_id'] = 2
        e['events'].append({'sequence': 1, 'event_type': 'RESULT_ARTIFACT_ACCEPTED', 'payload': '{"artifactId":"new"}'})
        self.assertFalse(final_receipt_matches_artifact(e))

    def test_daily_oracle_checks_each_date_and_duplicate_row_even_when_totals_match(self):
        oracle = [{'day': '2026-01-10', 'amount': 10}, {'day': '2026-01-11', 'amount': 20}]
        actual = [{'ordered_at_day': '2026-01-11T00:00:00', 'metric': '20.00'},
                  {'ordered_at_day': '2026-01-10', 'metric': '10.0'}]
        columns = {'ordered_at_day': 'day', 'metric': 'amount'}
        self.assertTrue(numeric_table_matches(actual, oracle, columns, ('day',), True))
        actual[0]['metric'], actual[1]['metric'] = '10', '20'
        self.assertFalse(numeric_table_matches(actual, oracle, columns, ('day',), True))
        self.assertFalse(numeric_table_matches([actual[0], actual[0]], oracle, columns, ('day',), True))
        actual[0]['metric'] = 'NaN'
        self.assertFalse(numeric_table_matches(actual, oracle, columns, ('day',), True))

    def test_database_decimal_oracle_preserves_average_and_detects_small_error(self):
        import importlib.util
        from pathlib import Path
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('oracle_decimal', Path(__file__).with_name('verify-offline-catalog.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with patch.object(module.subprocess, 'check_output', return_value='{"average":498.7538472834067548,"count":681}\n'):
            oracle = module.sql('fixture', 'SELECT AVG(amount) AS average', preserve_decimals=True)
        self.assertEqual([{'average': '498.7538472834067548', 'count': 681}], oracle)
        actual = [{'average': '498.753847283406754800', 'count': '681'}]
        columns = {'average': 'average', 'count': 'count'}
        self.assertTrue(numeric_table_matches(actual, oracle, columns, exact_columns=True))
        actual[0]['average'] = '498.7538472834067549'
        self.assertFalse(numeric_table_matches(actual, oracle, columns, exact_columns=True))

class QueryContractEvidenceTest(unittest.TestCase):
    def fixture(self):
        import hashlib
        identity = '1c67f9d2-c51e-4809-9891-7bf54a831fbb'
        text = '完整计算口径、分母范围与月份归属。'
        q = {'clarification_id': identity, 'revision': 1, 'raw_expression': '本次度量',
             'resolved_value': text, 'run_id': 'actual-run', 'status': 'ANSWERED',
             'selected_scope': 'QUERY', 'answered_by': 'alice'}
        a = {'clarification_id': identity, 'selected_scope': 'QUERY', 'answered_by': 'alice', 'custom_answer': text}
        measure = {'clarificationId': identity, 'sourceRevision': 1, 'definitionText': text,
                   'outputCode': 'q_1c67f9d2c51e480998917bf54a831fbb_1', 'businessName': '本次度量',
                   'sourceContentHash': hashlib.sha256(text.encode()).hexdigest()}
        return {'resultContract': {'queryMeasures': [measure]}}, {'run': [{'run_id': 'actual-run'}], 'questions': [q], 'answers': [a]}

    def test_exact_full_query_text_and_owner_are_required(self):
        import copy
        plan, evidence = self.fixture()
        self.assertTrue(query_result_contract_matches_answers(plan, evidence, 'alice'))
        for field, value in [('sourceRevision', 2), ('definitionText', '截断的口径'),
                             ('sourceContentHash', 'different'), ('outputCode', 'invented'), ('businessName', 'different')]:
            changed = copy.deepcopy(plan)
            changed['resultContract']['queryMeasures'][0][field] = value
            self.assertFalse(query_result_contract_matches_answers(changed, evidence, 'alice'))
        self.assertFalse(query_result_contract_matches_answers(plan, evidence, 'bob'))

    def test_cross_query_saved_pending_and_duplicate_output_receipts_are_not_proof(self):
        import copy
        plan, evidence = self.fixture()
        for field, value in [('run_id', 'other-run'), ('selected_scope', 'USER'), ('status', 'PENDING')]:
            changed = copy.deepcopy(evidence)
            changed['questions'][0][field] = value
            self.assertFalse(query_result_contract_matches_answers(plan, changed, 'alice'))
        evidence['answers'] = []
        self.assertFalse(query_result_contract_matches_answers(plan, evidence, 'alice'))
        plan, evidence = self.fixture()
        plan['resultContract']['queryMeasures'] *= 2
        self.assertFalse(query_result_contract_matches_answers(plan, evidence, 'alice'))

if __name__ == '__main__':
    unittest.main()
