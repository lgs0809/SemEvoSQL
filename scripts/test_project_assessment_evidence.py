"""Acceptance evidence must honor query-specific consent and immutable model cache identity."""
import copy
import hashlib
import json
from importlib.machinery import SourceFileLoader
from pathlib import Path
import tempfile
import unittest

proof=SourceFileLoader('assessment_evidence',str(Path(__file__).with_name('verify-project-definition-assessment.py'))).load_module()

class ContributionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.c={'id':11,'project_id':4,'content_revision':1,'content_hash':'frozen','definition_text':'meaning',
            'base_version_id':12,'representation_hash':'structure','dependency_fingerprint':'dependencies',
            'assessment_json':{'alignment':{'callId':'call','model':'gpt-5.6-terra'},'baseVersionId':12,'catalogHash':'catalog'}}
        self.p={'id':30,'definition_revision':1,'archived':False,'candidate_content_revision':1,
            'authorization_principal':'member','user_id':'member','sharing':'PRIVATE','retained_allowed_use':True}
        self.u={'preference_id':30,'definition_revision':1,'run_owner':'member','user_id':'member',
            'project_id':4,'sharing_allowed':True,'valid':True,'event_type':'COUNTED','actual_query':True,
            'run_type':'INTERACTIVE_QUERY','status':'SUCCEEDED','run_id':'run'}
    def accepted(self,p=None,u=None):
        return proof.contribution_receipts(self.c,[p or self.p],[u or self.u],{'member'})
    def test_future_private_preserves_explicit_historical_permission(self):
        self.assertEqual(1,len(self.accepted()))
        self.assertEqual([],self.accepted(u={**self.u,'sharing_allowed':False}))
    def test_withdrawal_or_failed_ownership_never_counts(self):
        for change in ({'valid':False},{'event_type':'WITHDRAWN'},{'run_owner':'other'},
                {'actual_query':False},{'project_id':5},{'run_type':'INTERNAL'}, {'status':'RUNNING'}):
            self.assertEqual([],self.accepted(u={**self.u,**change}),change)
    def test_revision_and_principal_sources_are_exact(self):
        for change in ({'archived':True},{'authorization_principal':'other'},
                {'candidate_content_revision':2},{'definition_revision':2},{'retained_allowed_use':False}):
            self.assertEqual([],self.accepted(p={**self.p,**change}),change)
    def archive(self,path):
        prior={'status':'PASS','checks':{'actual_terra_alignment_gateway_receipt':True},
            'facts':{'candidate':[self.c]},'gatewayReceipts':['Model HTTP request callId=call purpose=PROJECT_DEFINITION_ALIGNMENT httpAttempt=1']}
        path.write_text(json.dumps(prior));return hashlib.sha256(path.read_bytes()).hexdigest()
    def test_cached_receipt_accepts_only_same_verified_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'proof.json';sha=self.archive(path)
            self.assertEqual(1,len(proof.archived_alignment_receipts(self.c,path,sha)))
            for key in ('content_revision','base_version_id','representation_hash','dependency_fingerprint'):
                changed=copy.deepcopy(self.c);changed[key]='different'
                with self.assertRaises(ValueError):proof.archived_alignment_receipts(changed,path,sha)
            changed=copy.deepcopy(self.c);changed['assessment_json']['catalogHash']='different'
            with self.assertRaises(ValueError):proof.archived_alignment_receipts(changed,path,sha)
    def test_cached_receipt_rejects_hash_and_unverified_archive(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'proof.json';sha=self.archive(path)
            with self.assertRaises(ValueError):proof.archived_alignment_receipts(self.c,path,'wrong')
            d=json.loads(path.read_text());d['status']='FAIL';path.write_text(json.dumps(d))
            sha=hashlib.sha256(path.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):proof.archived_alignment_receipts(self.c,path,sha)

if __name__=='__main__':unittest.main()
