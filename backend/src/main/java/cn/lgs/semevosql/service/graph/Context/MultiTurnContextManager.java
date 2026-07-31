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
package cn.lgs.semevosql.service.graph.Context;

import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Manages multi-turn dialogue context for each thread. Completed turns and the current
 * pending turn are persisted in MySQL so a process restart does not erase conversation
 * history. Only the in-flight StringBuilder remains local to the active worker.
 */
@Slf4j
@Component
public class MultiTurnContextManager {

	private final ConversationTurnRepository repository;

	private final ConversationContextAssembler contextAssembler;

	private final ConversationTurnSummarizer turnSummarizer;

	private final ConversationContextPromptRenderer contextRenderer;

	private final ConversationContextCompactionService compactionService;

	private final Map<String, PendingTurn> pendingTurns = new ConcurrentHashMap<>();

	public MultiTurnContextManager(ConversationTurnRepository repository, ConversationContextAssembler contextAssembler,
			ConversationTurnSummarizer turnSummarizer, ConversationContextPromptRenderer contextRenderer,
			ConversationContextCompactionService compactionService) {
		this.repository = repository;
		this.contextAssembler = contextAssembler;
		this.turnSummarizer = turnSummarizer;
		this.contextRenderer = contextRenderer;
		this.compactionService = compactionService;
	}

	/** Start or restore the current durable conversation turn. */
	public void beginTurn(String runId, String threadId, String userQuestion) {
		if (StringUtils.isAnyBlank(runId, threadId, userQuestion)) {
			return;
		}
		ConversationTurn durable = repository.begin(runId, threadId, userQuestion.trim());
		pendingTurns.compute(threadId, (key, current) -> {
			if (current != null && current.runId.equals(runId)) {
				return current;
			}
			return new PendingTurn(durable.runId(), durable.userQuestion(), durable.plannerOutput());
		});
	}

	/** Append planner output to the active turn. */
	public void appendPlannerChunk(String threadId, String chunk) {
		if (StringUtils.isAnyBlank(threadId, chunk)) {
			return;
		}
		PendingTurn pending = pendingTurns.get(threadId);
		if (pending != null) {
			synchronized (pending) {
				pending.planBuilder.append(chunk);
			}
		}
	}

	/** Persist the current partial plan before a durable human-review pause. */
	public void persistPending(String threadId) {
		PendingTurn pending = pendingTurns.get(threadId);
		if (pending != null) {
			repository.savePending(pending.runId, pending.plan());
		}
	}

	/** Return the exact durable planner output that was presented for human review. */
	public String requirePendingPlannerOutput(String runId, String threadId) {
		if (StringUtils.isAnyBlank(runId, threadId)) {
			throw new IllegalArgumentException("runId and threadId are required");
		}
		ConversationTurn durable = repository.findByRun(runId)
			.orElseThrow(() -> new IllegalStateException("Pending conversation turn not found for run: " + runId));
		if (!threadId.equals(durable.threadId())) {
			throw new IllegalArgumentException("Conversation turn does not belong to thread: " + threadId);
		}
		if (!"PENDING".equals(durable.status())) {
			throw new IllegalStateException("Conversation turn is not waiting for plan review: " + runId);
		}
		String plannerOutput = StringUtils.trimToNull(durable.plannerOutput());
		if (plannerOutput == null) {
			throw new IllegalStateException("Reviewed planner output is unavailable for run: " + runId);
		}
		return plannerOutput;
	}

	/** Complete the active turn and expose it to future prompts. */
	public void finishTurn(String threadId) {
		PendingTurn pending = pendingTurns.remove(threadId);
		if (pending == null) {
			ConversationTurn durable = repository.findPendingByThread(threadId).orElse(null);
			if (durable == null) {
				return;
			}
			pending = new PendingTurn(durable.runId(), durable.userQuestion(), durable.plannerOutput());
		}
		String plan = StringUtils.trimToEmpty(pending.plan());
		if (StringUtils.isBlank(plan)) {
			log.debug("No planner output recorded for thread {}, cancelling empty context turn", threadId);
			repository.cancel(pending.runId);
			return;
		}
		ConversationTurnSummarizer.CompletionContext completion = turnSummarizer.summarize(pending.runId,
				pending.userQuestion, plan);
		repository.complete(pending.runId, plan, completion);
		compactionService.maybeCompactAsync(threadId);
	}

	/** Reset an unfinished plan before replaying a failed run from the graph entry. */
	public void resetPendingForRetry(String threadId) {
		PendingTurn pending = pendingTurns.remove(threadId);
		if (pending != null) {
			repository.resetPending(pending.runId);
			return;
		}
		repository.findPendingByThread(threadId).ifPresent(turn -> repository.resetPending(turn.runId()));
	}

	/** Discard the current pending turn when the run is cancelled or aborted. */
	public void discardPending(String threadId) {
		PendingTurn pending = pendingTurns.remove(threadId);
		if (pending != null) {
			repository.cancel(pending.runId);
			return;
		}
		repository.findPendingByThread(threadId).ifPresent(turn -> repository.cancel(turn.runId()));
	}

	/** Close a durable turn by run id even when its in-memory StreamContext is gone. */
	public void discardRun(String runId, String threadId) {
		if (StringUtils.isBlank(runId)) {
			return;
		}
		if (StringUtils.isNotBlank(threadId)) {
			PendingTurn pending = pendingTurns.get(threadId);
			if (pending != null && runId.equals(pending.runId)) {
				pendingTurns.remove(threadId, pending);
			}
		}
		repository.cancel(runId);
	}

	/**
	 * Reset the current plan after a human rejection. This deliberately keeps all prior
	 * completed turns; the previous implementation removed the last historical turn.
	 */
	public void restartCurrentTurn(String runId, String threadId) {
		restartCurrentTurn(runId, threadId, null);
	}

	public void restartCurrentTurn(String runId, String threadId, String replacementQuestion) {
		if (StringUtils.isAnyBlank(runId, threadId)) {
			return;
		}
		ConversationTurn durable = repository.findByRun(runId)
			.orElseThrow(() -> new IllegalStateException("Pending conversation turn not found for run: " + runId));
		if (!threadId.equals(durable.threadId())) {
			throw new IllegalArgumentException("Conversation turn does not belong to thread: " + threadId);
		}
		String question = StringUtils.defaultIfBlank(replacementQuestion, durable.userQuestion());
		repository.resetPending(runId, replacementQuestion == null ? null : question);
		pendingTurns.put(threadId, new PendingTurn(runId, question, ""));
	}

	/** Build structured and retrieval-aware context for the current question. */
	public PreparedContext prepareContext(String threadId, String currentQuestion) {
		ConversationContextEnvelope envelope = contextAssembler.assemble(threadId, currentQuestion);
		return new PreparedContext(envelope,
				contextRenderer.render(envelope, ConversationContextPromptRenderer.Stage.GENERAL));
	}

	/** Compatibility view for callers that only need the rendered context. */
	public String buildContext(String threadId) {
		return prepareContext(threadId, "").rendered();
	}

	public record PreparedContext(ConversationContextEnvelope envelope, String rendered) {
	}

	private static final class PendingTurn {

		private final String runId;

		private final String userQuestion;

		private final StringBuilder planBuilder = new StringBuilder();

		private PendingTurn(String runId, String userQuestion, String plannerOutput) {
			this.runId = runId;
			this.userQuestion = userQuestion;
			if (plannerOutput != null) {
				this.planBuilder.append(plannerOutput);
			}
		}

		private String plan() {
			synchronized (this) {
				return planBuilder.toString();
			}
		}

	}

}
