/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export interface QueryCaseIndexReadiness {
  status: "INDEX_READY" | "PARTIAL" | "REINDEX_REQUIRED" | "LEXICAL_ONLY";
  approvedCaseCount: number;
  vectorCount: number;
  dimension?: number;
  detail: string;
}

export interface ValidatedQueryExample {
  id: string;
  project_id: number;
  project_version_id: number;
  catalog_hash: string;
  datasource_id?: number;
  episode_id: string;
  attempt_id: string;
  original_question?: string;
  normalized_question: string;
  intent_type?: string;
  conversation_independent: boolean | number;
  resolved_time_range_json?: string;
  typed_ir_json?: string;
  resolution_json?: string;
  quality_proof_json?: string;
  result_schema_hash?: string;
  canonical_shape_hash?: string;
  sql_text: string;
  run_id?: string;
  context_hash?: string;
  historical_sql_text?: string;
  status: "CANDIDATE" | "APPROVED" | "QUARANTINED" | "REJECTED" | "STALE";
  rebind_status:
    | "VALID"
    | "NEEDS_REBIND"
    | "REBOUND_PENDING_REPLAY"
    | "REBOUND"
    | "NEEDS_REVIEW"
    | "INVALID"
    | "SUPERSEDED";
  source_example_id?: string;
  quality_summary?: string;
  reviewed_by?: string;
  review_comment?: string;
  reviewed_time?: string;
  create_time: string;
  update_time: string;
  recall_count?: number;
  adopted_count?: number;
  failed_after_recall_count?: number;
  consecutive_recall_issue_count?: number;
  last_recalled_time?: string;
  derived_from_case_ids?: string;
  root_evidence_ids?: string;
  evidence_lineage_hash?: string;
  quarantine_reason?: string;
  quarantine_time?: string;
  assetReferences?: Array<Record<string, unknown>>;
  rebinds?: Array<Record<string, unknown>>;
}

export interface QueryPattern {
  id: string;
  project_id: number;
  project_version_id: number;
  catalog_hash: string;
  execution_compatibility_hash: string;
  shape_hash: string;
  instance_hash: string;
  intent_type: string;
  pattern_json: string;
  ambiguity_level: string;
  risk_level: string;
  episode_count: number;
  success_count: number;
  status: string;
  first_seen_time: string;
  last_seen_time: string;
}

export interface TrajectoryPath {
  id: string;
  episode_id: string;
  attempt_id: string;
  run_id?: string;
  pattern_id: string;
  path_signature: string;
  node_sequence_json: string;
  decision_sequence_json: string;
  source_sequence_json: string;
  status: string;
  correctness_score: number;
  safety_score: number;
  coverage_score: number;
  freshness_score: number;
  stability_score: number;
  latency_ms?: number;
  token_count?: number;
  retry_count: number;
  clarification_count: number;
  source_count: number;
  merge_count: number;
  cost_json: string;
  result_proof_json: string;
  create_time: string;
}

interface QueryPathProfile {
  id: string;
  pattern_id: string;
  path_signature: string;
  sample_count: number;
  success_count: number;
  correctness_rate: number;
  safety_rate: number;
  coverage_rate: number;
  freshness_rate: number;
  stability_rate: number;
  avg_latency_ms: number;
  avg_token_count: number;
  avg_retry_count: number;
  avg_clarification_count: number;
  dominated: boolean | number;
  pareto_rank: number;
  status: string;
}

export interface DetourSignal {
  id: string;
  pattern_id: string;
  path_id: string;
  signal_type: string;
  root_cause: string;
  confidence: number;
  occurrence_count: number;
  recurrence_rate: number;
  evidence_json: string;
  status: string;
  create_time: string;
}

export interface QueryPatternDetail extends QueryPattern {
  profiles: QueryPathProfile[];
  detours: DetourSignal[];
}

export interface RuntimeOptimizationCandidate {
  id: string;
  project_id: number;
  project_version_id: number;
  pattern_id: string;
  execution_compatibility_hash: string;
  optimization_type: string;
  status: string;
  applicability_json: string;
  proposal_json: string;
  baseline_metrics_json: string;
  shadow_metrics_json?: string;
  confidence: number;
  risk_level: string;
  reviewed_by?: string;
  review_comment?: string;
  reviewed_time?: string;
  create_time: string;
  update_time: string;
  gatePassed?: boolean;
  gateReasons?: string[];
  costReduction?: number;
  preferredPlans?: Array<Record<string, unknown>>;
}
