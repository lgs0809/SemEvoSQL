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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Authoritative resolver for the semantic plan that actually governed a Run.
 *
 * <p>The immutable execution snapshot is an admission/environment snapshot and may be captured before semantic
 * planning. Runtime semantic-plan events are therefore authoritative for completed planning, with the admission
 * snapshot and durable query-task plan retained only as compatibility fallbacks for older Runs.</p>
 */
@Service
public class SemanticPlanSnapshotService {

	private final JdbcTemplate jdbc;

	private final ExecutionSnapshotService executionSnapshotService;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public SemanticPlanSnapshotService(JdbcTemplate jdbc, ExecutionSnapshotService executionSnapshotService) {
		this.jdbc = jdbc;
		this.executionSnapshotService = executionSnapshotService;
	}

	public Optional<SemanticBlueprint> latest(String runId) {
		if (!StringUtils.hasText(runId)) {
			return Optional.empty();
		}
		Optional<SemanticBlueprint> runtime = latestRuntimeEvent(runId);
		if (runtime.isPresent()) {
			return runtime;
		}
		Optional<SemanticBlueprint> admission = admissionSnapshot(runId);
		return admission.isPresent() ? admission : latestTaskPlan(runId);
	}

	private Optional<SemanticBlueprint> latestRuntimeEvent(String runId) {
		List<String> payloads = jdbc.queryForList("""
				SELECT payload FROM qw_run_event
				WHERE run_id = ? AND event_type IN ('SEMANTIC_PLAN_SNAPSHOT', 'APPROVAL_PLAN_SNAPSHOT')
				  AND payload IS NOT NULL
				ORDER BY sequence DESC LIMIT 1
				""", String.class, runId);
		return read(payloads.isEmpty() ? null : payloads.get(0));
	}

	private Optional<SemanticBlueprint> admissionSnapshot(String runId) {
		List<String> snapshots = jdbc.queryForList("SELECT execution_snapshot FROM qw_query_run WHERE run_id = ?",
				String.class, runId);
		if (snapshots.isEmpty() || !StringUtils.hasText(snapshots.get(0))) {
			return Optional.empty();
		}
		return executionSnapshotService.readTyped(snapshots.get(0)).map(ExecutionSnapshot::semanticPlan);
	}

	private Optional<SemanticBlueprint> latestTaskPlan(String runId) {
		List<String> payloads = jdbc.queryForList("""
				SELECT semantic_plan_json::text FROM qw_query_task
				WHERE run_id = ? AND semantic_plan_json IS NOT NULL
				ORDER BY ordinal_no DESC LIMIT 1
				""", String.class, runId);
		return read(payloads.isEmpty() ? null : payloads.get(0));
	}

	private Optional<SemanticBlueprint> read(String payload) {
		if (!StringUtils.hasText(payload)) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.readValue(payload, SemanticBlueprint.class));
		}
		catch (Exception invalid) {
			return Optional.empty();
		}
	}
}
