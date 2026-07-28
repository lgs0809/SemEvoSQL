import assert from 'node:assert/strict';
import { test } from 'node:test';
import { filterDefinitionCandidates } from '../src/utils/definition-candidate-filters.ts';
const all = { search: '', lifecycle: 'ALL', threshold: 'ALL', alignment: 'ALL' };
const rows = [
  { id: 1, business_name: '支付金额', definition_text: '成功支付', lifecycle: 'ACCUMULATING', threshold_reached: false, assessment_json: { alignment: { relation: 'NEW' } } },
  { id: 2, business_name: '支付金额', definition_text: '包含退款', lifecycle: 'NEEDS_ADMIN_REVIEW', threshold_reached: true, assessment_json: { alignment: { relation: 'CONFLICT' } } },
  { id: 3, business_name: '收入', definition_text: '净额', lifecycle: 'PUBLISHED', threshold_reached: true },
];
test('evolution, threshold and conflict filters are independent and combine with AND', () => {
  assert.deepEqual(filterDefinitionCandidates(rows, all), rows);
  assert.deepEqual(filterDefinitionCandidates(rows, { ...all, threshold: 'PENDING' }), [rows[0]]);
  assert.deepEqual(filterDefinitionCandidates(rows, { ...all, lifecycle: 'NEEDS_ADMIN_REVIEW', threshold: 'REACHED', alignment: 'CONFLICT', search: '退款' }), [rows[1]]);
  assert.deepEqual(filterDefinitionCandidates(rows, { ...all, lifecycle: 'ACCUMULATING', alignment: 'CONFLICT' }), []);
});
test('pending comparisons remain visible and clearing filters restores every candidate', () => {
  assert.deepEqual(filterDefinitionCandidates(rows, { ...all, alignment: 'PENDING' }), [rows[2]]);
  assert.deepEqual(filterDefinitionCandidates(rows, { ...all, search: '  成功支付  ' }), [rows[0]]);
  assert.deepEqual(filterDefinitionCandidates(rows, all), rows);
});
