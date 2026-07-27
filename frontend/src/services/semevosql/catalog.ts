/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { axios, apiBase } from "./http.ts";
import type {
  MultiSourcePolicySnapshot,
  OfflineCatalogPreview,
  SemanticCatalogSnapshot,
} from "./contracts/catalog.ts";

export const catalogApi = {
  async semanticCatalog(
    projectId: number,
    versionId: number,
  ): Promise<SemanticCatalogSnapshot> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/semantic-catalog`,
      )
    ).data;
  },
  async exportSourceSchema(
    projectId: number,
    versionId: number,
  ): Promise<Record<string, unknown>> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/offline-catalog/source-schema`,
      )
    ).data;
  },
  async previewOfflineCatalog(
    projectId: number,
    versionId: number,
    raw: string,
  ): Promise<OfflineCatalogPreview> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/offline-catalog/preview`,
        raw,
        { headers: { "Content-Type": "application/json" } },
      )
    ).data;
  },
  async commitOfflineCatalog(
    projectId: number,
    versionId: number,
    importId: string,
  ): Promise<{ status: string }> {
    return (
      await axios.post(
        `${apiBase}/projects/${projectId}/versions/${versionId}/offline-catalog/${importId}/commit`,
      )
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
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/multi-source-policy`,
      )
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
  async multiSourcePolicyViolations(
    projectId: number,
    versionId: number,
  ): Promise<string[]> {
    return (
      await axios.get(
        `${apiBase}/projects/${projectId}/versions/${versionId}/multi-source-policy/violations`,
      )
    ).data;
  },
};
