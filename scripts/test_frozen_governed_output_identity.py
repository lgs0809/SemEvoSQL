import copy
import hashlib
import json
import unittest
from frozen_governed_output_identity import approved_governed_output_mapping, verify_governed_snapshot, PROJECTION_FIELDS


class GovernedDirectOutputIdentityTest(unittest.TestCase):
    def setUp(self):
        self.binding = {'projectId': 8, 'projectVersionId': 11, 'publishedCatalogHash': 'catalog'}
        column = {field: None for field in PROJECTION_FIELDS}
        column.update(model_code='orders', column_name='customer_id', business_name='客户标识',
            expression=None, allow_projection=True, allow_send_to_llm=True, status='ENABLED')
        definition = {'code': 'def1', 'revision': 3, 'type': 'ATTRIBUTE', 'name': '客户标识', 'aliases': [],
            'specification': {'legacyProjection': copy.deepcopy(column)}}
        binding = {'model': 'orders', 'code': 'binding1', 'definition': 'def1', 'definitionRevision': 3,
            'roleName': '客户标识', 'legacyAssetKey': 'customer_id', 'aliases': [], 'attributeMappings': {}}
        self.row = {'project_id': 8, 'project_version_id': 11, 'model_code': 'orders', 'binding_code': 'binding1',
            'definition_code': 'def1', 'definition_revision': 3, 'asset_type': 'ATTRIBUTE',
            'definition_format': 'LEGACY_PROJECTION', 'asset_key': 'customer_id', 'dictionary_code': None,
            'dictionary_revision': None, 'definition_json': definition, 'binding_json': binding,
            'column_projection': column, 'definition_content_hash': 'actual-hash', 'actual_definition_content_hash': 'actual-hash',
            'physical_table': 'public.orders', 'datasource_id': 7, 'model_status': 'ENABLED'}
        self.snapshot = {'catalogBinding': self.binding, 'version': {'project_id': 8, 'version_id': 11,
            'catalog_hash': 'catalog', 'status': 'PUBLISHED'}, 'attributes': [self.row]}
        self.ref = {'modelCode': 'orders', 'bindingCode': 'binding1', 'definitionCode': 'def1',
            'definitionRevision': 3, 'roleName': '客户标识', 'confirmedAliases': [], 'dictionaryCode': None, 'dictionaryRevision': None}
        self.group = {'alias': 'approved_customer', 'modelCode': 'orders', 'columnName': 'customer_id',
            'expression': 'customer_id', 'timeBucketGranularity': None}
        self.plan = {'projectId': 8, 'projectVersionId': 11, 'models': [{'modelCode': 'orders',
            'datasourceId': 7, 'physicalTable': 'public.orders'}], 'dimensions': [{'modelCode': 'orders',
            'columnName': 'customer_id', 'dimensionCode': 'approved_customer', 'dimensionType': 'ATTRIBUTE',
            'businessName': '客户标识', 'expression': None, 'definitionBinding': self.ref}],
            'groupBy': [self.group], 'projections': [dict(self.group, projectionType='DIMENSION')],
            'expectedResult': {'columns': ['approved_customer', 'amount']}}
        self.catalog = {'entities': [{'code': 'orders', 'attributes': [{'code': 'customer_id',
            'mapping': {'source': 'o', 'column': 'customer_id'}}],
            'source': {'tables': [{'alias': 'o', 'schema': 'public', 'table': 'orders'}]}}], 'dimensions': []}
        self.case = {'columns': {'customer_id': 'customer_id', 'amount': 'value'}, 'dateColumns': [],
            'oracleSql': 'SELECT o.customer_id,sum(amount) AS value FROM public.orders o GROUP BY o.customer_id'}

    def resolve(self):
        data = json.dumps(self.snapshot).encode()
        source = verify_governed_snapshot(data, hashlib.sha256(data).hexdigest(), self.binding)
        return approved_governed_output_mapping(self.case, self.plan, self.catalog, 7, source)

    def test_unique_direct_identity_rebinds_without_mutating_inputs(self):
        before = copy.deepcopy((self.case, self.plan, self.catalog, self.snapshot))
        mapping, proof = self.resolve()
        self.assertEqual(mapping, {'approved_customer': 'customer_id', 'amount': 'value'})
        self.assertEqual(proof[0]['definitionBinding'], self.ref)
        self.assertEqual((self.case, self.plan, self.catalog, self.snapshot), before)

    def test_question_id_and_expected_values_do_not_affect_identity(self):
        self.case.update(id='irrelevant', question='unrelated', expectedRows=[{'customer_id': -2, 'value': 9000}])
        self.assertEqual(self.resolve()[0]['approved_customer'], 'customer_id')

    def test_schema_join_cte_and_computed_oracle_are_rejected(self):
        for text in ['SELECT customer_id,sum(amount) AS value FROM archive.orders GROUP BY customer_id',
            'SELECT customer_id,sum(amount) AS value FROM orders JOIN customers USING(customer_id)',
            'WITH x AS (SELECT * FROM orders) SELECT customer_id FROM x',
            'SELECT customer_id+1 AS customer_id,sum(amount) AS value FROM orders GROUP BY customer_id',
            'SELECT x.customer_id,sum(amount) AS value FROM orders o GROUP BY customer_id']:
            with self.subTest(oracle=text):
                self.case['oracleSql'] = text
                with self.assertRaises(ValueError): self.resolve()

    def test_ambiguous_models_groups_or_projections_fail_closed(self):
        for target in ['entities', 'dimensions', 'groupBy', 'projections']:
            before = copy.deepcopy((self.catalog, self.plan))
            collection = self.catalog[target] if target == 'entities' else self.plan[target]
            collection.append(copy.deepcopy(collection[0]))
            with self.subTest(target=target):
                with self.assertRaises(ValueError): self.resolve()
            self.catalog, self.plan = before

    def test_original_revision_role_model_permissions_and_physical_source_are_required(self):
        for target, key, value in [('ref', 'definitionRevision', 2), ('ref', 'roleName', '其他'),
            ('ref', 'modelCode', 'another'), ('ref', 'confirmedAliases', ['alias']),
            ('row', 'physical_table', 'archive.orders'), ('row', 'datasource_id', 9),
            ('column', 'allow_projection', False), ('column', 'allow_send_to_llm', False),
            ('column', 'expression', 'customer_id+1')]:
            before = copy.deepcopy((self.plan, self.snapshot))
            item = self.plan['dimensions'][0]['definitionBinding'] if target == 'ref' else self.snapshot['attributes'][0]
            if target == 'column': item = item['column_projection']
            item[key] = value
            with self.subTest(target=target, key=key):
                with self.assertRaises(ValueError): self.resolve()
            self.plan, self.snapshot = before

    def test_all_19_immutable_properties_and_snapshot_identity_are_checked(self):
        for target, key, value in [('projection', 'nullable_flag', True), ('binding', 'roleName', 'changed'),
            ('row', 'actual_definition_content_hash', 'changed'), ('version', 'catalog_hash', 'changed')]:
            before = copy.deepcopy(self.snapshot)
            row = self.snapshot['attributes'][0]
            item = row['definition_json']['specification']['legacyProjection'] if target == 'projection' else (
                row['binding_json'] if target == 'binding' else self.snapshot['version'] if target == 'version' else row)
            item[key] = value
            with self.subTest(target=target, key=key):
                with self.assertRaises(ValueError): self.resolve()
            self.snapshot = before
        raw = json.dumps(self.snapshot).encode()
        with self.assertRaises(ValueError): verify_governed_snapshot(raw, 'wrong', self.binding)

    def test_extra_outputs_and_alias_collisions_are_rejected(self):
        self.plan['expectedResult']['columns'].append('unexpected')
        with self.assertRaises(ValueError): self.resolve()
        self.plan['expectedResult']['columns'].pop()
        self.case['columns']['approved_customer'] = 'another'
        with self.assertRaises(ValueError): self.resolve()


if __name__ == '__main__': unittest.main()
