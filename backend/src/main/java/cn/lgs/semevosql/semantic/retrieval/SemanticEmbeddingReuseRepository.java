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

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Copies a certified exact earlier published vector through the normal target document fence. */
final class SemanticEmbeddingReuseRepository {
    private final JdbcTemplate jdbc;
    SemanticEmbeddingReuseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean reuse(SemanticRetrievalDocument target, SemanticRetrievalIndexService.ConfiguredIdentity configured,
            AtomicEmbeddingIdentity.Profile profile) {
        if (profile == null) return false;
        var rows = jdbc.queryForList("""
            SELECT s.id,s.project_version_id,s.catalog_hash,e.embedding::text AS vector,
                e.encoding_identity::text AS proof,e.vector_sha256
            FROM qw_semantic_retrieval_document s
            JOIN qw_project_version v ON v.id=s.project_version_id AND v.project_id=s.project_id
            JOIN qw_semantic_retrieval_embedding e ON e.document_id=s.id AND e.content_hash=s.content_hash
            WHERE s.project_id=? AND s.project_version_id<? AND v.status='PUBLISHED'
                AND v.analysis_status='COMPLETED' AND v.catalog_hash=s.catalog_hash
                AND s.semantic_text=? AND s.content_hash=? AND s.source_fingerprint=?
                AND s.document_type=? AND s.asset_type=? AND s.asset_key=?
                AND s.datasource_id IS NOT DISTINCT FROM ? AND s.model_code=? AND s.physical_table=?
                AND s.generator_model IS NOT DISTINCT FROM ? AND s.generator_version IS NOT DISTINCT FROM ?
                AND s.generation_status=? AND e.embedding_model=? AND e.embedding_version=? AND e.dimension=?
                AND e.encoding_profile_sha256=? AND e.input_sha256=? AND e.encoding_identity IS NOT NULL
            ORDER BY s.project_version_id DESC,s.id LIMIT 1
            """, target.projectId(), target.projectVersionId(), target.semanticText(), target.contentHash(),
            target.sourceFingerprint(), target.documentType().name(), target.assetType(), target.assetKey(),
            target.datasourceId(), target.modelCode(), target.physicalTable(), target.generatorModel(),
            target.generatorVersion(), target.generationStatus(), configured.model(), configured.version(),
            profile.dimension(), profile.sha256(), AtomicEmbeddingIdentity.textSha(target.semanticText()));
        if (rows.isEmpty()) return false;
        var row = rows.get(0);
        String literal = Objects.toString(row.get("vector"));
        float[] vector;
        try {
            var values = literal.substring(1, literal.length() - 1).split(",");
            vector = new float[values.length];
            for (int i = 0; i < values.length; i++) vector[i] = Float.parseFloat(values[i]);
        } catch (RuntimeException malformed) { return false; }
        String proof = Objects.toString(row.get("proof"));
        if (!AtomicEmbeddingIdentity.matchesStored(proof, profile, target.semanticText(), vector)
                || !AtomicEmbeddingIdentity.vectorSha(vector).equals(Objects.toString(row.get("vector_sha256"))))
            return false;
        // Source and target are rechecked and locked in this one statement. Copy only the source generation
        // just validated above; a changed source, publication, vector or target becomes a normal cache miss.
        return jdbc.update("""
            WITH current_target AS MATERIALIZED (
                SELECT id FROM qw_semantic_retrieval_document
                WHERE id=? AND project_id=? AND project_version_id=? AND catalog_hash=?
                    AND content_hash=? AND source_fingerprint=? AND semantic_text=?
                    AND document_type=? AND asset_type=? AND asset_key=?
                    AND datasource_id IS NOT DISTINCT FROM ? AND model_code=? AND physical_table=?
                    AND generator_model IS NOT DISTINCT FROM ? AND generator_version IS NOT DISTINCT FROM ?
                    AND generation_status=? FOR UPDATE
            ), certified_source AS MATERIALIZED (
                SELECT s.id,s.project_version_id,s.catalog_hash,e.*
                FROM qw_semantic_retrieval_document s
                JOIN qw_project_version v ON v.id=s.project_version_id AND v.project_id=s.project_id
                JOIN qw_semantic_retrieval_embedding e ON e.document_id=s.id AND e.content_hash=s.content_hash
                WHERE s.id=? AND s.project_id=? AND s.project_version_id=? AND s.catalog_hash=?
                    AND v.status='PUBLISHED' AND v.analysis_status='COMPLETED' AND v.catalog_hash=s.catalog_hash
                    AND s.semantic_text=? AND s.content_hash=? AND s.source_fingerprint=?
                    AND s.document_type=? AND s.asset_type=? AND s.asset_key=?
                    AND s.datasource_id IS NOT DISTINCT FROM ? AND s.model_code=? AND s.physical_table=?
                    AND s.generator_model IS NOT DISTINCT FROM ? AND s.generator_version IS NOT DISTINCT FROM ?
                    AND s.generation_status=?
                    AND e.embedding_model=? AND e.embedding_version=? AND e.dimension=?
                    AND e.encoding_profile_sha256=? AND e.vector_sha256=? AND e.encoding_identity=?::jsonb
                    AND e.input_sha256=? AND e.embedding=?::vector
                FOR SHARE OF s,v,e
            )
            INSERT INTO qw_semantic_retrieval_embedding(document_id,embedding_model,embedding_version,content_hash,
                dimension,embedding,encoding_profile_sha256,encoding_identity,input_sha256,vector_sha256,reuse_source,update_time)
            SELECT t.id,s.embedding_model,s.embedding_version,s.content_hash,s.dimension,s.embedding,
                s.encoding_profile_sha256,s.encoding_identity,s.input_sha256,s.vector_sha256,
                jsonb_build_object('documentId',s.id,'projectVersionId',s.project_version_id,'catalogHash',s.catalog_hash,
                    'inputSha256',s.input_sha256,'vectorSha256',s.vector_sha256,'profileSha256',s.encoding_profile_sha256),
                CURRENT_TIMESTAMP FROM current_target t CROSS JOIN certified_source s
            ON CONFLICT(document_id,embedding_model,embedding_version) DO NOTHING
            """, target.id(), target.projectId(), target.projectVersionId(), target.catalogHash(), target.contentHash(),
            target.sourceFingerprint(), target.semanticText(), target.documentType().name(), target.assetType(),
            target.assetKey(), target.datasourceId(), target.modelCode(), target.physicalTable(), target.generatorModel(),
            target.generatorVersion(), target.generationStatus(), row.get("id"), target.projectId(), row.get("project_version_id"),
            row.get("catalog_hash"), target.semanticText(), target.contentHash(), target.sourceFingerprint(),
            target.documentType().name(), target.assetType(), target.assetKey(), target.datasourceId(), target.modelCode(),
            target.physicalTable(), target.generatorModel(), target.generatorVersion(), target.generationStatus(),
            configured.model(), configured.version(), profile.dimension(), profile.sha256(), row.get("vector_sha256"), proof,
            AtomicEmbeddingIdentity.textSha(target.semanticText()), literal) == 1;
    }
}
