/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase } from "./http.ts";
import type {
  ProjectDefinitionCandidate,
  ProjectDefinitionContributionEvidence,
} from "./contracts/governance.ts";

export const definitionsApi = {
  async decideProjectDefinition(
    projectId: number,
    candidateId: number,
    request: {
      action:
        | "EARLY_CREATE"
        | "RENAME"
        | "OVERWRITE"
        | "ASSOCIATE"
        | "REJECT"
        | "DEFER"
        | "RESUME";
      reason: string;
      contentRevision: number;
      evidenceRevision: number;
      baseVersion: number;
      catalogHash: string;
      contributionFingerprint: string;
      representationHash?: string;
      targetAsset?: string;
      publicName?: string;
    },
    idempotencyKey: string,
  ): Promise<{ id: number; action: string; reason: string }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/definition-candidates/${candidateId}/decisions`,
        request,
        { headers: { "Idempotency-Key": idempotencyKey } },
      )
    ).data;
  },
  async projectDefinitionCandidates(
    projectId: number,
  ): Promise<ProjectDefinitionCandidate[]> {
    return (
      await axios.get<ProjectDefinitionCandidate[]>(
        `${apiBase}/projects/${projectId}/definition-candidates`,
      )
    ).data;
  },
  async checkProjectDefinitionPublication(
    projectId: number,
    candidateId: number,
    request: {
      publicationId: number;
      contentRevision: number;
      representationHash: string;
    },
  ): Promise<{
    status: "NOT_READY" | "QUEUED" | "ALREADY_QUEUED";
    totalModels: number;
    readyModels: number;
  }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/definition-candidates/${candidateId}/publication-check`,
        request,
      )
    ).data;
  },
  async projectDefinitionContributions(
    projectId: number,
    candidateId: number,
    offset = 0,
  ): Promise<ProjectDefinitionContributionEvidence> {
    return (
      await axios.get<ProjectDefinitionContributionEvidence>(
        `${apiBase}/projects/${projectId}/definition-candidates/${candidateId}/contributions`,
        { params: { offset, limit: 50 } },
      )
    ).data;
  },
};
