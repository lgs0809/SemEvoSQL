/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export interface OnboardingQuestion {
  id: string;
  category: string;
  question: string;
  recommendedAnswer?: string;
  recommendationReason?: string;
  evidence?: string;
  answerSchema?: string;
  blocking: boolean;
  status: string;
  revision: number;
}

export interface OnboardingView {
  session: {
    sessionId: string;
    status: string;
    revision: number;
    summaryConfirmed: boolean;
  };
  nextQuestion?: OnboardingQuestion;
  conflicts: Array<{
    id: string;
    conflictType: string;
    message: string;
    status: string;
  }>;
  summary: {
    requiredItems: number;
    completedItems: number;
    blockingQuestions: number;
    blockingConflicts: number;
    openSemanticGaps: number;
    catalogReady: boolean;
    readyToConfirm: boolean;
    revision: number;
  };
}
