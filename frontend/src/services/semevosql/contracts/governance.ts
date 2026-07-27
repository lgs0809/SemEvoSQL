/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import type { SemanticProjectVersion } from "./projects.ts";

export interface SemanticPatchOperation {
  operation:
    | "ADD_COLUMN_SYNONYM"
    | "ADD_ENUM_ALIAS"
    | "ADD_ENUM_VALUE"
    | "ADD_METRIC"
    | "UPDATE_METRIC"
    | "ADD_DIMENSION"
    | "UPDATE_DIMENSION"
    | "ADD_RELATIONSHIP"
    | "UPDATE_RELATIONSHIP"
    | "ADD_GRAIN"
    | "UPDATE_GRAIN"
    | "ADD_RULE"
    | "UPDATE_RULE"
    | "ADD"
    | "UPDATE";
  assetType: string;
  assetKey: string;
  expectedCurrentFingerprint?: string;
  values: Record<string, unknown>;
  evidenceCaseIds: string[];
}

export interface SemanticPatch {
  schemaVersion: 1;
  sourceVersionId: number;
  sourceCatalogHash: string;
  operations: SemanticPatchOperation[];
}

export type MultiSourcePolicyPatch = SemanticPatch;

interface SemanticPatchValidationIssue {
  severity: "ERROR" | "WARNING";
  code: string;
  operationIndex?: number;
  assetKey?: string;
  message: string;
}

export interface SemanticPatchValidationReport {
  valid: boolean;
  errors: SemanticPatchValidationIssue[];
  warnings: SemanticPatchValidationIssue[];
  checkedOperations: number;
}

export interface SemanticReplayRun {
  replayRunId: string;
  runId: string;
  candidateId: string;
  status: string;
  progress: number;
  currentCaseId?: string;
  currentLevel?: string;
  checkpointJson?: string;
  resultJson?: string;
  errorMessage?: string;
  cancelRequested: boolean;
}

export interface SemanticEvolutionCandidate {
  id: string;
  project_id: number;
  source_version_id: number;
  source_catalog_hash: string;
  candidate_type: string;
  asset_type: string;
  asset_key: string;
  status: string;
  confidence: number;
  risk_level: string;
  mapping_classification?: "LOW_SAMPLE" | "STABLE_MAPPING" | "TRUE_AMBIGUITY";
  evidence_distribution_json?: string;
  distinct_conversation_count?: number;
  distinct_user_count?: number;
  distinct_root_evidence_count?: number;
  distinct_time_window_count?: number;
  patch_json: string;
  patch_hash?: string;
  evidence_summary: string;
  target_draft_version_id?: number;
  applied_time?: string;
  replay_summary_json?: string;
  reviewed_by?: string;
  review_comment?: string;
  reviewed_time?: string;
  create_time: string;
  update_time: string;
  evidence?: Array<Record<string, unknown>>;
  replayResults?: Array<Record<string, unknown>>;
  events?: Array<Record<string, unknown>>;
  assetDiff?: Array<{
    operation: string;
    assetType: string;
    assetKey: string;
    sourceFingerprint?: string;
    before: Record<string, unknown>;
    after: Record<string, unknown>;
    highRisk: boolean;
  }>;
}

export interface SemanticVersionTimeline {
  projectId: number;
  activeVersionId?: number;
  versions: SemanticProjectVersion[];
  activationEvents: Array<Record<string, unknown>>;
}

export interface CorpusRevision {
  id: number;
  projectId: number;
  revisionNo: number;
  sourceType: string;
  sourceRef?: string;
  contentHash?: string;
  idempotencyKey: string;
  semanticDiffDetected: boolean;
  semanticChangeSetId?: string;
  createdBy: string;
  createTime: string;
}

export interface SemanticChangeSet {
  changeSetId: string;
  projectId: number;
  baseSemanticVersionId: number;
  targetVersionLevel: "PATCH" | "MINOR" | "MAJOR";
  originType: "EPISODE" | "CORPUS" | "MANUAL" | "BASELINE_PROMOTION";
  originRef?: string;
  rootCause: string;
  status: string;
  riskLevel: string;
  semanticDiffHash?: string;
  replayRunId?: string;
  replaySummaryJson?: string;
  validationSummaryJson?: string;
  affectedAssetCount: number;
  materializedVersionId?: number;
  idempotencyKey: string;
  revision: number;
  createdBy: string;
  createTime: string;
  updateTime: string;
  completedTime?: string;
}

interface SemanticChangeItem {
  id: number;
  changeSetId: string;
  assetType: string;
  assetKey: string;
  operation: string;
  beforeHash?: string;
  afterHash?: string;
  patchJson: string;
  evidenceJson: string;
}

export interface SemanticChangeSetDetail {
  changeSet: SemanticChangeSet;
  items: SemanticChangeItem[];
  replayResults: Array<Record<string, unknown>>;
}

export interface EpisodeDiagnosis {
  episode: Record<string, unknown>;
  turns: Array<Record<string, unknown>>;
  attempts: Array<Record<string, unknown>>;
  signals: Array<Record<string, unknown>>;
  queryCases: Array<Record<string, unknown>>;
  changeSets: Array<Record<string, unknown>>;
}

export interface ProjectSemanticReadiness {
  projectId: number;
  queryReady: boolean;
  activeVersion: {
    semanticVersionId: number;
    version: { major: number; minor: number; patch: number };
    semanticStateHash: string;
    corpusRevisionId?: number;
    activatedTime?: string;
  } | null;
  knowledgeUpdateInProgress: boolean;
  knowledgeUpdateCount: number;
  latestCorpusRevision: Record<string, unknown>;
  knowledgeUpdates: Array<Record<string, unknown>>;
}

export interface ProjectDefinitionCandidate {
  id: number;
  content_revision: number;
  evidence_revision: number;
  lifecycle: string;
  business_name: string;
  definition_text: string;
  blocked_reason?: string;
  assessment_state: string;
  assessment_next_attempt_at?: string;
  publication?: {
    id: number;
    state: "PENDING" | "BUILDING" | "RETRYABLE_FAILURE" | "DONE" | "STALE";
    attempts: number;
    nextAttemptAt?: string;
    lastError?: string;
    preparedVersionId?: number;
    finishedAt?: string;
  };
  assessment_current: boolean;
  assessed_base_version_id?: number;
  assessed_catalog_hash?: string;
  current_base_version_id?: number;
  current_catalog_hash?: string;
  representation_hash?: string;
  approved_decision_id?: number;
  threshold_reached: boolean;
  contributions: {
    authorizedSources: number;
    validUsers: number;
    validUses: number;
    fingerprint: string;
  };
  assessment_json?: {
    alignment: {
      relation: string;
      reason?: string;
      targets?: string[];
      differences?: string[];
    };
    decision?: { administratorMayApprove: boolean };
  };
  structured_json?: {
    metric: { entity: string; timeAttribute?: string; unit?: string };
  };
  published_version_id?: number;
  public_asset_key?: string;
}

export interface ProjectDefinitionContributionEvidence {
  projectId: number;
  candidateId: number;
  contentRevision: number;
  evidenceRevision: number;
  totals: ProjectDefinitionCandidate["contributions"];
  offset: number;
  limit: number;
  totalRecords: number;
  records: Array<{
    user_id: string;
    preference_id: number;
    definition_revision: number;
    equivalence_kind: string;
    run_id: string;
    run_status: string;
    project_version_id: number;
    create_time: string;
    update_time: string;
    counted: boolean;
    contribution_state:
      "COUNTED" | "WITHDRAWN" | "SOURCE_INELIGIBLE" | "NOT_COUNTED";
  }>;
}
