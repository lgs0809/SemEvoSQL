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
package cn.lgs.semevosql.constant;

/**
 */
public final class Constant {

	private Constant() {

	}

	public static final String PROJECT_PROPERTIES_PREFIX = "semevosql";

	public static final String INPUT_KEY = "input";

	public static final String AGENT_ID = "agentId";

	public static final String PROJECT_ID = "projectId";

	public static final String PROJECT_VERSION_ID = "projectVersionId";

	public static final String CATALOG_HASH = "catalogHash";

	public static final String EPISODE_ID = "episodeId";

	public static final String ATTEMPT_ID = "attemptId";

	public static final String RUN_ID = "runId";

	/** Absolute wall-clock deadline for the currently bound durable execution attempt. */
	public static final String RUN_DEADLINE_EPOCH_MILLIS = "runDeadlineEpochMillis";

	public static final String DATASOURCE_ID = "datasourceId";

	public static final String FORCED_DATASOURCE_ID = "forcedDatasourceId";

	public static final String FORCED_PHYSICAL_TABLES = "forcedPhysicalTables";

	public static final String MULTI_TURN_CONTEXT = "MULTI_TURN_CONTEXT";

	public static final String CONVERSATION_CONTEXT_ENVELOPE = "CONVERSATION_CONTEXT_ENVELOPE";

	public static final String RESULT = "result";

	// Optional request-level Todo orchestration. Absent/false on the simple-query fast path.
	public static final String ORIGINAL_REQUEST = "ORIGINAL_REQUEST";

	public static final String REQUEST_ANALYSIS = "REQUEST_ANALYSIS";

	public static final String TODO_ENABLED = "TODO_ENABLED";

	public static final String ACTIVE_TODO_ID = "ACTIVE_TODO_ID";

	public static final String ACTIVE_QUERY = "ACTIVE_QUERY";

	public static final String PRINCIPAL_ID = "PRINCIPAL_ID";

	public static final String REQUEST_SEMANTIC_BINDINGS = "REQUEST_SEMANTIC_BINDINGS";

	public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";

	public static final String TODO_BOUNDARY_DECISION = "TODO_BOUNDARY_DECISION";

	public static final String SEMANTIC_EXECUTION_DECISION = "SEMANTIC_EXECUTION_DECISION";

	public static final String ADVANCED_EXECUTION_FALLBACK = "ADVANCED_EXECUTION_FALLBACK";

	public static final String NL2SQL_GRAPH_NAME = "nl2sqlGraph";


	public static final String QUERY_ENHANCE_NODE_OUTPUT = "QUERY_ENHANCE_NODE_OUTPUT";

	public static final String FEASIBILITY_ASSESSMENT_NODE_OUTPUT = "FEASIBILITY_ASSESSMENT_NODE_OUTPUT";

	public static final String EVIDENCE = "EVIDENCE";

	public static final String TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT = "TABLE_DOCUMENTS_FOR_SCHEMA";

	public static final String SCHEMA_RECALL_NODE_OUTPUT = "SCHEMA_RECALL_NODE_OUTPUT";

	public static final String COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT = "COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT";

	public static final String TABLE_RELATION_OUTPUT = "TABLE_RELATION_OUTPUT";

	public static final String TABLE_RELATION_EXCEPTION_OUTPUT = "TABLE_RELATION_EXCEPTION_OUTPUT";

	public static final String TABLE_RELATION_RETRY_COUNT = "TABLE_RELATION_RETRY_COUNT";

	public static final String GENEGRATED_SEMANTIC_MODEL_PROMPT = "GENEGRATED_SEMANTIC_MODEL_PROMPT";

	public static final String TYPED_SEMANTIC_PLAN = "TYPED_SEMANTIC_PLAN";

	public static final String QUERY_CASE_HINTS = "QUERY_CASE_HINTS";

	public static final String QUERY_PATTERN_ID = "QUERY_PATTERN_ID";

	public static final String PREFERRED_EXECUTION_PLAN = "PREFERRED_EXECUTION_PLAN";

	public static final String SQL_GENERATE_OUTPUT = "SQL_GENERATE_OUTPUT";

	/** Physical SQL produced by Query Preflight; empty for deterministic compiler paths. */
	public static final String SQL_PHYSICAL_OUTPUT = "SQL_PHYSICAL_OUTPUT";

	/** Structured, non-database Query Preflight diagnostics for traces and recovery. */
	public static final String SQL_DRY_PLAN_OUTPUT = "SQL_DRY_PLAN_OUTPUT";

	public static final String SQL_COMPILED_PARAMETERS = "SQL_COMPILED_PARAMETERS";

	public static final String SQL_COMPILER_MODE = "SQL_COMPILER_MODE";

	public static final String SQL_PATTERN_TEMPLATE_ID = "SQL_PATTERN_TEMPLATE_ID";

	public static final String SQL_GENERATE_SCHEMA_MISSING_ADVICE = "SQL_GENERATE_SCHEMA_MISSING_ADVICE";

	public static final String SQL_GENERATE_COUNT = "SQL_GENERATE_COUNT";

	// 重新生成SQL的原因
	public static final String SQL_REGENERATE_REASON = "SQL_REGENERATE_REASON";

	public static final String SEMANTIC_CONSISTENCY_NODE_OUTPUT = "SEMANTIC_CONSISTENCY_NODE_OUTPUT";

	public static final String PLANNER_NODE_OUTPUT = "PLANNER_NODE_OUTPUT";

	/** Exact persisted planner output used only for durable approval recovery. */
	public static final String RECOVERED_PLANNER_OUTPUT = "RECOVERED_PLANNER_OUTPUT";

	public static final String APPROVED_PLAN_RECOVERY = "APPROVED_PLAN_RECOVERY";

	public static final String SQL_EXECUTE_NODE_OUTPUT = "SQL_EXECUTE_NODE_OUTPUT";

	public static final String SQL_EXECUTED_QUERY_OUTPUT = "SQL_EXECUTED_QUERY_OUTPUT";

	public static final String POST_EXECUTION_REVIEW_OUTPUT = "POST_EXECUTION_REVIEW_OUTPUT";

	public static final String QUERY_REPAIR_BUDGET = "QUERY_REPAIR_BUDGET";

	public static final String RETRIEVAL_REPAIR_QUERY = "RETRIEVAL_REPAIR_QUERY";

	public static final String RETRIEVAL_REPAIR_HINT = "RETRIEVAL_REPAIR_HINT";

	public static final String SQL_RESULT_MEMORY_BY_STEP = "SQL_RESULT_MEMORY_BY_STEP";

	public static final String FORCE_SEMANTIC_REPLAN = "FORCE_SEMANTIC_REPLAN";

	public static final String SEMANTIC_REPLAN_FEEDBACK = "SEMANTIC_REPLAN_FEEDBACK";

	public static final String LAST_SQL_EXECUTED_STEP = "LAST_SQL_EXECUTED_STEP";

	public static final String LAST_SQL_RESULT_PAYLOAD = "LAST_SQL_RESULT_PAYLOAD";

	// dialect
	public static final String DB_DIALECT_TYPE = "DB_DIALECT_TYPE";

	// Plan当前需要执行的步骤编号
	public static final String PLAN_CURRENT_STEP = "PLAN_CURRENT_STEP";

	// Plan下一个需要进入的节点
	public static final String PLAN_NEXT_NODE = "PLAN_NEXT_NODE";

	// Plan validation
	public static final String PLAN_VALIDATION_STATUS = "PLAN_VALIDATION_STATUS";

	public static final String PLAN_VALIDATION_ERROR = "PLAN_VALIDATION_ERROR";

	public static final String PLAN_PARSED_OBJECT = "PLAN_PARSED_OBJECT";

	public static final String PLAN_PARSED_OUTPUT_HASH = "PLAN_PARSED_OUTPUT_HASH";

	public static final String PLAN_VALIDATED_OUTPUT_HASH = "PLAN_VALIDATED_OUTPUT_HASH";

	public static final String PLAN_REPAIR_COUNT = "PLAN_REPAIR_COUNT";

	// Node KEY
	public static final String REQUEST_ANALYSIS_NODE = "REQUEST_ANALYSIS_NODE";

	public static final String SEMANTIC_EXECUTION_NODE = "SEMANTIC_EXECUTION_NODE";

	public static final String TODO_BOUNDARY_NODE = "TODO_BOUNDARY_NODE";

	public static final String REQUEST_SYNTHESIS_NODE = "REQUEST_SYNTHESIS_NODE";

	public static final String PLANNER_NODE = "PLANNER_NODE";

	public static final String PLAN_EXECUTOR_NODE = "PLAN_EXECUTOR_NODE";



	public static final String QUERY_ENHANCE_NODE = "QUERY_ENHANCE_NODE";

	public static final String FEASIBILITY_ASSESSMENT_NODE = "FEASIBILITY_ASSESSMENT_NODE";

	public static final String REPORT_GENERATOR_NODE = "REPORT_GENERATOR_NODE";

	public static final String SCHEMA_RECALL_NODE = "SCHEMA_RECALL_NODE";

	public static final String TABLE_RELATION_NODE = "TABLE_RELATION_NODE";

	public static final String SEMANTIC_PLAN_NODE = "SEMANTIC_PLAN_NODE";

	public static final String SQL_GENERATE_NODE = "SQL_GENERATE_NODE";

	public static final String SQL_EXECUTE_NODE = "SQL_EXECUTE_NODE";

	public static final String POST_EXECUTION_REVIEW_NODE = "POST_EXECUTION_REVIEW_NODE";

	public static final String SEMANTIC_CONSISTENCY_NODE = "SEMANTIC_CONSISTENCY_NODE";

	public static final String HUMAN_FEEDBACK_NODE = "HUMAN_FEEDBACK_NODE";

	// Keys related to Python code execution
	public static final String PYTHON_GENERATE_NODE = "PYTHON_GENERATE_NODE";

	public static final String PYTHON_EXECUTE_NODE = "PYTHON_EXECUTE_NODE";

	public static final String PYTHON_ANALYZE_NODE = "PYTHON_ANALYZE_NODE";

	public static final String SQL_RESULT_LIST_MEMORY = "SQL_RESULT_LIST_MEMORY";

	public static final String PYTHON_IS_SUCCESS = "PYTHON_IS_SUCCESS";

	public static final String PYTHON_TRIES_COUNT = "PYTHON_TRIES_COUNT";

	// 标记是否进入Python执行失败的降级模式（超过最大重试次数后触发）
	public static final String PYTHON_FALLBACK_MODE = "PYTHON_FALLBACK_MODE";

	// If code execution succeeds, output code running result; if fails, output error
	// information
	public static final String PYTHON_EXECUTE_NODE_OUTPUT = "PYTHON_EXECUTE_NODE_OUTPUT";

	public static final String PYTHON_GENERATE_NODE_OUTPUT = "PYTHON_GENERATE_NODE_OUTPUT";

	public static final String PYTHON_ANALYSIS_NODE_OUTPUT = "PYTHON_ANALYSIS_NODE_OUTPUT";

	// Internal source-level SQL generation mode used by the multi-source coordinator.
	public static final String SQL_GENERATION_ONLY = "SQL_GENERATION_ONLY";

	// 人类复核相关
	public static final String HUMAN_REVIEW_ENABLED = "HUMAN_REVIEW_ENABLED";

	// Human feedback data payload
	public static final String HUMAN_FEEDBACK_DATA = "HUMAN_FEEDBACK_DATA";

	// StreamEvent 常量
	public static final String STREAM_EVENT_COMPLETE = "complete";

	public static final String STREAM_EVENT_ERROR = "error";

	public static final String STREAM_EVENT_RUN_ESTABLISHED = "run-established";

	// Langfuse 追踪：threadId 透传到 graph state，用于 token 累计
	public static final String TRACE_THREAD_ID = "TRACE_THREAD_ID";

}
