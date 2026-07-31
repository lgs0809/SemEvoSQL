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
package cn.lgs.semevosql.vo;

/**
 * Stable machine-readable API error codes. Messages may be localized or revised, while
 * these identifiers remain suitable for client-side branching and operational alerts.
 */
public enum ApiErrorCode {

	INVALID_INPUT,

	INVALID_REQUEST,

	INVALID_ARGUMENT,

	PROJECT_NOT_READY,

	OPTIMISTIC_LOCK_CONFLICT,

	CAPACITY_REJECTED,

	CLARIFICATION_REQUIRED,

	RUN_IN_PROGRESS,

	FORBIDDEN,

	UNAUTHORIZED,

	INVALID_STATE,

	SEMANTIC_PATCH_INVALID,

	MULTI_SOURCE_POLICY_INVALID,

	MODEL_CONFIG_NOT_FOUND,

	MODEL_CONFIG_CONFLICT,

	MODEL_CONNECTION_FAILED,

	MODEL_OUTPUT_INVALID,

	MODEL_UNAVAILABLE,

	SEMANTIC_CLARIFICATION_REQUIRED,

	SEMANTIC_PLANNING_REJECTED,

	QUERY_POLICY_REJECTED,

	QUERY_EXECUTION_FAILED,

	DATASOURCE_NOT_FOUND,

	DATASOURCE_CONFLICT,

	DATASOURCE_CONNECTION_FAILED,

	LOGICAL_RELATION_NOT_FOUND,

	LOGICAL_RELATION_CONFLICT,

	RESOURCE_NOT_FOUND,

	CONFLICT,

	SERVICE_UNAVAILABLE,

	INTERNAL_ERROR

}
