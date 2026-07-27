/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase } from "./http.ts";
import type {
  MaterialCategory,
  MaterialLifecycle,
  ProjectBundleIngestionResult,
  ProjectDocument,
  ProjectDocumentAttempt,
  ProjectDocumentIngestionResult,
  ProjectDocumentProvenance,
  ProjectDocumentType,
  SemanticMaterialType,
} from "./contracts/materials.ts";

export const materialsApi = {
  async projectDocuments(
    projectId: number,
    versionId: number,
  ): Promise<ProjectDocument[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents`,
      )
    ).data;
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
    formData.append("documentType", payload.documentType);
    if (payload.materialCategory)
      formData.append("materialCategory", payload.materialCategory);
    if (payload.lifecycle) formData.append("lifecycle", payload.lifecycle);
    if (payload.materialType)
      formData.append("materialType", payload.materialType);
    if (payload.datasourceId != null)
      formData.append("datasourceId", String(payload.datasourceId));
    if (payload.sourceName?.trim())
      formData.append("sourceName", payload.sourceName.trim());
    if (payload.sourceLocation?.trim())
      formData.append("sourceLocation", payload.sourceLocation.trim());
    formData.append("file", payload.file);
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/documents`,
        formData,
      )
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
    if (payload.materialCategory)
      formData.append("materialCategory", payload.materialCategory);
    if (payload.lifecycle) formData.append("lifecycle", payload.lifecycle);
    if (payload.datasourceId != null)
      formData.append("datasourceId", String(payload.datasourceId));
    if (payload.sourceName?.trim())
      formData.append("sourceName", payload.sourceName.trim());
    if (payload.sourceLocation?.trim())
      formData.append("sourceLocation", payload.sourceLocation.trim());
    formData.append("file", payload.file);
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
};
