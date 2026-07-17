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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService;
import cn.lgs.semevosql.run.RunEvent;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.QueryTaskRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphDurableRecoveryPlannerTest {

	private QueryRunService runService;

	private QueryTaskRepository queryTaskRepository;

	private GraphDurableRecoveryPlanner planner;

	@BeforeEach
	void setUp() {
		runService = mock(QueryRunService.class);
		queryTaskRepository = mock(QueryTaskRepository.class);
		planner = new GraphDurableRecoveryPlanner(runService, queryTaskRepository);
	}

	@Test
	void recoverablePlanMustMatchDurableProjectAndVersion() {
		QueryRun run = run("planner");
		SemanticBlueprint valid = plan(12L, 18L);
		SemanticBlueprint wrongProject = plan(99L, 18L);
		SemanticBlueprint wrongVersion = plan(12L, 19L);
		SemanticBlueprint nonExecutable = SemanticBlueprint.builder()
			.projectId(12L)
			.projectVersionId(18L)
			.executable(false)
			.build();

		assertThat(planner.validRecoverablePlan(run, valid)).isTrue();
		assertThat(planner.validRecoverablePlan(run, wrongProject)).isFalse();
		assertThat(planner.validRecoverablePlan(run, wrongVersion)).isFalse();
		assertThat(planner.validRecoverablePlan(run, nonExecutable)).isFalse();
	}

	@Test
	void replaySequenceKeepsPartiallyStreamedCurrentNodeVisible() {
		QueryRun run = run("planner");
		when(queryTaskRepository.enabled(run.runId())).thenReturn(false);
		when(runService.outputNodeSequence(run.runId(), 0L)).thenReturn(List.of("retrieval", "planner"));

		assertThat(planner.replayNodeSequence(run)).containsExactly("retrieval");
	}

	@Test
	void plannerSnapshotMustBeNewerScopedAndBoundToSameSemanticPlan() {
		QueryRun run = run("planner");
		SemanticBlueprint plan = plan(12L, 18L);
		String hash = new CanonicalJson().hash(plan);
		RunEvent semantic = event(4L, "semantic:simple:sem-" + hash + ":snapshot", "{}");
		RunEvent plannerEvent = event(5L, "planner:simple:sem-" + hash + ":snapshot",
				"{\"execution_plan\":[{\"step\":\"query\"}]}");
		when(queryTaskRepository.enabled(run.runId())).thenReturn(false);
		when(runService.latestEvent(run.runId(), "PLANNER_PLAN_SNAPSHOT")).thenReturn(plannerEvent);
		when(runService.latestEvent(run.runId(), "SEMANTIC_PLAN_SNAPSHOT")).thenReturn(semantic);

		assertThat(planner.recoverablePlannerOutput(run, plan)).isEqualTo(plannerEvent.payload());

		RunEvent stalePlanner = event(3L, plannerEvent.idempotencyKey(), plannerEvent.payload());
		when(runService.latestEvent(run.runId(), "PLANNER_PLAN_SNAPSHOT")).thenReturn(stalePlanner);
		assertThat(planner.recoverablePlannerOutput(run, plan)).isNull();

		RunEvent wrongHash = event(5L, "planner:simple:sem-deadbeef:snapshot", plannerEvent.payload());
		when(runService.latestEvent(run.runId(), "PLANNER_PLAN_SNAPSHOT")).thenReturn(wrongHash);
		assertThat(planner.recoverablePlannerOutput(run, plan)).isNull();
	}

	private QueryRun run(String currentNode) {
		return QueryRun.builder()
			.runId("run-1")
			.runType(RunType.INTERACTIVE_QUERY)
			.projectId(12L)
			.projectVersionId(18L)
			.status(RunStatus.RUNNING)
			.currentNode(currentNode)
			.build();
	}

	private SemanticBlueprint plan(Long projectId, Long versionId) {
		return SemanticBlueprint.builder()
			.projectId(projectId)
			.projectVersionId(versionId)
			.executable(true)
			.build();
	}

	private RunEvent event(long sequence, String key, String payload) {
		return RunEvent.builder()
			.runId("run-1")
			.sequence(sequence)
			.eventType("SNAPSHOT")
			.idempotencyKey(key)
			.payload(payload)
			.build();
	}

}
