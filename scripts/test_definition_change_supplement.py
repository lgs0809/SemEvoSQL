import copy
import json
from pathlib import Path
from importlib.machinery import SourceFileLoader
import unittest

module=SourceFileLoader('definition_change_supplement',str(Path(__file__).with_name('verify-definition-change.py'))).load_module()

class DefinitionChangeSupplementTest(unittest.TestCase):
    def setUp(self):
        self.answer='Keep the calculation unchanged. Withdraw only the supplied historical sharing.'
        self.response=dict(targetDefinitionId=30,sourceRevision=1,sourceContentHash='sha256:old',
            definitionText=None,intentExcerpt=self.answer,affectedRunIds=['actual-owned-run'])
        self.proposal=dict(preferenceId=30,revision=1,contentHash='sha256:old',newText=None,
            affectedRuns=['actual-owned-run'],modelEvidence=dict(response=json.dumps(self.response)))

    def matches(self,response=None,proposal=None,answer=None):
        proposal=copy.deepcopy(proposal or self.proposal)
        if response is not None:proposal['modelEvidence']['response']=json.dumps(response)
        return module.supplement_matches_frozen_proposal(proposal,answer or self.answer)

    def test_scope_only_fresh_supplement_never_requires_new_calculation(self):
        self.assertTrue(self.matches())

    def test_changed_complete_owner_excerpt(self):
        text='Effective orders include paid and refunded orders.'
        proposal=copy.deepcopy(self.proposal);proposal['newText']=text
        response=dict(self.response,definitionText=text,intentExcerpt=text)
        self.assertTrue(self.matches(response,proposal,answer=text))

    def test_unseen_owner_text_is_rejected(self):
        self.assertFalse(self.matches(dict(self.response,intentExcerpt='Unseen model-authored instruction')))

    def test_another_definition_or_revision_cannot_be_substituted(self):
        for key,value in [('targetDefinitionId',31),('sourceRevision',2),('sourceContentHash','sha256:new'),('targetDefinitionId',True)]:
            with self.subTest(key=key,value=value):self.assertFalse(self.matches(dict(self.response,**{key:value})))

    def test_extra_history_target_is_rejected(self):
        self.assertFalse(self.matches(dict(self.response,affectedRunIds=['actual-owned-run','unapproved-run'])))

    def test_changed_text_must_match_both_proposal_and_owner_excerpt(self):
        self.assertFalse(self.matches(dict(self.response,definitionText='Invented replacement')))
        proposal=copy.deepcopy(self.proposal);proposal['newText']='Invented replacement'
        self.assertFalse(self.matches(dict(self.response,definitionText='Invented replacement'),proposal))

    def test_duplicate_and_extra_response_fields_are_rejected(self):
        proposal=copy.deepcopy(self.proposal)
        proposal['modelEvidence']['response']=json.dumps(self.response)[:-1]+',"sourceRevision":1}'
        self.assertFalse(self.matches(proposal=proposal))
        self.assertFalse(self.matches(dict(self.response,authorization='granted')))

if __name__=='__main__':unittest.main()
