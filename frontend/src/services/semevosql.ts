/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
import axios from 'axios';

const operationsBase = '/api/semevosql/operations';
const apiBase = '/api/semevosql';

axios.interceptors.response.use(
  response => response,
  error => {
    const serverMessage = error?.response?.data?.message;
    if (serverMessage) error.message = serverMessage;
    return Promise.reject(error);
  },
);

const governedMutationHeaders = (operation: string) => {
  const requestId = crypto.randomUUID();
  return {
    'X-Request-ID': requestId,
    'Idempotency-Key': `${operation}:${requestId}`,
  };
};

export interface OperatorView {
  operator: string;
  source: string;
}

interface SemEvoSQLDashboard {
  episodes: number;
  successfulEpisodes: number;
  guardRejected: number;
  queryExampleCandidates: number;
  approvedQueryExamples: number;
  goldenCases: number;
  runningJobs: number;
  releases: number;
  catalogCache: { size: number; hits: number; misses: number; loading?: number };
}

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

interface OnboardingQuestion {
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
  session: { sessionId: string; status: string; revision: number; summaryConfirmed: boolean };
  nextQuestion?: OnboardingQuestion;
  conflicts: Array<{ id: string; conflictType: string; message: string; status: string }>;
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
  selectedScope?: 'QUERY' | 'USER' | 'PROJECT';
  status: string;
  revision: number;
}

export type SemanticBindingScope = 'QUERY' | 'USER' | 'PROJECT';

export type ProjectVersionCreationMode = 'CLONE' | 'BLANK';

export interface SemanticProject {
  id: number;
  projectCode: string;
  name: string;
  businessDomain: string;
  description?: string;
  status: string;
  activePublishedVersionId?: number;
  createdBy: string;
  createTime: string;
  updateTime: string;
}

export interface SemanticProjectVersion {
  id: number;
  projectId: number;
  versionNo: number;
  versionNumber: string;
  semanticMajor?: number;
  semanticMinor?: number;
  semanticPatch?: number;
  versionLevel?: 'INITIAL' | 'PATCH' | 'MINOR' | 'MAJOR';
  versionCause?: string;
  semanticStateHash?: string;
  corpusRevisionId?: number;
  parentVersionId?: number;
  creationMode: ProjectVersionCreationMode;
  initializationModelId?: number;
  status: string;
  analysisStatus: string;
  catalogHash?: string;
  createTime: string;
  publishedTime?: string;
  activatedTime?: string;
  deactivatedTime?: string;
}

export interface ProjectInitializationView {
  project: SemanticProject;
  version?: SemanticProjectVersion;
  openGapCount: number;
  nextGap?: { id: number; question: string; gapType: string; status: string };
}

export interface ProjectHealthSummary {
  projectId: number;
  available: boolean;
  queryReady: boolean;
  activeVersion?: {
    id: number;
    versionNumber: string;
    status: string;
    createTime: string;
    validatedTime?: string;
    publishedTime?: string;
  };
  nextAction?: {
    code: string;
    label: string;
    description: string;
    target: 'data' | 'business' | 'improve' | 'test' | 'release' | 'chat';
  };
  totalQueries: number;
  querySuccessRate: number;
  correctionCount: number;
}

export interface ProjectHealth {
  projectId: number;
  projectStatus: string;
  queryReady: boolean;
  activeVersion?: {
    id: number;
    versionNumber: string;
    status: string;
    createTime: string;
    validatedTime?: string;
    publishedTime?: string;
  };
  workingVersion?: {
    id: number;
    versionNumber: string;
    status: string;
    createTime: string;
    validatedTime?: string;
    publishedTime?: string;
  };
  understanding: {
    catalogReady: boolean;
    readinessViolations: string[];
    openGapCount: number;
    unresolvedConflictCount: number;
    datasourceCount: number;
    documentCount: number;
    modelCount: number;
    metricCount: number;
    dimensionCount: number;
    relationshipCount: number;
  };
  quality: {
    windowDays: number;
    totalQueries: number;
    succeededQueries: number;
    failedQueries: number;
    clarifiedRunCount: number;
    correctionCount: number;
    confirmedTrustedAnswerCount: number;
    successfulWithoutCorrectionCount: number;
    queryCaseReusedRunCount: number;
    querySuccessRate: number;
    clarificationRate: number;
    correctionRate: number;
    confirmedTrustedAnswerRate: number;
    correctionFreeSuccessfulAnswerRate: number;
    queryCaseReuseRate: number;
  };
  freshness: {
    latestSourceFreshnessAsOf?: string;
    lastSuccessfulQueryAt?: string;
    observationStatus: 'OBSERVED' | 'UNOBSERVED';
  };
  release: {
    replayCaseCount: number;
    replayPassedCount: number;
    pendingLearningChangeCount: number;
    replayPassRate?: number;
  };
  nextActions: Array<{
    code: string;
    label: string;
    description: string;
    target: 'data' | 'business' | 'improve' | 'test' | 'release' | 'chat';
  }>;
}

export interface ProjectReleaseCenter {
  projectId: number;
  activeVersionId?: number;
  versions: Array<{
    id: number;
    versionNumber: string;
    parentVersionId?: number;
    status: string;
    catalogHash?: string;
    structuredReleaseReport?: string;
    publishedTime?: string;
    publishedBy?: string;
    active: boolean;
    activatedTime?: string;
    activatedBy?: string;
    governanceDecidedBy?: string;
    changes: Array<{
      kind: 'ADDED' | 'MODIFIED' | 'REMOVED';
      operation: string;
      assetType: string;
      assetKey: string;
      businessName: string;
      candidateId: string;
      candidateStatus: string;
    }>;
    replay: { total: number; passed: number; failed: number; needsAttention: number };
    goldenReplay: {
      registeredCaseCount: number;
      latestJobStatus?: string;
      total: number;
      passed: number;
      failed: number;
      safetyPassed?: boolean;
      observedAt?: string;
    };
  }>;
  controlledReleases: Array<{
    id: string;
    baselineVersionId: number;
    candidateVersionId: number;
    releaseType: string;
    status: string;
    trafficPercent: number;
    sampleCount: number;
    failureCount: number;
    rollbackReason?: string;
    createTime: string;
    updateTime: string;
  }>;
}

export interface ProjectDatasourceBinding {
  id: number;
  projectId: number;
  projectVersionId: number;
  datasourceId: number;
  datasourceName?: string;
  datasourceType?: string;
  domainCode: string;
  domainName: string;
  responsibility: string;
  priority: number;
  exposedTables: string[];
  createTime: string;
  updateTime: string;
}

interface SaveProjectDatasourceBindingPayload {
  domainCode: string;
  domainName: string;
  responsibility: string;
  priority?: number;
  exposedTables: string[];
}

export type ProjectDocumentType =
  | 'DATA_DICTIONARY'
  | 'METRIC_SPEC'
  | 'GLOSSARY'
  | 'REPORT_SPEC'
  | 'HISTORICAL_SQL'
  | 'SYSTEM_RESPONSIBILITY'
  | 'SYNC_POLICY'
  | 'ENUM_SPEC'
  | 'REQUIREMENT';

export type MaterialCategory =
  | 'DATABASE_SCHEMA'
  | 'DATA_DICTIONARY'
  | 'METRIC_DEFINITION'
  | 'BACKEND_SOURCE'
  | 'DATA_ACCESS_CODE'
  | 'SQL_QUERY'
  | 'DATABASE_MIGRATION'
  | 'API_DOCUMENTATION'
  | 'PRODUCT_REQUIREMENT'
  | 'SYSTEM_DESIGN'
  | 'BUSINESS_RULE'
  | 'TEST_MATERIAL'
  | 'REPORT_OR_BI'
  | 'BUSINESS_GLOSSARY'
  | 'OTHER';

export type MaterialLifecycle = 'CURRENT' | 'HISTORICAL' | 'DEPRECATED' | 'UNKNOWN';

type SemanticMaterialType = 'JSON' | 'YAML' | 'MARKDOWN' | 'DDL' | 'HISTORICAL_SQL';

export interface ProjectDocument {
  id: number;
  projectId: number;
  projectVersionId: number;
  documentType: ProjectDocumentType;
  materialCategory?: MaterialCategory;
  lifecycle?: MaterialLifecycle;
  materialType: SemanticMaterialType;
  sourceType: 'INLINE' | 'UPLOAD' | 'CLONE' | 'DATABASE_SCAN';
  sourceMaterialId?: number;
  sourceName?: string;
  originalFilename?: string;
  mediaType?: string;
  filePath?: string;
  fileSize?: number;
  sourceLocation?: string;
  datasourceId?: number;
  contentHash: string;
  content?: string;
  contentLength: number;
  contentTruncated: boolean;
  status: 'RECEIVED' | 'PARSED' | 'APPLIED' | 'REVIEW_REQUIRED' | 'FAILED';
  parseSummary?: string;
  errorMessage?: string;
  createTime: string;
  updateTime: string;
}

export interface ProjectDocumentAttempt {
  id: number;
  attemptNo: number;
  status: ProjectDocument['status'];
  contentHash: string;
  sourceLocation?: string;
  extractionModel?: string;
  parseSummary?: string;
  errorMessage?: string;
  startTime: string;
  finishTime?: string;
  createTime: string;
}

export interface ProjectDocumentProvenance {
  id: number;
  attemptId: number;
  assetType:
    | 'MODEL'
    | 'COLUMN'
    | 'METRIC'
    | 'DIMENSION'
    | 'RELATIONSHIP'
    | 'GRAIN'
    | 'ENUM_VALUE'
    | 'RULE';
  assetKey: string;
  assetFingerprint: string;
  disposition: 'APPLIED' | 'CONFLICT';
  conflictGapKey?: string;
  confidence: number;
  sourceLocation?: string;
  extractionModel?: string;
  evidence?: string;
  createTime: string;
}

interface ProjectDocumentIngestionResult {
  material: ProjectDocument;
  status: ProjectDocument['status'];
  parseSummary?: string;
  createdGapCount: number;
  duplicate: boolean;
}

interface ProjectBundleIngestionResult {
  archiveName: string;
  processedCount: number;
  duplicateCount: number;
  createdCount: number;
  entries: Array<{
    entryName: string;
    materialCategory: MaterialCategory;
    materialId?: number;
    status?: ProjectDocument['status'];
    duplicate: boolean;
  }>;
}

interface SemanticCatalogModel extends Record<string, unknown> {
  id?: number;
  datasourceId?: number;
  modelCode?: string;
  physicalTable?: string;
  businessName?: string;
  modelType?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

export interface SemanticCatalogColumn extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  columnName?: string;
  businessName?: string;
  dataType?: string;
  role?: string;
  expression?: string;
  synonyms?: string;
  description?: string;
  sensitivityLevel?: string;
  maskingPolicy?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogMetric extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  metricCode?: string;
  businessName?: string;
  expression?: string;
  aggregation?: string;
  unit?: string;
  timeColumn?: string;
  filterExpression?: string;
  additiveType?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogDimension extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  dimensionCode?: string;
  businessName?: string;
  columnName?: string;
  expression?: string;
  dimensionType?: string;
  hierarchy?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogRelationship extends Record<string, unknown> {
  id?: number;
  relationshipCode?: string;
  sourceModelCode?: string;
  targetModelCode?: string;
  cardinality?: string;
  joinType?: string;
  joinCondition?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogGrain extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  grainCode?: string;
  keyColumns?: string;
  timeColumn?: string;
  uniquenessRule?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogEnumValue extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  columnName?: string;
  valueCode?: string;
  businessName?: string;
  aliases?: string;
  description?: string;
  sortOrder?: number;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogRule extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  ruleCode?: string;
  ruleType?: string;
  businessName?: string;
  expression?: string;
  severity?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

export interface SemanticCatalogSnapshot {
  projectId: number;
  projectVersionId: number;
  models: SemanticCatalogModel[];
  columns: SemanticCatalogColumn[];
  metrics: SemanticCatalogMetric[];
  dimensions: SemanticCatalogDimension[];
  relationships: SemanticCatalogRelationship[];
  grains: SemanticCatalogGrain[];
  enumValues: SemanticCatalogEnumValue[];
  rules: SemanticCatalogRule[];
}

export interface MultiSourcePolicySnapshot {
  projectId?: number;
  projectVersionId?: number;
  logicalBindings: Array<Record<string, unknown>>;
  authorityRules: Array<Record<string, unknown>>;
  freshnessPolicies: Array<Record<string, unknown>>;
  crossSourceRelationships: Array<Record<string, unknown>>;
  mergePolicies: Array<Record<string, unknown>>;
}

export type QueryApprovalMode = 'REQUIRE_APPROVAL' | 'AUTO_EXECUTE';

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

export interface QueryCorrectionOption {
  assetType: 'METRIC' | 'DIMENSION' | 'ENUM_VALUE' | 'TIME_COLUMN';
  assetKey: string;
  businessLabel: string;
  modelCode?: string;
}

interface QueryCorrectionOptions {
  runId: string;
  assetType: 'METRIC' | 'DIMENSION' | 'ENUM_VALUE' | 'TIME_COLUMN';
  options: QueryCorrectionOption[];
}

interface QueryCorrectionResult {
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
  state: 'PASSED' | 'FAILED' | 'WAITING' | 'UNKNOWN';
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
  kind: 'CORRECTION' | 'EVOLUTION' | 'REPLAY' | 'RELEASE' | string;
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
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
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
}

export interface ProjectMessage {
  messageId: string;
  conversationId: string;
  sequenceNo: number;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  runId?: string;
  status: string;
  metadataJson?: string;
  createTime: string;
  updateTime: string;
}

interface ProjectConversationView {
  conversation: ProjectConversation;
  messages: ProjectMessage[];
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

export interface QueryCaseIndexReadiness {
  status: 'INDEX_READY' | 'PARTIAL' | 'REINDEX_REQUIRED' | 'LEXICAL_ONLY';
  approvedCaseCount: number;
  vectorCount: number;
  dimension?: number;
  detail: string;
}

export interface ValidatedQueryExample {
  id: string;
  project_id: number;
  project_version_id: number;
  catalog_hash: string;
  datasource_id?: number;
  episode_id: string;
  attempt_id: string;
  original_question?: string;
  normalized_question: string;
  intent_type?: string;
  conversation_independent: boolean | number;
  resolved_time_range_json?: string;
  typed_ir_json?: string;
  resolution_json?: string;
  quality_proof_json?: string;
  result_schema_hash?: string;
  canonical_shape_hash?: string;
  sql_text: string;
  run_id?: string;
  context_hash?: string;
  historical_sql_text?: string;
  status: 'CANDIDATE' | 'APPROVED' | 'QUARANTINED' | 'REJECTED' | 'STALE';
  rebind_status:
    | 'VALID'
    | 'NEEDS_REBIND'
    | 'REBOUND_PENDING_REPLAY'
    | 'REBOUND'
    | 'NEEDS_REVIEW'
    | 'INVALID'
    | 'SUPERSEDED';
  source_example_id?: string;
  quality_summary?: string;
  reviewed_by?: string;
  review_comment?: string;
  reviewed_time?: string;
  create_time: string;
  update_time: string;
  recall_count?: number;
  adopted_count?: number;
  failed_after_recall_count?: number;
  consecutive_recall_issue_count?: number;
  last_recalled_time?: string;
  derived_from_case_ids?: string;
  root_evidence_ids?: string;
  evidence_lineage_hash?: string;
  quarantine_reason?: string;
  quarantine_time?: string;
  assetReferences?: Array<Record<string, unknown>>;
  rebinds?: Array<Record<string, unknown>>;
}

export interface QueryPattern {
  id: string;
  project_id: number;
  project_version_id: number;
  catalog_hash: string;
  execution_compatibility_hash: string;
  shape_hash: string;
  instance_hash: string;
  intent_type: string;
  pattern_json: string;
  ambiguity_level: string;
  risk_level: string;
  episode_count: number;
  success_count: number;
  status: string;
  first_seen_time: string;
  last_seen_time: string;
}

export interface TrajectoryPath {
  id: string;
  episode_id: string;
  attempt_id: string;
  run_id?: string;
  pattern_id: string;
  path_signature: string;
  node_sequence_json: string;
  decision_sequence_json: string;
  source_sequence_json: string;
  status: string;
  correctness_score: number;
  safety_score: number;
  coverage_score: number;
  freshness_score: number;
  stability_score: number;
  latency_ms?: number;
  token_count?: number;
  retry_count: number;
  clarification_count: number;
  source_count: number;
  merge_count: number;
  cost_json: string;
  result_proof_json: string;
  create_time: string;
}

interface QueryPathProfile {
  id: string;
  pattern_id: string;
  path_signature: string;
  sample_count: number;
  success_count: number;
  correctness_rate: number;
  safety_rate: number;
  coverage_rate: number;
  freshness_rate: number;
  stability_rate: number;
  avg_latency_ms: number;
  avg_token_count: number;
  avg_retry_count: number;
  avg_clarification_count: number;
  dominated: boolean | number;
  pareto_rank: number;
  status: string;
}

export interface DetourSignal {
  id: string;
  pattern_id: string;
  path_id: string;
  signal_type: string;
  root_cause: string;
  confidence: number;
  occurrence_count: number;
  recurrence_rate: number;
  evidence_json: string;
  status: string;
  create_time: string;
}

export interface QueryPatternDetail extends QueryPattern {
  profiles: QueryPathProfile[];
  detours: DetourSignal[];
}

export interface SemanticPatchOperation {
  operation:
    | 'ADD_COLUMN_SYNONYM'
    | 'ADD_ENUM_ALIAS'
    | 'ADD_ENUM_VALUE'
    | 'ADD_METRIC'
    | 'UPDATE_METRIC'
    | 'ADD_DIMENSION'
    | 'UPDATE_DIMENSION'
    | 'ADD_RELATIONSHIP'
    | 'UPDATE_RELATIONSHIP'
    | 'ADD_GRAIN'
    | 'UPDATE_GRAIN'
    | 'ADD_RULE'
    | 'UPDATE_RULE'
    | 'ADD'
    | 'UPDATE';
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

type MultiSourcePolicyPatch = SemanticPatch;

interface SemanticPatchValidationIssue {
  severity: 'ERROR' | 'WARNING';
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
  mapping_classification?: 'LOW_SAMPLE' | 'STABLE_MAPPING' | 'TRUE_AMBIGUITY';
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
  targetVersionLevel: 'PATCH' | 'MINOR' | 'MAJOR';
  originType: 'EPISODE' | 'CORPUS' | 'MANUAL' | 'BASELINE_PROMOTION';
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
  };
  knowledgeUpdateInProgress: boolean;
  knowledgeUpdateCount: number;
  latestCorpusRevision: Record<string, unknown>;
  knowledgeUpdates: Array<Record<string, unknown>>;
}

export interface RuntimeOptimizationCandidate {
  id: string;
  project_id: number;
  project_version_id: number;
  pattern_id: string;
  execution_compatibility_hash: string;
  optimization_type: string;
  status: string;
  applicability_json: string;
  proposal_json: string;
  baseline_metrics_json: string;
  shadow_metrics_json?: string;
  confidence: number;
  risk_level: string;
  reviewed_by?: string;
  review_comment?: string;
  reviewed_time?: string;
  create_time: string;
  update_time: string;
  gatePassed?: boolean;
  gateReasons?: string[];
  costReduction?: number;
  preferredPlans?: Array<Record<string, unknown>>;
}

interface CreateProjectPayload {
  projectCode: string;
  name: string;
  businessDomain: string;
  description?: string;
  firstVersionNumber: string;
  source?: string;
  datasourceBindings?: Array<{
    datasourceId: number;
    domainCode: string;
    domainName: string;
    responsibility: string;
    priority?: number;
    exposedTables: string[];
  }>;
}

export const semEvoSQLService = {
  async currentOperator(): Promise<OperatorView> {
    return (await axios.get(`${apiBase}/operator-context`)).data;
  },
  async listProjects(): Promise<SemanticProject[]> {
    return (await axios.get(`${apiBase}/projects`)).data;
  },
  async project(projectId: number): Promise<ProjectInitializationView> {
    return (await axios.get(`${apiBase}/projects/${projectId}`)).data;
  },
  async projectHealth(projectId: number): Promise<ProjectHealth> {
    return (await axios.get(`${apiBase}/projects/${projectId}/health`)).data;
  },
  async projectHealthSummaries(): Promise<ProjectHealthSummary[]> {
    return (await axios.get(`${apiBase}/projects/health-summary`)).data;
  },
  async projectReleaseCenter(projectId: number): Promise<ProjectReleaseCenter> {
    return (await axios.get(`${apiBase}/projects/${projectId}/release-center`)).data;
  },
  async createProject(payload: CreateProjectPayload): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects`, payload)).data;
  },
  async projectVersions(projectId: number): Promise<SemanticProjectVersion[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/versions`)).data;
  },
  async queryExamples(
    projectId: number,
    projectVersionId?: number,
    status?: string,
    rebindStatus?: string,
  ): Promise<ValidatedQueryExample[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/query-examples`, {
        params: {
          projectVersionId,
          status: status || undefined,
          rebindStatus: rebindStatus || undefined,
          limit: 200,
        },
      })
    ).data;
  },
  async queryExample(projectId: number, exampleId: string): Promise<ValidatedQueryExample> {
    return (await axios.get(`${apiBase}/projects/${projectId}/query-examples/${exampleId}`)).data;
  },
  async queryCaseIndexReadiness(projectId: number): Promise<QueryCaseIndexReadiness> {
    return (await axios.get(`${apiBase}/operations/projects/${projectId}/query-case-index`)).data;
  },
  async reindexQueryCaseIndex(projectId: number): Promise<{ indexedEmbeddings: number; projectId: number }> {
    return (
      await axios.post(`${apiBase}/operations/query-case-index/reindex`, undefined, {
        params: { projectId },
        headers: governedMutationHeaders(`query-case-index-reindex:${projectId}`),
      })
    ).data;
  },
  async restoreQuarantinedQueryExample(
    projectId: number,
    exampleId: string,
    reason: string,
  ): Promise<ValidatedQueryExample> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/query-examples/${exampleId}/quarantine/restore`,
        { reason: reason.trim() },
        { headers: governedMutationHeaders(`query-case-restore:${exampleId}`) },
      )
    ).data;
  },
  async rejectQuarantinedQueryExample(
    projectId: number,
    exampleId: string,
    reason: string,
  ): Promise<ValidatedQueryExample> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/query-examples/${exampleId}/quarantine/reject`,
        { reason: reason.trim() },
        { headers: governedMutationHeaders(`query-case-reject:${exampleId}`) },
      )
    ).data;
  },
  async projectDatasourceBindings(
    projectId: number,
    versionId: number,
  ): Promise<ProjectDatasourceBinding[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/versions/${versionId}/datasources`))
      .data;
  },
  async saveProjectDatasourceBinding(
    projectId: number,
    versionId: number,
    datasourceId: number,
    payload: SaveProjectDatasourceBindingPayload,
  ): Promise<ProjectDatasourceBinding> {
    return (
      await axios.put(
        `${apiBase}/projects/${projectId}/versions/${versionId}/datasources/${datasourceId}`,
        payload,
      )
    ).data;
  },
  async deleteProjectDatasourceBinding(
    projectId: number,
    versionId: number,
    datasourceId: number,
  ): Promise<void> {
    await axios.delete(
      `${apiBase}/projects/${projectId}/versions/${versionId}/datasources/${datasourceId}`,
    );
  },
  async projectDocuments(projectId: number, versionId: number): Promise<ProjectDocument[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/versions/${versionId}/documents`))
      .data;
  },
  async uploadProjectDocument(
    projectId: number,
    versionId: number,
    payload: {
      documentType: ProjectDocumentType;
      materialCategory?: MaterialCategory;
      lifecycle?: MaterialLifecycle;
      materialType?: SemanticMaterialType;
      datasourceId?: number;
      sourceName?: string;
      sourceLocation?: string;
      file: File;
    },
  ): Promise<ProjectDocumentIngestionResult> {
    const formData = new FormData();
    formData.append('documentType', payload.documentType);
    if (payload.materialCategory) formData.append('materialCategory', payload.materialCategory);
    if (payload.lifecycle) formData.append('lifecycle', payload.lifecycle);
    if (payload.materialType) formData.append('materialType', payload.materialType);
    if (payload.datasourceId != null) formData.append('datasourceId', String(payload.datasourceId));
    if (payload.sourceName?.trim()) formData.append('sourceName', payload.sourceName.trim());
    if (payload.sourceLocation?.trim())
      formData.append('sourceLocation', payload.sourceLocation.trim());
    formData.append('file', payload.file);
    return (
      await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/documents`, formData)
    ).data;
  },
  async uploadProjectBundle(
    projectId: number,
    versionId: number,
    payload: {
      materialCategory?: MaterialCategory;
      lifecycle?: MaterialLifecycle;
      datasourceId?: number;
      sourceName?: string;
      sourceLocation?: string;
      file: File;
    },
  ): Promise<ProjectBundleIngestionResult> {
    const formData = new FormData();
    if (payload.materialCategory) formData.append('materialCategory', payload.materialCategory);
    if (payload.lifecycle) formData.append('lifecycle', payload.lifecycle);
    if (payload.datasourceId != null) formData.append('datasourceId', String(payload.datasourceId));
    if (payload.sourceName?.trim()) formData.append('sourceName', payload.sourceName.trim());
    if (payload.sourceLocation?.trim())
      formData.append('sourceLocation', payload.sourceLocation.trim());
    formData.append('file', payload.file);
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents/bundle`,
        formData,
      )
    ).data;
  },
  async projectDocumentAttempts(
    projectId: number,
    versionId: number,
    documentId: number,
  ): Promise<ProjectDocumentAttempt[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents/${documentId}/attempts`,
      )
    ).data;
  },
  async projectDocumentProvenance(
    projectId: number,
    versionId: number,
    documentId: number,
  ): Promise<ProjectDocumentProvenance[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents/${documentId}/provenance`,
      )
    ).data;
  },
  async reparseProjectDocument(
    projectId: number,
    versionId: number,
    documentId: number,
    extractionModel?: string,
  ): Promise<ProjectDocumentIngestionResult> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents/${documentId}/reparse`,
        { extractionModel: extractionModel?.trim() || undefined },
      )
    ).data;
  },
  async deleteProjectDocument(
    projectId: number,
    versionId: number,
    documentId: number,
  ): Promise<void> {
    await axios.delete(
      `${apiBase}/projects/${projectId}/versions/${versionId}/documents/${documentId}`,
    );
  },
  async semanticCatalog(projectId: number, versionId: number): Promise<SemanticCatalogSnapshot> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/versions/${versionId}/semantic-catalog`)
    ).data;
  },
  async scanProjectDatasource(
    projectId: number,
    versionId: number,
    datasourceId: number,
    tables: string[],
  ): Promise<void> {
    await axios.post(
      `${apiBase}/projects/${projectId}/versions/${versionId}/semantic-catalog/scan-database`,
      { datasourceId, tables },
    );
  },
  async replaceSemanticCatalog(
    projectId: number,
    versionId: number,
    catalog: SemanticCatalogSnapshot,
  ): Promise<SemanticCatalogSnapshot> {
    return (
      await axios.put(
        `${apiBase}/projects/${projectId}/versions/${versionId}/semantic-catalog`,
        catalog,
      )
    ).data;
  },
  async multiSourcePolicy(
    projectId: number,
    versionId: number,
  ): Promise<MultiSourcePolicySnapshot> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/versions/${versionId}/multi-source-policy`)
    ).data;
  },
  async replaceMultiSourcePolicy(
    projectId: number,
    versionId: number,
    policy: MultiSourcePolicySnapshot,
  ): Promise<MultiSourcePolicySnapshot> {
    return (
      await axios.put(
        `${apiBase}/projects/${projectId}/versions/${versionId}/multi-source-policy`,
        policy,
      )
    ).data;
  },
  async multiSourcePolicyViolations(projectId: number, versionId: number): Promise<string[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/multi-source-policy/violations`,
      )
    ).data;
  },
  async createProjectVersion(
    projectId: number,
    payload: {
      versionNumber: string;
      creationMode: ProjectVersionCreationMode;
      parentVersionId?: number;
      source?: string;
    },
  ): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects/${projectId}/versions`, payload)).data;
  },
  async initializeProjectVersion(
    projectId: number,
    versionId: number,
    initializationModelId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/initialize`, {
        initializationModelId,
      })
    ).data;
  },
  async validateProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/validate`))
      .data;
  },
  async publishProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/publish`))
      .data;
  },
  async activateProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/activate`))
      .data;
  },
  async dashboard(projectId: number): Promise<SemEvoSQLDashboard> {
    return (await axios.get(`${operationsBase}/projects/${projectId}/dashboard`)).data;
  },
  async episodes(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/episodes`)).data;
  },
  async jobs(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/jobs`)).data;
  },
  async releases(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/releases`)).data;
  },
  async goldenCases(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/golden-cases`)).data;
  },
  async createReplay(projectId: number, versionId: number) {
    return (
      await axios.post(`${operationsBase}/projects/${projectId}/jobs`, {
        projectVersionId: versionId,
        jobType: 'REPLAY',
        idempotencyKey: `ui-replay-${versionId}-${Date.now()}`,
        options: {},
      })
    ).data;
  },
  async trajectoryPatterns(
    projectId: number,
    projectVersionId?: number,
    limit = 100,
  ): Promise<QueryPattern[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/trajectory/patterns`, {
        params: { projectVersionId, limit },
      })
    ).data;
  },
  async trajectoryPattern(patternId: string): Promise<QueryPatternDetail> {
    return (await axios.get(`${apiBase}/trajectory/patterns/${patternId}`)).data;
  },
  async trajectoryPaths(patternId: string, limit = 100): Promise<TrajectoryPath[]> {
    return (
      await axios.get(`${apiBase}/trajectory/patterns/${patternId}/paths`, { params: { limit } })
    ).data;
  },
  async recomputeTrajectoryPattern(patternId: string): Promise<QueryPatternDetail> {
    return (await axios.post(`${apiBase}/trajectory/patterns/${patternId}/recompute`)).data;
  },
  async detourSignals(projectId: number, status?: string, limit = 200): Promise<DetourSignal[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/trajectory/detours`, {
        params: { status: status || undefined, limit },
      })
    ).data;
  },
  async semanticEvolutionCandidates(
    projectId: number,
    status?: string,
    limit = 200,
  ): Promise<SemanticEvolutionCandidate[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/semantic-evolution/candidates`, {
        params: { status: status || undefined, limit },
      })
    ).data;
  },
  async semanticEvolutionCandidate(candidateId: string): Promise<SemanticEvolutionCandidate> {
    return (await axios.get(`${apiBase}/semantic-evolution/candidates/${candidateId}`)).data;
  },
  async updateSemanticEvolutionPatch(
    candidateId: string,
    patch: SemanticPatch,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(`${apiBase}/semantic-evolution/candidates/${candidateId}/patch`, patch, {
        headers: governedMutationHeaders(`semantic-patch-edit:${candidateId}`),
      })
    ).data;
  },
  async preflightSemanticEvolutionPatch(
    candidateId: string,
    patch?: SemanticPatch,
  ): Promise<SemanticPatchValidationReport> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/patch/preflight`,
        patch,
      )
    ).data;
  },
  async updateMultiSourcePolicyPatch(
    candidateId: string,
    patch: MultiSourcePolicyPatch,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/policy-patch`,
        patch,
        {
          headers: governedMutationHeaders(`multi-source-policy-patch-edit:${candidateId}`),
        },
      )
    ).data;
  },
  async preflightMultiSourcePolicyPatch(
    candidateId: string,
    patch?: MultiSourcePolicyPatch,
  ): Promise<SemanticPatchValidationReport> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/policy-patch/preflight`,
        patch,
      )
    ).data;
  },
  async reviewSemanticEvolution(
    candidateId: string,
    approved: boolean,
    comment?: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/review`,
        {
          approved,
          comment: comment?.trim() || undefined,
        },
        { headers: governedMutationHeaders(`semantic-review:${candidateId}`) },
      )
    ).data;
  },
  async createSemanticEvolutionDraft(
    candidateId: string,
    versionNumber: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/draft`,
        {
          versionNumber,
        },
        {
          headers: governedMutationHeaders(`semantic-draft:${candidateId}`),
        },
      )
    ).data;
  },
  async replaySemanticEvolution(candidateId: string): Promise<SemanticReplayRun> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/replay`,
        undefined,
        { headers: governedMutationHeaders(`semantic-replay:${candidateId}`) },
      )
    ).data;
  },
  async semanticEvolutionReplayRun(replayRunId: string): Promise<SemanticReplayRun> {
    return (await axios.get(`${apiBase}/semantic-evolution/replay-runs/${replayRunId}`)).data;
  },
  async semanticEvolutionReplayEvents(replayRunId: string, afterSequence = 0): Promise<RunEvent[]> {
    return (
      await axios.get(`${apiBase}/semantic-evolution/replay-runs/${replayRunId}/events`, {
        params: { afterSequence, limit: 200 },
      })
    ).data;
  },
  async cancelSemanticEvolutionReplay(replayRunId: string): Promise<SemanticReplayRun> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/replay-runs/${replayRunId}/cancel`,
        undefined,
        { headers: governedMutationHeaders(`semantic-replay-cancel:${replayRunId}`) },
      )
    ).data;
  },
  async semanticEvolutionReplayResults(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (
      await axios.get(`${apiBase}/semantic-evolution/candidates/${candidateId}/replay-results`)
    ).data;
  },
  async semanticEvolutionAttestations(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (await axios.get(`${apiBase}/semantic-evolution/candidates/${candidateId}/attestations`))
      .data;
  },
  async semanticEvolutionReleaseDecisions(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (
      await axios.get(`${apiBase}/semantic-evolution/candidates/${candidateId}/release-decisions`)
    ).data;
  },
  async recordSemanticEvolutionReplay(
    candidateId: string,
    passed: boolean,
    summary: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/manual-replay`,
        {
          passed,
          summary,
        },
        {
          headers: governedMutationHeaders(`semantic-manual-replay:${candidateId}`),
        },
      )
    ).data;
  },
  async readySemanticEvolution(candidateId: string): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(`${apiBase}/semantic-evolution/candidates/${candidateId}/ready`, undefined, {
        headers: governedMutationHeaders(`semantic-ready:${candidateId}`),
      })
    ).data;
  },
  async acknowledgeSemanticEvolutionPublished(
    candidateId: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (await axios.post(`${apiBase}/semantic-evolution/candidates/${candidateId}/published`))
      .data;
  },
  async staleSemanticEvolution(
    candidateId: string,
    reason: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(`${apiBase}/semantic-evolution/candidates/${candidateId}/stale`, undefined, {
        params: { reason },
        headers: governedMutationHeaders(`semantic-stale:${candidateId}`),
      })
    ).data;
  },
  async semanticVersionTimeline(projectId: number): Promise<SemanticVersionTimeline> {
    return (await axios.get(`${apiBase}/projects/${projectId}/semantic-versions/timeline`)).data;
  },
  async corpusRevisions(projectId: number): Promise<CorpusRevision[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/corpus-revisions`)).data;
  },
  async semanticChangeSets(
    projectId: number,
    status?: string,
    limit = 100,
  ): Promise<SemanticChangeSet[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/semantic-change-sets`, {
        params: { status: status || undefined, limit },
      })
    ).data;
  },
  async semanticChangeSet(changeSetId: string): Promise<SemanticChangeSetDetail> {
    return (await axios.get(`${apiBase}/semantic-change-sets/${changeSetId}`)).data;
  },
  async episodeDiagnosis(episodeId: string): Promise<EpisodeDiagnosis> {
    return (await axios.get(`${apiBase}/episodes/${episodeId}/diagnosis`)).data;
  },
  async semanticReadiness(projectId: number): Promise<ProjectSemanticReadiness> {
    return (await axios.get(`${apiBase}/projects/${projectId}/semantic-readiness`)).data;
  },
  async promoteSemanticChangeSet(
    changeSetId: string,
    reason?: string,
  ): Promise<Record<string, unknown>> {
    return (
      await axios.post(
        `${apiBase}/semantic-change-sets/${changeSetId}/promote`,
        { reason: reason?.trim() || undefined },
        { headers: governedMutationHeaders(`semantic-major-promote:${changeSetId}`) },
      )
    ).data;
  },
  async rollbackSemanticVersion(
    projectId: number,
    versionId: number,
    reason?: string,
  ): Promise<Record<string, unknown>> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/semantic-versions/${versionId}/rollback`,
        { reason: reason?.trim() || undefined },
        { headers: governedMutationHeaders(`semantic-version-rollback:${versionId}`) },
      )
    ).data;
  },
  async runtimeOptimizationCandidates(
    projectId: number,
    status?: string,
    limit = 200,
  ): Promise<RuntimeOptimizationCandidate[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/runtime-optimization/candidates`, {
        params: { status: status || undefined, limit },
      })
    ).data;
  },
  async runtimeOptimizationCandidate(candidateId: string): Promise<RuntimeOptimizationCandidate> {
    return (await axios.get(`${apiBase}/runtime-optimization/candidates/${candidateId}`)).data;
  },
  async recordOptimizationShadow(
    candidateId: string,
    metrics: Record<string, number>,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(`${apiBase}/runtime-optimization/candidates/${candidateId}/shadow`, {
        metrics,
      })
    ).data;
  },
  async approveRuntimeOptimization(
    candidateId: string,
    comment?: string,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(
        `${apiBase}/runtime-optimization/candidates/${candidateId}/approve`,
        { comment: comment?.trim() || undefined },
        { headers: governedMutationHeaders(`runtime-optimization-approve:${candidateId}`) },
      )
    ).data;
  },
  async rejectRuntimeOptimization(
    candidateId: string,
    comment: string,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(
        `${apiBase}/runtime-optimization/candidates/${candidateId}/reject`,
        { comment },
        { headers: governedMutationHeaders(`runtime-optimization-reject:${candidateId}`) },
      )
    ).data;
  },
  async enableRuntimeOptimization(candidateId: string): Promise<RuntimeOptimizationCandidate> {
    return (await axios.post(`${apiBase}/runtime-optimization/candidates/${candidateId}/enable`))
      .data;
  },
  async disableRuntimeOptimization(
    candidateId: string,
    reason: string,
    degraded = false,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(
        `${apiBase}/runtime-optimization/candidates/${candidateId}/disable`,
        undefined,
        {
          params: { reason, degraded },
        },
      )
    ).data;
  },

  async startOnboarding(projectId: number, versionId: number): Promise<OnboardingView> {
    return (
      await axios.post(`${apiBase}/projects/${projectId}/versions/${versionId}/onboarding/start`, {
        idempotencyKey: `ui-onboarding-${projectId}-${versionId}`,
      })
    ).data;
  },
  async onboarding(projectId: number, versionId: number): Promise<OnboardingView> {
    return (await axios.get(`${apiBase}/projects/${projectId}/versions/${versionId}/onboarding`))
      .data;
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
          answerType: 'JSON_OR_TEXT',
          revision: question.revision,
          idempotencyKey: crypto.randomUUID(),
        },
      )
    ).data;
  },
  async confirmOnboarding(projectId: number, versionId: number, revision: number) {
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
  async projectConversations(projectId: number): Promise<ProjectConversation[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/conversations`)).data;
  },
  async createProjectConversation(
    projectId: number,
    title = '新对话',
  ): Promise<ProjectConversation> {
    return (await axios.post(`${apiBase}/projects/${projectId}/conversations`, { title })).data;
  },
  async projectConversation(
    projectId: number,
    conversationId: string,
  ): Promise<ProjectConversationView> {
    return (await axios.get(`${apiBase}/projects/${projectId}/conversations/${conversationId}`))
      .data;
  },
  async sendProjectMessage(
    projectId: number,
    conversationId: string,
    content: string,
    approvalMode: QueryApprovalMode = 'REQUIRE_APPROVAL',
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
    assetType: 'METRIC' | 'DIMENSION' | 'ENUM_VALUE' | 'TIME_COLUMN',
  ): Promise<QueryCorrectionOptions> {
    return (
      await axios.get(`${apiBase}/runs/${runId}/correction-options`, {
        params: { assetType },
      })
    ).data;
  },
  async correctBinding(
    projectId: number,
    conversationId: string,
    runId: string,
    payload: {
      rawExpression: string;
      assetType: 'METRIC' | 'DIMENSION' | 'ENUM_VALUE' | 'TIME_COLUMN';
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
    category: 'DEFINITION' | 'TIME' | 'FILTER' | 'RELATIONSHIP' | 'PLANNING',
    correctionText: string,
  ): Promise<{ candidateId: string; status: string }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/conversations/${conversationId}/runs/${runId}/corrections/definition`,
        { category, correctionText },
      )
    ).data;
  },
  async promoteSemanticPreference(preferenceId: number) {
    return (await axios.post(`${apiBase}/semantic-preferences/${preferenceId}/promote-project`))
      .data;
  },
  async continueSemanticPreference(preferenceId: number) {
    return (await axios.post(`${apiBase}/semantic-preferences/${preferenceId}/continue-personal`))
      .data;
  },
  async dismissSemanticPreferenceUpgrade(preferenceId: number) {
    return (await axios.post(`${apiBase}/semantic-preferences/${preferenceId}/dismiss-upgrade`))
      .data;
  },
  async diagnosis(runId: string): Promise<QueryDiagnosis> {
    return (await axios.get(`${apiBase}/runs/${runId}/diagnosis`)).data;
  },
  async run(runId: string): Promise<QueryRun> {
    return (await axios.get(`${apiBase}/runs/${runId}`)).data;
  },
  async runEvents(runId: string, afterSequence = 0, limit = 200): Promise<RunEvent[]> {
    return (
      await axios.get(`${apiBase}/runs/${runId}/events`, { params: { afterSequence, limit } })
    ).data;
  },
  async resultArtifact(runId: string, artifactId: string): Promise<ResultArtifact> {
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
      `${apiBase}/runs/${encodeURIComponent(runId)}/stream?afterSequence=${Math.max(0, afterSequence)}`,
    );
    const handle = (message: MessageEvent) => onEvent(JSON.parse(message.data) as RunEvent);
    source.onmessage = handle;
    [
      'NODE_OUTPUT',
      'RUN_STARTED',
      'CLARIFICATION_REQUIRED',
      'CLARIFICATION_ANSWERED',
      'HUMAN_FEEDBACK_REQUIRED',
      'HUMAN_FEEDBACK_ANSWERED',
      'HUMAN_FEEDBACK_APPLIED',
      'HUMAN_FEEDBACK_REPLAN_RESTARTED',
      'HUMAN_FEEDBACK_APPROVED_PLAN_RESTARTED',
      'ENTRY_REPLAY_QUEUED',
      'RESUME_REQUESTED',
      'SOURCE_SQL_GENERATED',
      'MULTI_SOURCE_RUN_ESTABLISHED',
      'SOURCE_SUBRUNS_CREATED',
      'SOURCE_SUBRUN_RUNNING',
      'SOURCE_SUBRUN_COMPLETED',
      'SOURCE_SUBRUN_FAILED',
      'SOURCE_SUBRUN_PARTIAL_FAILURE',
      'MERGE_COMPLETED',
      'RESULT_ARTIFACT_READY',
      'MULTI_SOURCE_QUEUE_REJECTED',
      'CANCEL_REQUESTED',
      'RUN_CANCELLED',
      'RUN_SUCCEEDED',
      'RUN_FAILED',
    ].forEach(eventType => source.addEventListener(eventType, handle as EventListener));
    source.onopen = () => onOpen?.();
    source.onerror = () => onTransportError?.();
    return source;
  },
  async cancelRun(runId: string) {
    return (
      await axios.post(`${apiBase}/runs/${runId}/cancel`, { idempotencyKey: crypto.randomUUID() })
    ).data;
  },
  async resumeRun(runId: string) {
    return (
      await axios.post(`${apiBase}/runs/${runId}/resume`, { idempotencyKey: crypto.randomUUID() })
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
    idempotencyKey: string,
  ) {
    return (
      await axios.post(
        `${apiBase}/runs/${runId}/clarification/${clarification.clarificationId}/answer`,
        {
          revision: clarification.revision,
          idempotencyKey,
          selectedOption,
          customAnswer: customAnswer.trim() || undefined,
          scope,
        },
      )
    ).data;
  },
};
