import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConversationDrafts, queryComposerState, uniqueConversations } from '../src/utils/query-workspace.ts';

test('distinct conversations with identical titles and timestamps remain accessible', () => {
  const a = { conversationId: 'a', title: '金额是多少', updateTime: 'same' };
  const b = { ...a, conversationId: 'b' };
  assert.deepEqual(uniqueConversations([a, b, { ...a }]), [a, b]);
});

test('ready projects allow direct questions and waiting confirmation blocks keyboard submission', () => {
  const ready = { projectSelected: true, modelReady: true, versionReady: true, busy: false, needsConfirmation: false };
  assert.equal(queryComposerState(ready).enabled, true);
  for (const patch of [{ projectSelected: false }, { modelReady: false }, { versionReady: false }, { busy: true }, { needsConfirmation: true }]) {
    assert.equal(queryComposerState({ ...ready, ...patch }).enabled, false);
  }
  assert.match(queryComposerState({ ...ready, busy: true, needsConfirmation: true }).placeholder, /确认/);
});

test('project and conversation switches preserve separate drafts without leaking them', () => {
  const drafts = new ConversationDrafts();
  assert.equal(drafts.switchTo(1, 'a', ''), '');
  assert.equal(drafts.switchTo(1, 'b', '一月金额'), '');
  assert.equal(drafts.switchTo(2, 'a', '二月金额'), '');
  assert.equal(drafts.switchTo(1, 'a', '其他项目的问题'), '一月金额');
  assert.equal(drafts.switchTo(1, 'b', '一月金额'), '二月金额');
  assert.equal(drafts.switchTo(undefined, undefined, '二月金额'), '');
  assert.equal(new ConversationDrafts().switchTo(1, 'a', ''), '');
});
