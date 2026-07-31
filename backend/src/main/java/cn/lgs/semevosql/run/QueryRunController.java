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

import cn.lgs.semevosql.connector.JdbcStatementCancellationRegistry;
import cn.lgs.semevosql.connector.JdbcStatementCancellationRegistry.CancellationResult;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.service.graph.Context.MultiTurnContextManager;
import cn.lgs.semevosql.service.graph.GraphService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/semevosql/runs")
@RequiredArgsConstructor
public class QueryRunController {

	private final QueryRunService runService;

	private final QueryRunPublicPresenter publicPresenter;

	private final GraphService graphService;

	private final ThreadExecutionGuardService threadExecutionGuardService;

	private final MultiTurnContextManager multiTurnContextManager;

	private final SemEvoSQLProductionService productionService;

	private final OperatorContext.Resolver operatorResolver;

	private final RuntimeMutationScopeService runtimeMutationScope;

	@GetMapping("/{runId}")
	public QueryRunPublicView get(@PathVariable String runId) {
		return publicPresenter.present(runService.get(runId));
	}

	@GetMapping("/{runId}/events")
	public List<RunEvent> events(@PathVariable String runId,
			@RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
			@RequestParam(value = "limit", defaultValue = "200") int limit) {
		return runService.events(runId, afterSequence, limit);
	}

	@GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<RunEvent>> stream(@PathVariable String runId,
			@RequestParam(value = "afterSequence", required = false) Long afterSequence,
			@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
		long effectiveSequence = Math.max(afterSequence == null ? 0 : Math.max(0, afterSequence),
				parseSequence(lastEventId));
		return runService.stream(runId, effectiveSequence)
			.map(event -> ServerSentEvent.<RunEvent>builder(event)
				.id(Long.toString(event.sequence()))
				.event(event.eventType())
				.build());
	}

	@PostMapping("/{runId}/cancel")
	public QueryRunPublicView cancel(@PathVariable String runId, @Valid @RequestBody RunCommandRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "run-cancel:" + runId);
		runtimeMutationScope.requireRun(runId, operator);
		QueryRun run = runService.cancel(runId, request.idempotencyKey());
		if (run.status() == QueryRun.RunStatus.CANCEL_REQUESTED) {
			recordSqlCancellation(runId, run.currentNode(), request.idempotencyKey());
		}
		if (run.status() != QueryRun.RunStatus.CANCEL_REQUESTED) {
			if (run.terminal()) {
				if (run.status() == QueryRun.RunStatus.CANCELLED) {
					completeCancelledEpisode(run);
					runService.appendEvent(run.runId(), "RUN_CANCELLED", run.currentNode(), null, "Run cancelled",
							"run-cancelled:" + run.runId());
				}
				multiTurnContextManager.discardRun(run.runId(), run.threadId());
				threadExecutionGuardService.release(run.threadId(), run.runId());
			}
			return publicPresenter.present(run);
		}
		if (run.threadId() != null && !run.threadId().isBlank()) {
			graphService.stopStreamProcessing(run.threadId());
		}
		QueryRun cancelled = runService.acknowledgeCancelled(runId);
		completeCancelledEpisode(cancelled);
		runService.appendEvent(cancelled.runId(), "RUN_CANCELLED", cancelled.currentNode(), null, "Run cancelled",
				"run-cancelled:" + cancelled.runId());
		multiTurnContextManager.discardRun(cancelled.runId(), cancelled.threadId());
		threadExecutionGuardService.release(cancelled.threadId(), cancelled.runId());
		return publicPresenter.present(cancelled);
	}

	private void completeCancelledEpisode(QueryRun run) {
		if (run.episodeId() == null || run.episodeId().isBlank()) {
			return;
		}
		productionService.completeEpisode(run.episodeId(),
				new SemEvoSQLProductionService.CompletionRequest("CANCELLED", null, null, null));
	}

	@PostMapping("/{runId}/resume")
	public QueryRunPublicView resume(@PathVariable String runId, @Valid @RequestBody RunCommandRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "run-resume:" + runId);
		runtimeMutationScope.requireRun(runId, operator);
		return publicPresenter.present(runService.resume(runId, request.idempotencyKey()));
	}

	private void recordSqlCancellation(String runId, String currentNode, String idempotencyKey) {
		CancellationResult result = JdbcStatementCancellationRegistry.cancelPrefix(runId);
		String summary = "JDBC cancellation signal matched=" + result.matchedStatements() + ", cancelled="
				+ result.cancelledStatements() + ", errors=" + result.errors().size();
		runService.appendEvent(runId, "SQL_CANCEL_SIGNAL_SENT", currentNode, null, summary,
				"sql-cancel-signal:" + runId + ":" + idempotencyKey);
	}

	private long parseSequence(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			return Math.max(0, Long.parseLong(value));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Last-Event-ID must be a non-negative sequence number");
		}
	}

	public record RunCommandRequest(@NotBlank String idempotencyKey) {
	}

}
