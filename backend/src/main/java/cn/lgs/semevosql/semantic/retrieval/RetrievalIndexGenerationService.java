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

import cn.lgs.semevosql.common.json.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Versioned retrieval-index generation with copy-on-write reuse across Semantic Versions.
 *
 * <p>Unchanged retrieval documents and their vectors are copied from the base Semantic Version.
 * The existing document builder then recomputes only missing/changed source fingerprints and its
 * existing stale-vector check avoids embedding calls for copied vectors.
 */
@Service
@RequiredArgsConstructor
public class RetrievalIndexGenerationService {

    private final JdbcTemplate jdbc;

    private final SemanticRetrievalDocumentBuildService buildService;

    private final CanonicalJson canonicalJson = new CanonicalJson();

    public GenerationResult buildForChangeSet(String changeSetId, Long targetSemanticVersionId,
            String semanticStateHash) {
        Map<String, Object> changeSet = one("SELECT * FROM qw_semantic_change_set WHERE id = ?", changeSetId);
        Long projectId = number(changeSet.get("project_id"));
        Long baseVersionId = number(changeSet.get("base_semantic_version_id"));
        if (!Objects.equals(number(changeSet.get("materialized_version_id")), targetSemanticVersionId)) {
            throw new IllegalStateException("Retrieval generation target is not the ChangeSet materialized version");
        }
        String status = Objects.toString(changeSet.get("status"), "");
        if (!Set.of("REPLAYING", "INDEXING", "READY", "ACTIVATING").contains(status)) {
            throw new IllegalStateException("Retrieval generation requires replay-approved ChangeSet; actual=" + status);
        }
        String embeddingConfigHash = modelConfigHash("EMBEDDING");
        String rerankConfigHash = modelConfigHash("RERANK");
        String generationId = generationId(projectId, targetSemanticVersionId, embeddingConfigHash);
        ExistingGeneration existing = existing(generationId);
        if (existing != null && "READY".equals(existing.status())) {
            return new GenerationResult(generationId, existing.affectedAssetCount(), existing.indexedAssetCount(), true);
        }
        Set<AffectedAsset> affected = affected(changeSetId);
        upsertBuilding(generationId, projectId, targetSemanticVersionId, semanticStateHash, embeddingConfigHash,
                rerankConfigHash, affected.size());
        int reused = copyUnchanged(projectId, baseVersionId, targetSemanticVersionId, semanticStateHash, affected);
        try {
            SemanticRetrievalDocumentBuildService.BuildResult built = buildService.build(projectId,
                    targetSemanticVersionId, semanticStateHash);
            jdbc.update("""
                    UPDATE qw_retrieval_index_generation
                    SET status = 'READY', indexed_asset_count = ?, ready_time = CURRENT_TIMESTAMP, error_message = NULL
                    WHERE id = ?
                    """, built.documents(), generationId);
            return new GenerationResult(generationId, affected.size(), built.documents(), reused > 0);
        }
        catch (RuntimeException ex) {
            jdbc.update("""
                    UPDATE qw_retrieval_index_generation
                    SET status = 'FAILED', error_message = ? WHERE id = ?
                    """, truncate(ex.getMessage(), 2048), generationId);
            throw ex;
        }
    }

    /** Embedding config changes rebuild retrieval state without creating a Semantic Version. */
    public GenerationResult rebuildCurrentVersion(Long projectId, Long semanticVersionId, String semanticStateHash) {
        String embeddingConfigHash = modelConfigHash("EMBEDDING");
        String rerankConfigHash = modelConfigHash("RERANK");
        String generationId = generationId(projectId, semanticVersionId, embeddingConfigHash);
        upsertBuilding(generationId, projectId, semanticVersionId, semanticStateHash, embeddingConfigHash,
                rerankConfigHash, 0);
        try {
            SemanticRetrievalDocumentBuildService.BuildResult built = buildService.build(projectId, semanticVersionId,
                    semanticStateHash);
            jdbc.update("""
                    UPDATE qw_retrieval_index_generation
                    SET status = 'READY', indexed_asset_count = ?, ready_time = CURRENT_TIMESTAMP, error_message = NULL
                    WHERE id = ?
                    """, built.documents(), generationId);
            return new GenerationResult(generationId, 0, built.documents(), false);
        }
        catch (RuntimeException ex) {
            jdbc.update("UPDATE qw_retrieval_index_generation SET status = 'FAILED', error_message = ? WHERE id = ?",
                    truncate(ex.getMessage(), 2048), generationId);
            throw ex;
        }
    }

    public String currentModelConfigRevision() {
        return canonicalJson.hash(Map.of("chat", modelConfigProjection("CHAT"), "embedding",
                modelConfigProjection("EMBEDDING"), "rerank", modelConfigProjection("RERANK")));
    }

    private int copyUnchanged(Long projectId, Long baseVersionId, Long targetVersionId, String targetHash,
            Set<AffectedAsset> affected) {
        List<Map<String, Object>> documents = jdbc.queryForList("""
                SELECT * FROM qw_semantic_retrieval_document
                WHERE project_id = ? AND project_version_id = ?
                ORDER BY document_type, asset_key
                """, projectId, baseVersionId);
        int copied = 0;
        for (Map<String, Object> document : documents) {
            if (requiresRebuild(document, affected)) {
                continue;
            }
            String sourceDocumentId = Objects.toString(document.get("id"));
            String newDocumentId = canonicalJson.hash(Map.of("projectVersionId", targetVersionId, "documentType",
                    Objects.toString(document.get("document_type")), "assetKey", Objects.toString(document.get("asset_key"))));
            int inserted = jdbc.update("""
                    INSERT INTO qw_semantic_retrieval_document
                    (id, project_id, project_version_id, catalog_hash, document_type, asset_type, asset_key,
                     datasource_id, model_code, physical_table, lexical_text, semantic_text, source_fingerprint,
                     content_hash, generator_model, generator_version, generation_status, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (project_version_id, document_type, asset_key) DO NOTHING
                    """, newDocumentId, projectId, targetVersionId, targetHash, document.get("document_type"),
                    document.get("asset_type"), document.get("asset_key"), document.get("datasource_id"),
                    document.get("model_code"), document.get("physical_table"), document.get("lexical_text"),
                    document.get("semantic_text"), document.get("source_fingerprint"), document.get("content_hash"),
                    document.get("generator_model"), document.get("generator_version"), document.get("generation_status"));
            if (inserted == 1) {
                jdbc.update("""
                        INSERT INTO qw_semantic_retrieval_embedding
                        (document_id, embedding_model, embedding_version, content_hash, dimension, embedding, update_time)
                        SELECT ?, embedding_model, embedding_version, content_hash, dimension, embedding, CURRENT_TIMESTAMP
                        FROM qw_semantic_retrieval_embedding WHERE document_id = ?
                        ON CONFLICT (document_id, embedding_model, embedding_version) DO NOTHING
                        """, newDocumentId, sourceDocumentId);
                copied++;
            }
        }
        return copied;
    }

    private boolean requiresRebuild(Map<String, Object> document, Set<AffectedAsset> affected) {
        String documentType = Objects.toString(document.get("document_type"), "");
        String assetKey = Objects.toString(document.get("asset_key"), "");
        String modelCode = Objects.toString(document.get("model_code"), "");
        for (AffectedAsset item : affected) {
            String type = item.assetType();
            if (type.equals(documentType) && item.assetKey().equals(assetKey)) {
                return true;
            }
            if ("COLUMN".equals(type) && "MODEL".equals(documentType)
                    && item.assetKey().startsWith(modelCode + ":")) {
                return true;
            }
            if (Set.of("RELATIONSHIP", "GRAIN", "RULE", "PROJECT_ALIAS", "MODEL").contains(type)
                    && "MODEL".equals(documentType)) {
                return true;
            }
        }
        return false;
    }

    private Set<AffectedAsset> affected(String changeSetId) {
        Set<AffectedAsset> result = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT asset_type, asset_key FROM qw_semantic_change_item WHERE change_set_id = ?
                """, changeSetId)) {
            result.add(new AffectedAsset(Objects.toString(row.get("asset_type"), "").toUpperCase(),
                    Objects.toString(row.get("asset_key"), "")));
        }
        return Set.copyOf(result);
    }

    private void upsertBuilding(String generationId, Long projectId, Long versionId, String semanticStateHash,
            String embeddingConfigHash, String rerankConfigHash, int affectedAssetCount) {
        String modelConfigRevision = currentModelConfigRevision();
        jdbc.update("""
                INSERT INTO qw_retrieval_index_generation
                (id, project_id, semantic_version_id, semantic_state_hash, embedding_config_hash, rerank_config_hash,
                 model_config_revision, status, build_mode, affected_asset_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?, ?)
                ON CONFLICT (project_id, semantic_version_id, embedding_config_hash) DO UPDATE
                SET semantic_state_hash = EXCLUDED.semantic_state_hash,
                    rerank_config_hash = EXCLUDED.rerank_config_hash,
                    model_config_revision = EXCLUDED.model_config_revision,
                    status = 'BUILDING', build_mode = EXCLUDED.build_mode,
                    affected_asset_count = EXCLUDED.affected_asset_count,
                    error_message = NULL, ready_time = NULL
                """, generationId, projectId, versionId, semanticStateHash, embeddingConfigHash, rerankConfigHash,
                modelConfigRevision, affectedAssetCount == 0 ? "FULL" : "INCREMENTAL", affectedAssetCount);
    }

    private ExistingGeneration existing(String generationId) {
        return jdbc.query("""
                SELECT status, affected_asset_count, indexed_asset_count
                FROM qw_retrieval_index_generation WHERE id = ?
                """, (rs, rowNum) -> new ExistingGeneration(rs.getString("status"), rs.getInt("affected_asset_count"),
                rs.getInt("indexed_asset_count")), generationId).stream().findFirst().orElse(null);
    }

    private String generationId(Long projectId, Long versionId, String embeddingConfigHash) {
        return canonicalJson.hash(Map.of("projectId", projectId, "semanticVersionId", versionId,
                "embeddingConfigHash", embeddingConfigHash));
    }

    private String modelConfigHash(String modelType) {
        return canonicalJson.hash(Map.of("configuration", modelConfigProjection(modelType),
            "documentBuilder", SemanticModelDocumentAssembler.VERSION));
    }

    private List<Map<String, Object>> modelConfigProjection(String modelType) {
        List<Map<String, Object>> projections = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT id, provider, base_url, model_name, model_type, completions_path, embeddings_path,
                       rerank_path, updated_time
                FROM model_config
                WHERE model_type = ? AND is_active = TRUE AND is_deleted = 0
                ORDER BY id
                """, modelType)) {
            Map<String, Object> safe = new LinkedHashMap<>();
            row.forEach((key, value) -> safe.put(key, value == null ? "" : value));
            projections.add(Map.copyOf(safe));
        }
        return List.copyOf(projections);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> values = jdbc.queryForList(sql, args);
        if (values.size() != 1) {
            throw new IllegalArgumentException("Expected one retrieval generation row");
        }
        return values.get(0);
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String truncate(String value, int max) {
        return !StringUtils.hasText(value) || value.length() <= max ? value : value.substring(0, max);
    }

    private record AffectedAsset(String assetType, String assetKey) {
    }

    private record ExistingGeneration(String status, int affectedAssetCount, int indexedAssetCount) {
    }

    public record GenerationResult(String generationId, int affectedAssetCount, int indexedAssetCount,
            boolean reusedBaseArtifacts) {
    }
}
