/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import {
  axios,
  apiBase,
  operationsBase,
  governedMutationHeaders,
} from "./http.ts";
import type {
  DetourSignal,
  QueryCaseIndexReadiness,
  QueryPattern,
  QueryPatternDetail,
  RuntimeOptimizationCandidate,
  TrajectoryPath,
  ValidatedQueryExample,
} from "./contracts/learning.ts";
import type { SemEvoSQLDashboard } from "./contracts/projects.ts";

export const learningApi = {
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
  async queryExample(
    projectId: number,
    exampleId: string,
  ): Promise<ValidatedQueryExample> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/query-examples/${exampleId}`,
      )
    ).data;
  },
  async queryCaseIndexReadiness(
    projectId: number,
  ): Promise<QueryCaseIndexReadiness> {
    return (
      await axios.get(
        `${apiBase}/operations/projects/${projectId}/query-case-index`,
      )
    ).data;
  },
  async reindexQueryCaseIndex(
    projectId: number,
  ): Promise<{ indexedEmbeddings: number; projectId: number }> {
    return (
      await axios.post(
        `${apiBase}/operations/query-case-index/reindex`,
        undefined,
        {
          params: { projectId },
          headers: governedMutationHeaders(
            `query-case-index-reindex:${projectId}`,
          ),
        },
      )
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
  async dashboard(projectId: number): Promise<SemEvoSQLDashboard> {
    return (
      await axios.get(`${operationsBase}/projects/${projectId}/dashboard`)
    ).data;
  },
  async episodes(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/episodes`))
      .data;
  },
  async jobs(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/jobs`))
      .data;
  },
  async releases(projectId: number) {
    return (await axios.get(`${operationsBase}/projects/${projectId}/releases`))
      .data;
  },
  async goldenCases(projectId: number) {
    return (
      await axios.get(`${operationsBase}/projects/${projectId}/golden-cases`)
    ).data;
  },
  async createReplay(
    projectId: number,
    versionId: number,
    idempotencyKey = `ui-replay-${versionId}-${crypto.randomUUID()}`,
  ) {
    return (
      await axios.post(`${operationsBase}/projects/${projectId}/jobs`, {
        projectVersionId: versionId,
        jobType: "REPLAY",
        idempotencyKey,
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
    return (await axios.get(`${apiBase}/trajectory/patterns/${patternId}`))
      .data;
  },
  async trajectoryPaths(
    patternId: string,
    limit = 100,
  ): Promise<TrajectoryPath[]> {
    return (
      await axios.get(`${apiBase}/trajectory/patterns/${patternId}/paths`, {
        params: { limit },
      })
    ).data;
  },
  async recomputeTrajectoryPattern(
    patternId: string,
  ): Promise<QueryPatternDetail> {
    return (
      await axios.post(`${apiBase}/trajectory/patterns/${patternId}/recompute`)
    ).data;
  },
  async detourSignals(
    projectId: number,
    status?: string,
    limit = 200,
  ): Promise<DetourSignal[]> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/trajectory/detours`, {
        params: { status: status || undefined, limit },
      })
    ).data;
  },
  async runtimeOptimizationCandidates(
    projectId: number,
    status?: string,
    limit = 200,
  ): Promise<RuntimeOptimizationCandidate[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/runtime-optimization/candidates`,
        {
          params: { status: status || undefined, limit },
        },
      )
    ).data;
  },
  async runtimeOptimizationCandidate(
    candidateId: string,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.get(
        `${apiBase}/runtime-optimization/candidates/${candidateId}`,
      )
    ).data;
  },
  async recordOptimizationShadow(
    candidateId: string,
    metrics: Record<string, number>,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(
        `${apiBase}/runtime-optimization/candidates/${candidateId}/shadow`,
        {
          metrics,
        },
      )
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
        {
          headers: governedMutationHeaders(
            `runtime-optimization-approve:${candidateId}`,
          ),
        },
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
        {
          headers: governedMutationHeaders(
            `runtime-optimization-reject:${candidateId}`,
          ),
        },
      )
    ).data;
  },
  async enableRuntimeOptimization(
    candidateId: string,
  ): Promise<RuntimeOptimizationCandidate> {
    return (
      await axios.post(
        `${apiBase}/runtime-optimization/candidates/${candidateId}/enable`,
      )
    ).data;
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
};
