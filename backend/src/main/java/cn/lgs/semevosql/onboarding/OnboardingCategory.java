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
package cn.lgs.semevosql.onboarding;

public enum OnboardingCategory {

	PROJECT_GOAL, SUPPORTED_QUERY_SCOPE, UNSUPPORTED_QUERY_SCOPE, DATASOURCE_SCOPE, MODEL_BUSINESS_NAME, MODEL_TYPE,
	MODEL_GRAIN, MODEL_UNIQUENESS, DEFAULT_TIME_COLUMN, TIME_SEMANTICS, TIMEZONE, METRIC_DEFINITION, METRIC_AGGREGATION,
	METRIC_FILTER, METRIC_DISTINCT_RULE, METRIC_ADDITIVITY, DIMENSION_DEFINITION, ENUM_MEANING, RELATIONSHIP_JOIN,
	RELATIONSHIP_CARDINALITY, FAN_OUT_POLICY, BUSINESS_FILTER_RULE, LOGICAL_DELETE_RULE, TEST_DATA_FILTER_RULE,
	QUERY_AMBIGUITY_POLICY, RUNTIME_CLARIFICATION_POLICY, SEMANTIC_GAP, GOLDEN_QUESTION, ACCEPTANCE_CRITERIA

}
