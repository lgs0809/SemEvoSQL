/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

interface SemanticCatalogModel extends Record<string, unknown> {
  id?: number;
  datasourceId?: number;
  modelCode?: string;
  physicalTable?: string;
  businessName?: string;
  modelType?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

export interface SemanticCatalogColumn extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  columnName?: string;
  businessName?: string;
  dataType?: string;
  role?: string;
  expression?: string;
  synonyms?: string;
  description?: string;
  sensitivityLevel?: string;
  maskingPolicy?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogMetric extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  metricCode?: string;
  businessName?: string;
  expression?: string;
  aggregation?: string;
  unit?: string;
  timeColumn?: string;
  filterExpression?: string;
  additiveType?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogDimension extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  dimensionCode?: string;
  businessName?: string;
  columnName?: string;
  expression?: string;
  dimensionType?: string;
  hierarchy?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogRelationship extends Record<string, unknown> {
  id?: number;
  relationshipCode?: string;
  sourceModelCode?: string;
  targetModelCode?: string;
  cardinality?: string;
  joinType?: string;
  joinCondition?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogGrain extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  grainCode?: string;
  keyColumns?: string;
  timeColumn?: string;
  uniquenessRule?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogEnumValue extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  columnName?: string;
  valueCode?: string;
  businessName?: string;
  aliases?: string;
  description?: string;
  sortOrder?: number;
  evidence?: string;
  status?: string;
}

interface SemanticCatalogRule extends Record<string, unknown> {
  id?: number;
  modelCode?: string;
  ruleCode?: string;
  ruleType?: string;
  businessName?: string;
  expression?: string;
  severity?: string;
  description?: string;
  evidence?: string;
  status?: string;
}

export interface SemanticCatalogSnapshot {
  projectId: number;
  projectVersionId: number;
  models: SemanticCatalogModel[];
  columns: SemanticCatalogColumn[];
  metrics: SemanticCatalogMetric[];
  dimensions: SemanticCatalogDimension[];
  relationships: SemanticCatalogRelationship[];
  grains: SemanticCatalogGrain[];
  enumValues: SemanticCatalogEnumValue[];
  rules: SemanticCatalogRule[];
}

export interface OfflineCatalogPreview {
  importId: string;
  status: string;
  inputHash: string;
  sourceFingerprint: string;
  baselineCatalogHash: string;
  baselineVersionRevision: number;
  plannedCatalog: SemanticCatalogSnapshot;
  unresolvedIssues: Array<{
    target: string;
    question: string;
    blocking: boolean;
  }>;
}

export interface MultiSourcePolicySnapshot {
  projectId?: number;
  projectVersionId?: number;
  logicalBindings: Array<Record<string, unknown>>;
  authorityRules: Array<Record<string, unknown>>;
  freshnessPolicies: Array<Record<string, unknown>>;
  crossSourceRelationships: Array<Record<string, unknown>>;
  mergePolicies: Array<Record<string, unknown>>;
}
