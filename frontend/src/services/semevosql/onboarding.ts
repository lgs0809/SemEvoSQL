/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase } from "./http.ts";
import type {
  OnboardingQuestion,
  OnboardingView,
} from "./contracts/onboarding.ts";

export const onboardingApi = {
  async startOnboarding(
    projectId: number,
    versionId: number,
  ): Promise<OnboardingView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/onboarding/start`,
        {
          idempotencyKey: `ui-onboarding-${projectId}-${versionId}`,
        },
      )
    ).data;
  },
  async onboarding(
    projectId: number,
    versionId: number,
  ): Promise<OnboardingView> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/onboarding`,
      )
    ).data;
  },
  async answerOnboarding(
    projectId: number,
    versionId: number,
    question: OnboardingQuestion,
    answer: string,
  ): Promise<OnboardingView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/onboarding/questions/${question.id}/answer`,
        {
          answer,
          answerType: "JSON_OR_TEXT",
          revision: question.revision,
          idempotencyKey: crypto.randomUUID(),
        },
      )
    ).data;
  },
  async confirmOnboarding(
    projectId: number,
    versionId: number,
    revision: number,
  ) {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/onboarding/confirm`,
        {
          revision,
          idempotencyKey: crypto.randomUUID(),
        },
      )
    ).data;
  },
};
