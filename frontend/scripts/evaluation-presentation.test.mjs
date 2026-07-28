import assert from 'node:assert/strict';
import { test } from 'node:test';
import { replayCaseEvidence, replayVerdict, storedObject } from '../src/utils/evaluation-presentation.ts';

test('job completion cannot disguise zero cases, failed assertions or missing safety evidence', () => {
  const verdict = result => replayVerdict({ status: 'SUCCEEDED', result_json: JSON.stringify(result) });
  assert.equal(verdict({ total: 0, passed: 0, failed: 0, safetyPassed: true }).label, '未执行用例');
  assert.equal(verdict({ total: 2, passed: 1, failed: 1, safetyPassed: true }).type, 'danger');
  assert.equal(verdict({ total: 2, passed: 2, failed: 0, safetyPassed: false }).type, 'danger');
  assert.equal(verdict({ total: 2, passed: 2, failed: 0 }).type, 'warning');
  assert.equal(verdict({ total: 2, passed: 2, failed: 0, safetyPassed: true }).type, 'success');
  assert.equal(verdict({ total: 2, passed: 2, failed: 1, safetyPassed: true }).label, '结果待核对');
  assert.equal(replayVerdict({ status: 'RUNNING', result_json: { total: 2, passed: 2, failed: 0, safetyPassed: true } }).type, 'info');
});

test('saved evidence distinguishes execution output, absence and conflicting case claims', () => {
  const [actual, legacy, inconsistent, unknown] = replayCaseEvidence({proofs:[
    {caseCode:'january',status:'PASSED',modelCallCount:1,latencyMs:392,resultRowCount:1,
      sourceQueries:[{sql:'SELECT SUM(amount) WHERE ordered_at >= ?',parameters:['2026-01-01']}],
      executionProof:[{rows:[{amount:'wrong duplicate'}]},{artifactType:'SOURCE_RESULT',columns:['amount'],rows:[{amount:'420.00'}]}]},
    {caseCode:'older',status:'FAILED',errors:['Expected timeStartInclusive mismatch'],latencyMs:null},
    {caseCode:'conflict',status:'PASSED',errors:['Guard rejected'],modelCallCount:-1},
    {caseCode:'unknown'},
  ]});
  assert.equal(actual.label,'通过');
  assert.deepEqual(actual.results,[{columns:['amount'],rows:[{amount:'420.00'}]}]);
  assert.equal(actual.queries[0].parameters[0],'2026-01-01');
  assert.equal(legacy.label,'未通过');
  assert.equal(legacy.latencyMs,undefined);
  assert.deepEqual(legacy.results,[]);
  assert.equal(inconsistent.type,'danger');
  assert.equal(inconsistent.modelCalls,undefined);
  assert.equal(unknown.label,'未核验');
  assert.deepEqual(replayCaseEvidence('not json'),[]);
  assert.deepEqual(replayCaseEvidence({proofs:[null,'not evidence',[]]}),[]);
});
test('legacy and current envelopes are readable while malformed or future payloads stay unverified', () => {
  assert.deepEqual(storedObject('{"expectedOutcome":"SUCCEED"}'), { expectedOutcome: 'SUCCEED' });
  assert.deepEqual(storedObject({ schemaVersion: 1, payload: { total: 1 } }), { total: 1 });
  assert.deepEqual(storedObject({ type: 'jsonb', value: JSON.stringify({ schemaVersion: 1, payload: { total: 1 } }) }), { total: 1 });
  assert.equal(replayVerdict({ status: 'SUCCEEDED', result_json: { type: 'jsonb', value: JSON.stringify({ total: 1, passed: 1, failed: 0, safetyPassed: true }) } }).label, '检查通过');
  assert.equal(storedObject({ type: 'jsonb', value: 'bad' }), undefined);
  for (const value of ['bad', '[]', null, { schemaVersion: 2, payload: { total: 1 } }]) assert.equal(storedObject(value), undefined);
});
