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
package cn.lgs.semevosql.run;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import org.junit.jupiter.api.Test;

class RunExecutionFenceServiceTest {

	@Test
	void terminalRunRejectsLateBlockingResult() {
		QueryRunService runs = mock(QueryRunService.class);
		when(runs.get("run-1")).thenReturn(run(RunStatus.FAILED, "attempt-1", "instance-a"));
		when(runs.instanceId()).thenReturn("instance-a");

		assertThatThrownBy(() -> new RunExecutionFenceService(runs).assertActive("run-1", "attempt-1"))
			.isInstanceOf(LateRunResultDroppedException.class)
			.hasMessageContaining("currentStatus=FAILED");
	}

	@Test
	void recoveredAttemptRejectsLateResultFromSupersededAttempt() {
		QueryRunService runs = mock(QueryRunService.class);
		when(runs.get("run-1")).thenReturn(run(RunStatus.RUNNING, "attempt-2", "instance-a"));
		when(runs.instanceId()).thenReturn("instance-a");

		assertThatThrownBy(() -> new RunExecutionFenceService(runs).assertActive("run-1", "attempt-1"))
			.isInstanceOf(LateRunResultDroppedException.class)
			.hasMessageContaining("currentAttempt=attempt-2");
	}

	@Test
	void matchingRunAttemptAndLeaseRemainWritable() {
		QueryRunService runs = mock(QueryRunService.class);
		when(runs.get("run-1")).thenReturn(run(RunStatus.RUNNING, "attempt-2", "instance-a"));
		when(runs.instanceId()).thenReturn("instance-a");

		assertThatCode(() -> new RunExecutionFenceService(runs).assertActive("run-1", "attempt-2"))
			.doesNotThrowAnyException();
	}

	private QueryRun run(RunStatus status, String attemptId, String owner) {
		return QueryRun.builder().runId("run-1").status(status).attemptId(attemptId).ownerInstance(owner).build();
	}
}
