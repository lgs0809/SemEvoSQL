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

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/** SQL persistence boundary for Query Cases and rebind records. */
@Repository
public class QueryCaseRepository {

	private final JdbcTemplate jdbc;

	private final QueryCaseAssetReferenceRepository assetReferences;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public QueryCaseRepository(JdbcTemplate jdbc, QueryCaseAssetReferenceRepository assetReferences) {
		this.jdbc = jdbc;
		this.assetReferences = assetReferences;
	}

	public List<QueryCaseSummary> list(Long projectId, Long projectVersionId, String status, String rebindStatus,
			int limit) {
		if (projectId == null) {
			throw new IllegalArgumentException("projectId is required");
		}
		String normalizedStatus = normalizeStatus(status);
		String normalizedRebind = normalizeStatus(rebindStatus);
		int bounded = Math.max(1, Math.min(limit, 200));
		StringBuilder sql = new StringBuilder("SELECT * FROM qw_query_example WHERE project_id = ?");
		List<Object> args = new ArrayList<>();
		args.add(projectId);
		if (projectVersionId != null) {
			sql.append(" AND project_version_id = ?");
			args.add(projectVersionId);
		}
		if (normalizedStatus != null) {
			sql.append(" AND status = ?");
			args.add(normalizedStatus);
		}
		if (normalizedRebind != null) {
			sql.append(" AND rebind_status = ?");
			args.add(normalizedRebind);
		}
		sql.append(" ORDER BY create_time DESC LIMIT ?");
		args.add(bounded);
		return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::summary).toList();
	}

	public QueryCaseSummary get(Long projectId, String queryCaseId) {
		return summary(require(projectId, queryCaseId));
	}

	public QueryCaseDetail detail(Long projectId, String queryCaseId) {
		QueryCaseSummary summary = get(projectId, queryCaseId);
		List<QueryCaseRebindResult> rebinds = jdbc.queryForList("""
				SELECT * FROM qw_query_example_rebind WHERE source_example_id = ?
				ORDER BY create_time DESC
				""", queryCaseId).stream().map(this::rebind).toList();
		return new QueryCaseDetail(summary, assetReferences.findByCaseId(queryCaseId), rebinds,
				new QueryCaseQualityProof(
						parseObject(Objects.toString(summary.attributes().get("quality_proof_json"), ""))));
	}

	public Map<String, Object> require(String queryCaseId) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qw_query_example WHERE id = ?", queryCaseId);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Query example not found: " + queryCaseId);
		}
		return new LinkedHashMap<>(rows.get(0));
	}

	public Map<String, Object> require(Long projectId, String queryCaseId) {
		List<Map<String, Object>> rows = jdbc
			.queryForList("SELECT * FROM qw_query_example WHERE id = ? AND project_id = ?", queryCaseId, projectId);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Query example not found in project: " + queryCaseId);
		}
		return new LinkedHashMap<>(rows.get(0));
	}

	public Optional<Map<String, Object>> findByFingerprint(String fingerprint) {
		return jdbc.queryForList("SELECT * FROM qw_query_example WHERE fingerprint = ?", fingerprint)
			.stream()
			.findFirst()
			.map(LinkedHashMap::new);
	}

	public List<Map<String, Object>> approvedCandidates(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash) {
		String contextPredicate = StringUtils.hasText(contextHash)
				? " AND (conversation_independent = TRUE OR (conversation_independent = FALSE AND context_hash = ?))"
				: " AND conversation_independent = TRUE";
		String sql = """
				SELECT * FROM qw_query_example
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				  AND status = 'APPROVED' AND rebind_status IN ('VALID','REBOUND')
				%s
				ORDER BY reviewed_time DESC, id
				LIMIT 1000
				""".formatted(contextPredicate);
		List<Object> args = new ArrayList<>(List.of(projectId, projectVersionId, catalogHash));
		if (StringUtils.hasText(contextHash)) {
			args.add(contextHash);
		}
		return jdbc.queryForList(sql, args.toArray());
	}

	public List<Map<String, Object>> approvedCandidates(Long projectId, Long projectVersionId, String catalogHash,
			String contextHash, List<String> terms, int limit) {
		int bounded = Math.max(1, Math.min(limit, 1000));
		if (terms == null || terms.isEmpty()) {
			return List.of();
		}
		List<String> distinctTerms = terms.stream().filter(StringUtils::hasText).distinct().limit(64).toList();
		if (distinctTerms.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(distinctTerms.size(), "?"));
		String contextPredicate = StringUtils.hasText(contextHash)
				? " AND (q.conversation_independent = TRUE OR (q.conversation_independent = FALSE AND q.context_hash = ?))"
				: " AND q.conversation_independent = TRUE";
		String sql = """
				SELECT q.id, q.normalized_question, q.sql_text, q.datasource_id, q.catalog_hash, q.typed_ir_json,
				       q.intent_type, q.quality_proof_json, q.canonical_shape_hash, q.reviewed_time,
				       score.matched_terms, score.matched_tf
				FROM qw_query_example q
				JOIN (
				  SELECT query_example_id, COUNT(*) AS matched_terms, SUM(term_frequency) AS matched_tf
				  FROM qw_query_case_term
				  WHERE term IN (%s)
				  GROUP BY query_example_id
				) score ON score.query_example_id = q.id
				WHERE q.project_id = ? AND q.project_version_id = ? AND q.catalog_hash = ?
				  AND q.status = 'APPROVED' AND q.rebind_status IN ('VALID','REBOUND')
				%s
				ORDER BY score.matched_terms DESC, score.matched_tf DESC, q.reviewed_time DESC, q.id
				LIMIT ?
				""".formatted(placeholders, contextPredicate);
		List<Object> args = new ArrayList<>(distinctTerms);
		args.add(projectId);
		args.add(projectVersionId);
		args.add(catalogHash);
		if (StringUtils.hasText(contextHash)) {
			args.add(contextHash);
		}
		args.add(bounded);
		return jdbc.queryForList(sql, args.toArray());
	}

	public List<Map<String, Object>> approvedSourceCases(Long projectId, Long sourceVersionId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_query_example
				WHERE project_id = ? AND project_version_id = ? AND status = 'APPROVED'
				  AND rebind_status IN ('VALID','REBOUND')
				ORDER BY reviewed_time, create_time
				""", projectId, sourceVersionId);
	}

	public Optional<Map<String, Object>> existingRebind(String sourceId, Long targetVersionId, String catalogHash) {
		return jdbc.queryForList("""
				SELECT * FROM qw_query_example_rebind
				WHERE source_example_id = ? AND target_version_id = ? AND target_catalog_hash = ?
				""", sourceId, targetVersionId, catalogHash).stream().findFirst().map(LinkedHashMap::new);
	}

	/**
	 * Final Trusted Query gate applied after lexical/vector retrieval. Retrieval score alone can never
	 * make a historical SQL or plan reusable.
	 */
	public boolean trustedForReuse(Map<String, Object> row, String catalogHash) {
		if (row == null || !StringUtils.hasText(catalogHash)) {
			return false;
		}
		String id = Objects.toString(row.get("id"), "");
		if (!StringUtils.hasText(id)) {
			return false;
		}
		List<Map<String, Object>> authoritativeRows = jdbc.queryForList("""
				SELECT status, rebind_status, catalog_hash, typed_ir_json, quality_proof_json
				FROM qw_query_example WHERE id = ?
				""", id);
		if (authoritativeRows.size() != 1) {
			return false;
		}
		Map<String, Object> authoritative = authoritativeRows.get(0);
		return "APPROVED".equalsIgnoreCase(Objects.toString(authoritative.get("status"), ""))
				&& Set.of("VALID", "REBOUND").contains(Objects.toString(authoritative.get("rebind_status"), "").toUpperCase(Locale.ROOT))
				&& catalogHash.equals(Objects.toString(authoritative.get("catalog_hash"), "").trim())
				&& StringUtils.hasText(Objects.toString(authoritative.get("typed_ir_json"), ""))
				&& StringUtils.hasText(Objects.toString(authoritative.get("quality_proof_json"), ""))
				&& assetReferences.fingerprintEvidenceCompatible(id, catalogHash);
	}

	public List<Map<String, Object>> bindingDependencies(String queryCaseId) {
		if (!StringUtils.hasText(queryCaseId)) {
			return List.of();
		}
		return jdbc.queryForList("""
				SELECT binding_scope, binding_source, principal_id
				FROM qw_query_case_binding_dependency
				WHERE query_example_id = ?
				ORDER BY id
				""", queryCaseId);
	}

	public JdbcTemplate jdbc() {
		return jdbc;
	}

	private QueryCaseSummary summary(Map<String, Object> row) {
		return new QueryCaseSummary(Objects.toString(row.get("id"), null), number(row.get("project_id")),
				number(row.get("project_version_id")), Objects.toString(row.get("catalog_hash"), null),
				Objects.toString(row.get("status"), null), Objects.toString(row.get("rebind_status"), null),
				Objects.toString(row.get("normalized_question"), null), Objects.toString(row.get("intent_type"), null),
				longValue(row.get("recall_count")), longValue(row.get("adopted_count")),
				longValue(row.get("failed_after_recall_count")), row);
	}

	private QueryCaseRebindResult rebind(Map<String, Object> row) {
		return new QueryCaseRebindResult(Objects.toString(row.get("id"), null),
				Objects.toString(row.get("source_example_id"), null), number(row.get("target_version_id")),
				Objects.toString(row.get("target_catalog_hash"), null),
				Objects.toString(row.get("target_example_id"), null), Objects.toString(row.get("status"), null), row);
	}

	private Map<String, Object> parseObject(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return mapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			return Map.of("invalidJson", true);
		}
	}

	private static String normalizeStatus(String value) {
		return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
	}

	private static Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private static long longValue(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

}
