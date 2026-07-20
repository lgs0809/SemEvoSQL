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
package cn.lgs.semevosql.clarification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project-wide business-language aliases pinned to a concrete Project Version. */
@Service
public class ProjectSemanticAliasService {

	private static final Set<String> PROJECT_ALIAS_TYPES = Set.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN");

	private final JdbcTemplate jdbc;

	private final SemanticBindingTargetValidator targetValidator;

	public ProjectSemanticAliasService(JdbcTemplate jdbc, SemanticBindingTargetValidator targetValidator) {
		this.jdbc = jdbc;
		this.targetValidator = targetValidator;
	}

	public List<ProjectSemanticAlias> applicable(Long projectId, Long projectVersionId, String query) {
		String normalizedQuery = normalizePhrase(query);
		if (projectId == null || projectVersionId == null || normalizedQuery.isBlank()) {
			return List.of();
		}
		return jdbc.query("""
				SELECT * FROM qw_project_semantic_alias
				WHERE project_id = ? AND project_version_id = ? AND status = 'ENABLED'
				ORDER BY LENGTH(normalized_phrase) DESC, id
				""", this::map, projectId, projectVersionId)
			.stream()
			.filter(value -> normalizedQuery.contains(value.normalizedPhrase()))
			.toList();
	}

	public Optional<ProjectSemanticAlias> find(Long projectId, Long projectVersionId, String rawPhrase) {
		String normalized = normalizePhrase(rawPhrase);
		if (projectId == null || projectVersionId == null || normalized.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query("""
				SELECT * FROM qw_project_semantic_alias
				WHERE project_id = ? AND project_version_id = ? AND normalized_phrase = ? AND status = 'ENABLED'
				""", this::map, projectId, projectVersionId, normalized).stream().findFirst();
	}

	@Transactional
	public ProjectSemanticAlias save(Long projectId, Long projectVersionId, String rawPhrase, String assetType,
			String assetKey, String businessLabel, String evidence) {
		String normalized = requiredNormalize(rawPhrase);
		String type = required(assetType, "assetType");
		if (!PROJECT_ALIAS_TYPES.contains(type)) {
			throw new IllegalArgumentException(
					"Project aliases are only for durable business-language bindings: " + type);
		}
		String key = required(assetKey, "assetKey");
		targetValidator.requireAsset(projectId, projectVersionId, type, key);
		String label = required(businessLabel, "businessLabel");
		jdbc.update("""
				INSERT INTO qw_project_semantic_alias
				(project_id, project_version_id, normalized_phrase, display_phrase, asset_type, asset_key,
				 business_label, evidence, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED')
				ON CONFLICT (project_version_id, normalized_phrase)
				DO UPDATE SET display_phrase = EXCLUDED.display_phrase, asset_type = EXCLUDED.asset_type,
				              asset_key = EXCLUDED.asset_key, business_label = EXCLUDED.business_label,
				              evidence = EXCLUDED.evidence, status = 'ENABLED', update_time = CURRENT_TIMESTAMP
				""", projectId, projectVersionId, normalized, rawPhrase.trim(), type, key, label, evidence);
		return find(projectId, projectVersionId, rawPhrase).orElseThrow();
	}

	@Transactional
	public void disable(Long projectId, Long projectVersionId, String rawPhrase) {
		jdbc.update("""
				UPDATE qw_project_semantic_alias
				SET status = 'DISABLED', update_time = CURRENT_TIMESTAMP
				WHERE project_id = ? AND project_version_id = ? AND normalized_phrase = ?
				""", projectId, projectVersionId, requiredNormalize(rawPhrase));
	}

	@Transactional
	public void cloneAliases(Long projectId, Long sourceVersionId, Long targetVersionId) {
		jdbc.update("""
				INSERT INTO qw_project_semantic_alias
				(project_id, project_version_id, normalized_phrase, display_phrase, asset_type, asset_key,
				 business_label, evidence, status, create_time, update_time)
				SELECT project_id, ?, normalized_phrase, display_phrase, asset_type, asset_key,
				       business_label, evidence, status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				FROM qw_project_semantic_alias
				WHERE project_id = ? AND project_version_id = ?
				ON CONFLICT (project_version_id, normalized_phrase) DO NOTHING
				""", targetVersionId, projectId, sourceVersionId);
	}

	private ProjectSemanticAlias map(ResultSet rs, int rowNum) throws SQLException {
		return new ProjectSemanticAlias(rs.getLong("id"), rs.getLong("project_id"), rs.getLong("project_version_id"),
				rs.getString("normalized_phrase"), rs.getString("display_phrase"), rs.getString("asset_type"),
				rs.getString("asset_key"), rs.getString("business_label"), rs.getString("evidence"),
				rs.getString("status"), time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")));
	}

	public static String normalizePhrase(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private static String requiredNormalize(String value) {
		String normalized = normalizePhrase(value);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("rawPhrase is required");
		}
		return normalized;
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	public record ProjectSemanticAlias(Long id, Long projectId, Long projectVersionId, String normalizedPhrase,
			String displayPhrase, String assetType, String assetKey, String businessLabel, String evidence,
			String status, LocalDateTime createTime, LocalDateTime updateTime) {
	}

}
