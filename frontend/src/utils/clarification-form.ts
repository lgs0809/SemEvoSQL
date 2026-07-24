/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import type { RuntimeClarification, SemanticBindingScope } from '../services/semevosql';

export interface ClarificationForm {
  selectedOption: string;
  scope: SemanticBindingScope;
  customAnswer: string;
}

/** Polling the same immutable question must not erase the user's in-progress answer. */
export function clarificationForm(
  current: RuntimeClarification | undefined,
  loaded: RuntimeClarification,
  form: ClarificationForm,
): ClarificationForm {
  if (current?.runId === loaded.runId && current.clarificationId === loaded.clarificationId &&
      current.revision === loaded.revision) return { ...form };
  return {
    selectedOption: loaded.recommendedOption || '',
    scope: loaded.selectedScope || (loaded.assetType === 'SEMANTIC_DEFINITION_UPDATE' ? 'USER' : 'QUERY'),
    customAnswer: '',
  };
}
