import assert from 'node:assert/strict';
import test from 'node:test';
import { canResumeRun, resumeExpired } from '../src/utils/run-resume-policy.ts';

test('a stale retryable response expires while the page remains open', () => {
  const run = { status: 'FAILED', retryable: true, resumeDeadlineEpochMillis: 2000 };
  assert.equal(canResumeRun(run, 1999), true);
  assert.equal(canResumeRun(run, 2000), false);
  assert.equal(resumeExpired(run, 2001), true);
});

test('a permanent semantic rejection is not relabeled as a later timeout', () => {
  const run = { status: 'FAILED', retryable: false, errorCode: 'SEMANTIC_PLANNING_REJECTED', resumeDeadlineEpochMillis: 1 };
  assert.equal(resumeExpired(run, 5000), false);
  assert.equal(canResumeRun(run, 5000), false);
  assert.equal(resumeExpired({ ...run, errorCode: 'QUERY_EXPIRED' }, 5000), true);
});
test('human waiting and non-retryable failures never get a resume button', () => {
  assert.equal(canResumeRun({ status: 'WAITING_HUMAN', resumeDeadlineEpochMillis: 1 }, 5000), false);
  assert.equal(resumeExpired({ status: 'WAITING_HUMAN', resumeDeadlineEpochMillis: 1 }, 5000), false);
  assert.equal(canResumeRun({ status: 'FAILED', retryable: false }, 0), false);
  assert.equal(canResumeRun({ status: 'SUCCEEDED' }, 0), false);
  assert.equal(canResumeRun({ status: 'QUEUED', resumeDeadlineEpochMillis: 100 }, 100), false);
  assert.equal(canResumeRun(undefined, 0), false);
});
