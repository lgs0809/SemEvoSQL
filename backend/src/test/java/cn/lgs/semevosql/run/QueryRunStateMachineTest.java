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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryRunStateMachineTest {

	private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
			RunStatus.QUEUED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCEL_REQUESTED, RunStatus.FAILED),
			RunStatus.RUNNING,
			EnumSet.of(RunStatus.QUEUED, RunStatus.WAITING_HUMAN, RunStatus.SUCCEEDED, RunStatus.FAILED,
					RunStatus.CANCEL_REQUESTED),
			RunStatus.WAITING_HUMAN,
			EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.CANCEL_REQUESTED, RunStatus.EXPIRED),
			RunStatus.CANCEL_REQUESTED, EnumSet.of(RunStatus.CANCELLED),
			RunStatus.FAILED, EnumSet.of(RunStatus.QUEUED),
			RunStatus.SUCCEEDED, Set.of(),
			RunStatus.CANCELLED, Set.of(),
			RunStatus.EXPIRED, Set.of());

	@Test
	void transitionMatrixIsExhaustiveAndFailClosed() {
		for (RunStatus source : RunStatus.values()) {
			for (RunStatus target : RunStatus.values()) {
				boolean expected = ALLOWED.get(source).contains(target);
				assertThat(QueryRunStateMachine.transitionAllowed(source, target))
					.as("%s -> %s", source, target)
					.isEqualTo(expected);
				if (expected) {
					assertThatCode(() -> QueryRunStateMachine.assertTransition(source, target)).doesNotThrowAnyException();
				}
				else {
					assertThatThrownBy(() -> QueryRunStateMachine.assertTransition(source, target))
						.isInstanceOf(IllegalStateException.class)
						.hasMessage("Invalid run transition: " + source + " -> " + target);
				}
			}
		}
	}

	@Test
	void onlyTerminalStatusesAreTerminal() {
		assertThat(EnumSet.allOf(RunStatus.class).stream().filter(QueryRunStateMachine::terminal).toList())
			.containsExactlyInAnyOrder(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.EXPIRED);
	}

	@Test
	void cancellingInteractiveRunRejectsLateRuntimeOutputButAllowsCancellationProtocol() {
		QueryRun run = run(RunStatus.CANCEL_REQUESTED, RunType.INTERACTIVE_QUERY);

		for (String event : new String[] { "CANCEL_REQUESTED", "SQL_CANCEL_SIGNAL_SENT", "RUN_CANCELLED" }) {
			assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(run, event)).doesNotThrowAnyException();
		}
		assertThatThrownBy(() -> QueryRunStateMachine.assertLateEventAllowed(run, "NODE_OUTPUT"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("rejects late runtime event NODE_OUTPUT");
	}

	@Test
	void terminalInteractiveRunAllowsOnlyItsMarkerGovernanceEventsAndFinalizationWarning() {
		QueryRun succeeded = run(RunStatus.SUCCEEDED, RunType.INTERACTIVE_QUERY);
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(succeeded, "RUN_SUCCEEDED"))
			.doesNotThrowAnyException();
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(succeeded, "QUERY_BINDING_CORRECTED"))
			.doesNotThrowAnyException();
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(succeeded, "REQUEST_REQUIREMENT_CORRECTED"))
			.doesNotThrowAnyException();
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(succeeded, "RUN_FINALIZATION_WARNING"))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> QueryRunStateMachine.assertLateEventAllowed(succeeded, "NODE_OUTPUT"))
			.isInstanceOf(IllegalStateException.class);

		QueryRun failed = run(RunStatus.FAILED, RunType.INTERACTIVE_QUERY);
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(failed, "RUN_FAILED"))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> QueryRunStateMachine.assertLateEventAllowed(failed, "RUN_FINALIZATION_WARNING"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonInteractiveRunKeepsLateGovernanceEventsPermissiveForBackgroundWorkflows() {
		QueryRun run = run(RunStatus.SUCCEEDED, RunType.REPLAY);
		assertThatCode(() -> QueryRunStateMachine.assertLateEventAllowed(run, "BACKGROUND_REPLAY_AUDIT"))
			.doesNotThrowAnyException();
	}

	private QueryRun run(RunStatus status, RunType runType) {
		return QueryRun.builder().runId("run-1").runType(runType).status(status).build();
	}

}
