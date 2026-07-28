import assert from 'node:assert/strict';
import { test } from 'node:test';
import { clarificationForm } from '../src/utils/clarification-form.ts';

const question={runId:'run-a',clarificationId:'question-a',revision:1,assetType:'SEMANTIC_DEFINITION_UPDATE'};
const answer={selectedOption:'CONFIRM_VALID_HISTORY',scope:'PROJECT',customAnswer:'我的补充'};

test('repeated event catch-up preserves an in-progress exact answer and sharing choice',()=>{
  let form=answer;
  for(let n=0;n<5;n++) form=clarificationForm(question,{...question},form);
  assert.deepEqual(form,answer);
});

test('new question, Run or revision cannot inherit unsubmitted consent or text',()=>{
  for(const changed of [{...question,revision:2},{...question,clarificationId:'other'},{...question,runId:'other'}]) {
    assert.deepEqual(clarificationForm(question,changed,answer),{selectedOption:'',scope:'USER',customAnswer:''});
  }
});

test('ordinary initial query confirmation retains QUERY default and explicit submitted scope',()=>{
  assert.equal(clarificationForm(undefined,{...question,assetType:'TEXT_DEFINITION'},answer).scope,'QUERY');
  assert.equal(clarificationForm(undefined,{...question,selectedScope:'PROJECT'},answer).scope,'PROJECT');
});
