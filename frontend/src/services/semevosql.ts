/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { definitionsApi } from "./semevosql/definitions.ts";
import { projectsApi } from "./semevosql/projects.ts";
import { learningApi } from "./semevosql/learning.ts";
import { materialsApi } from "./semevosql/materials.ts";
import { catalogApi } from "./semevosql/catalog.ts";
import { governanceApi } from "./semevosql/governance.ts";
import { onboardingApi } from "./semevosql/onboarding.ts";
import { queryApi } from "./semevosql/query.ts";
import type {
  ProjectVersionCreationMode,
  ProjectInitializationView,
  ProjectReleaseCenter,
} from "./semevosql/contracts/projects.ts";
import type { EpisodeDiagnosis } from "./semevosql/contracts/governance.ts";

export type {
  OperatorView,
  ProjectVersionCreationMode,
  SemanticProject,
  SemanticProjectVersion,
  ProjectInitializationView,
  ProjectHealthSummary,
  ProjectHealth,
  ProjectReleaseCenter,
  ProjectDatasourceBinding,
} from "./semevosql/contracts/projects.ts";
export type {
  QueryRun,
  RunEvent,
  RuntimeClarification,
  SemanticBindingScope,
  QueryApprovalMode,
  ProjectConversation,
  QueryExecutionExplanation,
  QueryTaskAnswer,
  QueryCorrectionOption,
  QueryDiagnosis,
  SemanticPreferenceUpgradePrompt,
  ProjectMessage,
  ResultArtifact,
} from "./semevosql/contracts/query.ts";
export type { OnboardingView } from "./semevosql/contracts/onboarding.ts";
export type {
  ProjectDocumentType,
  MaterialCategory,
  MaterialLifecycle,
  ProjectDocument,
  ProjectDocumentAttempt,
  ProjectDocumentProvenance,
} from "./semevosql/contracts/materials.ts";
export type {
  SemanticCatalogColumn,
  SemanticCatalogSnapshot,
  OfflineCatalogPreview,
  MultiSourcePolicySnapshot,
} from "./semevosql/contracts/catalog.ts";
export type {
  QueryCaseIndexReadiness,
  ValidatedQueryExample,
  QueryPattern,
  TrajectoryPath,
  DetourSignal,
  QueryPatternDetail,
  RuntimeOptimizationCandidate,
} from "./semevosql/contracts/learning.ts";
export type {
  SemanticPatchOperation,
  SemanticPatch,
  SemanticPatchValidationReport,
  SemanticReplayRun,
  SemanticEvolutionCandidate,
  SemanticVersionTimeline,
  CorpusRevision,
  SemanticChangeSet,
  SemanticChangeSetDetail,
  EpisodeDiagnosis,
  ProjectSemanticReadiness,
  ProjectDefinitionCandidate,
  ProjectDefinitionContributionEvidence,
} from "./semevosql/contracts/governance.ts";

/** Compatibility facade; requests and domain contracts live in dedicated modules. */
export const semEvoSQLService = {
  ...definitionsApi,
  ...projectsApi,
  ...learningApi,
  ...materialsApi,
  ...catalogApi,
  ...governanceApi,
  ...onboardingApi,
  ...queryApi,
} satisfies {
  createProjectVersion(
    projectId: number,
    payload: {
      versionNumber: string;
      creationMode: ProjectVersionCreationMode;
      parentVersionId?: number;
      source?: string;
    },
  ): Promise<ProjectInitializationView>;
  projectReleaseCenter(projectId: number): Promise<ProjectReleaseCenter>;
  episodeDiagnosis(episodeId: string): Promise<EpisodeDiagnosis>;
};
