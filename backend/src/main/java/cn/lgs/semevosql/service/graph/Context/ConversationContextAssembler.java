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
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.CompactedHistory;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.ConversationState;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.RetrievalReport;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds recent, retrieved and structured conversation memory for one graph invocation.
 */
@Component
public class ConversationContextAssembler {

	private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]+", Pattern.CASE_INSENSITIVE);

	private static final Pattern EXPLICIT_REFERENCE = Pattern
		.compile("刚才|之前|上次|前面|继续|再查|再看|相比|比较|那个|这些|它们|同样|基于上述|那.{0,8}呢");

	private final ConversationTurnRepository repository;

	private final ConversationContextCompactionRepository compactionRepository;

	private final ConversationContextProperties properties;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public ConversationContextAssembler(ConversationTurnRepository repository,
			ConversationContextCompactionRepository compactionRepository, ConversationContextProperties properties) {
		this.repository = repository;
		this.compactionRepository = compactionRepository;
		this.properties = properties;
	}

	public ConversationContextEnvelope assemble(String threadId, String currentQuestion) {
		if (threadId == null || threadId.isBlank()) {
			return ConversationContextEnvelope.empty();
		}
		List<ConversationTurn> candidates = repository.completedHistory(threadId,
				Math.max(properties.getRecentTurnCount(), properties.getRetrievalCandidateCount()));
		if (candidates.isEmpty()) {
			return ConversationContextEnvelope.empty();
		}
		int recentStart = Math.max(0, candidates.size() - Math.max(1, properties.getRecentTurnCount()));
		List<TurnView> recent = candidates.subList(recentStart, candidates.size())
			.stream()
			.map(turn -> view(turn, 1.0D))
			.toList();
		boolean explicitReference = currentQuestion != null && EXPLICIT_REFERENCE.matcher(currentQuestion).find();
		Set<String> queryFeatures = features(currentQuestion);
		List<TurnView> retrieved = new ArrayList<>();
		for (ConversationTurn turn : candidates.subList(0, recentStart)) {
			ConversationTurnSummary summary = summary(turn);
			double relevance = relevance(queryFeatures, features(summary.searchableText(turn.userQuestion())));
			if (explicitReference && relevance > 0.0D) {
				relevance += properties.getExplicitReferenceBoost() * recencyWeight(turn, candidates);
			}
			if (relevance >= properties.getMinRelevanceScore()) {
				retrieved.add(view(turn, Math.min(1.0D, relevance)));
			}
		}
		retrieved.sort(Comparator.comparingDouble(TurnView::relevanceScore)
			.reversed()
			.thenComparing(Comparator.comparingLong(TurnView::sequence).reversed()));
		if (retrieved.size() > properties.getRetrievedTurnCount()) {
			retrieved = new ArrayList<>(retrieved.subList(0, properties.getRetrievedTurnCount()));
		}
		return new ConversationContextEnvelope(ConversationContextEnvelope.CURRENT_SCHEMA_VERSION, mergeState(recent),
				compactedHistory(threadId), recent, retrieved,
				new RetrievalReport(Math.max(0, recentStart), retrieved.size(), explicitReference));
	}

	private TurnView view(ConversationTurn turn, double relevance) {
		ConversationTurnSummary summary = summary(turn);
		return new TurnView(turn.turnSequence(), turn.userQuestion(),
				turn.canonicalQuery() == null || turn.canonicalQuery().isBlank() ? summary.canonicalQuery()
						: turn.canonicalQuery(),
				summary, relevance, turn.promptTokenEstimate(), turn.runId(), turn.revision());
	}

	private ConversationTurnSummary summary(ConversationTurn turn) {
		return ConversationContextSummarySupport.read(turn);
	}

	private CompactedHistory compactedHistory(String threadId) {
		ConversationContextCompactionSnapshot snapshot = compactionRepository.find(threadId).orElse(null);
		if (snapshot == null || !snapshot.valid() || snapshot.summaryJson() == null || snapshot.summaryJson().isBlank()) {
			return CompactedHistory.empty();
		}
		try {
			ConversationContextCompressionOutput output = mapper.readValue(snapshot.summaryJson(),
					ConversationContextCompressionOutput.class);
			if (output.schemaVersion() != ConversationContextCompressionOutput.CURRENT_SCHEMA_VERSION
					|| output.coveredThroughSequence() != snapshot.coveredThroughSequence()) {
				return CompactedHistory.empty();
			}
			return new CompactedHistory(output.coveredThroughSequence(), output.summary(),
					output.importantCorrections(), output.unresolvedQuestions());
		}
		catch (Exception ignored) {
			return CompactedHistory.empty();
		}
	}

	private ConversationState mergeState(List<TurnView> recent) {
		for (int index = recent.size() - 1; index >= 0; index--) {
			ConversationTurnSummary summary = recent.get(index).summary();
			if (hasStructuredState(summary)) {
				return new ConversationState(summary.models(), summary.metrics(), summary.dimensions(),
						summary.filters(), summary.timeRange(), summary.groupBy(), summary.clarifications(),
						summary.result());
			}
		}
		return ConversationState.empty();
	}

	private boolean hasStructuredState(ConversationTurnSummary summary) {
		return !summary.models().isEmpty() || !summary.metrics().isEmpty() || !summary.dimensions().isEmpty()
				|| !summary.filters().isEmpty() || summary.timeRange() != null || !summary.groupBy().isEmpty()
				|| !summary.clarifications().isEmpty() || summary.result() != null;
	}

	private double recencyWeight(ConversationTurn turn, List<ConversationTurn> candidates) {
		long newest = candidates.get(candidates.size() - 1).turnSequence();
		long distance = Math.max(0L, newest - turn.turnSequence());
		return 1.0D / (1.0D + distance);
	}

	private double relevance(Set<String> query, Set<String> candidate) {
		if (query.isEmpty() || candidate.isEmpty()) {
			return 0.0D;
		}
		long overlap = query.stream().filter(candidate::contains).count();
		return (double) overlap / query.size();
	}

	private Set<String> features(String value) {
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		Set<String> result = new LinkedHashSet<>();
		Matcher matcher = ASCII_TOKEN.matcher(normalized);
		while (matcher.find()) {
			result.add(matcher.group());
		}
		List<Integer> cjk = normalized.codePoints().filter(this::isCjk).boxed().toList();
		if (cjk.size() == 1) {
			result.add(new String(Character.toChars(cjk.get(0))));
		}
		for (int index = 0; index + 1 < cjk.size(); index++) {
			result
				.add(new String(Character.toChars(cjk.get(index))) + new String(Character.toChars(cjk.get(index + 1))));
		}
		return result;
	}

	private boolean isCjk(int codePoint) {
		Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
		return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
				|| script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
	}

}
