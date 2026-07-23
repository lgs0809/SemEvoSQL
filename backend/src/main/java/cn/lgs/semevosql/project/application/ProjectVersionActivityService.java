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
package cn.lgs.semevosql.project.application;

import cn.lgs.semevosql.common.OperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists who published or activated a project version without mutating immutable
 * version history.
 */
@Service
@RequiredArgsConstructor
public class ProjectVersionActivityService {

	private final JdbcTemplate jdbc;

	public void record(Long projectId, Long projectVersionId, ActivityType activityType, OperatorContext operator) {
		try {
			jdbc.update(
					"""
							INSERT INTO qw_project_version_activity
							(project_id, project_version_id, activity_type, operator_name, operator_role, request_id, idempotency_key)
							VALUES (?, ?, ?, ?, ?, ?, ?)
							""",
					projectId, projectVersionId, activityType.name(), operator.operator(), "LOCAL_OPERATOR",
					operator.requestId(), operator.idempotencyKey());
		}
		catch (DuplicateKeyException ignored) {
			// Governed mutations are idempotent. Replaying the same request must not
			// duplicate activity facts.
		}
	}

	public enum ActivityType {

		PUBLISHED, ACTIVATED

	}

}
