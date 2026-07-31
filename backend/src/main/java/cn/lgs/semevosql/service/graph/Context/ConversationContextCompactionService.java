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

import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.service.graph.Context.ConversationContextCompressionPrompt.CompressionPromptRequest;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import cn.lgs.semevosql.util.JsonUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Best-effort cumulative compaction of older completed turns. */
@Slf4j
@Component
public class ConversationContextCompactionService {

	private static final int SNAPSHOT_VERSION = 1;

	private final ConversationTurnRepository turnRepository;

	private final ConversationContextCompactionRepository compactionRepository;

	private final ConversationContextCompressionPrompt compressionPrompt;

	private final ConversationContextCompressionValidator validator;

	private final ConversationContextProperties properties;

	private final ApproximateTokenCounter tokenCounter;

	private final CanonicalJson canonicalJson;

	private final ConcurrentHashMap<String, Object> threadLocks = new ConcurrentHashMap<>();

	public ConversationContextCompactionService(ConversationTurnRepository turnRepository,
			ConversationContextCompactionRepository compactionRepository,
			ConversationContextCompressionPrompt compressionPrompt, ConversationContextCompressionValidator validator,
			ConversationContextProperties properties, ApproximateTokenCounter tokenCounter,
			CanonicalJson canonicalJson) {
		this.turnRepository = turnRepository;
		this.compactionRepository = compactionRepository;
		this.compressionPrompt = compressionPrompt;
		this.validator = validator;
		this.properties = properties;
		this.tokenCounter = tokenCounter;
		this.canonicalJson = canonicalJson;
	}

	public void maybeCompactAsync(String threadId) {
		if (!properties.isCompressionEnabled() || threadId == null || threadId.isBlank()) {
			return;
		}
		Mono.fromRunnable(() -> maybeCompact(threadId)).subscribeOn(Schedulers.boundedElastic()).subscribe();
	}

	void maybeCompact(String threadId) {
		if (!properties.isCompressionEnabled() || threadId == null || threadId.isBlank()) {
			return;
		}
		Object lock = threadLocks.computeIfAbsent(threadId, ignored -> new Object());
		try {
			synchronized (lock) {
				compact(threadId);
			}
		}
		catch (Exception ex) {
			log.warn("Conversation context compaction failed open for thread {}: {}", threadId, ex.getMessage());
		}
		finally {
			threadLocks.remove(threadId, lock);
		}
	}

	private void compact(String threadId) throws Exception {
		ConversationContextCompactionSnapshot existing = compactionRepository.find(threadId).orElse(null);
		long coveredSequence = existing == null ? 0L : existing.coveredThroughSequence();
		ConversationContextCompressionOutput previous = existing == null ? null : parse(existing.summaryJson());
		List<ConversationTurn> uncovered = turnRepository.completedAfter(threadId, coveredSequence, Integer.MAX_VALUE);
		int recentCount = Math.max(1, properties.getRecentTurnCount());
		int compressibleCount = Math.max(0, uncovered.size() - recentCount);
		if (compressibleCount < Math.max(1, properties.getMinimumCompressibleTurns())) {
			return;
		}
		List<ConversationTurn> compressible = uncovered.subList(0, compressibleCount);
		int tokenEstimate = compressible.stream().mapToInt(this::tokenEstimate).sum();
		double threshold = properties.getGeneralMaxTokens() * properties.getCompressionTriggerRatio();
		if (tokenEstimate <= threshold) {
			return;
		}
		int batchSize = Math.min(Math.max(1, properties.getCompressionMaxBatchTurns()), compressible.size());
		List<ConversationTurn> batch = List.copyOf(compressible.subList(0, batchSize));
		long expectedSequence = batch.get(batch.size() - 1).turnSequence();
		CompressionPromptRequest request = compressionPrompt.build(previous, batch, expectedSequence);
		String rawOutput = compressionPrompt.call(request);
		ConversationContextCompressionOutput output = validator.validate(rawOutput, expectedSequence,
				request.validatorInput());
		String digest = canonicalJson.hash(new DigestInput(previous, batch.stream().map(DigestTurn::from).toList()));
		LocalDateTime now = LocalDateTime.now();
		compactionRepository
			.upsert(new ConversationContextCompactionSnapshot(threadId, expectedSequence, canonicalJson.write(output),
					digest, SNAPSHOT_VERSION, existing == null ? now : existing.createTime(), now));
	}

	private ConversationContextCompressionOutput parse(String summaryJson) throws Exception {
		return JsonUtil.getObjectMapper().readValue(summaryJson, ConversationContextCompressionOutput.class);
	}

	private int tokenEstimate(ConversationTurn turn) {
		if (turn.promptTokenEstimate() > 0) {
			return turn.promptTokenEstimate();
		}
		ConversationTurnSummary summary = ConversationContextSummarySupport.read(turn);
		String value = String.join("\n", Objects.toString(turn.userQuestion(), ""),
				Objects.toString(turn.canonicalQuery(), summary.canonicalQuery()),
				Objects.toString(summary.models(), ""), Objects.toString(summary.metrics(), ""),
				Objects.toString(summary.dimensions(), ""), Objects.toString(summary.filters(), ""),
				Objects.toString(summary.timeRange(), ""), Objects.toString(summary.groupBy(), ""),
				Objects.toString(summary.clarifications(), ""));
		return tokenCounter.estimate(value);
	}

	private record DigestInput(ConversationContextCompressionOutput previous, List<DigestTurn> turns) {
	}

	private record DigestTurn(String id, long sequence, long revision, String contextSummaryJson) {

		private static DigestTurn from(ConversationTurn turn) {
			return new DigestTurn(turn.id(), turn.turnSequence(), turn.revision(), turn.contextSummaryJson());
		}
	}

}
