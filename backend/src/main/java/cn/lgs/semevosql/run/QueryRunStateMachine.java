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

import cn.lgs.semevosql.run.QueryRun.RunStatus;
import cn.lgs.semevosql.run.QueryRun.RunType;

/**
 * Durable QueryRun lifecycle policy.
 *
 * <p>The state matrix and late-event rules live here instead of being spread across orchestration code so recovery,
 * clarification and cancellation share one fail-closed contract.</p>
 */
final class QueryRunStateMachine {

	private QueryRunStateMachine() {
	}

	static void assertTransition(RunStatus source, RunStatus target) {
		assertTransition(source, target, "Invalid run transition: " + source + " -> " + target);
	}

	static void assertTransition(RunStatus source, RunStatus target, String failureMessage) {
		if (!transitionAllowed(source, target)) {
			throw new IllegalStateException(failureMessage);
		}
	}

	static boolean transitionAllowed(RunStatus source, RunStatus target) {
		return switch (source) {
			case QUEUED -> target == RunStatus.RUNNING || target == RunStatus.CANCEL_REQUESTED
					|| target == RunStatus.FAILED;
			case RUNNING -> target == RunStatus.QUEUED || target == RunStatus.WAITING_HUMAN
					|| target == RunStatus.SUCCEEDED || target == RunStatus.FAILED || target == RunStatus.CANCEL_REQUESTED;
			case WAITING_HUMAN -> target == RunStatus.QUEUED || target == RunStatus.RUNNING
					|| target == RunStatus.CANCEL_REQUESTED || target == RunStatus.EXPIRED;
			case CANCEL_REQUESTED -> target == RunStatus.CANCELLED;
			case FAILED -> target == RunStatus.QUEUED;
			case SUCCEEDED, CANCELLED, EXPIRED -> false;
		};
	}

	static boolean terminal(RunStatus status) {
		return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED
				|| status == RunStatus.EXPIRED;
	}

	static boolean terminalEvent(RunEvent event) {
		return terminalMarkerMatches(RunStatus.SUCCEEDED, event.eventType())
				|| terminalMarkerMatches(RunStatus.FAILED, event.eventType())
				|| terminalMarkerMatches(RunStatus.CANCELLED, event.eventType())
				|| terminalMarkerMatches(RunStatus.EXPIRED, event.eventType());
	}

	static void assertLateEventAllowed(QueryRun run, String eventType) {
		if (run.runType() != RunType.INTERACTIVE_QUERY) {
			return;
		}
		if (run.status() == RunStatus.CANCEL_REQUESTED) {
			if ("CANCEL_REQUESTED".equals(eventType) || "SQL_CANCEL_SIGNAL_SENT".equals(eventType)
					|| "RUN_CANCELLED".equals(eventType)) {
				return;
			}
			throw new IllegalStateException(
					"Cancelling interactive run rejects late runtime event " + eventType + ": " + run.runId());
		}
		if (!run.terminal()) {
			return;
		}
		if (terminalMarkerMatches(run.status(), eventType) || postTerminalGovernanceEvent(eventType)) {
			return;
		}
		if (run.status() == RunStatus.SUCCEEDED && "RUN_FINALIZATION_WARNING".equals(eventType)) {
			return;
		}
		throw new IllegalStateException(
				"Terminal interactive run rejects late runtime event " + eventType + ": " + run.runId());
	}

	static boolean terminalMarkerMatches(RunStatus status, String eventType) {
		return switch (status) {
			case SUCCEEDED -> "RUN_SUCCEEDED".equals(eventType);
			case FAILED -> "RUN_FAILED".equals(eventType);
			case CANCELLED -> "RUN_CANCELLED".equals(eventType);
			case EXPIRED -> "RUN_EXPIRED".equals(eventType);
			default -> false;
		};
	}

	static boolean postTerminalGovernanceEvent(String eventType) {
		return "QUERY_BINDING_CORRECTED".equals(eventType)
				|| "REQUEST_REQUIREMENT_CORRECTED".equals(eventType)
				|| "SEMANTIC_DEFINITION_CORRECTION_PROPOSED".equals(eventType);
	}

}
