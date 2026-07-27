/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export type ProjectDocumentType =
  | "DATA_DICTIONARY"
  | "METRIC_SPEC"
  | "GLOSSARY"
  | "REPORT_SPEC"
  | "HISTORICAL_SQL"
  | "SYSTEM_RESPONSIBILITY"
  | "SYNC_POLICY"
  | "ENUM_SPEC"
  | "REQUIREMENT";

export type MaterialCategory =
  | "DATABASE_SCHEMA"
  | "DATA_DICTIONARY"
  | "METRIC_DEFINITION"
  | "BACKEND_SOURCE"
  | "DATA_ACCESS_CODE"
  | "SQL_QUERY"
  | "DATABASE_MIGRATION"
  | "API_DOCUMENTATION"
  | "PRODUCT_REQUIREMENT"
  | "SYSTEM_DESIGN"
  | "BUSINESS_RULE"
  | "TEST_MATERIAL"
  | "REPORT_OR_BI"
  | "BUSINESS_GLOSSARY"
  | "OTHER";

export type MaterialLifecycle =
  "CURRENT" | "HISTORICAL" | "DEPRECATED" | "UNKNOWN";

export type SemanticMaterialType =
  "JSON" | "YAML" | "MARKDOWN" | "DDL" | "HISTORICAL_SQL";

export interface ProjectDocument {
  id: number;
  projectId: number;
  projectVersionId: number;
  documentType: ProjectDocumentType;
  materialCategory?: MaterialCategory;
  lifecycle?: MaterialLifecycle;
  materialType: SemanticMaterialType;
  sourceType: "INLINE" | "UPLOAD" | "CLONE" | "DATABASE_SCAN";
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
  status: "RECEIVED" | "PARSED" | "APPLIED" | "REVIEW_REQUIRED" | "FAILED";
  parseSummary?: string;
  errorMessage?: string;
  createTime: string;
  updateTime: string;
}

export interface ProjectDocumentAttempt {
  id: number;
  attemptNo: number;
  status: ProjectDocument["status"];
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
    | "MODEL"
    | "COLUMN"
    | "METRIC"
    | "DIMENSION"
    | "RELATIONSHIP"
    | "GRAIN"
    | "ENUM_VALUE"
    | "RULE";
  assetKey: string;
  assetFingerprint: string;
  disposition: "APPLIED" | "CONFLICT";
  conflictGapKey?: string;
  confidence: number;
  sourceLocation?: string;
  extractionModel?: string;
  evidence?: string;
  createTime: string;
}

export interface ProjectDocumentIngestionResult {
  material: ProjectDocument;
  status: ProjectDocument["status"];
  parseSummary?: string;
  createdGapCount: number;
  duplicate: boolean;
}

export interface ProjectBundleIngestionResult {
  archiveName: string;
  processedCount: number;
  duplicateCount: number;
  createdCount: number;
  entries: Array<{
    entryName: string;
    materialCategory: MaterialCategory;
    materialId?: number;
    status?: ProjectDocument["status"];
    duplicate: boolean;
  }>;
}
