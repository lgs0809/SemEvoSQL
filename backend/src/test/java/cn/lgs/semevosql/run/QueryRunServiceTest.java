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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;
import cn.lgs.semevosql.run.QueryRunService.CreateRunCommand;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class QueryRunServiceTest {

	@Test
	void interactiveRunPersistsOneAbsoluteDeadlineAtCreation() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		when(repository.findByIdempotencyKey("idem-deadline")).thenReturn(Optional.empty());
		when(repository.insertIfAbsent(any(QueryRun.class))).thenReturn(1);
		QueryRunService service = new QueryRunService(repository, "instance-a");

		long before = System.currentTimeMillis();
		service.create(command("idem-deadline", "request-deadline"));
		long after = System.currentTimeMillis();

		ArgumentCaptor<QueryRun> captor = ArgumentCaptor.forClass(QueryRun.class);
		verify(repository).insertIfAbsent(captor.capture());
		assertThat(captor.getValue().deadlineEpochMillis()).isNotNull();
		assertThat(captor.getValue().deadlineEpochMillis()).isBetween(before + 299_000L, after + 301_000L);
	}

	@Test
	void idempotencyKeyCanOnlyReplayTheSameCreateCommand() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		QueryRun existing = run(RunStatus.QUEUED, null);
		when(repository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

		CreateRunCommand same = command("idem-1", "request-a");
		assertThat(service.create(same)).isSameAs(existing);

		CreateRunCommand different = command("idem-1", "request-b");
		assertThatThrownBy(() -> service.create(different))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("different run command");
		verify(repository, never()).insertIfAbsent(any());
	}

	@Test
	void terminalRunCannotBeResumed() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		QueryRun succeeded = run(RunStatus.SUCCEEDED, null);
		when(repository.findEventByIdempotency("run-1", "resume:resume-1")).thenReturn(Optional.empty());
		when(repository.lock("run-1")).thenReturn(succeeded);

		assertThatThrownBy(() -> service.resume("run-1", "resume-1"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("WAITING_HUMAN or FAILED");
		verify(repository, never()).updateStatus(eq("run-1"), anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	void expiredInteractiveRunCannotAcceptLateRuntimeEvent() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		when(repository.findEventByIdempotency("run-1", "late-event")).thenReturn(Optional.empty());
		when(repository.lock("run-1")).thenReturn(QueryRun.builder()
			.runId("run-1")
			.runType(RunType.INTERACTIVE_QUERY)
			.status(RunStatus.RUNNING)
			.deadlineEpochMillis(System.currentTimeMillis() - 1)
			.lastEventSequence(0)
			.build());

		assertThatThrownBy(() -> service.appendEvent("run-1", "NODE_OUTPUT", "planner", "{}", "late", "late-event"))
			.isInstanceOf(RunDeadlineExceededException.class);
		verify(repository, never()).advanceSequenceLocked(any(), anyLong(), any());
	}

	@Test
	void attemptAwareTransitionRejectsSupersededAttempt() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		when(repository.lock("run-1")).thenReturn(run(RunStatus.RUNNING, "instance-a", "attempt-2"));

		assertThatThrownBy(() -> service.transition("run-1", "attempt-1", RunStatus.SUCCEEDED, "done", null, null))
			.isInstanceOf(LateRunResultDroppedException.class)
			.hasMessageContaining("currentAttempt=attempt-2");
		verify(repository, never()).updateStatus(eq("run-1"), anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	void waitingHumanResumeTransitionsToQueuedAndPersistsOneDurableEvent() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		QueryRun waiting = run(RunStatus.WAITING_HUMAN, null);
		QueryRun queuedBeforeEvent = run(RunStatus.QUEUED, null);
		QueryRun queuedAfterEvent = QueryRun.builder()
			.runId("run-1")
			.runType(RunType.INTERACTIVE_QUERY)
			.projectId(12L)
			.projectVersionId(18L)
			.threadId("thread-1")
			.status(RunStatus.QUEUED)
			.currentNode("HUMAN_FEEDBACK_NODE")
			.lastEventSequence(1)
			.requestId("request-a")
			.idempotencyKey("idem-1")
			.revision(2)
			.requestPayload("payload")
			.recoveryPayload("payload")
			.executionSnapshot("snapshot")
			.build();
		RunEvent persisted = RunEvent.builder()
			.runId("run-1")
			.sequence(1)
			.eventType("RESUME_REQUESTED")
			.nodeName("HUMAN_FEEDBACK_NODE")
			.payloadSummary("Run queued for resume")
			.idempotencyKey("resume:resume-1")
			.build();

		when(repository.findEventByIdempotency("run-1", "resume:resume-1"))
			.thenReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(persisted));
		when(repository.lock("run-1")).thenReturn(waiting, queuedBeforeEvent);
		when(repository.updateStatus(eq("run-1"), eq(0L), eq(RunStatus.QUEUED), eq("HUMAN_FEEDBACK_NODE"),
				any(), any(), any())).thenReturn(1);
		when(repository.findById("run-1")).thenReturn(Optional.of(queuedAfterEvent));
		when(repository.advanceSequenceLocked("run-1", 1L, "HUMAN_FEEDBACK_NODE")).thenReturn(1);

		QueryRun resumed = service.resume("run-1", "resume-1");

		assertThat(resumed.status()).isEqualTo(RunStatus.QUEUED);
		assertThat(resumed.lastEventSequence()).isEqualTo(1);
		verify(repository).insertEvent(any(RunEvent.class));
	}

	@Test
	void leaseCanOnlyBeReleasedByItsOwnerInstance() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		when(repository.lock("run-1")).thenReturn(run(RunStatus.RUNNING, "instance-b"));

		service.releaseLease("run-1");
		verify(repository, never()).clearLease(any(), anyLong(), any());

		when(repository.lock("run-1")).thenReturn(run(RunStatus.RUNNING, "instance-a"));
		service.releaseLease("run-1");
		verify(repository).clearLease("run-1", 0L, "instance-a");
	}

	@Test
	void leaseRenewalFailsClosedWhenRepositoryRejectsOwner() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		when(repository.lock("run-1")).thenReturn(run(RunStatus.RUNNING, "instance-b"));
		when(repository.renewLease(eq("run-1"), eq(0L), eq("instance-a"), any())).thenReturn(0);

		assertThatThrownBy(() -> service.renewLease("run-1"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cannot be renewed by this instance");
	}

	@Test
	void cancelIsIdempotentForTerminalRuns() {
		QueryRunRepository repository = mock(QueryRunRepository.class);
		QueryRunService service = new QueryRunService(repository, "instance-a");
		QueryRun failed = run(RunStatus.FAILED, null);
		when(repository.lock("run-1")).thenReturn(failed);

		assertThat(service.cancel("run-1", "cancel-1")).isSameAs(failed);
		verify(repository, never()).updateStatus(eq("run-1"), anyLong(), any(), any(), any(), any(), any());
	}

	private CreateRunCommand command(String idempotencyKey, String requestId) {
		return new CreateRunCommand(RunType.INTERACTIVE_QUERY, 12L, 18L, "thread-1", requestId, idempotencyKey,
				"payload", "snapshot");
	}

	private QueryRun run(RunStatus status, String ownerInstance) {
		return run(status, ownerInstance, null);
	}

	private QueryRun run(RunStatus status, String ownerInstance, String attemptId) {
		return QueryRun.builder()
			.runId("run-1")
			.runType(RunType.INTERACTIVE_QUERY)
			.projectId(12L)
			.projectVersionId(18L)
			.threadId("thread-1")
			.status(status)
			.attemptId(attemptId)
			.currentNode("HUMAN_FEEDBACK_NODE")
			.lastEventSequence(0)
			.requestId("request-a")
			.idempotencyKey("idem-1")
			.ownerInstance(ownerInstance)
			.revision(0)
			.requestPayload("payload")
			.recoveryPayload("payload")
			.executionSnapshot("snapshot")
			.build();
	}

}
