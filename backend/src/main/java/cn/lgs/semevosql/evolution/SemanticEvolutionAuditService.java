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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only audit ledger and idempotency authority for semantic evolution. */
@Service
public class SemanticEvolutionAuditService {

	private final JdbcTemplate jdbc;

	private final CanonicalJson canonicalJson;

	@Autowired
	public SemanticEvolutionAuditService(JdbcTemplate jdbc) {
		this(jdbc, new CanonicalJson());
	}

	SemanticEvolutionAuditService(JdbcTemplate jdbc, CanonicalJson canonicalJson) {
		this.jdbc = jdbc;
		this.canonicalJson = canonicalJson;
	}

	public IdempotencyDecision inspect(String candidateId, String eventType, OperatorContext operator,
			Object requestPayload) {
		String requestHash = hash(requestPayload);
		List<Map<String, Object>> existing = jdbc.queryForList("""
				SELECT request_hash, payload FROM qw_semantic_evolution_event
				WHERE candidate_id = ? AND idempotency_key = ?
				""", candidateId, operator.idempotencyKey());
		if (existing.isEmpty()) {
			return new IdempotencyDecision(false, requestHash, null);
		}
		Map<String, Object> event = existing.get(0);
		if (!Objects.equals(requestHash, Objects.toString(event.get("request_hash"), ""))) {
			throw new IllegalArgumentException(
					"Idempotency-Key is already bound to a different semantic evolution command");
		}
		return new IdempotencyDecision(true, requestHash, Objects.toString(event.get("payload"), null));
	}

	@Transactional
	public void append(String candidateId, String eventType, String fromStatus, String toStatus,
			OperatorContext operator, Long sourceVersionId, Long targetVersionId, String patchHash, String replayRunId,
			Object requestPayload, Object eventPayload) {
		String requestHash = hash(requestPayload);
		String payload = json(eventPayload);
		jdbc.queryForObject("SELECT id FROM qw_semantic_evolution_candidate WHERE id = ? FOR UPDATE", String.class,
				candidateId);
		for (int attempt = 0; attempt < 3; attempt++) {
			long sequence = jdbc.queryForObject("""
					SELECT COALESCE(MAX(sequence), 0) + 1 FROM qw_semantic_evolution_event
					WHERE candidate_id = ?
					""", Long.class, candidateId);
			try {
				jdbc.update("""
						INSERT INTO qw_semantic_evolution_event
						(id, candidate_id, sequence, event_type, from_status, to_status, actor, actor_source,
						 request_id, idempotency_key, request_hash, source_version_id, target_version_id,
						 patch_hash, replay_run_id, payload, create_time)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
						""", UUID.randomUUID().toString(), candidateId, sequence, eventType, fromStatus, toStatus,
						operator.operator(), operator.source(), operator.requestId(), operator.idempotencyKey(),
						requestHash, sourceVersionId, targetVersionId, patchHash, replayRunId, payload);
				return;
			}
			catch (DuplicateKeyException ex) {
				IdempotencyDecision decision = inspect(candidateId, eventType, operator, requestPayload);
				if (decision.replayed()) {
					return;
				}
				if (attempt == 2) {
					throw ex;
				}
			}
		}
	}

	public List<Map<String, Object>> events(String candidateId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_semantic_evolution_event
				WHERE candidate_id = ? ORDER BY sequence
				""", candidateId);
	}

	private String hash(Object value) {
		return canonicalJson.hash(value == null ? Map.of() : value);
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to encode semantic evolution audit payload", ex);
		}
	}

	public record IdempotencyDecision(boolean replayed, String requestHash, String storedPayload) {
	}

}
