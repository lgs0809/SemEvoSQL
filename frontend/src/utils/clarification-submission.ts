/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

/** Same confirmed question revision + identity + exact answer is one logical submission.
 * Recomputable after refresh; no answer text or credential is kept in browser storage.
 */
export async function clarificationSubmission(
  principal: string,
  runId: string,
  clarificationId: string,
  revision: number,
  selectedOption: string,
  customAnswer: string,
  scope: 'QUERY' | 'USER' | 'PROJECT',
) {
  if (!principal || !runId || !clarificationId || !Number.isSafeInteger(revision) || revision < 0) {
    throw new Error('确认问题的身份或修订信息缺失，请刷新后重试。');
  }
  const payload = {
    revision,
    selectedOption,
    customAnswer: customAnswer.trim() || undefined,
    scope,
  };
  const bytes = new TextEncoder().encode(
    JSON.stringify(['clarification-answer-v1', principal, runId, clarificationId, payload]),
  );
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  const key = Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2, '0')).join('');
  return Object.freeze({ ...payload, idempotencyKey: `clarification:${key}` });
}
