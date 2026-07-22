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
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.CandidateStatus;
import cn.lgs.semevosql.evolution.SemanticEvolutionStateMachine.Mutation;
import cn.lgs.semevosql.learning.ValidatedQueryExampleService;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Completes evolution and starts idempotent rebind only after version commit. */
@Slf4j
@Component
public class SemanticEvolutionPublicationListener {

	private final JdbcTemplate jdbc;

	private final ValidatedQueryExampleService queryExampleService;

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticEvolutionAuditService auditService;

	private final SemanticEvolutionStateMachine stateMachine;

	public SemanticEvolutionPublicationListener(JdbcTemplate jdbc, ValidatedQueryExampleService queryExampleService,
			SemanticCatalogRepository catalogRepository, SemanticEvolutionAuditService auditService,
			SemanticEvolutionStateMachine stateMachine) {
		this.jdbc = jdbc;
		this.queryExampleService = queryExampleService;
		this.catalogRepository = catalogRepository;
		this.auditService = auditService;
		this.stateMachine = stateMachine;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void published(ProjectVersionPublishedEvent event) {
		for (Map<String, Object> candidate : jdbc.queryForList("""
				SELECT * FROM qw_semantic_evolution_candidate
				WHERE project_id = ? AND target_draft_version_id = ? AND status IN ('READY_FOR_PUBLISH','PUBLISHED')
				ORDER BY create_time
				""", event.projectId(), event.versionId())) {
			completeCandidate(candidate, event);
		}
	}

	public void retry(Long projectId, Long versionId) {
		Map<String, Object> version = one("SELECT * FROM qw_project_version WHERE id = ? AND project_id = ?", versionId,
				projectId);
		if (!"PUBLISHED".equals(text(version.get("status")))) {
			throw new IllegalStateException("Rebind compensation requires a PUBLISHED project version");
		}
		published(new ProjectVersionPublishedEvent(projectId, versionId, number(version.get("parent_version_id")),
				text(version.get("catalog_hash"))));
	}

	private void completeCandidate(Map<String, Object> candidate, ProjectVersionPublishedEvent event) {
		String candidateId = text(candidate.get("id"));
		CandidateStatus currentStatus = CandidateStatus.valueOf(text(candidate.get("status")));
		boolean newlyPublished = currentStatus == CandidateStatus.READY_FOR_PUBLISH;
		if (newlyPublished) {
			stateMachine.transition(candidateId, CandidateStatus.READY_FOR_PUBLISH, number(candidate.get("revision")),
					CandidateStatus.PUBLISHED, Mutation.published());
		}
		else if ("SUCCEEDED".equals(text(candidate.get("rebind_status")))) {
			return;
		}
		OperatorContext operator = new OperatorContext("semevosql-system", "AFTER_COMMIT_EVENT",
				"version-published:" + event.versionId(), "version-published:" + candidateId + ":" + event.versionId());
		auditService.append(candidateId, "VERSION_PUBLISHED", text(candidate.get("status")), "PUBLISHED", operator,
				number(candidate.get("source_version_id")), event.versionId(), text(candidate.get("patch_hash")), null,
				event, Map.of("versionId", event.versionId(), "catalogHash", event.catalogHash()));
		try {
			ValidatedQueryExampleService.RebindReport report = queryExampleService.rebindApprovedExamples(
					event.projectId(), number(candidate.get("source_version_id")), event.versionId(),
					event.catalogHash(), catalogRepository.loadCatalog(event.projectId(), event.versionId()));
			jdbc.update("""
					UPDATE qw_semantic_evolution_candidate
					SET rebind_status = 'SUCCEEDED', rebind_error = NULL, update_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", candidateId);
			auditService.append(candidateId, "QUERY_CASE_REBIND_COMPLETED", "PUBLISHED", "PUBLISHED",
					new OperatorContext(operator.operator(), operator.source(), operator.requestId(),
							operator.idempotencyKey() + ":rebind"),
					number(candidate.get("source_version_id")), event.versionId(), text(candidate.get("patch_hash")),
					null, event, report);
		}
		catch (RuntimeException ex) {
			log.error("Published version {} Query Case rebind failed for candidate {}", event.versionId(), candidateId,
					ex);
			jdbc.update("""
					UPDATE qw_semantic_evolution_candidate
					SET rebind_status = 'FAILED', rebind_error = ?, update_time = CURRENT_TIMESTAMP
					WHERE id = ?
					""", message(ex), candidateId);
			auditService.append(candidateId, "QUERY_CASE_REBIND_FAILED", "PUBLISHED", "PUBLISHED",
					new OperatorContext(operator.operator(), operator.source(), operator.requestId(),
							operator.idempotencyKey() + ":rebind-failed"),
					number(candidate.get("source_version_id")), event.versionId(), text(candidate.get("patch_hash")),
					null, event, Map.of("error", message(ex)));
		}
	}

	private Map<String, Object> one(String sql, Object... args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Project version not found or not unique");
		}
		return rows.get(0);
	}

	private Long number(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return Objects.toString(current.getMessage(), current.getClass().getSimpleName());
	}

}
