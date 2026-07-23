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
package cn.lgs.semevosql.trajectory;

import cn.lgs.semevosql.learning.QueryCaseGovernanceProperties;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Independent evidence and resolution-distribution statistics for semantic evolution. */
@Service
public class SemanticEvolutionEvidenceService {

	private static final String CONFLICT = "__CONFLICT__";

	private final JdbcTemplate jdbc;

	private final QueryCaseGovernanceProperties properties;

	public SemanticEvolutionEvidenceService(JdbcTemplate jdbc, QueryCaseGovernanceProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	public IndependentEvidence independentEvidence(String patternId, Map<String, Object> signal) {
		List<Map<String, Object>> rows = signalRows(patternId, signal);
		Set<String> conversations = new LinkedHashSet<>();
		Set<String> users = new LinkedHashSet<>();
		Set<String> roots = new LinkedHashSet<>();
		Set<String> windows = new LinkedHashSet<>();
		for (Map<String, Object> row : rows) {
			Set<String> rowRoots = rootEvidence(row);
			if (rowRoots.isEmpty()) {
				continue;
			}
			roots.addAll(rowRoots);
			String conversationId = text(row.get("conversation_id"));
			String episodeId = text(row.get("episode_id"));
			conversations.add(StringUtils.hasText(conversationId) ? conversationId : "EPISODE:" + episodeId);
			String createdBy = text(row.get("created_by"));
			if (authenticatedUser(createdBy)) {
				users.add(createdBy);
			}
			String window = timeWindow(row.get("create_time"));
			if (StringUtils.hasText(window)) {
				windows.add(window);
			}
		}
		return new IndependentEvidence(rows.size(), conversations.size(), users.isEmpty() ? null : users.size(),
				roots.size(), windows.size(), List.copyOf(conversations), List.copyOf(roots));
	}

	public boolean eligible(IndependentEvidence evidence) {
		return evidence.distinctConversationCount() >= properties.getEvolutionMinIndependentConversations()
				&& evidence.distinctRootEvidenceCount() >= properties.getEvolutionMinRootEvidence();
	}

	public MappingDistribution mappingDistribution(Long projectId, Long projectVersionId, String rawExpression,
			String assetType, String assetScope) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(rawExpression)
				|| !StringUtils.hasText(assetType)) {
			return MappingDistribution.empty();
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT d.id detour_id, d.asset_type, d.asset_key, d.evidence_json,
				       p.episode_id, p.run_id, p.create_time,
				       (SELECT MIN(m.conversation_id) FROM qw_project_message m
				        WHERE m.run_id = p.run_id AND m.role = 'USER') conversation_id,
				       (SELECT MIN(c.created_by) FROM qw_project_message m
				        JOIN qw_project_conversation c ON c.conversation_id = m.conversation_id
				        WHERE m.run_id = p.run_id AND m.role = 'USER') created_by
				FROM qw_detour_signal d
				JOIN qw_trajectory_path p ON p.id = d.path_id
				WHERE d.project_id = ? AND d.project_version_id = ?
				  AND d.root_cause = 'SEMANTIC_EVOLUTION'
				  AND d.issue_type IN ('TERM_ALIAS_MISSING','ENUM_MAPPING_MISSING','ENUM_MAPPING_AMBIGUOUS')
				  AND d.asset_type = ?
				ORDER BY d.create_time, d.id
				""", projectId, projectVersionId, assetType);
		String normalizedRaw = normalize(rawExpression);
		String normalizedScope = normalize(assetScope);
		Map<String, Set<String>> resolutionByRoot = new LinkedHashMap<>();
		int sampleCount = 0;
		for (Map<String, Object> row : rows) {
			Map<String, Object> evidence = readObject(text(row.get("evidence_json")));
			String rowRaw = first(evidence.get("rawExpression"), evidence.get("alias"));
			String rowScope = scope(evidence, text(row.get("asset_key")));
			if (!Objects.equals(normalizedRaw, normalize(rowRaw))
					|| !Objects.equals(normalizedScope, normalize(rowScope))) {
				continue;
			}
			Set<String> roots = rootEvidence(row);
			if (roots.isEmpty()) {
				continue;
			}
			sampleCount++;
			String resolution = text(row.get("asset_key"));
			for (String root : roots) {
				resolutionByRoot.computeIfAbsent(root, ignored -> new LinkedHashSet<>()).add(resolution);
			}
		}
		Map<String, Long> counts = new LinkedHashMap<>();
		for (Set<String> resolutions : resolutionByRoot.values()) {
			String value = resolutions.size() == 1 ? resolutions.iterator().next() : CONFLICT;
			counts.merge(value, 1L, Long::sum);
		}
		int independent = resolutionByRoot.size();
		Map.Entry<String, Long> dominant = counts.entrySet()
			.stream()
			.filter(entry -> !CONFLICT.equals(entry.getKey()))
			.max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
				.thenComparing(Map.Entry::getKey))
			.orElse(Map.entry("", 0L));
		double dominantRatio = independent == 0 ? 0 : dominant.getValue() / (double) independent;
		double conflictRatio = independent == 0 ? 0 : 1 - dominantRatio;
		double entropy = normalizedEntropy(counts, independent);
		String classification;
		if (independent < properties.getStableMappingMinIndependentEvidence()) {
			classification = "LOW_SAMPLE";
		}
		else if (dominantRatio >= properties.getStableMappingDominantRatio()
				&& conflictRatio <= properties.getStableMappingMaxConflictRatio()
				&& entropy <= properties.getStableMappingMaxEntropy() && StringUtils.hasText(dominant.getKey())) {
			classification = "STABLE_MAPPING";
		}
		else {
			classification = "TRUE_AMBIGUITY";
		}
		return new MappingDistribution(sampleCount, independent, dominant.getKey(), dominantRatio, conflictRatio,
				entropy, classification, Map.copyOf(counts), List.copyOf(resolutionByRoot.keySet()));
	}

	private List<Map<String, Object>> signalRows(String patternId, Map<String, Object> signal) {
		return jdbc.queryForList("""
				SELECT d.id detour_id, d.asset_type, d.asset_key, d.evidence_json,
				       p.episode_id, p.run_id, p.create_time,
				       (SELECT MIN(m.conversation_id) FROM qw_project_message m
				        WHERE m.run_id = p.run_id AND m.role = 'USER') conversation_id,
				       (SELECT MIN(c.created_by) FROM qw_project_message m
				        JOIN qw_project_conversation c ON c.conversation_id = m.conversation_id
				        WHERE m.run_id = p.run_id AND m.role = 'USER') created_by
				FROM qw_detour_signal d
				JOIN qw_trajectory_path p ON p.id = d.path_id
				WHERE d.pattern_id = ? AND d.signal_type = ? AND d.root_cause = ?
				 AND d.issue_type IS NOT DISTINCT FROM ?
				 AND d.asset_type IS NOT DISTINCT FROM ?
				 AND d.asset_key IS NOT DISTINCT FROM ?
				ORDER BY d.create_time, d.id
				""", patternId, signal.get("signal_type"), signal.get("root_cause"), signal.get("issue_type"),
				signal.get("asset_type"), signal.get("asset_key"));
	}

	private Set<String> rootEvidence(Map<String, Object> row) {
		String runId = text(row.get("run_id"));
		if (StringUtils.hasText(runId)) {
			List<Map<String, Object>> sources = jdbc.queryForList("""
					SELECT q.id, q.status, q.root_evidence_ids, q.episode_id
					FROM qw_query_case_usage u
					JOIN qw_query_example q ON q.id = u.query_example_id
					WHERE u.run_id = ? AND u.recalled = TRUE
					ORDER BY q.id
					""", runId);
			if (!sources.isEmpty()) {
				Set<String> roots = new LinkedHashSet<>();
				for (Map<String, Object> source : sources) {
					if (!"APPROVED".equals(text(source.get("status")))) {
						continue;
					}
					List<String> sourceRoots = readList(text(source.get("root_evidence_ids")));
					if (sourceRoots.isEmpty()) {
						String episodeId = text(source.get("episode_id"));
						roots.add(StringUtils.hasText(episodeId) ? "EPISODE:" + episodeId
								: "CASE:" + text(source.get("id")));
					}
					else {
						roots.addAll(sourceRoots);
					}
				}
				return roots;
			}
		}
		String episodeId = text(row.get("episode_id"));
		return StringUtils.hasText(episodeId) ? Set.of("EPISODE:" + episodeId) : Set.of();
	}

	private String scope(Map<String, Object> evidence, String assetKey) {
		String explicit = text(evidence.get("assetScope"));
		if (StringUtils.hasText(explicit)) {
			return explicit;
		}
		String modelCode = text(evidence.get("modelCode"));
		String columnName = text(evidence.get("columnName"));
		if (StringUtils.hasText(modelCode) && StringUtils.hasText(columnName)) {
			return modelCode + ":" + columnName;
		}
		if (StringUtils.hasText(assetKey) && assetKey.contains(":")) {
			return assetKey.substring(0, assetKey.lastIndexOf(':'));
		}
		return "GLOBAL";
	}

	private double normalizedEntropy(Map<String, Long> counts, int total) {
		if (total <= 0 || counts.size() <= 1) {
			return 0;
		}
		double entropy = 0;
		for (long count : counts.values()) {
			double probability = count / (double) total;
			if (probability > 0) {
				entropy -= probability * Math.log(probability);
			}
		}
		return entropy / Math.log(counts.size());
	}

	private boolean authenticatedUser(String value) {
		return StringUtils.hasText(value) && !Set.of("local-operator", "semevosql-system").contains(value);
	}

	private String timeWindow(Object value) {
		if (value instanceof Timestamp timestamp) {
			return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toLocalDate().toString();
		}
		if (value instanceof LocalDate date) {
			return date.toString();
		}
		return "";
	}

	private Map<String, Object> readObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			return Map.of();
		}
	}

	private List<String> readList(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			List<String> values = JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
			return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
		}
		catch (Exception ex) {
			return List.of();
		}
	}

	private String first(Object first, Object second) {
		String value = text(first);
		return StringUtils.hasText(value) ? value : text(second);
	}

	private String normalize(String value) {
		return text(value).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	public record IndependentEvidence(int sampleCount, int distinctConversationCount, Integer distinctUserCount,
			int distinctRootEvidenceCount, int distinctTimeWindowCount, List<String> conversationIds,
			List<String> rootEvidenceIds) {
	}

	public record MappingDistribution(int sampleCount, int independentEvidenceCount, String dominantResolution,
			double dominantResolutionRatio, double conflictRatio, double entropy, String classification,
			Map<String, Long> resolutionCounts, List<String> rootEvidenceIds) {

		static MappingDistribution empty() {
			return new MappingDistribution(0, 0, "", 0, 0, 0, "LOW_SAMPLE", Map.of(), List.of());
		}
	}

}
