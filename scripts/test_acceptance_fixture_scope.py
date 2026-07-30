import unittest
from acceptance_fixture_scope import business_database


class AcceptanceFixtureScopeTest(unittest.TestCase):
    def test_only_exact_named_project_source_reader_and_fixture_can_expand_legacy_scope(self):
        source={'id':3,'database_name':'semevosql_quality_finish_20261002',
            'username':'semevosql_reader_finish_20261002','host':'metadata-db','port':5432}
        calls=[]
        def sql(database, query):
            calls.append((database,query))
            return [source] if database=='semevosql_acceptance' else [{'fixture':'SEMEVOSQL_QUALITY_V1'}]
        self.assertEqual(business_database(4,sql,'finish_20261002'),'semevosql_quality_finish_20261002')
        self.assertIn("p.id=4 AND p.project_code='semevosql-quality-finish_20261002'",calls[0][1])
        for key in ('database_name','username','host','port'):
            saved=source[key];source[key]='wrong'
            with self.assertRaises(ValueError): business_database(4,sql,'finish_20261002')
            source[key]=saved
        with self.assertRaises(ValueError): business_database(4,lambda database,query:[],'finish_20261002')
        with self.assertRaises(ValueError): business_database(4,lambda database,query:[source,source],'finish_20261002')
        with self.assertRaises(ValueError): business_database(4,lambda database,query:[source] if database=='semevosql_acceptance' else [{'fixture':'production'}],'finish_20261002')

    def test_frozen_or_injected_namespace_and_unidentified_projects_are_never_shared(self):
        def forbidden(*args): self.fail('Invalid identities must be rejected before database access')
        self.assertEqual(business_database(1,forbidden),'semevosql_acceptance_business')
        for namespace in ('benchmark_20261002',"x' OR 1=1",'UPPER'):
            with self.assertRaises(ValueError): business_database(4,forbidden,namespace)
        with self.assertRaises(ValueError): business_database(4,forbidden)
