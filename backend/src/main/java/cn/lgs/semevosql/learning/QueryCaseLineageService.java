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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Root-evidence de-duplication, cycle protection and immutable lineage events. */
@Service
public class QueryCaseLineageService {

	private final JdbcTemplate jdbc;

	private final QueryCaseRepository repository;

	public QueryCaseLineageService(JdbcTemplate jdbc, QueryCaseRepository repository) {
		this.jdbc = jdbc;
		this.repository = repository;
	}

	public Lineage forCapture(String newCaseId, String runId, String episodeId) {
		List<String> derived = StringUtils.hasText(runId) ? jdbc.queryForList("""
				SELECT query_example_id FROM qw_query_case_usage
				WHERE run_id = ? AND recalled = TRUE ORDER BY query_example_id
				""", String.class, runId) : List.of();
		return resolve(newCaseId, derived, "EPISODE:" + episodeId);
	}

	public Lineage forRebind(String newCaseId, String sourceCaseId) {
		return resolve(newCaseId, List.of(sourceCaseId), null);
	}

	private Lineage resolve(String newCaseId, List<String> sourceCaseIds, String directRoot) {
		List<String> derived = sourceCaseIds.stream().filter(StringUtils::hasText).distinct().sorted().toList();
		assertAcyclic(newCaseId, derived);
		Set<String> roots = new LinkedHashSet<>();
		for (String sourceId : derived) {
			Map<String, Object> source = repository.require(sourceId);
			List<String> sourceRoots = parseList(Objects.toString(source.get("root_evidence_ids"), ""));
			if (sourceRoots.isEmpty()) {
				String episodeId = Objects.toString(source.get("episode_id"), "");
				roots.add(StringUtils.hasText(episodeId) ? "EPISODE:" + episodeId : "CASE:" + sourceId);
			}
			else {
				roots.addAll(sourceRoots);
			}
		}
		if (roots.isEmpty() && StringUtils.hasText(directRoot)) {
			roots.add(directRoot);
		}
		List<String> rootEvidence = roots.stream().sorted().toList();
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("derivedFromCaseIds", derived);
		canonical.put("rootEvidenceIds", rootEvidence);
		return new Lineage(derived, rootEvidence, sha256(json(canonical)));
	}

	private void assertAcyclic(String newCaseId, List<String> sources) {
		ArrayDeque<String> remaining = new ArrayDeque<>(sources);
		Set<String> visited = new LinkedHashSet<>();
		while (!remaining.isEmpty()) {
			String current = remaining.removeFirst();
			if (Objects.equals(newCaseId, current)) {
				throw new IllegalStateException("Query Case lineage cycle detected at " + current);
			}
			if (!visited.add(current)) {
				continue;
			}
			Map<String, Object> source = repository.require(current);
			remaining.addAll(parseList(Objects.toString(source.get("derived_from_case_ids"), "")));
		}
	}

	@Transactional
	public void appendEvent(String queryCaseId, String eventType, String fromStatus, String toStatus, String actor,
			String actorSource, Map<String, Object> payload) {
		jdbc.queryForObject("SELECT id FROM qw_query_example WHERE id = ? FOR UPDATE", String.class, queryCaseId);
		Long sequence = jdbc.queryForObject("""
				SELECT COALESCE(MAX(sequence), 0) + 1 FROM qw_query_case_event WHERE query_example_id = ?
				""", Long.class, queryCaseId);
		jdbc.update("""
				INSERT INTO qw_query_case_event
				(id, query_example_id, sequence, event_type, from_status, to_status, actor, actor_source,
				 payload, create_time)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CURRENT_TIMESTAMP)
				""", UUID.randomUUID().toString(), queryCaseId, sequence, eventType, fromStatus, toStatus,
				StringUtils.hasText(actor) ? actor : "semevosql-system",
				StringUtils.hasText(actorSource) ? actorSource : "SYSTEM", json(payload == null ? Map.of() : payload));
	}

	private List<String> parseList(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			List<String> parsed = JsonUtil.getObjectMapper().readValue(value, new TypeReference<>() {
			});
			return parsed == null ? List.of()
					: parsed.stream().filter(StringUtils::hasText).distinct().sorted().toList();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid Query Case lineage JSON", ex);
		}
	}

	public String json(List<String> values) {
		return json((Object) new ArrayList<>(values));
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to encode Query Case lineage", ex);
		}
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	public record Lineage(List<String> derivedFromCaseIds, List<String> rootEvidenceIds, String lineageHash) {
		public Lineage {
			derivedFromCaseIds = List.copyOf(derivedFromCaseIds);
			rootEvidenceIds = List.copyOf(rootEvidenceIds);
		}
	}

}
