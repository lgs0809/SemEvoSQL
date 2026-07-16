/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.semantic.domain;

/**
 * Stable, typed root-cause vocabulary shared by runtime clarification, SQL validation,
 * trajectory aggregation and semantic evolution. Values describe the defect or ambiguity,
 * not the transport-level exception that exposed it.
 */
public enum SemanticIssueType {

	TERM_ALIAS_MISSING, ENUM_MAPPING_MISSING, ENUM_MAPPING_AMBIGUOUS, METRIC_MISSING, METRIC_AMBIGUOUS,
	METRIC_FORMULA_INCORRECT, METRIC_TIME_COLUMN_INCORRECT, METRIC_FILTER_INCOMPLETE, DIMENSION_MISSING,
	DIMENSION_AMBIGUOUS, RELATIONSHIP_MISSING, RELATIONSHIP_INCORRECT, CARDINALITY_INCORRECT, JOIN_CONDITION_INCORRECT,
	GRAIN_MISSING, GRAIN_INCORRECT, TIME_SEMANTICS_MISSING, TIME_SEMANTICS_AMBIGUOUS, PLANNING_POLICY_GAP,
	DATASOURCE_AUTHORITY_INCORRECT, MULTI_SOURCE_POLICY_INCORRECT, SCHEMA_DRIFT, PLANNER_DEFECT, SQL_COMPILER_DEFECT,
	LLM_SQL_GENERATION_DEFECT,
	USER_QUESTION_AMBIGUOUS, OUT_OF_SCOPE, PERMISSION_DENIED, UNKNOWN

}
