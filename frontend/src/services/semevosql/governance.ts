/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase, governedMutationHeaders } from "./http.ts";
import type {
  CorpusRevision,
  EpisodeDiagnosis,
  MultiSourcePolicyPatch,
  ProjectSemanticReadiness,
  SemanticChangeSet,
  SemanticChangeSetDetail,
  SemanticEvolutionCandidate,
  SemanticPatch,
  SemanticPatchValidationReport,
  SemanticReplayRun,
  SemanticVersionTimeline,
} from "./contracts/governance.ts";
import type { RunEvent } from "./contracts/query.ts";

export const governanceApi = {
  async semanticEvolutionCandidates(
    projectId: number,
    status?: string,
    limit = 200,
  ): Promise<SemanticEvolutionCandidate[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/semantic-evolution/candidates`,
        {
          params: { status: status || undefined, limit },
        },
      )
    ).data;
  },
  async semanticEvolutionCandidate(
    candidateId: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.get(`${apiBase}/semantic-evolution/candidates/${candidateId}`)
    ).data;
  },
  async updateSemanticEvolutionPatch(
    candidateId: string,
    patch: SemanticPatch,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/patch`,
        patch,
        {
          headers: governedMutationHeaders(
            `semantic-patch-edit:${candidateId}`,
          ),
        },
      )
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
          headers: governedMutationHeaders(
            `multi-source-policy-patch-edit:${candidateId}`,
          ),
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
  async replaySemanticEvolution(
    candidateId: string,
  ): Promise<SemanticReplayRun> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/replay`,
        undefined,
        { headers: governedMutationHeaders(`semantic-replay:${candidateId}`) },
      )
    ).data;
  },
  async semanticEvolutionReplayRun(
    replayRunId: string,
  ): Promise<SemanticReplayRun> {
    return (
      await axios.get(
        `${apiBase}/semantic-evolution/replay-runs/${replayRunId}`,
      )
    ).data;
  },
  async semanticEvolutionReplayEvents(
    replayRunId: string,
    afterSequence = 0,
  ): Promise<RunEvent[]> {
    return (
      await axios.get(
        `${apiBase}/semantic-evolution/replay-runs/${replayRunId}/events`,
        {
          params: { afterSequence, limit: 200 },
        },
      )
    ).data;
  },
  async cancelSemanticEvolutionReplay(
    replayRunId: string,
  ): Promise<SemanticReplayRun> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/replay-runs/${replayRunId}/cancel`,
        undefined,
        {
          headers: governedMutationHeaders(
            `semantic-replay-cancel:${replayRunId}`,
          ),
        },
      )
    ).data;
  },
  async semanticEvolutionReplayResults(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (
      await axios.get(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/replay-results`,
      )
    ).data;
  },
  async semanticEvolutionAttestations(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (
      await axios.get(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/attestations`,
      )
    ).data;
  },
  async semanticEvolutionReleaseDecisions(
    candidateId: string,
  ): Promise<Array<Record<string, unknown>>> {
    return (
      await axios.get(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/release-decisions`,
      )
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
          headers: governedMutationHeaders(
            `semantic-manual-replay:${candidateId}`,
          ),
        },
      )
    ).data;
  },
  async readySemanticEvolution(
    candidateId: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/ready`,
        undefined,
        {
          headers: governedMutationHeaders(`semantic-ready:${candidateId}`),
        },
      )
    ).data;
  },
  async acknowledgeSemanticEvolutionPublished(
    candidateId: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/published`,
      )
    ).data;
  },
  async staleSemanticEvolution(
    candidateId: string,
    reason: string,
  ): Promise<SemanticEvolutionCandidate> {
    return (
      await axios.post(
        `${apiBase}/semantic-evolution/candidates/${candidateId}/stale`,
        undefined,
        {
          params: { reason },
          headers: governedMutationHeaders(`semantic-stale:${candidateId}`),
        },
      )
    ).data;
  },
  async semanticVersionTimeline(
    projectId: number,
  ): Promise<SemanticVersionTimeline> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/semantic-versions/timeline`,
      )
    ).data;
  },
  async corpusRevisions(projectId: number): Promise<CorpusRevision[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/corpus-revisions`)
    ).data;
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
  async semanticChangeSet(
    changeSetId: string,
  ): Promise<SemanticChangeSetDetail> {
    return (await axios.get(`${apiBase}/semantic-change-sets/${changeSetId}`))
      .data;
  },
  async episodeDiagnosis(episodeId: string): Promise<EpisodeDiagnosis> {
    return (await axios.get(`${apiBase}/episodes/${episodeId}/diagnosis`)).data;
  },
  async semanticReadiness(
    projectId: number,
  ): Promise<ProjectSemanticReadiness> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/semantic-readiness`)
    ).data;
  },
  async promoteSemanticChangeSet(
    changeSetId: string,
    reason?: string,
  ): Promise<Record<string, unknown>> {
    return (
      await axios.post(
        `${apiBase}/semantic-change-sets/${changeSetId}/promote`,
        { reason: reason?.trim() || undefined },
        {
          headers: governedMutationHeaders(
            `semantic-major-promote:${changeSetId}`,
          ),
        },
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
        {
          headers: governedMutationHeaders(
            `semantic-version-rollback:${versionId}`,
          ),
        },
      )
    ).data;
  },
};
