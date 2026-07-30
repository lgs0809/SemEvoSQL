import copy
import unittest
from frozen_output_identity import approved_output_mapping


class ApprovedOutputIdentityTest(unittest.TestCase):
    def setUp(self):
        self.case = {'columns': {'created_time': 'month', 'count': 'n'}, 'dateColumns': ['month'],
            'oracleSql': "SELECT date_trunc('month',created_at)::date AS month,count(*) AS n FROM events WHERE created_at >= TIMESTAMP '2026-01-01' GROUP BY 1"}
        self.catalog = {'dimensions': [{'code': 'created_time', 'entity': 'event_model', 'attribute': 'created'}],
            'entities': [{'code': 'event_model', 'source': {'tables': [{'alias': 'e', 'schema': 'public', 'table': 'events'}]},
                'attributes': [{'code': 'created', 'dataType': 'datetime', 'mapping': {'source': 'e', 'column': 'created_at'}}]}]}
        self.group = {'alias': 'created_at_month', 'modelCode': 'event_model', 'columnName': 'created_at',
            'timeBucketGranularity': 'MONTH', 'expression': "DATE_TRUNC('month', created_at)"}
        self.plan = {'models': [{'modelCode': 'event_model', 'datasourceId': 7, 'physicalTable': 'public.events'}],
            'groupBy': [self.group], 'projections': [dict(self.group, projectionType='TIME_BUCKET')],
            'expectedResult': {'columns': ['created_at_month', 'count']}}

    def resolve(self):
        return approved_output_mapping(self.case, self.plan, self.catalog, 7)

    def test_unique_approved_bucket_rebinds_without_mutating_inputs(self):
        before = copy.deepcopy((self.case, self.plan, self.catalog))
        mapping, proof = self.resolve()
        self.assertEqual(mapping, {'created_at_month': 'month', 'count': 'n'})
        self.assertEqual(proof[0]['columnName'], 'created_at')
        self.assertEqual((self.case, self.plan, self.catalog), before)

    def test_questions_and_result_values_cannot_influence_binding(self):
        self.case.update(id='unrelated-case', question='irrelevant', expectedRows=[{'month': '1900-01-01', 'n': -42}])
        self.assertEqual(self.resolve()[0], {'created_at_month': 'month', 'count': 'n'})

    def test_duplicate_matching_groups_are_rejected(self):
        self.plan['groupBy'].append(dict(self.group, alias='other_month'))
        with self.assertRaises(ValueError): self.resolve()

    def test_duplicate_projection_and_unexpected_extra_column_are_rejected(self):
        self.plan['projections'].append(dict(self.plan['projections'][0]))
        with self.assertRaises(ValueError): self.resolve()
        self.plan['projections'].pop()
        self.plan['expectedResult']['columns'].append('unexpected')
        with self.assertRaises(ValueError): self.resolve()

    def test_wrong_source_field_or_granularity_are_rejected(self):
        self.plan['models'][0]['datasourceId'] = 8
        with self.assertRaises(ValueError): self.resolve()
        self.plan['models'][0]['datasourceId'] = 7
        self.group['columnName'] = 'paid_at'
        with self.assertRaises(ValueError): self.resolve()
        self.group['columnName'] = 'created_at'
        self.group['timeBucketGranularity'] = 'DAY'
        with self.assertRaises(ValueError): self.resolve()

    def test_expression_and_projection_disagreement_are_rejected(self):
        self.group['expression'] = "DATE_TRUNC('day', created_at)"
        with self.assertRaises(ValueError): self.resolve()
        self.group['expression'] = "DATE_TRUNC('month', created_at)"
        self.plan['projections'][0]['columnName'] = 'paid_at'
        with self.assertRaises(ValueError): self.resolve()

    def test_multiple_oracle_buckets_and_join_sources_fail_closed(self):
        self.case['oracleSql'] += ", date_trunc('month',created_at)::date AS another"
        with self.assertRaises(ValueError): self.resolve()
        self.case['oracleSql'] = "SELECT date_trunc('month',created_at)::date AS month,count(*) AS n FROM events JOIN users ON users.id=events.id"
        with self.assertRaises(ValueError): self.resolve()

    def test_explicit_or_implicit_oracle_schema_must_match_frozen_source(self):
        self.case['oracleSql'] = self.case['oracleSql'].replace('FROM events', 'FROM archive.events')
        with self.assertRaises(ValueError): self.resolve()
        self.case['oracleSql'] = self.case['oracleSql'].replace('archive.events', 'public.events')
        self.assertEqual(self.resolve()[0]['created_at_month'], 'month')
        self.catalog['entities'][0]['source']['tables'][0]['schema'] = 'archive'
        self.case['oracleSql'] = self.case['oracleSql'].replace('public.events', 'events')
        with self.assertRaises(ValueError): self.resolve()

    def test_date_label_alone_cannot_rebind_a_non_time_metric(self):
        self.case['columns'] = {'unknown_metric': 'month', 'count': 'n'}
        with self.assertRaises(ValueError): self.resolve()

class FrozenProvenanceTest(unittest.TestCase):
    def setUp(self):
        import hashlib
        import json
        self.json = json
        self.hashlib = hashlib
        self.suite = {'projectId': 8, 'projectVersionId': 11}
        self.package = {'sourceSchemaFingerprint': 'sha256:fingerprint', 'catalog': {'dimensions': []}}
        self.bytes = json.dumps(self.package).encode()
        self.hash = hashlib.sha256(self.bytes).hexdigest()
        canonical = 'sha256:' + hashlib.sha256(json.dumps(self.package, ensure_ascii=False, sort_keys=True,
            separators=(',', ':')).encode()).hexdigest()
        self.imports = {'imports': [{'project_id': 8, 'project_version_id': 11, 'status': 'COMMITTED',
            'input_hash': canonical, 'source_fingerprint': 'sha256:fingerprint', 'import_id': 'actual-import'}]}
        self.published = {'database': [{'id': 8, 'version_id': 11, 'active_version_id': 11,
            'status': 'PUBLISHED', 'catalog_hash': 'frozen-catalog'}]}
        self.binding = {'projectId': 8, 'projectVersionId': 11, 'publishedCatalogHash': 'frozen-catalog'}
        self.plan = {'projectId': 8, 'projectVersionId': 11, 'expectedResult': {'columns': ['a']}}
        self.plan_event = {'event_type': 'APPROVAL_PLAN_SNAPSHOT', 'sequence': 10, 'payload': json.dumps(self.plan)}
        recovery = json.dumps({'runId': 'run-1', 'requestId': 'request-1', 'recoveredSemanticPlan': self.plan})
        self.answer = {'event_type': 'HUMAN_FEEDBACK_ANSWERED', 'sequence': 12,
            'idempotency_key': 'human-feedback:request-1:approve:10',
            'payload': json.dumps({'answerPayload': json.dumps({'approved': True}),
                'recoveryHash': hashlib.sha256(recovery.encode()).hexdigest()})}
        self.evidence = {'run': [{'run_id': 'run-1', 'recovery_payload': recovery,
            'execution_snapshot': json.dumps({'payload': {'project': {'projectId': 8, 'projectVersionId': 11, 'catalogHash': 'frozen-catalog'}}})}],
            'events': [self.plan_event, {'event_type': 'HUMAN_FEEDBACK_REQUIRED', 'sequence': 11}, self.answer,
                {'event_type': 'HUMAN_FEEDBACK_APPLIED', 'sequence': 13, 'payload': json.dumps({'approved': True})}]}

    def test_catalog_bytes_import_and_publication_must_all_bind(self):
        from frozen_output_identity import verify_frozen_catalog
        package, proof = verify_frozen_catalog(self.suite, self.bytes, self.hash, self.imports, self.published)
        self.assertEqual(package, self.package)
        self.assertEqual(proof['importId'], 'actual-import')
        with self.assertRaises(ValueError): verify_frozen_catalog(self.suite, self.bytes, 'wrong', self.imports, self.published)
        self.imports['imports'][0]['project_version_id'] = 12
        with self.assertRaises(ValueError): verify_frozen_catalog(self.suite, self.bytes, self.hash, self.imports, self.published)

    def test_published_version_and_canonical_input_hash_cannot_be_substituted(self):
        from frozen_output_identity import verify_frozen_catalog
        self.published['database'][0]['status'] = 'DRAFT'
        with self.assertRaises(ValueError): verify_frozen_catalog(self.suite, self.bytes, self.hash, self.imports, self.published)
        self.published['database'][0]['status'] = 'PUBLISHED'
        self.imports['imports'][0]['input_hash'] = 'wrong'
        with self.assertRaises(ValueError): verify_frozen_catalog(self.suite, self.bytes, self.hash, self.imports, self.published)

    def test_exact_recovery_hash_and_plan_bind_the_receipt(self):
        from frozen_output_identity import verified_approval
        plan, proof = verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)
        self.assertEqual(plan, self.plan)
        self.assertEqual(proof['answerSequence'], 12)
        self.evidence['run'][0]['recovery_payload'] += ' '
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)

    def test_later_approval_cannot_bind_another_plan(self):
        from frozen_output_identity import verified_approval
        self.plan_event['payload'] = self.json.dumps(dict(self.plan, expectedResult={'columns': ['b']}))
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)

    def test_request_identity_and_catalog_snapshot_mismatch_fail_closed(self):
        from frozen_output_identity import verified_approval
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'another-request', self.binding)
        self.binding['publishedCatalogHash'] = 'another-catalog'
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)

    def test_multiple_answer_or_applied_receipts_are_rejected(self):
        from frozen_output_identity import verified_approval
        self.evidence['events'].append(dict(self.answer, sequence=14))
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)
        self.evidence['events'].pop()
        self.evidence['events'].append(dict(self.evidence['events'][-1], sequence=14))
        with self.assertRaises(ValueError): verified_approval(self.evidence, self.plan_event, 'request-1', self.binding)


if __name__ == '__main__': unittest.main()
