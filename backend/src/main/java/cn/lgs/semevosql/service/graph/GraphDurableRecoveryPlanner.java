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
package cn.lgs.semevosql.service.graph;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.QueryTaskRepository;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Builds deterministic takeover state from durable Run/Task events after process loss. */
@Slf4j
@Component
@RequiredArgsConstructor
class GraphDurableRecoveryPlanner {

	private final QueryRunService runService;

	private final QueryTaskRepository queryTaskRepository;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	List<String> replayNodeSequence(QueryRun run) {
		long replayStartSequence = 0;
		if (queryTaskRepository.enabled(run.runId())) {
			var activeTask = queryTaskRepository.active(run.runId()).orElse(null);
			if (activeTask == null) {
				return List.of();
			}
			replayStartSequence = runService
				.eventByIdempotency(run.runId(), "todo-activated:" + run.runId() + ":" + activeTask.taskId())
				.map(RunEvent::sequence)
				.orElseGet(() -> runService.eventByIdempotency(run.runId(), "request-analysis:" + run.runId())
					.map(RunEvent::sequence)
					.orElse(0L));
		}
		List<String> visiblePasses = new ArrayList<>(runService.outputNodeSequence(run.runId(), replayStartSequence));
		if (!visiblePasses.isEmpty() && Objects.equals(visiblePasses.get(visiblePasses.size() - 1), run.currentNode())) {
			visiblePasses.remove(visiblePasses.size() - 1);
		}
		return List.copyOf(visiblePasses);
	}

	SemanticBlueprint recoverableSemanticPlan(QueryRun run) {
		if (queryTaskRepository.enabled(run.runId())) {
			var activeTask = queryTaskRepository.active(run.runId()).orElse(null);
			if (activeTask == null) {
				return null;
			}
			SemanticBlueprint plan = queryTaskRepository.plan(run.runId(), activeTask.taskId());
			return validRecoverablePlan(run, plan) ? plan : null;
		}
		RunEvent snapshot;
		try {
			snapshot = runService.latestEvent(run.runId(), "SEMANTIC_PLAN_SNAPSHOT");
		}
		catch (IllegalArgumentException missing) {
			return null;
		}
		if (!StringUtils.hasText(snapshot.payload())
				|| !Objects.toString(snapshot.idempotencyKey(), "").contains(":simple:")) {
			return null;
		}
		try {
			SemanticBlueprint plan = JsonUtil.getObjectMapper().readValue(snapshot.payload(), SemanticBlueprint.class);
			return validRecoverablePlan(run, plan) ? plan : null;
		}
		catch (Exception invalid) {
			log.warn("Unable to reuse persisted Semantic Blueprint for durable run {}: {}", run.runId(), invalid.getMessage());
			return null;
		}
	}

	String recoverablePlannerOutput(QueryRun run, SemanticBlueprint recoverablePlan) {
		if (recoverablePlan == null) {
			return null;
		}
		String scope = "simple";
		if (queryTaskRepository.enabled(run.runId())) {
			var activeTask = queryTaskRepository.active(run.runId()).orElse(null);
			if (activeTask == null) {
				return null;
			}
			scope = activeTask.taskId();
		}
		RunEvent snapshot;
		RunEvent semanticSnapshot;
		try {
			snapshot = runService.latestEvent(run.runId(), "PLANNER_PLAN_SNAPSHOT");
			semanticSnapshot = runService.latestEvent(run.runId(), "SEMANTIC_PLAN_SNAPSHOT");
		}
		catch (IllegalArgumentException missing) {
			return null;
		}
		String scopedToken = ":" + scope + ":";
		String plannerKey = Objects.toString(snapshot.idempotencyKey(), "");
		String semanticKey = Objects.toString(semanticSnapshot.idempotencyKey(), "");
		if (!StringUtils.hasText(snapshot.payload()) || !plannerKey.contains(scopedToken) || !semanticKey.contains(scopedToken)
				|| snapshot.sequence() <= semanticSnapshot.sequence()) {
			return null;
		}
		String semanticHashToken = ":sem-" + canonicalJson.hash(recoverablePlan) + ":";
		if (plannerKey.contains(":sem-") && !plannerKey.contains(semanticHashToken)) {
			return null;
		}
		try {
			var parsed = JsonUtil.getObjectMapper().readTree(snapshot.payload());
			return parsed.path("execution_plan").isArray() && !parsed.path("execution_plan").isEmpty() ? snapshot.payload()
					: null;
		}
		catch (Exception invalid) {
			log.warn("Unable to reuse persisted Planner output for durable run {}: {}", run.runId(), invalid.getMessage());
			return null;
		}
	}

	boolean validRecoverablePlan(QueryRun run, SemanticBlueprint plan) {
		return plan != null && plan.isExecutable() && Objects.equals(run.projectId(), plan.getProjectId())
				&& Objects.equals(run.projectVersionId(), plan.getProjectVersionId());
	}

}
