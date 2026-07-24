/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import type { RunEvent } from '../services/semevosql';

export function isSemanticUpdate(events: RunEvent[] = []): boolean {
  const analysis = [...events].reverse().find(event => event.eventType === 'REQUEST_ANALYSIS_COMPLETED');
  if (!analysis?.payload) return false;
  try {
    return JSON.parse(analysis.payload).requestType === 'SEMANTIC_UPDATE';
  } catch {
    return false;
  }
}
