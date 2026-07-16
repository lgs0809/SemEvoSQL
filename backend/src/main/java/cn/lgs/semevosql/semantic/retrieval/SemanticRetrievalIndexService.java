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

import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.common.json.CanonicalJson;
import java.util.ArrayList;
import java.util.Collections;
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

/** pgvector/HNSW lifecycle for Semantic Catalog retrieval documents. */
@Service
public class SemanticRetrievalIndexService {

	public static final String INDEX_SCOPE = "SEMANTIC_CATALOG";

	// Keep each remote/local model call bounded. Small committed batches make a long catalog
	// build resumable because staleDocuments() skips vectors persisted by earlier batches.
	private static final int EMBEDDING_BATCH_SIZE = 2;


	private static final int VECTOR_HNSW_MAX_DIMENSION = 2000;

	private static final int HALF_VECTOR_HNSW_MAX_DIMENSION = 4000;

	private static final Logger log = LoggerFactory.getLogger(SemanticRetrievalIndexService.class);

	private final JdbcTemplate jdbc;

	private final EmbeddingModel embeddingModel;

	private final EmbeddingModelIdentityProvider embeddingModelIdentityProvider;

    private final SemanticEmbeddingReuseRepository reuse;

	private EmbeddingIndexModelProvider indexModelProvider;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	public SemanticRetrievalIndexService(JdbcTemplate jdbc, Optional<EmbeddingModel> embeddingModel,
			Optional<EmbeddingModelIdentityProvider> embeddingModelIdentityProvider) {
		this.jdbc = jdbc;
		this.embeddingModel = embeddingModel.orElse(null);
		this.embeddingModelIdentityProvider = embeddingModelIdentityProvider.orElse(null);
        this.reuse = new SemanticEmbeddingReuseRepository(jdbc);
	}

    @org.springframework.beans.factory.annotation.Autowired
    public void setIndexModelProvider(Optional<EmbeddingIndexModelProvider> provider) {
        this.indexModelProvider = provider.orElse(null);
    }

    private EmbeddingModel indexModel() {
        return indexModelProvider == null ? embeddingModel : indexModelProvider.currentIndexEmbeddingModel();
    }

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public synchronized IndexingResult indexDocuments(List<SemanticRetrievalDocument> documents) {
		if (!embeddingConfigured() || documents == null || documents.isEmpty()) {
			return new IndexingResult(0, false);
		}
		ConfiguredIdentity identity = configuredIdentity();
		RegistryEntry registry = registry().orElse(null);
		if (registry != null) {
			assertCompatible(identity, registry);
			ensureHnswIndex(registry.dimension());
		}
		List<SemanticRetrievalDocument> stale = staleDocuments(documents, identity);
		if (stale.isEmpty()) {
			return new IndexingResult(0, registry != null);
		}
		int indexed = 0;
		try {
            var model = indexModel();
            var profile = model instanceof CertifiedEmbeddingModel certified
                ? certified.currentDocumentProfile().orElse(null) : null;
            if (!certifiedForConfigured(profile)) profile = null;
            var pending = new ArrayList<SemanticRetrievalDocument>();
            for (var document : stale) {
                requireCurrentIdentity(identity);
                if (reuse.reuse(document, identity, profile)) {
                    indexed++;
                    if (registry == null) {
                        register(identity, profile.dimension());
                        registry = registry().orElseThrow();
                        assertCompatible(identity, registry);
                        ensureHnswIndex(registry.dimension());
                    }
                } else pending.add(document);
            }
			for (int offset = 0; offset < pending.size(); offset += EMBEDDING_BATCH_SIZE) {
				List<SemanticRetrievalDocument> batch = pending.subList(offset,
						Math.min(pending.size(), offset + EMBEDDING_BATCH_SIZE));
				var encoded = EmbeddingModelSupport.embedCertifiedTexts(model,
						batch.stream().map(SemanticRetrievalDocument::semanticText).toList());
                List<float[]> vectors = encoded.vectors();
                requireCurrentIdentity(identity);
				if (vectors.size() != batch.size()) {
					throw new IllegalStateException("Embedding model returned " + vectors.size() + " vectors for "
							+ batch.size() + " Semantic Catalog documents");
				}
				if (registry == null) {
					int dimension = requireDimension(vectors.get(0));
					register(identity, dimension);
					registry = registry()
						.orElseThrow(() -> new IllegalStateException("Semantic embedding registry was not created"));
					assertCompatible(identity, registry);
					ensureHnswIndex(registry.dimension());
				}
				for (int index = 0; index < batch.size(); index++) {
					float[] vector = vectors.get(index);
					if (requireDimension(vector) != registry.dimension()) {
						throw new EmbeddingReindexRequiredException("Semantic Catalog embedding dimension changed from "
								+ registry.dimension() + " to " + vector.length + "; explicit reindex is required");
					}
                    if (upsertEmbedding(batch.get(index), identity, vector, encoded.identity(), index)) indexed++;
				}
			}
			// A document scope may change while the provider encodes. A fenced write must
			// remain retryable even when the provider returned a valid vector.
			return new IndexingResult(indexed, indexed == stale.size());
		}
		catch (EmbeddingReindexRequiredException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("Unable to build Semantic Catalog embeddings; authorized PostgreSQL FTS retrieval remains available", ex);
			return new IndexingResult(indexed, false);
		}
	}

	public Map<String, Double> vectorScores(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, int limit) {
		if (!embeddingConfigured() || projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(query) || limit <= 0) {
			return Map.of();
		}
		RegistryEntry registry = registry().orElse(null);
		if (registry == null) {
			return Map.of();
		}
		ConfiguredIdentity identity = configuredIdentity();
		try {
			assertCompatible(identity, registry);
		}
		catch (EmbeddingReindexRequiredException ex) {
			log.warn(
					"Semantic Catalog vector index requires explicit reindex; authorized PostgreSQL FTS retrieval remains available: {}",
					ex.getMessage());
			return Map.of();
		}
		float[] queryVector;
		try {
			List<float[]> vectors = EmbeddingModelSupport.embedTexts(embeddingModel, List.of(query));
			if (vectors.size() != 1 || vectors.get(0) == null || vectors.get(0).length == 0) {
				return Map.of();
			}
			queryVector = vectors.get(0);
		}
		catch (RuntimeException ex) {
			log.warn("Semantic query embedding is unavailable; authorized PostgreSQL FTS retrieval remains available", ex);
			return Map.of();
		}
        if (!Objects.equals(identity, configuredIdentity())) return Map.of();
		if (queryVector.length != registry.dimension()) {
			log.warn(
					"Semantic query embedding dimension is {} but active Semantic Catalog index dimension is {}; "
							+ "explicit reindex is required; authorized PostgreSQL FTS retrieval remains available",
					queryVector.length, registry.dimension());
			return Map.of();
		}
		return queryVector(projectId, projectVersionId, catalogHash, queryVector, identity, registry, scope, limit);
	}

    /** Compatibility for internal callers; HTTP requests use durable maintenance instead. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized int reindexAll() {
        var identity=reindexIdentity();
        stageReindex(identity);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        return Objects.requireNonNull(transaction.execute(status->publishStagedReindex(identity)));
    }

    public ConfiguredIdentity reindexIdentity() {
        if(!embeddingConfigured())throw new IllegalStateException("Embedding model is not configured");
        return configuredIdentity();
    }

    /** Commit one immutable document at a time. A later retry reuses every completed vector. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized void stageReindex(ConfiguredIdentity identity) {
        requireCurrentIdentity(identity);
        var model = indexModel();
        var profile = model instanceof CertifiedEmbeddingModel certified
            ? certified.currentDocumentProfile().orElse(null) : null;
        if (!certifiedForConfigured(profile)) profile = null;
        List<SemanticRetrievalDocument> documents=jdbc.queryForList(
            "SELECT * FROM qw_semantic_retrieval_document ORDER BY project_version_id,document_type,asset_key")
            .stream().map(this::mapDocument).toList();
        for(var document:staleDocuments(documents,identity)) {
            requireCurrentIdentity(identity);
            if (reuse.reuse(document, identity, profile)) continue;
            var encoded=EmbeddingModelSupport.embedCertifiedTexts(model,List.of(document.semanticText()));
            var vectors=encoded.vectors();
            if(vectors.size()!=1)throw new IllegalStateException("Embedding response cardinality mismatch");
            var vector=vectors.get(0);requireDimension(vector);
            requireCurrentIdentity(identity);
            if(!upsertEmbedding(document,identity,vector,encoded.identity(),0))
                throw new IllegalStateException("Semantic document changed during reindex");
        }
    }

    /** Call inside the maintenance publication transaction, after locking its revision/lease. */
    public int publishStagedReindex(ConfiguredIdentity identity) {
        requireCurrentIdentity(identity);
        var documents=jdbc.queryForList("SELECT id FROM qw_semantic_retrieval_document ORDER BY id FOR SHARE");
        if(documents.isEmpty())return 0;
        var coverage=jdbc.queryForMap("""
            SELECT count(*) AS documents,count(e.document_id) AS vectors,
                count(DISTINCT e.dimension) AS dimensions,min(e.dimension) AS dimension
            FROM qw_semantic_retrieval_document d LEFT JOIN qw_semantic_retrieval_embedding e
                ON e.document_id=d.id AND e.content_hash=d.content_hash
                AND e.embedding_model=? AND e.embedding_version=?
            """,identity.model(),identity.version());
        int count=((Number)coverage.get("documents")).intValue();
        if(count!=((Number)coverage.get("vectors")).intValue()
                || ((Number)coverage.get("dimensions")).intValue()!=1)
            throw new IllegalStateException("Semantic reindex coverage is incomplete or has inconsistent dimensions");
        int dimension=((Number)coverage.get("dimension")).intValue();
        ensureHnswIndex(dimension);
        requireCurrentIdentity(identity);
        activateRegistry(identity,dimension);
        // Previous vectors remain available for audit and recovery; no business or historical data is deleted.
        return count;
    }

	public void assertConfiguredIndexCompatible() {
		if (!embeddingConfigured()) {
			return;
		}
		registry().ifPresent(value -> assertCompatible(configuredIdentity(), value));
	}

	/** Explicit Catalog -> retrieval-index consistency state used by release/activation gates. */
	public IndexReadiness readiness(Long projectId, Long projectVersionId, String catalogHash) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_FAILED, 0, 0, "missing catalog identity");
		}
		Integer documentCount = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				""", Integer.class, projectId, projectVersionId, catalogHash);
		int documents = documentCount == null ? 0 : documentCount;
		if (documents <= 0) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_PENDING, 0, 0, "retrieval documents are not built");
		}
		if (!embeddingConfigured()) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_READY, documents, 0,
					"lexical retrieval ready; no embedding model is configured");
		}
		try {
			ConfiguredIdentity identity = configuredIdentity();
			RegistryEntry registry = registry().orElse(null);
			if (registry == null) {
				return new IndexReadiness(IndexReadinessStatus.INDEX_PENDING, documents, 0,
						"embedding registry is not initialized");
			}
			assertCompatible(identity, registry);
			Integer vectorCount = jdbc.queryForObject("""
					SELECT COUNT(*)
					FROM qw_semantic_retrieval_document d
					JOIN qw_semantic_retrieval_embedding e ON e.document_id = d.id
					WHERE d.project_id = ? AND d.project_version_id = ? AND d.catalog_hash = ?
					  AND e.embedding_model = ? AND e.embedding_version = ?
					  AND e.dimension = ? AND e.content_hash = d.content_hash
					""", Integer.class, projectId, projectVersionId, catalogHash, identity.model(), identity.version(),
					registry.dimension());
			int vectors = vectorCount == null ? 0 : vectorCount;
			if (vectors != documents) {
				return new IndexReadiness(IndexReadinessStatus.INDEX_BUILDING, documents, vectors,
						"embedding coverage is incomplete");
			}
			return new IndexReadiness(IndexReadinessStatus.INDEX_READY, documents, vectors, "catalog index is aligned");
		}
		catch (RuntimeException ex) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_FAILED, documents, 0, ex.getMessage());
		}
	}

	public void assertReady(Long projectId, Long projectVersionId, String catalogHash) {
		IndexReadiness readiness = readiness(projectId, projectVersionId, catalogHash);
		if (readiness.status() != IndexReadinessStatus.INDEX_READY) {
			throw new SemanticIndexNotReadyException(projectId, projectVersionId, readiness);
		}
	}

	public enum IndexReadinessStatus {
		INDEX_PENDING,
		INDEX_BUILDING,
		INDEX_READY,
		INDEX_FAILED
	}

	public record IndexReadiness(IndexReadinessStatus status, int documentCount, int vectorCount, String detail) {
	}

	private List<SemanticRetrievalDocument> staleDocuments(List<SemanticRetrievalDocument> documents,
			ConfiguredIdentity identity) {
		List<String> ids = documents.stream().map(SemanticRetrievalDocument::id).distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>(ids);
		args.add(identity.model());
		args.add(identity.version());
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT document_id, content_hash
				FROM qw_semantic_retrieval_embedding
				WHERE document_id IN (%s) AND embedding_model = ? AND embedding_version = ?
				""".formatted(placeholders(ids.size())), args.toArray())) {
			hashes.put(Objects.toString(row.get("document_id")), Objects.toString(row.get("content_hash")));
		}
		return documents.stream()
			.filter(document -> !Objects.equals(hashes.get(document.id()), document.contentHash()))
			.toList();
	}

    private void requireCurrentIdentity(ConfiguredIdentity expected) {
        if (!Objects.equals(expected, configuredIdentity()))
            throw new EmbeddingReindexRequiredException("Embedding configuration changed during index generation");
    }

	private boolean upsertEmbedding(SemanticRetrievalDocument document, ConfiguredIdentity identity, float[] vector,
            AtomicEmbeddingIdentity certification, int responseIndex) {
        if (certification != null && !certifiedForConfigured(certification.profile())) certification = null;
        String proof = certification == null ? null : certification.proof(responseIndex);
        if (certification != null
                && !AtomicEmbeddingIdentity.matchesStored(proof, certification.profile(), document.semanticText(), vector))
            throw new IllegalStateException("Atomic embedding response differs from current document or configured model");
        var input = certification == null ? null : certification.inputs().get(responseIndex);
        return jdbc.update("""
                WITH current_document AS MATERIALIZED (
                    SELECT id FROM qw_semantic_retrieval_document
                    WHERE id = ? AND project_id = ? AND project_version_id = ?
                      AND catalog_hash = ? AND content_hash = ? AND source_fingerprint = ?
                    FOR UPDATE
                )
				INSERT INTO qw_semantic_retrieval_embedding
				(document_id, embedding_model, embedding_version, content_hash, dimension, embedding,
                    encoding_profile_sha256,encoding_identity,input_sha256,vector_sha256,reuse_source,update_time)
				SELECT id, ?, ?, ?, ?, ?::vector, ?, ?::jsonb, ?, ?, NULL, CURRENT_TIMESTAMP FROM current_document
				ON CONFLICT (document_id, embedding_model, embedding_version)
				DO UPDATE SET content_hash = EXCLUDED.content_hash,
				              dimension = EXCLUDED.dimension,
				              embedding = EXCLUDED.embedding,
                              encoding_profile_sha256 = EXCLUDED.encoding_profile_sha256,
                              encoding_identity = EXCLUDED.encoding_identity,
                              input_sha256 = EXCLUDED.input_sha256,
                              vector_sha256 = EXCLUDED.vector_sha256,
                              reuse_source = NULL,
				              update_time = CURRENT_TIMESTAMP
				""", document.id(), document.projectId(), document.projectVersionId(), document.catalogHash(),
                document.contentHash(), document.sourceFingerprint(), identity.model(), identity.version(),
                document.contentHash(), vector.length, vectorLiteral(vector),
                certification == null ? null : certification.profile().sha256(), proof,
                input == null ? null : input.inputSha256(), input == null ? null : input.vectorSha256()) == 1;
	}

    private boolean certifiedForConfigured(AtomicEmbeddingIdentity.Profile profile) {
        if (profile == null || embeddingModelIdentityProvider == null) return false;
        var current = embeddingModelIdentityProvider.currentEmbeddingIdentity().orElse(null);
        if (current == null) return false;
        // Registry model is provider:modelName; only the explicit registered modelName certifies actual weights.
        var dimensions = current.attributes().get("embeddingDimensions");
        return profile.model().equals(current.attributes().get("modelName"))
            && dimensions instanceof Number value && value.intValue() == profile.dimension();
    }

	private Map<String, Double> queryVector(Long projectId, Long projectVersionId, String catalogHash,
			float[] queryVector, ConfiguredIdentity identity, RegistryEntry registry,
			SemanticRetrievalScope requestedScope, int limit) {
		SemanticRetrievalScope scope = requestedScope == null ? SemanticRetrievalScope.all() : requestedScope;
		StringBuilder where = new StringBuilder("""
				WHERE d.project_id = ? AND d.project_version_id = ? AND d.catalog_hash = ?
				  AND e.embedding_model = ? AND e.embedding_version = ? AND e.dimension = ?
				  AND e.content_hash = d.content_hash
				""");
		List<Object> args = new ArrayList<>(List.of(projectId, projectVersionId, catalogHash, identity.model(),
				identity.version(), registry.dimension()));
		if (scope.datasourceId() != null) {
			where.append(" AND d.datasource_id = ?");
			args.add(scope.datasourceId());
		}
		if (!scope.modelCodes().isEmpty()) {
			where.append(" AND d.model_code IN (").append(placeholders(scope.modelCodes().size())).append(')');
			args.addAll(scope.modelCodes());
		}
		if (!scope.documentTypes().isEmpty()) {
			where.append(" AND d.document_type IN (").append(placeholders(scope.documentTypes().size())).append(')');
			args.addAll(scope.documentTypes().stream().map(Enum::name).toList());
		}
		if (!scope.assetKeys().isEmpty()) {
			where.append(" AND d.asset_key IN (").append(placeholders(scope.assetKeys().size())).append(')');
			args.addAll(scope.assetKeys());
		}
		String vector = vectorLiteral(queryVector);
		String distanceType = distanceType(registry.dimension());
		String sql = """
				SELECT d.id, 1 - ((e.embedding::%s) <=> (?::%s)) AS similarity
				FROM qw_semantic_retrieval_embedding e
				JOIN qw_semantic_retrieval_document d ON d.id = e.document_id
				%s
				ORDER BY (e.embedding::%s) <=> (?::%s)
				LIMIT ?
				""".formatted(distanceType, distanceType, where, distanceType, distanceType);
		List<Object> queryArgs = new ArrayList<>();
		queryArgs.add(vector);
		queryArgs.addAll(args);
		queryArgs.add(vector);
		queryArgs.add(Math.min(Math.max(limit, 1), 500));
		Map<String, Double> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(sql, queryArgs.toArray())) {
			if (row.get("similarity") instanceof Number number) {
				result.put(Objects.toString(row.get("id")), Math.max(0d, number.doubleValue()));
			}
		}
		return result;
	}

	private Optional<RegistryEntry> registry() {
		return jdbc.queryForList("""
				SELECT embedding_model, embedding_version, dimension, status
				FROM qw_embedding_index_registry WHERE index_scope = ?
				""", INDEX_SCOPE)
			.stream()
			.findFirst()
			.map(row -> new RegistryEntry(Objects.toString(row.get("embedding_model")),
					Objects.toString(row.get("embedding_version")), ((Number) row.get("dimension")).intValue(),
					Objects.toString(row.get("status"))));
	}

	private void register(ConfiguredIdentity identity, int dimension) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO NOTHING
				""", INDEX_SCOPE, identity.model(), identity.version(), dimension);
	}

	private void activateRegistry(ConfiguredIdentity identity, int dimension) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO UPDATE
				SET embedding_model = EXCLUDED.embedding_model,
				    embedding_version = EXCLUDED.embedding_version,
				    dimension = EXCLUDED.dimension,
				    status = 'ACTIVE',
				    active_since = CURRENT_TIMESTAMP,
				    update_time = CURRENT_TIMESTAMP
				""", INDEX_SCOPE, identity.model(), identity.version(), dimension);
	}

	private void assertCompatible(ConfiguredIdentity identity, RegistryEntry registry) {
		if (!"ACTIVE".equals(registry.status()) || !Objects.equals(identity.model(), registry.model())
				|| !Objects.equals(identity.version(), registry.version())) {
			throw new EmbeddingReindexRequiredException(
					"Active Semantic Catalog embedding index differs from configured embedding model; explicit reindex is required");
		}
	}

	private void ensureHnswIndex(int dimension) {
		if (dimension <= 0) {
			throw new IllegalArgumentException("Embedding dimension must be positive");
		}
		if (dimension > HALF_VECTOR_HNSW_MAX_DIMENSION) {
			log.warn("Semantic Catalog embedding dimension {} exceeds pgvector HNSW limits; vector recall remains exact-scan capable without HNSW",
					dimension);
			return;
		}
		String castType = distanceType(dimension);
		String operatorClass = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector_cosine_ops" : "halfvec_cosine_ops";
		jdbc.execute("CREATE INDEX IF NOT EXISTS idx_qw_semantic_embedding_hnsw_" + dimension + " "
				+ "ON qw_semantic_retrieval_embedding USING hnsw ((embedding::" + castType + ") " + operatorClass + ") WHERE dimension=" + dimension);
	}

	private String distanceType(int dimension) {
		String type = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector" : "halfvec";
		return type + "(" + dimension + ")";
	}

	private ConfiguredIdentity configuredIdentity() {
        if (embeddingModelIdentityProvider != null) {
            var configured = embeddingModelIdentityProvider.currentEmbeddingIdentity().orElse(null);
            if (configured != null) {
                var identity = EmbeddingEncodingIdentity.configured(configured.model(), configured.attributes());
                return new ConfiguredIdentity(truncate(identity.model(), 255), identity.version());
            }
        }
        // Unconfigured test/custom implementations have no external model identity provider.
        String implementation = embeddingModel.getClass().getName();
        return new ConfiguredIdentity(truncate(implementation, 255), canonicalJson.hash(Map.of("implementation", implementation)));
    }

	private boolean embeddingConfigured() {
		if (embeddingModel == null) {
			return false;
		}
		return embeddingModelIdentityProvider == null
				|| embeddingModelIdentityProvider.currentEmbeddingIdentity().isPresent();
	}

	private int requireDimension(float[] vector) {
		if (vector == null || vector.length == 0) {
			throw new IllegalStateException("Embedding model did not return a usable vector");
		}
		return vector.length;
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

	private SemanticRetrievalDocument mapDocument(Map<String, Object> row) {
		return new SemanticRetrievalDocument(Objects.toString(row.get("id")), number(row.get("project_id")),
				number(row.get("project_version_id")), Objects.toString(row.get("catalog_hash")),
				SemanticRetrievalDocument.DocumentType.valueOf(Objects.toString(row.get("document_type"))),
				Objects.toString(row.get("asset_type")), Objects.toString(row.get("asset_key")),
				row.get("datasource_id") instanceof Number value ? value.intValue() : null,
				Objects.toString(row.get("model_code")), Objects.toString(row.get("physical_table")),
				Objects.toString(row.get("lexical_text"), ""), Objects.toString(row.get("semantic_text"), ""),
				Objects.toString(row.get("source_fingerprint")), Objects.toString(row.get("content_hash")),
				Objects.toString(row.get("generator_model"), ""), Objects.toString(row.get("generator_version"), ""),
				Objects.toString(row.get("generation_status")));
	}

	private Long number(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	private String placeholders(int count) {
		return String.join(",", Collections.nCopies(count, "?"));
	}

	private String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	public record IndexingResult(int indexedDocuments, boolean vectorAvailable) {
	}


	public record ConfiguredIdentity(String model, String version) {
	}

	private record RegistryEntry(String model, String version, int dimension, String status) {
	}

	public static class EmbeddingReindexRequiredException extends IllegalStateException {

		public EmbeddingReindexRequiredException(String message) {
			super(message);
		}

	}

}
