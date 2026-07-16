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
package cn.lgs.semevosql.semantic.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/** Bounded PostgreSQL FTS + pgvector channels, followed by RRF and configured reranking. */
@Service
public class SemanticHybridRetrievalService {

    private static final double RRF_K = 60d;
    @Value("${semevosql.retrieval.model-rerank-top-k:12}") private int modelRerankTopK = 12;
    @Value("${semevosql.retrieval.model-retain-top-k:6}") private int modelRetainTopK = 6;
    @Value("${semevosql.retrieval.channel-top-k:48}") private int channelTopK = 48;

	private static final Logger log = LoggerFactory.getLogger(SemanticHybridRetrievalService.class);

	private final SemanticRetrievalDocumentRepository documentRepository;

	private final SemanticRetrievalIndexService indexService;

	private final RerankModelProvider rerankModelProvider;

	public SemanticHybridRetrievalService(SemanticRetrievalDocumentRepository documentRepository,
			SemanticRetrievalIndexService indexService, RerankModelProvider rerankModelProvider) {
		this.documentRepository = documentRepository;
		this.indexService = indexService;
		this.rerankModelProvider = rerankModelProvider;
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, SemanticRetrievalScope.all(), RetrievalMode.HYBRID,
				limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			RetrievalMode mode, int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, SemanticRetrievalScope.all(), mode, limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, scope, RetrievalMode.HYBRID, limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, RetrievalMode mode, int limit) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(query) || limit <= 0) {
			return List.of();
		}
		SemanticRetrievalScope effectiveScope = scope == null ? SemanticRetrievalScope.all() : scope;
		RetrievalMode effectiveMode = mode == null ? RetrievalMode.HYBRID : mode;
        if (query.codePoints().noneMatch(Character::isLetterOrDigit)) return List.of();
        int channelLimit = Math.max(1, Math.min(500, channelTopK));
        Map<String, Double> lexicalScores = Map.of();
        RuntimeException lexicalFailure = null;
        if (effectiveMode.includesLexical()) {
            try {
                lexicalScores = documentRepository.lexicalScores(projectId, projectVersionId, catalogHash,
                    query, effectiveScope, channelLimit);
            } catch (RuntimeException unavailable) {
                lexicalFailure = unavailable;
                log.warn("Semantic FTS channel unavailable; attempting the vector channel", unavailable);
            }
        }
        Map<String, Double> vectorScores = Map.of();
        RuntimeException vectorFailure = null;
        if (effectiveMode.includesVector()) {
            try {
                vectorScores = indexService.vectorScores(projectId, projectVersionId, catalogHash,
                    query, effectiveScope, channelLimit);
            } catch (RuntimeException unavailable) {
                vectorFailure = unavailable;
                log.warn("Semantic vector channel unavailable; continuing with FTS", unavailable);
            }
        }
        // A failed catalog read must not be presented as an empty definition namespace.
        if (lexicalFailure != null && vectorScores.isEmpty()) throw lexicalFailure;
        if (vectorFailure != null && lexicalScores.isEmpty()) throw vectorFailure;
        Map<String, Integer> lexicalRanks = ranks(lexicalScores);
        Map<String, Integer> vectorRanks = ranks(vectorScores);
        Set<String> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(lexicalRanks.keySet());
        candidateIds.addAll(vectorRanks.keySet());
        if (candidateIds.isEmpty()) return List.of();
        List<SemanticRetrievalDocument> documents = documentRepository.findByIds(projectId, projectVersionId,
            catalogHash, effectiveScope, candidateIds);
        Map<String, SemanticRetrievalDocument> byId = documents.stream()
            .collect(Collectors.toMap(SemanticRetrievalDocument::id, value -> value, (left, right) -> left));
		List<RetrievalHit> hits = new ArrayList<>();
		for (String id : candidateIds) {
			SemanticRetrievalDocument document = byId.get(id);
			if (document == null) {
				continue;
			}
			LinkedHashMap<String, Integer> channelRanks = new LinkedHashMap<>();
			LinkedHashMap<String, Double> channelScores = new LinkedHashMap<>();
			double rrf = 0d;
            if (lexicalRanks.containsKey(id)) {
                channelRanks.put("FTS", lexicalRanks.get(id));
                channelScores.put("FTS", lexicalScores.get(id));
                rrf += 1d / (RRF_K + lexicalRanks.get(id));
            }
			if (vectorRanks.containsKey(id)) {
				channelRanks.put("VECTOR", vectorRanks.get(id));
				channelScores.put("VECTOR", vectorScores.get(id));
				rrf += 1d / (RRF_K + vectorRanks.get(id));
			}
			hits.add(new RetrievalHit(document.documentType(), document.assetType(), document.assetKey(),
					document.modelCode(), document.physicalTable(), rrf, Map.copyOf(channelRanks),
					Map.copyOf(channelScores)));
		}
		List<RetrievalHit> rrfRanked = hits.stream()
			.sorted(Comparator.comparingDouble(RetrievalHit::score)
				.reversed()
				.thenComparing(RetrievalHit::modelCode)
				.thenComparing(RetrievalHit::assetKey))
			.toList();
		List<RetrievalHit> withRrfEvidence = addRrfEvidence(rrfRanked);
		return rerank(query, withRrfEvidence, documents, Math.min(limit, Math.max(1, Math.min(100, modelRetainTopK))));
	}

	private List<RetrievalHit> addRrfEvidence(List<RetrievalHit> ranked) {
		List<RetrievalHit> output = new ArrayList<>(ranked.size());
		for (int index = 0; index < ranked.size(); index++) {
			RetrievalHit hit = ranked.get(index);
			LinkedHashMap<String, Integer> ranks = new LinkedHashMap<>(hit.channelRanks());
			LinkedHashMap<String, Double> scores = new LinkedHashMap<>(hit.channelScores());
			ranks.put("RRF", index + 1);
			scores.put("RRF", hit.score());
			output.add(new RetrievalHit(hit.documentType(), hit.assetType(), hit.assetKey(), hit.modelCode(),
					hit.physicalTable(), hit.score(), Map.copyOf(ranks), Map.copyOf(scores)));
		}
		return List.copyOf(output);
	}

	private List<RetrievalHit> rerank(String query, List<RetrievalHit> rrfRanked,
			List<SemanticRetrievalDocument> documents, int limit) {
		if (rrfRanked.isEmpty()) {
			return List.of();
		}
		RerankModel reranker;
		try {
			reranker = rerankModelProvider.currentRerankModel();
		}
		catch (RuntimeException unavailable) {
			return rerankFallback(rrfRanked, limit, unavailable);
		}
		int candidateLimit = Math.min(rrfRanked.size(), Math.max(1, Math.min(100, modelRerankTopK)));
		List<RetrievalHit> candidates = rrfRanked.subList(0, candidateLimit);
		Map<String, SemanticRetrievalDocument> byAssetKey = documents.stream()
			.collect(Collectors.toMap(SemanticRetrievalDocument::assetKey, value -> value, (left, right) -> left));
		List<String> rerankDocuments = candidates.stream()
			.map(hit -> rerankText(byAssetKey.get(hit.assetKey())))
			.toList();
		List<RerankModel.RerankScore> scores;
		try {
			scores = reranker.rerank(query, rerankDocuments, Math.min(limit, candidates.size()));
		}
		catch (RuntimeException unavailable) {
			return rerankFallback(rrfRanked, limit, unavailable);
		}
		List<RerankModel.RerankScore> ordered = scores.stream()
			.filter(score -> score.index() >= 0 && score.index() < candidates.size())
			.sorted(Comparator.comparingDouble(RerankModel.RerankScore::score).reversed())
			.limit(limit)
			.toList();
		if (ordered.isEmpty()) {
			return rerankFallback(rrfRanked, limit, new IllegalStateException("Rerank model returned no usable scores."));
		}
		List<RetrievalHit> reranked = new ArrayList<>(limit);
		Set<Integer> usedIndexes = new LinkedHashSet<>();
		for (int rank = 0; rank < ordered.size(); rank++) {
			RerankModel.RerankScore score = ordered.get(rank);
			if (!usedIndexes.add(score.index())) {
				continue;
			}
			RetrievalHit hit = candidates.get(score.index());
			LinkedHashMap<String, Integer> ranks = new LinkedHashMap<>(hit.channelRanks());
			LinkedHashMap<String, Double> channelScores = new LinkedHashMap<>(hit.channelScores());
			ranks.put("RERANK", rank + 1);
			channelScores.put("RERANK", score.score());
			reranked.add(new RetrievalHit(hit.documentType(), hit.assetType(), hit.assetKey(), hit.modelCode(),
					hit.physicalTable(), score.score(), Map.copyOf(ranks), Map.copyOf(channelScores)));
		}
		if (reranked.isEmpty()) {
			return rerankFallback(rrfRanked, limit, new IllegalStateException("Rerank model returned no usable scores."));
		}
		if (reranked.size() >= limit || rrfRanked.size() <= candidateLimit) {
			return List.copyOf(reranked);
		}
		Set<String> selected = reranked.stream().map(this::hitIdentity)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		for (RetrievalHit hit : rrfRanked) {
			if (reranked.size() >= limit) {
				break;
			}
			if (selected.add(hitIdentity(hit))) {
				reranked.add(hit);
			}
		}
		return List.copyOf(reranked);
	}

	private List<RetrievalHit> rerankFallback(List<RetrievalHit> rrfRanked, int limit, RuntimeException failure) {
		log.warn("Rerank unavailable; continuing with governed RRF candidates: {}", failure.getMessage());
		return List.copyOf(rrfRanked.subList(0, Math.min(limit, rrfRanked.size())));
	}

	private String hitIdentity(RetrievalHit hit) {
		return hit.documentType() + "|" + hit.modelCode() + "|" + hit.assetKey();
	}

	private String rerankText(SemanticRetrievalDocument document) {
		if (document == null) {
			return "";
		}
		return StringUtils.hasText(document.semanticText()) ? document.semanticText() : document.lexicalText();
	}

	private Map<String, Integer> ranks(Map<String, Double> scores) {
		List<Map.Entry<String, Double>> sorted = scores.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
			.toList();
		Map<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < sorted.size(); index++) {
			result.put(sorted.get(index).getKey(), index + 1);
		}
		return result;
	}

	public record RetrievalHit(SemanticRetrievalDocument.DocumentType documentType, String assetType, String assetKey,
			String modelCode, String physicalTable, double score, Map<String, Integer> channelRanks,
			Map<String, Double> channelScores) {

		public Map<String, Double> channels() {
			return channelScores;
		}

		public Set<String> matchedAssetKeys() {
			return Set.of(assetKey);
		}
	}

    public enum RetrievalMode {
        FTS(true, false), VECTOR(false, true), HYBRID(true, true),
        /** Compatibility names now use only FTS; there is no in-memory exact/BM25 ranking channel. */
        @Deprecated EXACT(true, false), @Deprecated BM25(true, false);
        private final boolean lexical;
        private final boolean vector;
        RetrievalMode(boolean lexical, boolean vector) { this.lexical = lexical; this.vector = vector; }
        public boolean includesLexical() { return lexical; }
        public boolean includesVector() { return vector; }
    }

}
