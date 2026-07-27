/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export interface OperatorView {
  operator: string;
  source: string;
  administrator: boolean;
}

export interface SemEvoSQLDashboard {
  episodes: number;
  successfulEpisodes: number;
  guardRejected: number;
  queryExampleCandidates: number;
  approvedQueryExamples: number;
  goldenCases: number;
  runningJobs: number;
  releases: number;
  catalogCache: {
    size: number;
    hits: number;
    misses: number;
    loading?: number;
  };
}

export type ProjectVersionCreationMode = "CLONE" | "BLANK";

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
  versionLevel?: "INITIAL" | "PATCH" | "MINOR" | "MAJOR";
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
    target: "data" | "business" | "improve" | "test" | "release" | "chat";
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
    observationStatus: "OBSERVED" | "UNOBSERVED";
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
    target: "data" | "business" | "improve" | "test" | "release" | "chat";
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
      kind: "ADDED" | "MODIFIED" | "REMOVED";
      operation: string;
      assetType: string;
      assetKey: string;
      businessName: string;
      candidateId: string;
      candidateStatus: string;
    }>;
    replay: {
      total: number;
      passed: number;
      failed: number;
      needsAttention: number;
    };
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

export interface SaveProjectDatasourceBindingPayload {
  domainCode: string;
  domainName: string;
  responsibility: string;
  priority?: number;
  exposedTables: string[];
}

export interface CreateProjectPayload {
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
