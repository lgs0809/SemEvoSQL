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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.service.aimodelconfig.ModelConfigDataService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Persistent lexical and vector index lifecycle for approved Query Cases. */
@Service
public class QueryCaseRetrievalIndexService {

	private static final int EMBEDDING_BATCH_SIZE = 4;

	private static final int TERM_BATCH_SIZE = 1_000;

	private static final int VECTOR_HNSW_MAX_DIMENSION = 2_000;

	private static final int HALF_VECTOR_HNSW_MAX_DIMENSION = 4_000;

	private static final String TERM_INSERT_SQL = """
			INSERT INTO qw_query_case_term(query_example_id, term, term_frequency, document_length)
			VALUES (?, ?, ?, ?)
			ON CONFLICT (query_example_id, term) DO UPDATE
			SET term_frequency = EXCLUDED.term_frequency,
			    document_length = EXCLUDED.document_length
			""";

	private static final double BM25_K1 = 1.2d;

	private static final double BM25_B = 0.75d;

	private static final Logger log = LoggerFactory.getLogger(QueryCaseRetrievalIndexService.class);

	private final JdbcTemplate jdbc;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	private final Cache<CatalogKey, Boolean> indexedCatalogs = Caffeine.newBuilder()
		.maximumSize(10_000)
		.expireAfterWrite(Duration.ofMinutes(5))
		.build();

	private final Cache<CorpusKey, CorpusStatistics> corpusStatistics = Caffeine.newBuilder()
		.maximumSize(20_000)
		.expireAfterWrite(Duration.ofMinutes(5))
		.build();

	private final Cache<DocumentFrequencyKey, Map<String, Integer>> documentFrequencies = Caffeine.newBuilder()
		.maximumSize(50_000)
		.expireAfterWrite(Duration.ofMinutes(5))
		.build();

	private final Optional<EmbeddingModel> embeddingModel;

	private final Optional<ModelConfigDataService> modelConfigDataService;

	public QueryCaseRetrievalIndexService(JdbcTemplate jdbc, Optional<EmbeddingModel> embeddingModel,
			Optional<ModelConfigDataService> modelConfigDataService) {
		this.jdbc = jdbc;
		this.embeddingModel = embeddingModel;
		this.modelConfigDataService = modelConfigDataService;
	}

	@Transactional
	public void ensureCatalogIndexed(Long projectId, Long projectVersionId, String catalogHash) {
		CatalogKey key = new CatalogKey(projectId, projectVersionId, catalogHash);
		indexedCatalogs.get(key, ignored -> {
			List<Map<String, Object>> missing = jdbc.queryForList("""
					SELECT q.id, q.normalized_question
					FROM qw_query_example q
					LEFT JOIN qw_query_case_term t ON t.query_example_id = q.id
					WHERE q.project_id = ? AND q.project_version_id = ? AND q.catalog_hash = ?
					  AND q.status = 'APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
					  AND t.query_example_id IS NULL
					ORDER BY q.id
					""", projectId, projectVersionId, catalogHash);
			indexMissingTerms(missing);
			if (!missing.isEmpty()) {
				invalidateStatistics(key);
			}
			return Boolean.TRUE;
		});
	}

	@Transactional
	public void indexApprovedCase(String queryCaseId, String normalizedQuestion) {
		if (!StringUtils.hasText(queryCaseId) || !StringUtils.hasText(normalizedQuestion)) {
			return;
		}
		indexTerms(queryCaseId, normalizedQuestion);
		invalidateCatalogCaches(catalogKey(queryCaseId));
		// Query Case persistence and user feedback are authoritative facts. Do not make that synchronous transaction wait
		// for the optional embedding channel; vector coverage is rebuilt explicitly or lazily outside this write path.
	}

	@Transactional
	public void remove(String queryCaseId) {
		Optional<CatalogKey> key = catalogKey(queryCaseId);
		jdbc.update("DELETE FROM qw_query_case_term WHERE query_example_id = ?", queryCaseId);
		jdbc.update("DELETE FROM qw_query_case_embedding WHERE query_example_id = ?", queryCaseId);
		invalidateCatalogCaches(key);
	}

	public Map<String, Double> vectorScores(float[] queryVector, List<Map<String, Object>> rows) {
		if (embeddingModel.isEmpty() || queryVector == null || queryVector.length == 0 || rows == null
				|| rows.isEmpty()) {
			return Map.of();
		}
		EmbeddingDescriptor descriptor = descriptor(queryVector.length);
		try {
			ensureActiveEmbedding(descriptor);
		}
		catch (RuntimeException ex) {
			log.warn("Query Case vector recall is unavailable; continuing with lexical recall", ex);
			return Map.of();
		}
		Map<String, String> indexedHashes = readEmbeddingHashes(rows, descriptor);
		List<Map<String, Object>> staleOrMissing = rows.stream()
			.filter(row -> !Objects.equals(indexedHashes.get(Objects.toString(row.get("id"))), contentHash(row)))
			.toList();
		if (!staleOrMissing.isEmpty()) {
			persistEmbeddings(staleOrMissing, descriptor);
		}
		Map<String, String> currentHashes = staleOrMissing.isEmpty() ? indexedHashes
				: readEmbeddingHashes(rows, descriptor);
		List<Map<String, Object>> currentRows = rows.stream()
			.filter(row -> Objects.equals(currentHashes.get(Objects.toString(row.get("id"))), contentHash(row)))
			.toList();
		return currentRows.isEmpty() ? Map.of() : queryVectorScores(queryVector, currentRows, descriptor);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int reindexApprovedCases() {
		return reindexApprovedCases(null);
	}

	public QueryCaseIndexReadiness readiness(Long projectId) {
		long approvedCaseCount = countApprovedCases(projectId);
		List<Map<String, Object>> registry = jdbc.queryForList("""
				SELECT embedding_model, embedding_version, dimension, status
				FROM qw_embedding_index_registry
				WHERE index_scope = 'QUERY_CASE'
				""");
			if (registry.isEmpty()) {
				return new QueryCaseIndexReadiness("LEXICAL_ONLY", approvedCaseCount, 0, null,
						"语义向量索引尚未建立；当前仍可使用精确匹配和关键词召回");
		}
		Map<String, Object> active = registry.get(0);
		int dimension = ((Number) active.get("dimension")).intValue();
		EmbeddingDescriptor configured = descriptor(dimension);
		boolean identityAligned = "ACTIVE".equals(Objects.toString(active.get("status")))
				&& Objects.equals(configured.model(), Objects.toString(active.get("embedding_model")))
				&& Objects.equals(configured.version(), Objects.toString(active.get("embedding_version")));
		long vectorCount = countCurrentVectors(projectId, active, dimension);
			if (!identityAligned) {
				return new QueryCaseIndexReadiness("REINDEX_REQUIRED", approvedCaseCount, vectorCount, dimension,
						"当前向量模型与已有索引不一致；重建索引后可恢复完整的语义召回，现阶段仍保留精确匹配能力");
		}
			if (vectorCount < approvedCaseCount) {
				return new QueryCaseIndexReadiness("PARTIAL", approvedCaseCount, vectorCount, dimension,
						"部分已验证案例还没有生成当前模型对应的向量");
		}
		return new QueryCaseIndexReadiness("INDEX_READY", approvedCaseCount, vectorCount, dimension,
				"语义向量索引已与当前模型对齐");
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public synchronized int reindexApprovedCases(Long projectId) {
		if (embeddingModel.isEmpty()) {
			return 0;
		}
		String projectPredicate = projectId == null ? "" : " AND project_id = ?";
		Object[] queryArgs = projectId == null ? new Object[0] : new Object[] { projectId };
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, normalized_question
				FROM qw_query_example
				WHERE status = 'APPROVED' AND rebind_status IN ('VALID','REBOUND')
				%s
				ORDER BY id
				""".formatted(projectPredicate), queryArgs);
		if (rows.isEmpty()) {
			return 0;
		}
		List<PreparedEmbedding> prepared = prepareEmbeddings(rows);
		int dimension = prepared.get(0).vector().length;
		if (prepared.stream().anyMatch(value -> value.vector().length != dimension)) {
			throw new IllegalStateException("Query Case reindex produced inconsistent embedding dimensions");
		}
		EmbeddingDescriptor descriptor = descriptor(dimension);
		boolean preserveOtherProjects = projectId != null && activeEmbeddingCompatible(descriptor);
		markEmbeddingIndexBuilding(descriptor);
		jdbc.execute("DROP INDEX IF EXISTS idx_qw_query_case_embedding_hnsw");
		if (preserveOtherProjects) {
			deleteEmbeddings(rows);
		}
		else {
			jdbc.update("DELETE FROM qw_query_case_embedding");
		}
		for (PreparedEmbedding value : prepared) {
			upsertEmbedding(value.row(), descriptor, value.vector());
		}
		activateEmbeddingIndex(descriptor);
		ensureActiveEmbedding(descriptor);
		return prepared.size();
	}

	public Map<String, Double> bm25Scores(Long projectId, Long projectVersionId, String catalogHash, String contextHash,
			List<String> queryTerms, List<Map<String, Object>> rows) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash) || queryTerms == null
				|| queryTerms.isEmpty() || rows == null || rows.isEmpty()) {
			return Map.of();
		}
		List<String> terms = queryTerms.stream().filter(StringUtils::hasText).distinct().limit(64).toList();
		List<String> candidateIds = rows.stream()
			.map(row -> Objects.toString(row.get("id"), ""))
			.filter(StringUtils::hasText)
			.distinct()
			.toList();
		if (terms.isEmpty() || candidateIds.isEmpty()) {
			return Map.of();
		}

		CorpusStatistics corpus = corpusStatistics(projectId, projectVersionId, catalogHash, contextHash);
		if (corpus.documentCount() <= 0 || corpus.averageDocumentLength() <= 0) {
			return Map.of();
		}
		Map<String, Integer> documentFrequency = documentFrequency(projectId, projectVersionId, catalogHash,
				contextHash, terms);
		Map<String, Map<String, TermOccurrence>> occurrences = candidateOccurrences(candidateIds, terms);
		Map<String, Double> scores = new LinkedHashMap<>();
		for (String candidateId : candidateIds) {
			Map<String, TermOccurrence> candidateTerms = occurrences.getOrDefault(candidateId, Map.of());
			double score = 0;
			for (String term : terms) {
				TermOccurrence occurrence = candidateTerms.get(term);
				if (occurrence == null || occurrence.termFrequency() <= 0) {
					continue;
				}
				int df = documentFrequency.getOrDefault(term, 0);
				double idf = Math.log(1d + (corpus.documentCount() - df + 0.5d) / (df + 0.5d));
				double lengthNormalization = 1d - BM25_B
						+ BM25_B * occurrence.documentLength() / corpus.averageDocumentLength();
				double tf = occurrence.termFrequency();
				score += idf * (tf * (BM25_K1 + 1d)) / (tf + BM25_K1 * lengthNormalization);
			}
			scores.put(candidateId, score);
		}
		return scores;
	}

	private void indexTerms(String queryCaseId, String text) {
		jdbc.update("DELETE FROM qw_query_case_term WHERE query_example_id = ?", queryCaseId);
		List<Object[]> batch = new ArrayList<>();
		appendTermRows(batch, queryCaseId, text);
		flushTermBatch(batch);
	}

	private void indexMissingTerms(List<Map<String, Object>> missing) {
		if (missing == null || missing.isEmpty()) {
			return;
		}
		List<Object[]> batch = new ArrayList<>(Math.min(TERM_BATCH_SIZE, missing.size() * 4));
		for (Map<String, Object> row : missing) {
			appendTermRows(batch, Objects.toString(row.get("id")),
					Objects.toString(row.get("normalized_question"), ""));
			if (batch.size() >= TERM_BATCH_SIZE) {
				flushTermBatch(batch);
			}
		}
		flushTermBatch(batch);
	}

	private void appendTermRows(List<Object[]> batch, String queryCaseId, String text) {
		Map<String, Integer> frequencies = QueryCaseTextFeatures.termFrequency(text);
		int documentLength = frequencies.values().stream().mapToInt(Integer::intValue).sum();
		for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
			batch.add(new Object[] { queryCaseId, truncate(entry.getKey(), 64), entry.getValue(), documentLength });
		}
	}

	private void flushTermBatch(List<Object[]> batch) {
		if (batch.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(TERM_INSERT_SQL, batch);
		batch.clear();
	}

	private void persistEmbeddings(List<Map<String, Object>> rows, EmbeddingDescriptor descriptor) {
		for (int offset = 0; offset < rows.size(); offset += EMBEDDING_BATCH_SIZE) {
			List<Map<String, Object>> batch = rows.subList(offset,
					Math.min(rows.size(), offset + EMBEDDING_BATCH_SIZE));
			try {
				List<float[]> vectors = embedRows(batch);
				if (vectors.size() != batch.size()) {
					log.warn("Embedding model returned {} vectors for {} Query Cases", vectors.size(), batch.size());
					return;
				}
				for (int index = 0; index < batch.size(); index++) {
					float[] vector = vectors.get(index);
					if (vector == null || vector.length == 0) {
						continue;
					}
					if (vector.length != descriptor.dimension()) {
						throw new IllegalStateException("Embedding dimension changed from " + descriptor.dimension()
								+ " to " + vector.length + "; reindex is required");
					}
					upsertEmbedding(batch.get(index), descriptor, vector);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Unable to persist Query Case embeddings for model {}; lexical recall remains available",
						descriptor.model(), ex);
				return;
			}
		}
	}

	private List<PreparedEmbedding> prepareEmbeddings(List<Map<String, Object>> rows) {
		List<PreparedEmbedding> prepared = new ArrayList<>(rows.size());
		for (int offset = 0; offset < rows.size(); offset += EMBEDDING_BATCH_SIZE) {
			List<Map<String, Object>> batch = rows.subList(offset,
					Math.min(rows.size(), offset + EMBEDDING_BATCH_SIZE));
			List<float[]> vectors = embedRows(batch);
			if (vectors.size() != batch.size()) {
				throw new IllegalStateException("Embedding model returned " + vectors.size() + " vectors for " + batch.size()
						+ " Query Case reindex rows");
			}
			for (int index = 0; index < batch.size(); index++) {
				float[] vector = vectors.get(index);
				if (vector == null || vector.length == 0) {
					throw new IllegalStateException("Embedding model returned an empty Query Case vector");
				}
				prepared.add(new PreparedEmbedding(batch.get(index), vector));
			}
		}
		return List.copyOf(prepared);
	}

	private List<float[]> embedRows(List<Map<String, Object>> rows) {
		List<String> texts = rows.stream()
			.map(row -> Objects.toString(row.get("normalized_question"), ""))
			.toList();
		try {
			return EmbeddingModelSupport.embedTexts(embeddingModel.orElseThrow(), texts);
		}
		catch (RuntimeException failure) {
			if (rows.size() <= 1) {
				throw failure;
			}
			int middle = rows.size() / 2;
			List<float[]> left = embedRows(rows.subList(0, middle));
			List<float[]> right = embedRows(rows.subList(middle, rows.size()));
			List<float[]> combined = new ArrayList<>(left.size() + right.size());
			combined.addAll(left);
			combined.addAll(right);
			return List.copyOf(combined);
		}
	}

	private void upsertEmbedding(Map<String, Object> row, EmbeddingDescriptor descriptor, float[] vector) {
		String queryCaseId = Objects.toString(row.get("id"));
		String text = Objects.toString(row.get("normalized_question"), "");
		jdbc.update("""
				INSERT INTO qw_query_case_embedding
				(query_example_id, embedding_model, embedding_version, content_hash, dimension, embedding, update_time)
				VALUES (?, ?, ?, ?, ?, ?::vector, CURRENT_TIMESTAMP)
				ON CONFLICT (query_example_id, embedding_model, embedding_version)
				DO UPDATE SET content_hash = EXCLUDED.content_hash,
				              dimension = EXCLUDED.dimension,
				              embedding = EXCLUDED.embedding,
				              update_time = CURRENT_TIMESTAMP
				""", queryCaseId, descriptor.model(), descriptor.version(),
				canonicalJson.hash(QueryCaseTextFeatures.normalize(text)), vector.length, vectorLiteral(vector));
	}

	private CorpusStatistics corpusStatistics(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash) {
		CorpusKey key = new CorpusKey(projectId, projectVersionId, catalogHash, normalizeContext(contextHash));
		return corpusStatistics.get(key,
				ignored -> loadCorpusStatistics(projectId, projectVersionId, catalogHash, contextHash));
	}

	private CorpusStatistics loadCorpusStatistics(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash) {
		String contextPredicate = StringUtils.hasText(contextHash)
				? " AND (q.conversation_independent = TRUE OR (q.conversation_independent = FALSE AND q.context_hash = ?))"
				: " AND q.conversation_independent = TRUE";
		String sql = """
				SELECT COUNT(*) AS document_count, COALESCE(AVG(lengths.document_length), 0) AS average_document_length
				FROM qw_query_example q
				JOIN (
				  SELECT query_example_id, MAX(document_length) AS document_length
				  FROM qw_query_case_term
				  GROUP BY query_example_id
				) lengths ON lengths.query_example_id = q.id
				WHERE q.project_id = ? AND q.project_version_id = ? AND q.catalog_hash = ?
				  AND q.status = 'APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
				%s
				""".formatted(contextPredicate);
		List<Object> args = new ArrayList<>(List.of(projectId, projectVersionId, catalogHash));
		if (StringUtils.hasText(contextHash)) {
			args.add(contextHash);
		}
		Map<String, Object> row = jdbc.queryForMap(sql, args.toArray());
		return new CorpusStatistics(((Number) row.get("document_count")).longValue(),
				((Number) row.get("average_document_length")).doubleValue());
	}

	private Map<String, Integer> documentFrequency(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash, List<String> terms) {
		List<String> normalizedTerms = terms.stream().distinct().sorted().toList();
		DocumentFrequencyKey key = new DocumentFrequencyKey(projectId, projectVersionId, catalogHash,
				normalizeContext(contextHash), normalizedTerms);
		return documentFrequencies.get(key, ignored -> loadDocumentFrequency(projectId, projectVersionId, catalogHash,
				contextHash, normalizedTerms));
	}

	private Map<String, Integer> loadDocumentFrequency(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash, List<String> terms) {
		String contextPredicate = StringUtils.hasText(contextHash)
				? " AND (q.conversation_independent = TRUE OR (q.conversation_independent = FALSE AND q.context_hash = ?))"
				: " AND q.conversation_independent = TRUE";
		String sql = """
				SELECT t.term, COUNT(DISTINCT t.query_example_id) AS document_frequency
				FROM qw_query_case_term t
				JOIN qw_query_example q ON q.id = t.query_example_id
				WHERE t.term IN (%s)
				  AND q.project_id = ? AND q.project_version_id = ? AND q.catalog_hash = ?
				  AND q.status = 'APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
				%s
				GROUP BY t.term
				""".formatted(placeholders(terms.size()), contextPredicate);
		List<Object> args = new ArrayList<>(terms);
		args.add(projectId);
		args.add(projectVersionId);
		args.add(catalogHash);
		if (StringUtils.hasText(contextHash)) {
			args.add(contextHash);
		}
		Map<String, Integer> result = new HashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
			result.put(Objects.toString(row.get("term")), ((Number) row.get("document_frequency")).intValue());
		}
		return Map.copyOf(result);
	}

	private Map<String, Map<String, TermOccurrence>> candidateOccurrences(List<String> candidateIds,
			List<String> terms) {
		String sql = """
				SELECT query_example_id, term, term_frequency, document_length
				FROM qw_query_case_term
				WHERE query_example_id IN (%s) AND term IN (%s)
				""".formatted(placeholders(candidateIds.size()), placeholders(terms.size()));
		List<Object> args = new ArrayList<>(candidateIds);
		args.addAll(terms);
		Map<String, Map<String, TermOccurrence>> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
			String queryCaseId = Objects.toString(row.get("query_example_id"));
			String term = Objects.toString(row.get("term"));
			result.computeIfAbsent(queryCaseId, ignored -> new LinkedHashMap<>())
				.put(term, new TermOccurrence(((Number) row.get("term_frequency")).intValue(),
						((Number) row.get("document_length")).intValue()));
		}
		return result;
	}

	private Map<String, String> readEmbeddingHashes(List<Map<String, Object>> rows, EmbeddingDescriptor descriptor) {
		if (rows.isEmpty()) {
			return Map.of();
		}
		List<String> ids = rows.stream().map(row -> Objects.toString(row.get("id"))).distinct().toList();
		List<Object> args = new ArrayList<>(ids);
		args.add(descriptor.model());
		args.add(descriptor.version());
		Map<String, String> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT query_example_id, content_hash
				FROM qw_query_case_embedding
				WHERE query_example_id IN (%s)
				  AND embedding_model = ?
				  AND embedding_version = ?
				""".formatted(placeholders(ids.size())), args.toArray())) {
			result.put(Objects.toString(row.get("query_example_id")), Objects.toString(row.get("content_hash"), ""));
		}
		return result;
	}

	private Map<String, Double> queryVectorScores(float[] queryVector, List<Map<String, Object>> rows,
			EmbeddingDescriptor descriptor) {
		List<String> ids = rows.stream().map(row -> Objects.toString(row.get("id"))).distinct().toList();
		String vector = vectorLiteral(queryVector);
		String distanceType = distanceType(descriptor.dimension());
		String sql = """
				SELECT query_example_id,
				       1 - ((embedding::%s) <=> (?::%s)) AS similarity
				FROM qw_query_case_embedding
				WHERE query_example_id IN (%s)
				  AND embedding_model = ?
				  AND embedding_version = ?
				  AND dimension = ?
				ORDER BY (embedding::%s) <=> (?::%s)
				LIMIT ?
				""".formatted(distanceType, distanceType, placeholders(ids.size()), distanceType, distanceType);
		List<Object> args = new ArrayList<>();
		args.add(vector);
		args.addAll(ids);
		args.add(descriptor.model());
		args.add(descriptor.version());
		args.add(descriptor.dimension());
		args.add(vector);
		args.add(ids.size());
		Map<String, Double> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
			Object similarity = row.get("similarity");
			if (similarity instanceof Number number) {
				result.put(Objects.toString(row.get("query_example_id")), Math.max(0d, number.doubleValue()));
			}
		}
		return result;
	}

	private long countApprovedCases(Long projectId) {
		String projectPredicate = projectId == null ? "" : " AND project_id = ?";
		Object[] args = projectId == null ? new Object[0] : new Object[] { projectId };
		Long count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_query_example
				WHERE status = 'APPROVED' AND rebind_status IN ('VALID','REBOUND')
				%s
				""".formatted(projectPredicate), Long.class, args);
		return count == null ? 0 : count;
	}

	private long countCurrentVectors(Long projectId, Map<String, Object> registry, int dimension) {
		String projectPredicate = projectId == null ? "" : " AND q.project_id = ?";
		List<Object> args = new ArrayList<>();
		args.add(Objects.toString(registry.get("embedding_model")));
		args.add(Objects.toString(registry.get("embedding_version")));
		args.add(dimension);
		if (projectId != null) {
			args.add(projectId);
		}
		Long count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_query_case_embedding e
				JOIN qw_query_example q ON q.id = e.query_example_id
				WHERE q.status = 'APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
				  AND e.embedding_model = ? AND e.embedding_version = ? AND e.dimension = ?
				%s
				""".formatted(projectPredicate), Long.class, args.toArray());
		return count == null ? 0 : count;
	}

	private boolean activeEmbeddingCompatible(EmbeddingDescriptor descriptor) {
		return jdbc.queryForList("""
				SELECT embedding_model, embedding_version, dimension, status
				FROM qw_embedding_index_registry
				WHERE index_scope = 'QUERY_CASE'
				""")
			.stream()
			.findFirst()
			.map(active -> Objects.equals(descriptor.model(), Objects.toString(active.get("embedding_model")))
					&& Objects.equals(descriptor.version(), Objects.toString(active.get("embedding_version")))
					&& ((Number) active.get("dimension")).intValue() == descriptor.dimension()
					&& "ACTIVE".equals(Objects.toString(active.get("status"))))
			.orElse(false);
	}

	private void deleteEmbeddings(List<Map<String, Object>> rows) {
		List<String> ids = rows.stream().map(row -> Objects.toString(row.get("id"))).filter(StringUtils::hasText).toList();
		if (!ids.isEmpty()) {
			jdbc.update("DELETE FROM qw_query_case_embedding WHERE query_example_id IN (" + placeholders(ids.size()) + ")",
					ids.toArray());
		}
	}

	private void markEmbeddingIndexBuilding(EmbeddingDescriptor descriptor) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES ('QUERY_CASE', ?, ?, ?, 'BUILDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO UPDATE
				SET status = 'BUILDING', update_time = CURRENT_TIMESTAMP
				""", descriptor.model(), descriptor.version(), descriptor.dimension());
	}

	private void activateEmbeddingIndex(EmbeddingDescriptor descriptor) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES ('QUERY_CASE', ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO UPDATE
				SET embedding_model = EXCLUDED.embedding_model,
				    embedding_version = EXCLUDED.embedding_version,
				    dimension = EXCLUDED.dimension,
				    status = 'ACTIVE', active_since = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
				""", descriptor.model(), descriptor.version(), descriptor.dimension());
	}

	private void ensureActiveEmbedding(EmbeddingDescriptor descriptor) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT embedding_model, embedding_version, dimension, status
				FROM qw_embedding_index_registry
				WHERE index_scope = 'QUERY_CASE'
				""");
		if (rows.isEmpty()) {
			jdbc.update("""
					INSERT INTO qw_embedding_index_registry
					(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
					VALUES ('QUERY_CASE', ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					ON CONFLICT (index_scope) DO NOTHING
					""", descriptor.model(), descriptor.version(), descriptor.dimension());
			rows = jdbc.queryForList("""
					SELECT embedding_model, embedding_version, dimension, status
					FROM qw_embedding_index_registry
					WHERE index_scope = 'QUERY_CASE'
					""");
		}
		Map<String, Object> active = rows.get(0);
		boolean same = Objects.equals(descriptor.model(), Objects.toString(active.get("embedding_model")))
				&& Objects.equals(descriptor.version(), Objects.toString(active.get("embedding_version")))
				&& ((Number) active.get("dimension")).intValue() == descriptor.dimension()
				&& "ACTIVE".equals(Objects.toString(active.get("status")));
		if (!same) {
			throw new IllegalStateException(
					"Active Query Case embedding index differs from configured embedding model; "
							+ "run reindex before vector recall");
		}
		ensureHnswIndex(descriptor.dimension());
	}

	private void ensureHnswIndex(int dimension) {
		if (dimension <= 0) {
			throw new IllegalArgumentException("Embedding dimension must be positive");
		}
		if (dimension > HALF_VECTOR_HNSW_MAX_DIMENSION) {
			log.warn("Query Case embedding dimension {} exceeds pgvector HNSW limits; vector recall remains exact-scan capable without HNSW",
					dimension);
			return;
		}
		String castType = distanceType(dimension);
		String operatorClass = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector_cosine_ops" : "halfvec_cosine_ops";
		jdbc.execute("CREATE INDEX IF NOT EXISTS idx_qw_query_case_embedding_hnsw "
				+ "ON qw_query_case_embedding USING hnsw ((embedding::" + castType + ") " + operatorClass + ")");
	}

	private String distanceType(int dimension) {
		String type = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector" : "halfvec";
		return type + "(" + dimension + ")";
	}

	private EmbeddingDescriptor descriptor(int dimension) {
		String model = embeddingModel.map(value -> value.getClass().getName()).orElse("none");
		LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
		identity.put("implementation", model);
		ModelConfigDTO config = modelConfigDataService
			.map(service -> service.getActiveConfigByType(ModelType.EMBEDDING))
			.orElse(null);
		if (config != null) {
			model = Objects.toString(config.getProvider(), "") + ":" + Objects.toString(config.getModelName(), "");
			identity.put("provider", config.getProvider());
			identity.put("modelName", config.getModelName());
			identity.put("baseUrl", config.getBaseUrl());
			identity.put("embeddingsPath", config.getEmbeddingsPath());
		}
		return new EmbeddingDescriptor(truncate(model, 255), canonicalJson.hash(identity), dimension);
	}

	private String contentHash(Map<String, Object> row) {
		return canonicalJson
			.hash(QueryCaseTextFeatures.normalize(Objects.toString(row.get("normalized_question"), "")));
	}

	private String vectorLiteral(float[] vector) {
		StringBuilder builder = new StringBuilder(vector.length * 12 + 2).append('[');
		for (int index = 0; index < vector.length; index++) {
			if (index > 0) {
				builder.append(',');
			}
			builder.append(Float.toString(vector[index]));
		}
		return builder.append(']').toString();
	}

	private String placeholders(int count) {
		return String.join(",", java.util.Collections.nCopies(count, "?"));
	}

	private String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	private String normalizeContext(String contextHash) {
		return StringUtils.hasText(contextHash) ? contextHash.trim() : "";
	}

	private Optional<CatalogKey> catalogKey(String queryCaseId) {
		return jdbc.queryForList("""
				SELECT project_id, project_version_id, catalog_hash
				FROM qw_query_example
				WHERE id = ?
				""", queryCaseId)
			.stream()
			.findFirst()
			.map(row -> new CatalogKey(((Number) row.get("project_id")).longValue(),
					((Number) row.get("project_version_id")).longValue(), Objects.toString(row.get("catalog_hash"))));
	}

	private void invalidateCatalogCaches(Optional<CatalogKey> key) {
		if (key.isEmpty()) {
			indexedCatalogs.invalidateAll();
			corpusStatistics.invalidateAll();
			documentFrequencies.invalidateAll();
			return;
		}
		CatalogKey catalog = key.get();
		indexedCatalogs.invalidate(catalog);
		invalidateStatistics(catalog);
	}

	private void invalidateStatistics(CatalogKey catalog) {
		corpusStatistics.asMap()
			.keySet()
			.removeIf(key -> sameCatalog(catalog, key.projectId(), key.projectVersionId(), key.catalogHash()));
		documentFrequencies.asMap()
			.keySet()
			.removeIf(key -> sameCatalog(catalog, key.projectId(), key.projectVersionId(), key.catalogHash()));
	}

	private boolean sameCatalog(CatalogKey catalog, Long projectId, Long projectVersionId, String catalogHash) {
		return Objects.equals(catalog.projectId(), projectId)
				&& Objects.equals(catalog.projectVersionId(), projectVersionId)
				&& Objects.equals(catalog.catalogHash(), catalogHash);
	}

	public record QueryCaseIndexReadiness(String status, long approvedCaseCount, long vectorCount, Integer dimension,
			String detail) {
	}

	private record PreparedEmbedding(Map<String, Object> row, float[] vector) {
	}

	private record CatalogKey(Long projectId, Long projectVersionId, String catalogHash) {
	}

	private record CorpusKey(Long projectId, Long projectVersionId, String catalogHash, String contextHash) {
	}

	private record DocumentFrequencyKey(Long projectId, Long projectVersionId, String catalogHash, String contextHash,
			List<String> terms) {
	}

	private record CorpusStatistics(long documentCount, double averageDocumentLength) {
	}

	private record TermOccurrence(int termFrequency, int documentLength) {
	}

	private record EmbeddingDescriptor(String model, String version, int dimension) {
	}

}
