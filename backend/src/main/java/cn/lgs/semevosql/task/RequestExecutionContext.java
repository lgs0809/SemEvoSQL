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
package cn.lgs.semevosql.task;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.review.PostExecutionReview;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Shared request-scoped facts for serial QueryTask execution.
 *
 * <p>Only a user-confirmed clarification or a task whose post-execution review is PASS may add accepted facts.
 * Free-form reasoning, failed retrieval candidates and model chain-of-thought have no representation here.</p>
 */
public final class RequestExecutionContext {

	private final String requestId;

	private final String originalQuery;

	private final List<QueryTask> tasks;

	private final List<ResolvedClarification> clarifications = new ArrayList<>();

	private final List<AcceptedEvidence> acceptedEvidence = new ArrayList<>();

	private final List<TaskExecutionResult> completedTasks = new ArrayList<>();

	public RequestExecutionContext(String requestId, String originalQuery, List<QueryTask> tasks) {
		if (!StringUtils.hasText(requestId) || !StringUtils.hasText(originalQuery)) {
			throw new IllegalArgumentException("requestId and originalQuery are required");
		}
		this.requestId = requestId.trim();
		this.originalQuery = originalQuery.trim();
		this.tasks = List.copyOf(tasks == null ? List.of() : tasks);
		if (this.tasks.isEmpty()) {
			throw new IllegalArgumentException("At least one QueryTask is required");
		}
	}

	public synchronized void acceptClarification(String taskId, String question, String answer) {
		if (!StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
			throw new IllegalArgumentException("Confirmed clarification question/answer is required");
		}
		clarifications.add(new ResolvedClarification(taskId, question.trim(), answer.trim()));
	}

	public synchronized void acceptReviewedTask(TaskExecutionResult result) {
		if (result == null || result.review() == null || result.review().decision() != PostExecutionReview.Decision.PASS) {
			throw new IllegalArgumentException("Only Post Review PASS tasks may enter accepted request context");
		}
		completedTasks.removeIf(existing -> existing.taskId().equals(result.taskId()));
		completedTasks.add(result);
		if (result.evidence() != null) {
			acceptedEvidence.addAll(result.evidence());
		}
	}

	/** Non-authoritative previous-task hints; the current planner must still rebind and may override them. */
	public synchronized QueryCaseHints acceptedHints() {
		Set<String> models = new LinkedHashSet<>();
		Set<String> metrics = new LinkedHashSet<>();
		Set<String> dimensions = new LinkedHashSet<>();
		Set<String> grains = new LinkedHashSet<>();
		Set<String> relationships = new LinkedHashSet<>();
		Set<String> rules = new LinkedHashSet<>();
		TimeBindingHint time = null;
		for (TaskExecutionResult completed : completedTasks) {
			SemanticBlueprint plan = completed.plan();
			if (plan == null) {
				continue;
			}
			plan.getModels().forEach(value -> models.add(value.getModelCode()));
			plan.getMetrics().forEach(value -> metrics.add(value.getMetricCode()));
			plan.getDimensions().forEach(value -> dimensions.add(value.getDimensionCode()));
			plan.getGrains().forEach(value -> grains.add(value.getGrainCode()));
			plan.getRelationships().forEach(value -> relationships.add(value.getRelationshipCode()));
			plan.getRules().forEach(value -> rules.add(value.getRuleCode()));
			if (plan.getTimeRange() != null && StringUtils.hasText(plan.getTimeRange().getTimeColumn())) {
				time = new TimeBindingHint(plan.getTimeRange().getRelativeExpression(), plan.getTimeRange().getModelCode(),
						plan.getTimeRange().getTimeColumn(), "REQUEST_CONTEXT:" + completed.taskId(), 1.0d,
						plan.getTimeRange().getGranularity());
			}
		}
		return new QueryCaseHints(models, metrics, dimensions, grains, relationships, rules, List.of(), time, false,
				"REQUEST_ACCEPTED_CONTEXT", completedTasks.stream().map(TaskExecutionResult::taskId).toList(), 1.0d,
				Map.of());
	}

	public String requestId() {
		return requestId;
	}

	public String originalQuery() {
		return originalQuery;
	}

	public List<QueryTask> tasks() {
		return tasks;
	}

	public synchronized List<ResolvedClarification> clarifications() {
		return List.copyOf(clarifications);
	}

	public synchronized List<AcceptedEvidence> acceptedEvidence() {
		return List.copyOf(acceptedEvidence);
	}

	public synchronized List<TaskExecutionResult> completedTasks() {
		return List.copyOf(completedTasks);
	}

	public record ResolvedClarification(String taskId, String question, String answer) {
	}

	public record AcceptedEvidence(String taskId, String evidenceType, String summary) {
	}

	public record TaskExecutionResult(String taskId, SemanticBlueprint plan, Object resultSummary,
			PostExecutionReview review, List<AcceptedEvidence> evidence) {
		public TaskExecutionResult {
			evidence = List.copyOf(evidence == null ? List.of() : evidence);
		}
	}
}
