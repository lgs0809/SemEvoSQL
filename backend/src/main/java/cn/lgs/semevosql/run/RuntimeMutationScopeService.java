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

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates that a local runtime mutation addresses an existing Run and its owning project. */
@Service
@RequiredArgsConstructor
public class RuntimeMutationScopeService {

	private final QueryRunService runService;

	private final ProjectScopeService projectScope;

	private final LocalOperatorService localOperator;

	private final JdbcTemplate jdbc;

	public QueryRun requireRun(String runId, OperatorContext operator) {
		localOperator.require(operator, "mutate local Query Run");
		QueryRun run = runService.get(runId);
		projectScope.requireProject(run.projectId(), operator);
		return run;
	}

	public QueryRun requireEpisode(String episodeId, OperatorContext operator) {
		String runId = jdbc.query("""
				SELECT run_id FROM qw_query_run
				WHERE episode_id = ?
				ORDER BY create_time DESC
				LIMIT 1
				""", (rs, rowNum) -> rs.getString(1), episodeId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("No Query Run found for episode: " + episodeId));
		return requireRun(runId, operator);
	}
}
