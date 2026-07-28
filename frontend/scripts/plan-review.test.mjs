import test from 'node:test';
import assert from 'node:assert/strict';
import { currentPlanReviewSummary } from '../src/utils/plan-review.ts';

const event = (sequence, runId, plan) => ({ sequence, runId,
  eventType: 'APPROVAL_PLAN_SNAPSHOT', payload: JSON.stringify(plan), createTime: '' });
const plan = (month) => ({ canonicalQuery: '旧问题仍写二月',
  resultContract: { personalMeasures: [{ businessName: '有效订单数' }] },
  metrics: [{ businessName: '底层全部订单数' }], models: [{ businessName: '全部订单' }],
  timeRange: { startInclusive: `2026-${month}-01T00:00`, endExclusive: '2026-04-01T00:00' } });

test('rejected plan is superseded by the current immutable approval snapshot', () => {
  const summary = currentPlanReviewSummary([
    event(14, 'current', plan('02')), event(21, 'current', plan('03')),
    event(23, 'another', plan('12'))], 'current', 22, 18);
  assert.match(summary, /有效订单数/);
  assert.match(summary, /2026-03-01/);
  assert.doesNotMatch(summary, /旧问题|二月|2026-02-01|底层全部订单数|2026-12-01/);
});

test('old, future, other-run or malformed snapshots never become current review', () => {
  assert.equal(currentPlanReviewSummary([event(14, 'current', plan('02'))], 'current', 22, 18), undefined);
  assert.equal(currentPlanReviewSummary([event(23, 'current', plan('03'))], 'current', 22, 18), undefined);
  assert.equal(currentPlanReviewSummary([event(21, 'other', plan('03'))], 'current', 22, 18), undefined);
  assert.equal(currentPlanReviewSummary([{ ...event(21, 'current', {}), payload: '{' }], 'current', 22, 18), undefined);
});
