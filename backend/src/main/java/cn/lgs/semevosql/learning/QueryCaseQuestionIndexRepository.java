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

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** SQL-ranked channels return bounded references, never the entire case corpus. */
final class QueryCaseQuestionIndexRepository {
    static final String TOKENIZER_VERSION = "cjk-unigram-bigram-ascii-v1";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    QueryCaseQuestionIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    // Authoritative status, scope and cheap quality checks run BEFORE either channel's LIMIT.
    // The existing complete-request and asset validation still runs on the fused shortlist.
    static final String ELIGIBLE = """
        q.project_id=:project AND q.project_version_id=:version AND q.catalog_hash=:catalog
        AND q.status='APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
        AND d.source_hash=qw_query_case_source_hash(q) AND d.tokenizer_version=:tokenizer
        AND (q.conversation_independent OR q.context_hash=:context)
        AND q.typed_ir_json IS NOT NULL AND q.quality_proof_json IS NOT NULL
        AND EXISTS (SELECT 1 FROM qw_query_run r WHERE r.run_id=q.run_id AND r.status='SUCCEEDED')
        AND NOT EXISTS (SELECT 1 FROM qw_feedback f WHERE f.episode_id=q.episode_id AND (f.rating < 3 OR f.adopted=FALSE))
        AND NOT EXISTS (SELECT 1 FROM qw_query_case_binding_dependency b WHERE b.query_example_id=q.id
            AND (b.binding_scope IN ('QUERY','PROJECT_PENDING')
              OR (b.binding_scope='USER' AND (CAST(:principal AS text) IS NULL OR b.principal_id IS DISTINCT FROM :principal))))
        AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(
            NULLIF(q.typed_ir_json->'payload'->'bindingDependencies','null'::jsonb),
            NULLIF(q.typed_ir_json->'bindingDependencies','null'::jsonb), '[]'::jsonb)) b
            WHERE COALESCE(b->>'scope',b->>'source') IN ('QUERY','PROJECT_PENDING')
               OR (COALESCE(b->>'scope',b->>'source')='USER' AND (CAST(:principal AS text) IS NULL OR b->>'principalId' IS DISTINCT FROM :principal)))
        AND EXISTS (SELECT 1 FROM qw_query_example_asset_ref a WHERE a.query_example_id=q.id)
        AND NOT EXISTS (SELECT 1 FROM qw_query_example_asset_ref a WHERE a.query_example_id=q.id
            AND (a.catalog_hash<>q.catalog_hash OR COALESCE(a.asset_fingerprint,'')=''))
        """;

    private MapSqlParameterSource scope(Scope s, int limit) {
        return new MapSqlParameterSource().addValue("project", s.projectId())
            .addValue("version", s.versionId()).addValue("catalog", s.catalogHash())
            .addValue("context", s.contextHash(), java.sql.Types.VARCHAR)
            .addValue("principal", s.principalId(), java.sql.Types.VARCHAR)
            .addValue("tokenizer", TOKENIZER_VERSION).addValue("limit", Math.max(1, Math.min(100, limit)));
    }

    static final String LEXICAL_SQL = """
        WITH input AS (SELECT to_tsquery('simple', :query) AS query), matches AS (
          SELECT q.id AS case_id, d.question_type, d.generation, d.source_hash,
                 ts_rank(d.search_vector, input.query) AS score,
                 (SELECT COUNT(*)::float8 FROM unnest(tsvector_to_array(d.search_vector)) term
                     WHERE term=ANY(CAST(:terms AS text[]))) / :termCount AS confidence,
                 row_number() OVER (PARTITION BY q.id ORDER BY ts_rank(d.search_vector,input.query) DESC,
                     d.question_type) AS text_rank
          FROM qw_query_case_question_index d JOIN qw_query_example q ON q.id=d.query_example_id
          CROSS JOIN input WHERE %s AND d.search_vector @@ input.query
        ) SELECT case_id, question_type, generation, source_hash, score, confidence
          FROM matches WHERE text_rank=1 ORDER BY score DESC, case_id LIMIT :limit
        """.formatted(ELIGIBLE);

    List<Hit> lexical(Scope scope, String question, int limit) {
        var terms = QueryCaseTextFeatures.queryTerms(question, 32).stream()
            .filter(t -> t.codePoints().allMatch(Character::isLetterOrDigit)).toList();
        if (terms.isEmpty()) return List.of();
        // Terms contain only letters/digits; the entire OR expression remains a bound value.
        var args = scope(scope, limit).addValue("query", String.join(" | ", terms))
            .addValue("terms", "{" + String.join(",", terms) + "}").addValue("termCount", (double) terms.size());
        return hits(named.queryForList(LEXICAL_SQL, args), "FTS");
    }

    List<Hit> vector(Scope scope, float[] vector, String model, String version, int limit) {
        String sql = """
            WITH matches AS (
              SELECT q.id AS case_id, d.question_type, d.generation, d.source_hash,
                     1-(d.embedding <=> CAST(:vector AS vector)) AS score,
                     row_number() OVER (PARTITION BY q.id ORDER BY d.embedding <=> CAST(:vector AS vector),
                         d.question_type) AS text_rank
              FROM qw_query_case_question_index d JOIN qw_query_example q ON q.id=d.query_example_id
              WHERE %s AND d.embedding IS NOT NULL AND d.embedding_model=:model
                AND d.embedding_version=:embeddingVersion AND d.embedding_dimension=:dimension
            ) SELECT case_id, question_type, generation, source_hash, score, score AS confidence
              FROM matches WHERE text_rank=1 AND score > 0 ORDER BY score DESC, case_id LIMIT :limit
            """.formatted(ELIGIBLE);
        return hits(named.queryForList(sql, scope(scope, limit).addValue("vector", vectorLiteral(vector))
            .addValue("model", model).addValue("embeddingVersion", version).addValue("dimension", vector.length)), "VECTOR");
    }

    private List<Hit> hits(List<Map<String,Object>> rows, String channel) {
        return rows.stream().map(r -> new Hit(r.get("case_id").toString(), r.get("question_type").toString(),
            ((Number)r.get("generation")).longValue(), r.get("source_hash").toString(), channel,
            ((Number)r.get("score")).doubleValue(), ((Number)r.get("confidence")).doubleValue())).toList();
    }

    static String vectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Empty embedding");
        double norm = 0;
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
            norm += (double)value * value;
            out.add(Float.toString(value));
        }
        if (norm == 0) throw new IllegalArgumentException("Zero embedding");
        return out.toString();
    }

    boolean current(Hit hit) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM qw_query_case_question_index d
            JOIN qw_query_example q ON q.id=d.query_example_id
            WHERE q.id=? AND d.question_type=? AND d.generation=? AND d.source_hash=?
              AND d.source_hash=qw_query_case_source_hash(q) AND q.status='APPROVED')
            """, Boolean.class, hit.caseId(), hit.questionType(), hit.generation(), hit.sourceHash()));
    }

    record Scope(Long projectId, Long versionId, String catalogHash, String contextHash, String principalId) { }
    record Hit(String caseId, String questionType, long generation, String sourceHash, String channel,
               double score, double confidence) { }
}
