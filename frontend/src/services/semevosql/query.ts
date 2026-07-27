/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase, operationsBase } from "./http.ts";
import type {
  ProjectConversation,
  ProjectConversationView,
  ProjectMessage,
  QueryApprovalMode,
  QueryCorrectionOptions,
  QueryCorrectionResult,
  QueryDiagnosis,
  QueryRun,
  ResultArtifact,
  RunEvent,
  RuntimeClarification,
  SemanticBindingScope,
} from "./contracts/query.ts";
import { clarificationSubmission } from "../../utils/clarification-submission.ts";
import { projectsApi } from "./projects.ts";

export const queryApi = {
  async projectConversations(
    projectId: number,
  ): Promise<ProjectConversation[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/conversations`))
      .data;
  },
  async createProjectConversation(
    projectId: number,
    title = "新对话",
  ): Promise<ProjectConversation> {
    return (
      await axios.post(`${apiBase}/projects/${projectId}/conversations`, {
        title,
      })
    ).data;
  },
  async projectConversation(
    projectId: number,
    conversationId: string,
  ): Promise<ProjectConversationView> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}`,
      )
    ).data;
  },
  async sendProjectMessage(
    projectId: number,
    conversationId: string,
    content: string,
    approvalMode: QueryApprovalMode = "REQUIRE_APPROVAL",
  ): Promise<{ userMessage: ProjectMessage; run: QueryRun }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/messages`,
        {
          content,
          idempotencyKey: crypto.randomUUID(),
          requestId: crypto.randomUUID(),
          approvalMode,
        },
      )
    ).data;
  },
  async syncProjectMessage(
    projectId: number,
    conversationId: string,
    runId: string,
  ): Promise<ProjectMessage> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/runs/${runId}/sync`,
      )
    ).data;
  },
  async submitProjectHumanReview(
    projectId: number,
    conversationId: string,
    runId: string,
    approved: boolean,
    feedback: string,
    idempotencyKey: string,
  ): Promise<QueryRun> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/runs/${runId}/human-review`,
        {
          approved,
          feedback: feedback.trim() || undefined,
          idempotencyKey,
        },
      )
    ).data;
  },
  async submitEpisodeFeedback(
    episodeId: string,
    userId: string,
    rating: number,
    adopted: boolean,
    comment?: string,
  ): Promise<Record<string, unknown>> {
    return (
      await axios.post(`${operationsBase}/episodes/${episodeId}/feedback`, {
        userId,
        rating,
        adopted,
        comment: comment?.trim() || undefined,
      })
    ).data;
  },
  async correctionOptions(
    runId: string,
    assetType: "METRIC" | "DIMENSION" | "ENUM_VALUE" | "TIME_COLUMN",
    query = "",
    afterId = 0,
  ): Promise<QueryCorrectionOptions> {
    return (
      await axios.get(`${apiBase}/runs/${runId}/correction-options`, {
        params: { assetType, query, afterId, pageSize: 50 },
      })
    ).data;
  },
  async correctBinding(
    projectId: number,
    conversationId: string,
    runId: string,
    payload: {
      rawExpression: string;
      assetType: "METRIC" | "DIMENSION" | "ENUM_VALUE" | "TIME_COLUMN";
      assetKey: string;
      businessLabel: string;
      scope: SemanticBindingScope;
      idempotencyKey: string;
    },
  ): Promise<QueryCorrectionResult> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/runs/${runId}/corrections/binding`,
        payload,
      )
    ).data;
  },
  async proposeDefinitionCorrection(
    projectId: number,
    conversationId: string,
    runId: string,
    category: "DEFINITION" | "TIME" | "FILTER" | "RELATIONSHIP" | "PLANNING",
    correctionText: string,
  ): Promise<{ candidateId: string; status: string }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/runs/${runId}/corrections/definition`,
        { category, correctionText },
      )
    ).data;
  },
  async promoteSemanticPreference(
    preferenceId: number,
    confirmation: { definitionRevision: number; sourceContentHash: string },
  ) {
    return (
      await axios.post(
        `${apiBase}/semantic-preferences/${preferenceId}/promote-project`,
        confirmation,
      )
    ).data;
  },
  async continueSemanticPreference(preferenceId: number) {
    return (
      await axios.post(
        `${apiBase}/semantic-preferences/${preferenceId}/continue-personal`,
      )
    ).data;
  },
  async dismissSemanticPreferenceUpgrade(preferenceId: number) {
    return (
      await axios.post(
        `${apiBase}/semantic-preferences/${preferenceId}/dismiss-upgrade`,
      )
    ).data;
  },
  async diagnosis(runId: string): Promise<QueryDiagnosis> {
    return (await axios.get(`${apiBase}/runs/${runId}/diagnosis`)).data;
  },
  async run(runId: string): Promise<QueryRun> {
    return (await axios.get(`${apiBase}/runs/${runId}`)).data;
  },
  async runEvents(
    runId: string,
    afterSequence = 0,
    limit = 200,
  ): Promise<RunEvent[]> {
    return (
      await axios.get(`${apiBase}/runs/${runId}/events`, {
        params: { afterSequence, limit },
      })
    ).data;
  },
  async resultArtifact(
    runId: string,
    artifactId: string,
  ): Promise<ResultArtifact> {
    return (
      await axios.get(
        `${apiBase}/runs/${encodeURIComponent(runId)}/artifacts/${encodeURIComponent(artifactId)}`,
      )
    ).data;
  },
  subscribeRun(
    runId: string,
    afterSequence: number,
    onEvent: (event: RunEvent) => void,
    onTransportError?: () => void,
    onOpen?: () => void,
  ) {
    const source = new EventSource(
      `${apiBase}/runs/${encodeURIComponent(runId)}/stream?allEvents=true&afterSequence=${Math.max(0, afterSequence)}`,
    );
    const handle = (message: MessageEvent) =>
      onEvent(JSON.parse(message.data) as RunEvent);
    source.onmessage = handle;
    // A stable SSE envelope carries every domain event, including newly introduced types.
    source.addEventListener("run-event", handle as EventListener);
    [
      "NODE_OUTPUT",
      "RUN_STARTED",
      "CLARIFICATION_REQUIRED",
      "CLARIFICATION_ANSWERED",
      "HUMAN_FEEDBACK_REQUIRED",
      "HUMAN_FEEDBACK_ANSWERED",
      "HUMAN_FEEDBACK_APPLIED",
      "HUMAN_FEEDBACK_REPLAN_RESTARTED",
      "HUMAN_FEEDBACK_APPROVED_PLAN_RESTARTED",
      "ENTRY_REPLAY_QUEUED",
      "RESUME_REQUESTED",
      "SOURCE_SQL_GENERATED",
      "MULTI_SOURCE_RUN_ESTABLISHED",
      "SOURCE_SUBRUNS_CREATED",
      "SOURCE_SUBRUN_RUNNING",
      "SOURCE_SUBRUN_COMPLETED",
      "SOURCE_SUBRUN_FAILED",
      "SOURCE_SUBRUN_PARTIAL_FAILURE",
      "MERGE_COMPLETED",
      "RESULT_ARTIFACT_READY",
      "MULTI_SOURCE_QUEUE_REJECTED",
      "CANCEL_REQUESTED",
      "RUN_CANCELLED",
      "RUN_SUCCEEDED",
      "RUN_FAILED",
    ].forEach((eventType) =>
      source.addEventListener(eventType, handle as EventListener),
    );
    source.onopen = () => onOpen?.();
    source.onerror = () => onTransportError?.();
    return source;
  },
  async cancelRun(runId: string) {
    return (
      await axios.post(`${apiBase}/runs/${runId}/cancel`, {
        idempotencyKey: crypto.randomUUID(),
      })
    ).data;
  },
  async resumeRun(runId: string) {
    return (
      await axios.post(`${apiBase}/runs/${runId}/resume`, {
        idempotencyKey: crypto.randomUUID(),
      })
    ).data;
  },
  async clarification(runId: string): Promise<RuntimeClarification> {
    return (await axios.get(`${apiBase}/runs/${runId}/clarification`)).data;
  },
  async answerClarification(
    runId: string,
    clarification: RuntimeClarification,
    selectedOption: string,
    customAnswer: string,
    scope: SemanticBindingScope,
  ) {
    // Snapshot before any network wait: a form edit cannot alter an in-flight answer.
    const clarificationId = clarification.clarificationId;
    const revision = clarification.revision;
    const operator = await projectsApi.currentOperator();
    const payload = await clarificationSubmission(
      operator.operator,
      runId,
      clarificationId,
      revision,
      selectedOption,
      customAnswer,
      scope,
    );
    return (
      await axios.post(
        `${apiBase}/runs/${runId}/clarification/${clarificationId}/answer`,
        payload,
      )
    ).data;
  },
};
