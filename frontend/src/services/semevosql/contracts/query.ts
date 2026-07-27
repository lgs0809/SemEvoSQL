/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export interface QueryRun {
  runId: string;
  runType: string;
  projectId?: number;
  projectVersionId?: number;
  episodeId?: string;
  status: string;
  currentNode?: string;
  errorCode?: string;
  errorMessage?: string;
  retryable?: boolean;
  resumeDeadlineEpochMillis?: number | null;
}

export interface RunEvent {
  runId: string;
  sequence: number;
  eventType: string;
  nodeName?: string;
  payload?: string;
  payloadSummary?: string;
  createTime: string;
}

export interface RuntimeClarification {
  clarificationId: string;
  runId: string;
  question: string;
  options: Array<{
    code: string;
    label: string;
    value: string;
    reason?: string;
    evidence?: string;
  }>;
  recommendedOption?: string;
  reason?: string;
  evidence?: string;
  issueType?: string;
  assetType?: string;
  assetKey?: string;
  rawExpression?: string;
  resolvedValue?: string;
  resolutionSource?: string;
  selectedScope?: "QUERY" | "USER" | "PROJECT";
  status: string;
  revision: number;
}

export type SemanticBindingScope = "QUERY" | "USER" | "PROJECT";

export type QueryApprovalMode = "REQUIRE_APPROVAL" | "AUTO_EXECUTE";

export interface ProjectConversation {
  conversationId: string;
  projectId: number;
  projectVersionId: number;
  title: string;
  status: string;
  createdBy: string;
  revision: number;
  createTime: string;
  updateTime: string;
}

export interface QueryExecutionExplanation {
  understoodQuery: string;
  semanticBindings: Array<Record<string, unknown>>;
  businessDefinitions: Array<Record<string, unknown>>;
  filters: Array<Record<string, unknown>>;
  time: Record<string, unknown>;
  groups: Array<Record<string, unknown>>;
  ordering: Array<Record<string, unknown>>;
  limit?: number;
  models: Array<Record<string, unknown>>;
  relationships: Array<Record<string, unknown>>;
  datasources: Array<Record<string, unknown>>;
  sqlExecutions: Array<Record<string, unknown>>;
  reusedSteps: string[];
  execution: Record<string, unknown>;
  resultColumns?: Array<{ key: string; label: string }>;
}

export interface QueryTaskAnswer {
  taskId: string;
  ordinal: number;
  question: string;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  report: string;
  explanation?: QueryExecutionExplanation;
  error?: string;
}

export interface QueryCorrectionOption {
  assetType: "METRIC" | "DIMENSION" | "ENUM_VALUE" | "TIME_COLUMN";
  assetKey: string;
  businessLabel: string;
  modelCode?: string;
}

export interface QueryCorrectionOptions {
  runId: string;
  assetType: "METRIC" | "DIMENSION" | "ENUM_VALUE" | "TIME_COLUMN";
  options: QueryCorrectionOption[];
  hasMore: boolean;
  nextAfterId?: number;
}

export interface QueryCorrectionResult {
  originalRunId: string;
  rerunId: string;
  scope: SemanticBindingScope;
  assetType: string;
  assetKey: string;
  businessLabel: string;
  candidateId?: string;
}

interface QueryDiagnosisStage {
  code: string;
  label: string;
  state: "PASSED" | "FAILED" | "WAITING" | "UNKNOWN";
  summary: string;
}

interface QueryDiagnosisRetrievalCandidate {
  documentType?: string;
  assetType: string;
  assetKey: string;
  modelCode?: string;
  physicalTable?: string;
  rrfScore: number;
  channelRanks: Record<string, number>;
  channelScores: Record<string, number>;
}

interface QueryDiagnosisRepairAction {
  code: string;
  label: string;
  description: string;
  enabled: boolean;
  kind: "CORRECTION" | "EVOLUTION" | "REPLAY" | "RELEASE" | string;
}

interface QueryDiagnosisGovernance {
  candidateId: string;
  candidateType: string;
  assetType: string;
  assetKey: string;
  status: string;
  riskLevel: string;
  targetDraftVersionId?: number;
  replaySummary?: string;
  patchReady: boolean;
  impact?: {
    candidateId: string;
    referencedAffectedCases: number;
    selectedDirectAffectedCases: number;
    selectedRepresentativeCases: number;
    totalSelectedCases: number;
    maxCases: number;
  };
  replayResultCounts: Record<string, number>;
}

export interface QueryDiagnosis {
  runId: string;
  projectId: number;
  projectVersionId: number;
  conversationId?: string;
  question?: string;
  runStatus: string;
  rootCause: string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  summary: string;
  stages: QueryDiagnosisStage[];
  selectedAssets?: {
    metricCodes: string[];
    dimensionCodes: string[];
    ruleCodes: string[];
    relationshipCodes: string[];
    grainCodes: string[];
  };
  retrievalCandidates: QueryDiagnosisRetrievalCandidate[];
  correction?: {
    eventType: string;
    rawExpression?: string;
    assetType?: string;
    assetKey?: string;
    businessLabel?: string;
    scope?: string;
    rerunId?: string;
    candidateId?: string;
    category?: string;
  };
  governance?: QueryDiagnosisGovernance;
  repairActions: QueryDiagnosisRepairAction[];
  pipeline?: {
    semanticPlanJson?: string;
    executionPlanJson?: string;
    semanticSql?: string;
    physicalSql?: string;
    dryPlan: Record<string, unknown>;
    sqlTraces: Array<Record<string, unknown>>;
    sourceExecutions: Array<Record<string, unknown>>;
    reviewDecision?: string;
    reviewIssueType?: string;
    reviewEvidence?: string;
    repairBudget?: string;
  };
  advanced?: {
    runErrorCode?: string;
    currentNode?: string;
    historicalExampleIds: string[];
    eventTypes: string[];
  };
}

export interface SemanticPreferenceUpgradePrompt {
  preferenceId: number;
  phrase?: string;
  displayPhrase?: string;
  assetType?: string;
  assetKey?: string;
  businessLabel?: string;
  hitCount?: number;
  definitionRevision?: number;
  sourceContentHash?: string;
  completeDefinition?: string;
}

export interface ProjectMessage {
  messageId: string;
  conversationId: string;
  sequenceNo: number;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  runId?: string;
  status: string;
  metadataJson?: string;
  createTime: string;
  updateTime: string;
}

export interface ProjectConversationView {
  conversation: ProjectConversation;
  messages: ProjectMessage[];
  feedbackSubmittedRunIds: string[];
}

export interface ResultArtifact {
  artifactId: string;
  runId: string;
  sourceSubRunId?: string;
  artifactType: string;
  schemaJson: string;
  dataJson: string;
  rowCount: number;
  contentHash: string;
  status: string;
  createTime: string;
  updateTime: string;
}
