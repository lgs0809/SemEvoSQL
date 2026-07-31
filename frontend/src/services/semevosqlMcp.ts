/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import axios from 'axios';

const apiBase = '/api/semevosql';

export interface ProjectMcpDeployment {
  deploymentId: string;
  projectId: number;
  principalId: string;
  status: 'RUNNING' | 'DISABLED' | 'REVOKED';
  endpoint: string;
  createdBy: string;
  createTime: string;
  updateTime: string;
  lastUsedTime?: string;
  lastRecoveredTime?: string;
}

export interface ProjectMcpCredentialResponse {
  deployment: ProjectMcpDeployment;
  credential: string;
  config: {
    endpoint: string;
    header: string;
    headerValue: string;
    usage: string;
  };
}

interface ProjectMcpDeploymentCheck {
  ok: boolean;
  running: boolean;
  bindingCurrent: boolean;
  endpoint: string;
}

export interface ProjectMcpOperations {
  credentialExpiresAt?: string;
  totalQueries: number;
  failedQueries: number;
  pendingQueries: number;
  auditEvents: number;
  recentAudit: Array<{
    action: string;
    outcome: string;
    detail?: string;
    createTime: string;
  }>;
}

export const projectMcpService = {
  async get(projectId: number): Promise<ProjectMcpDeployment | null> {
    return (await axios.get(`${apiBase}/projects/${projectId}/mcp-deployment`)).data;
  },

  async deploy(projectId: number): Promise<ProjectMcpCredentialResponse> {
    return (await axios.post(`${apiBase}/projects/${projectId}/mcp-deployment/deploy`)).data;
  },

  async enable(projectId: number): Promise<ProjectMcpDeployment> {
    return (await axios.post(`${apiBase}/projects/${projectId}/mcp-deployment/enable`)).data;
  },

  async disable(projectId: number): Promise<ProjectMcpDeployment> {
    return (await axios.post(`${apiBase}/projects/${projectId}/mcp-deployment/disable`)).data;
  },

  async rotateCredential(projectId: number): Promise<ProjectMcpCredentialResponse> {
    return (await axios.post(`${apiBase}/projects/${projectId}/mcp-deployment/rotate-credential`))
      .data;
  },

  async check(projectId: number): Promise<ProjectMcpDeploymentCheck> {
    return (await axios.post(`${apiBase}/projects/${projectId}/mcp-deployment/test`)).data;
  },

  async operations(projectId: number): Promise<ProjectMcpOperations> {
    return (await axios.get(`${apiBase}/projects/${projectId}/mcp-deployment/operations`)).data;
  },

  async revoke(projectId: number): Promise<void> {
    await axios.delete(`${apiBase}/projects/${projectId}/mcp-deployment`);
  },
};
