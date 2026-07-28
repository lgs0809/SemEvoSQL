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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Fail-closed isolation and governed recovery of unreliable Query Cases. */
@Service
public class QueryCaseQuarantineService {

	private final JdbcTemplate jdbc;

	private final QueryCaseRepository repository;

	private final QueryCaseLineageService events;

	private final QueryCaseGovernanceProperties properties;

	private final LocalOperatorService authorization;

	public QueryCaseQuarantineService(JdbcTemplate jdbc, QueryCaseRepository repository, QueryCaseLineageService events,
			QueryCaseGovernanceProperties properties, LocalOperatorService authorization) {
		this.jdbc = jdbc;
		this.repository = repository;
		this.events = events;
		this.properties = properties;
		this.authorization = authorization;
	}

	@Transactional
	public Optional<QueryCaseSummary> evaluate(String queryCaseId) {
		Map<String, Object> current = repository.require(queryCaseId);
		if (!"APPROVED".equals(Objects.toString(current.get("status")))) {
			return Optional.empty();
		}
		long recalls = longValue(current.get("recall_count"));
		long failures = longValue(current.get("failed_after_recall_count"));
		long consecutiveIssues = longValue(current.get("consecutive_recall_issue_count"));
		double failureRate = recalls == 0 ? 0 : (double) failures / recalls;
		Map<String, Object> triggers = new LinkedHashMap<>();
		if (failures >= properties.getQuarantineFailedCount()) {
			triggers.put("failedAfterRecallCount", failures);
		}
		if (recalls >= properties.getQuarantineFailureRateMinRecalls()
				&& failureRate >= properties.getQuarantineFailureRate()) {
			triggers.put("failureRate", failureRate);
		}
		if (consecutiveIssues >= properties.getQuarantineConsecutiveIssueCount()) {
			triggers.put("consecutiveRecallIssueCount", consecutiveIssues);
		}
		if (triggers.isEmpty()) {
			return Optional.empty();
		}
		String reason = "Automatic quarantine: " + triggers;
		int updated = jdbc.update("""
				UPDATE qw_query_example
				SET status = 'QUARANTINED', quarantine_reason = ?, quarantine_time = CURRENT_TIMESTAMP,
				    update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'APPROVED'
				""", reason, queryCaseId);
		if (updated != 1) {
			return Optional.empty();
		}
		events.appendEvent(queryCaseId, "QUERY_CASE_AUTO_QUARANTINED", "APPROVED", "QUARANTINED", "semevosql-system",
				"SYSTEM", Map.of("triggers", triggers, "recallCount", recalls, "failedAfterRecallCount", failures,
						"failureRate", failureRate));
		return Optional.of(repository.get(number(current.get("project_id")), queryCaseId));
	}

	@Transactional
	public QueryCaseSummary quarantine(Long projectId, String queryCaseId, String reason, OperatorContext operator) {
		if (projectId == null || !StringUtils.hasText(queryCaseId) || !StringUtils.hasText(reason)
				|| operator == null) {
			throw new IllegalArgumentException("projectId, queryCaseId, reason and operator are required");
		}
		authorization.require(operator, "quarantine approved Query Case");
		repository.require(projectId, queryCaseId);
		int updated = jdbc.update("""
				UPDATE qw_query_example
				SET status = 'QUARANTINED', quarantine_reason = ?, quarantine_time = CURRENT_TIMESTAMP,
				    review_comment = ?, reviewed_by = ?, reviewed_time = CURRENT_TIMESTAMP,
				    update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND project_id = ? AND status = 'APPROVED'
				""", reason.trim(), reason.trim(), operator.operator(), queryCaseId, projectId);
		if (updated != 1) {
			throw new IllegalStateException("Only APPROVED Query Cases can be manually quarantined");
		}
		events.appendEvent(queryCaseId, "QUERY_CASE_MANUALLY_QUARANTINED", "APPROVED", "QUARANTINED",
				operator.operator(), operator.source(),
				Map.of("reason", reason.trim(), "requestId", operator.requestId(), "idempotencyKey",
						operator.idempotencyKey(), "actorMode", "LOCAL_OPERATOR"));
		return repository.get(projectId, queryCaseId);
	}

	@Transactional
	public QueryCaseSummary restore(Long projectId, String queryCaseId, String reason, OperatorContext operator) {
		return transition(projectId, queryCaseId, "APPROVED", "QUERY_CASE_RESTORED", reason, operator);
	}

	@Transactional
	public QueryCaseSummary reject(Long projectId, String queryCaseId, String reason, OperatorContext operator) {
		return transition(projectId, queryCaseId, "REJECTED", "QUERY_CASE_REJECTED_FROM_QUARANTINE", reason, operator);
	}

	private QueryCaseSummary transition(Long projectId, String queryCaseId, String targetStatus, String eventType,
			String reason, OperatorContext operator) {
		if (projectId == null || !StringUtils.hasText(queryCaseId) || !StringUtils.hasText(reason)
				|| operator == null) {
			throw new IllegalArgumentException("projectId, queryCaseId, reason and operator are required");
		}
		authorization.require(operator, "govern quarantined Query Case");
		repository.require(projectId, queryCaseId);
		int updated = jdbc.update("""
				UPDATE qw_query_example
				SET status = ?, quarantine_reason = NULL, quarantine_time = NULL,
				    consecutive_recall_issue_count = 0,
				    failed_after_recall_count = CASE WHEN ? = 'APPROVED' THEN 0 ELSE failed_after_recall_count END,
				    review_comment = ?, reviewed_by = ?, reviewed_time = CURRENT_TIMESTAMP,
				    update_time = CURRENT_TIMESTAMP
				WHERE id = ? AND project_id = ? AND status = 'QUARANTINED'
				""", targetStatus, targetStatus, reason.trim(), operator.operator(), queryCaseId, projectId);
		if (updated != 1) {
			throw new IllegalStateException("Only QUARANTINED Query Cases can be restored or rejected");
		}
		events.appendEvent(queryCaseId, eventType, "QUARANTINED", targetStatus, operator.operator(), operator.source(),
				Map.of("reason", reason.trim(), "requestId", operator.requestId(), "idempotencyKey",
						operator.idempotencyKey(), "actorMode", "LOCAL_OPERATOR"));
		return repository.get(projectId, queryCaseId);
	}

	private static long longValue(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

	private static Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

}
