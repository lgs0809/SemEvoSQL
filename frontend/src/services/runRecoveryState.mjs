/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export function parseRunCursor(raw) {
  if (!raw) return undefined;
  try {
    const candidate = typeof raw === 'string' ? JSON.parse(raw) : raw;
    const projectId = Number(candidate?.projectId);
    const conversationId =
      typeof candidate?.conversationId === 'string' ? candidate.conversationId.trim() : '';
    const runId = typeof candidate?.runId === 'string' ? candidate.runId.trim() : '';
    if (!Number.isSafeInteger(projectId) || projectId <= 0 || !conversationId || !runId)
      return undefined;
    return {
      projectId,
      conversationId,
      runId,
      lastSequence: Math.max(0, Number(candidate.lastSequence) || 0),
    };
  } catch {
    return undefined;
  }
}

export function serializeRunCursor(cursor) {
  const normalized = parseRunCursor(cursor);
  return normalized ? JSON.stringify(normalized) : undefined;
}

export function mergeSequencedEvents(existing, incoming) {
  const bySequence = new Map();
  for (const event of [...(existing || []), ...(incoming || [])]) {
    const sequence = Number(event?.sequence);
    if (Number.isSafeInteger(sequence) && sequence > 0) bySequence.set(sequence, event);
  }
  const events = [...bySequence.values()].sort((left, right) => left.sequence - right.sequence);
  return { events, lastSequence: events.at(-1)?.sequence || 0 };
}

export function reconnectDelay(attempt, delays = [1000, 2000, 5000, 10000]) {
  if (!Array.isArray(delays) || delays.length === 0)
    throw new Error('Reconnect delays cannot be empty');
  const index = Math.min(Math.max(Number(attempt) - 1, 0), delays.length - 1);
  return delays[index];
}

export function canRestoreCursor(cursor, projectId, conversationIds) {
  const normalized = parseRunCursor(cursor);
  if (!normalized || normalized.projectId !== Number(projectId)) return false;
  return new Set(conversationIds || []).has(normalized.conversationId);
}

export function nextReplayCursor(batch, currentCursor) {
  if (!Array.isArray(batch) || batch.length === 0) return Math.max(0, Number(currentCursor) || 0);
  const maximum = batch.reduce((value, event) => Math.max(value, Number(event?.sequence) || 0), 0);
  return Math.max(Math.max(0, Number(currentCursor) || 0), maximum);
}
