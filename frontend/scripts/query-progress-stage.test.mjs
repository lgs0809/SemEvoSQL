import assert from 'node:assert/strict';
import { test } from 'node:test';
import { queryProgressStage } from '../src/utils/query-progress-stage.ts';

test('generic execution and budget events cannot claim SQL has started', () => {
  const events = [{eventType:'REQUEST_ANALYSIS_COMPLETED'}, {eventType:'TASK_EXECUTION_BUDGET_ALLOCATED'}, {eventType:'NODE_EXECUTION_STARTED'}];
  assert.equal(queryProgressStage({status:'RUNNING',currentNode:'case-history'},events),1);
  assert.equal(queryProgressStage({status:'RUNNING',currentNode:'request-analysis'},events.slice(1)),0);
  assert.equal(queryProgressStage({status:'WAITING_HUMAN',currentNode:'SQL_GENERATE_NODE'},events),1);
});

test('actual milestones survive generic trailing events and completion still requires success', () => {
  const events = [{eventType:'PLANNING_TRACE'}, {eventType:'SQL_EXECUTION_TRACE'}, {eventType:'NODE_EXECUTION_COMPLETED'}];
  assert.equal(queryProgressStage({status:'RUNNING',currentNode:'case-history'},events),2);
  assert.equal(queryProgressStage({status:'RUNNING',currentNode:'case-history'},[{eventType:'SEMANTIC_PLANNING_INTERRUPTED'}]),1);
  assert.equal(queryProgressStage({status:'FAILED',currentNode:'result-artifact'},events),3);
  assert.equal(queryProgressStage({status:'SUCCEEDED'},events),4);
});
