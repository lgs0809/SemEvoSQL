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
package cn.lgs.semevosql.service.datasource;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only product view describing which SemEvoSQL projects reference each datasource.
 */
@Service
@RequiredArgsConstructor
public class DatasourceUsageService {

	private final JdbcTemplate jdbc;

	public List<DatasourceUsage> listUsage() {
		return jdbc.query("""
				SELECT b.datasource_id,
				       p.id AS project_id,
				       p.name AS project_name,
				       COUNT(DISTINCT b.project_version_id) AS version_count,
				       BOOL_OR(p.active_version_id = b.project_version_id) AS used_by_active_version
				FROM qw_project_datasource_binding b
				JOIN qw_project p ON p.id = b.project_id
				GROUP BY b.datasource_id, p.id, p.name
				ORDER BY b.datasource_id, used_by_active_version DESC, p.name
				""", (rs, rowNum) -> new DatasourceUsage(rs.getInt("datasource_id"), rs.getLong("project_id"),
				rs.getString("project_name"), rs.getLong("version_count"), rs.getBoolean("used_by_active_version")));
	}

	public record DatasourceUsage(Integer datasourceId, Long projectId, String projectName, long versionCount,
			boolean usedByActiveVersion) {
	}

}
