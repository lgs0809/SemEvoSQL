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
package cn.lgs.semevosql.controller;

import cn.lgs.semevosql.exception.DatasourceConflictException;
import cn.lgs.semevosql.exception.DatasourceConnectionException;
import cn.lgs.semevosql.exception.DatasourceNotFoundException;
import cn.lgs.semevosql.exception.InternalServerException;
import cn.lgs.semevosql.exception.InvalidInputException;
import cn.lgs.semevosql.exception.LogicalRelationConflictException;
import cn.lgs.semevosql.exception.LogicalRelationNotFoundException;
import cn.lgs.semevosql.exception.ModelConfigConflictException;
import cn.lgs.semevosql.exception.ModelConfigNotFoundException;
import cn.lgs.semevosql.exception.ModelConnectionException;
import cn.lgs.semevosql.exception.SemEvoSQLException;
import cn.lgs.semevosql.clarification.RuntimeClarificationRequiredException;
import cn.lgs.semevosql.common.OptimisticLockingFailureException;
import cn.lgs.semevosql.concurrency.CapacityRejectedException;
import cn.lgs.semevosql.evolution.MultiSourcePolicyPatchService.InvalidMultiSourcePolicyPatchException;
import cn.lgs.semevosql.evolution.SemanticPatchValidator.SemanticPatchValidationException;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.project.domain.ProjectNotReadyException;
import cn.lgs.semevosql.run.RunInProgressException;
import cn.lgs.semevosql.vo.ApiErrorCode;
import cn.lgs.semevosql.vo.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/**
 * 全局异常处理器 (WebFlux 版本)
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

	private final SemEvoSQLMetrics metrics;

	@Autowired
	public GlobalExceptionHandler(SemEvoSQLMetrics metrics) {
		this.metrics = metrics;
	}

	public GlobalExceptionHandler() {
		this(SemEvoSQLMetrics.noop());
	}

	@ExceptionHandler(InvalidInputException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Object> handleInvalidInputException(InvalidInputException e) {
		log.warn("Invalid input: {}", e.getMessage());
		return ApiResponse.error(ApiErrorCode.INVALID_INPUT, e.getMessage(), e.getData());
	}

	@ExceptionHandler(ServerWebInputException.class)
	public ResponseEntity<ApiResponse<Object>> handleServerWebInput(ServerWebInputException e) {
		log.warn("Invalid HTTP request: {}", e.getReason());
		return ResponseEntity.badRequest().body(ApiResponse.error(ApiErrorCode.INVALID_REQUEST, "请求参数或请求体格式错误"));
	}

	@ExceptionHandler(ProjectNotReadyException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Object> handleProjectNotReady(ProjectNotReadyException e) {
		log.info("SemEvoSQL project is not ready: {}", e.getMessage());
		Map<String, Object> details = new LinkedHashMap<>();
		if (e.getProjectId() != null) {
			details.put("projectId", e.getProjectId());
		}
		if (e.getNextGapId() != null) {
			details.put("nextGapId", e.getNextGapId());
		}
		return ApiResponse.error(ApiErrorCode.PROJECT_NOT_READY, e.getMessage(), details);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ApiResponse<Object>> handleOptimisticLock(OptimisticLockingFailureException e) {
		Map<String, Object> details = Map.of("aggregateType", e.getAggregateType(), "aggregateId", e.getAggregateId(),
				"currentRevision", e.getCurrentRevision());
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, e.getMessage(), details));
	}

	@ExceptionHandler(CapacityRejectedException.class)
	public ResponseEntity<ApiResponse<Object>> handleCapacityRejected(CapacityRejectedException e) {
		metrics.capacityRejected(e.getScope(), e.getStatus().value());
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()));
		return new ResponseEntity<>(
				ApiResponse.error(ApiErrorCode.CAPACITY_REJECTED, e.getMessage(), Map.of("scope", e.getScope())),
				headers, e.getStatus());
	}

	@ExceptionHandler(RuntimeClarificationRequiredException.class)
	public ResponseEntity<ApiResponse<Object>> handleRuntimeClarificationRequired(
			RuntimeClarificationRequiredException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.CLARIFICATION_REQUIRED, e.getMessage(), Map.of("runId", e.getRunId(),
					"clarificationId", e.getClarificationId(), "status", "WAITING_HUMAN")));
	}

	@ExceptionHandler(RunInProgressException.class)
	public ResponseEntity<ApiResponse<Object>> handleRunInProgress(RunInProgressException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.RUN_IN_PROGRESS, e.getMessage(),
					Map.of("runId", e.getRunId(), "status", "RUNNING")));
	}

	@ExceptionHandler(SecurityException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ApiResponse<Object> handleSecurityException(SecurityException e) {
		log.warn("Forbidden SemEvoSQL operation: {}", e.getMessage());
		return ApiResponse.error(ApiErrorCode.FORBIDDEN, "当前操作没有足够权限。");
	}

	@ExceptionHandler(DatasourceNotFoundException.class)
	public ResponseEntity<ApiResponse<Object>> handleDatasourceNotFound(DatasourceNotFoundException e) {
		Map<String, Object> details = e.getDatasourceId() == null ? Map.of()
				: Map.of("datasourceId", e.getDatasourceId());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error(ApiErrorCode.DATASOURCE_NOT_FOUND, e.getMessage(), details));
	}

	@ExceptionHandler(DatasourceConflictException.class)
	public ResponseEntity<ApiResponse<Object>> handleDatasourceConflict(DatasourceConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.DATASOURCE_CONFLICT, e.getMessage()));
	}

	@ExceptionHandler(DatasourceConnectionException.class)
	public ResponseEntity<ApiResponse<Object>> handleDatasourceConnection(DatasourceConnectionException e) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
			.body(ApiResponse.error(ApiErrorCode.DATASOURCE_CONNECTION_FAILED, e.getMessage()));
	}

	@ExceptionHandler(LogicalRelationNotFoundException.class)
	public ResponseEntity<ApiResponse<Object>> handleLogicalRelationNotFound(LogicalRelationNotFoundException e) {
		Map<String, Object> details = e.getRelationId() == null ? Map.of() : Map.of("relationId", e.getRelationId());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error(ApiErrorCode.LOGICAL_RELATION_NOT_FOUND, e.getMessage(), details));
	}

	@ExceptionHandler(LogicalRelationConflictException.class)
	public ResponseEntity<ApiResponse<Object>> handleLogicalRelationConflict(LogicalRelationConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.LOGICAL_RELATION_CONFLICT, e.getMessage()));
	}

	@ExceptionHandler(ModelConfigNotFoundException.class)
	public ResponseEntity<ApiResponse<Object>> handleModelConfigNotFound(ModelConfigNotFoundException e) {
		Map<String, Object> details = e.getConfigId() == null ? Map.of() : Map.of("configId", e.getConfigId());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error(ApiErrorCode.MODEL_CONFIG_NOT_FOUND, e.getMessage(), details));
	}

	@ExceptionHandler(ModelConfigConflictException.class)
	public ResponseEntity<ApiResponse<Object>> handleModelConfigConflict(ModelConfigConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiErrorCode.MODEL_CONFIG_CONFLICT, e.getMessage()));
	}

	@ExceptionHandler(ModelConnectionException.class)
	public ResponseEntity<ApiResponse<Object>> handleModelConnection(ModelConnectionException e) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
			.body(ApiResponse.error(ApiErrorCode.MODEL_CONNECTION_FAILED, e.getMessage()));
	}

	@ExceptionHandler(SemEvoSQLException.class)
	public ResponseEntity<ApiResponse<Object>> handleSemEvoSQLException(SemEvoSQLException e) {
		HttpStatus status = switch (e.getErrorCode()) {
			case MODEL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case MODEL_OUTPUT_INVALID, QUERY_EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
			case SEMANTIC_CLARIFICATION_REQUIRED -> HttpStatus.CONFLICT;
			case SEMANTIC_PLANNING_REJECTED, QUERY_POLICY_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
		Map<String, Object> details = Map.of("retryable", e.isRetryable());
		if (status.is5xxServerError()) {
			log.error("SemEvoSQL runtime failure [{}]: {}", e.getErrorCode(), e.getPublicMessage(), e);
		}
		else {
			log.warn("SemEvoSQL request rejected [{}]: {}", e.getErrorCode(), e.getPublicMessage());
		}
		return ResponseEntity.status(status)
			.body(ApiResponse.error(e.getErrorCode(), e.getPublicMessage(), details));
	}

	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiResponse<Object> handleIllegalState(IllegalStateException e) {
		log.warn("SemEvoSQL state conflict: {}", e.getMessage());
		return ApiResponse.error(ApiErrorCode.INVALID_STATE, "当前操作无法在此状态下完成，请刷新后重试。");
	}

	@ExceptionHandler(SemanticPatchValidationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Object> handleSemanticPatchValidation(SemanticPatchValidationException e) {
		return ApiResponse.error(ApiErrorCode.SEMANTIC_PATCH_INVALID, e.getMessage(), e.report());
	}

	@ExceptionHandler(InvalidMultiSourcePolicyPatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Object> handleMultiSourcePolicyPatchValidation(InvalidMultiSourcePolicyPatchException e) {
		return ApiResponse.error(ApiErrorCode.MULTI_SOURCE_POLICY_INVALID, e.getMessage(), e.report());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Object> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("Invalid SemEvoSQL argument: {}", e.getMessage());
		return ApiResponse.error(ApiErrorCode.INVALID_ARGUMENT, "请求参数无效，请检查后重试。");
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Object>> handleResponseStatusException(ResponseStatusException e) {
		HttpStatusCode status = e.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("HTTP operation failed with status {}", status.value());
		}
		else {
			log.warn("HTTP operation rejected with status {}: {}", status.value(), e.getReason());
		}
		return ResponseEntity.status(status)
			.body(ApiResponse.error(errorCodeFor(status), responseStatusMessage(e, status)));
	}

	@ExceptionHandler(InternalServerException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Object> handleInternalServerException(InternalServerException e) {
		log.error("Internal server error: {}", e.getMessage(), e);
		return ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, "服务器内部错误");
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Object> handleGenericException(Exception e) {
		log.error("Unexpected error: {}", e.getMessage(), e);
		return ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, "服务器内部错误");
	}

	private ApiErrorCode errorCodeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 400, 405, 406, 415, 422 -> ApiErrorCode.INVALID_REQUEST;
			case 401 -> ApiErrorCode.UNAUTHORIZED;
			case 403 -> ApiErrorCode.FORBIDDEN;
			case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
			case 409 -> ApiErrorCode.CONFLICT;
			case 429 -> ApiErrorCode.CAPACITY_REJECTED;
			case 503 -> ApiErrorCode.SERVICE_UNAVAILABLE;
			default -> status.is4xxClientError() ? ApiErrorCode.INVALID_REQUEST : ApiErrorCode.INTERNAL_ERROR;
		};
	}

	private String responseStatusMessage(ResponseStatusException e, HttpStatusCode status) {
		if (status.is5xxServerError()) {
			return "服务器内部错误";
		}
		if (e.getReason() != null && !e.getReason().isBlank()) {
			return e.getReason();
		}
		HttpStatus resolved = HttpStatus.resolve(status.value());
		return resolved == null ? "请求处理失败" : resolved.getReasonPhrase();
	}

}
