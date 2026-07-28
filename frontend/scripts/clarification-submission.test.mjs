import assert from 'node:assert/strict';
import { test } from 'node:test';
import { clarificationSubmission } from '../src/utils/clarification-submission.ts';

const args = ['user-a', 'run-a', 'question-a', 3, 'OTHER', '有效成本按净投放额计算', 'USER'];

test('a retry or fresh page recomputes the same exact payload and key without browser storage', async () => {
  const first = await clarificationSubmission(...args);
  const retry = await clarificationSubmission(...args);
  assert.deepEqual(retry, first);
  assert.ok(Object.isFrozen(first));
  assert.match(first.idempotencyKey, /^clarification:[a-f0-9]{64}$/);
  assert.equal(first.customAnswer, args[5]);
});

test('identity, question revision, answer and scope changes never reuse an old key', async () => {
  const original = await clarificationSubmission(...args);
  for (const [index, value] of [[0, 'user-b'], [1, 'run-b'], [2, 'question-b'], [3, 4],
    [4, 'CANCEL'], [5, '成本需扣除退款'], [6, 'PROJECT']]) {
    const changed = [...args]; changed[index] = value;
    assert.notEqual((await clarificationSubmission(...changed)).idempotencyKey, original.idempotencyKey);
  }
});

test('uses the same whitespace normalization as the transmitted payload and rejects missing scope identity', async () => {
  const padded = [...args]; padded[5] = `  ${args[5]}  `;
  assert.deepEqual(await clarificationSubmission(...padded), await clarificationSubmission(...args));
  const missing = [...args]; missing[0] = '';
  await assert.rejects(clarificationSubmission(...missing), /身份或修订/);
});
