/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase } from "./http.ts";
import type {
  CreateProjectPayload,
  OperatorView,
  ProjectDatasourceBinding,
  ProjectHealth,
  ProjectHealthSummary,
  ProjectInitializationView,
  ProjectReleaseCenter,
  ProjectVersionCreationMode,
  SaveProjectDatasourceBindingPayload,
  SemanticProject,
  SemanticProjectVersion,
} from "./contracts/projects.ts";

export const projectsApi = {
  async currentOperator(): Promise<OperatorView> {
    return (await axios.get(`${apiBase}/operator-context`)).data;
  },
  async listProjects(signal?: AbortSignal): Promise<SemanticProject[]> {
    return (await axios.get(`${apiBase}/projects`, { signal })).data;
  },
  async project(projectId: number): Promise<ProjectInitializationView> {
    return (await axios.get(`${apiBase}/projects/${projectId}`)).data;
  },
  async projectHealth(
    projectId: number,
    signal?: AbortSignal,
  ): Promise<ProjectHealth> {
    return (
      await axios.get(`${apiBase}/projects/${projectId}/health`, { signal })
    ).data;
  },
  async projectHealthSummaries(
    signal?: AbortSignal,
  ): Promise<ProjectHealthSummary[]> {
    return (await axios.get(`${apiBase}/projects/health-summary`, { signal }))
      .data;
  },
  async projectReleaseCenter(projectId: number): Promise<ProjectReleaseCenter> {
    return (await axios.get(`${apiBase}/projects/${projectId}/release-center`))
      .data;
  },
  async createProject(
    payload: CreateProjectPayload,
  ): Promise<ProjectInitializationView> {
    return (await axios.post(`${apiBase}/projects`, payload)).data;
  },
  async projectVersions(projectId: number): Promise<SemanticProjectVersion[]> {
    return (await axios.get(`${apiBase}/projects/${projectId}/versions`)).data;
  },
  async projectDatasourceBindings(
    projectId: number,
    versionId: number,
  ): Promise<ProjectDatasourceBinding[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/datasources`,
      )
    ).data;
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
  async createProjectVersion(
    projectId: number,
    payload: {
      versionNumber: string;
      creationMode: ProjectVersionCreationMode;
      parentVersionId?: number;
      source?: string;
    },
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(`${apiBase}/projects/${projectId}/versions`, payload)
    ).data;
  },
  async initializeProjectVersion(
    projectId: number,
    versionId: number,
    initializationModelId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/initialize`,
        {
          initializationModelId,
        },
      )
    ).data;
  },
  async completeProjectAnalysis(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/analysis/complete`,
      )
    ).data;
  },
  async validateProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/validate`,
      )
    ).data;
  },
  async publishProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/publish`,
      )
    ).data;
  },
  async activateProjectVersion(
    projectId: number,
    versionId: number,
  ): Promise<ProjectInitializationView> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/activate`,
      )
    ).data;
  },
};
