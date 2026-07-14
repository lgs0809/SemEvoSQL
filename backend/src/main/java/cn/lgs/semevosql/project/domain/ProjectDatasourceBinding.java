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
package cn.lgs.semevosql.project.domain;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDatasourceBinding {

	private Long id;

	private Long projectId;

	private Long projectVersionId;

	private Integer datasourceId;

	private String datasourceName;

	private String datasourceType;

	private String domainCode;

	private String domainName;

	private String responsibility;

	private Integer priority;

	private List<String> exposedTables;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	public static ProjectDatasourceBinding create(Long projectId, Long projectVersionId, Integer datasourceId,
			String domainCode, String domainName, String responsibility, Integer priority, List<String> exposedTables) {
		if (projectId == null || projectId <= 0) {
			throw new IllegalArgumentException("projectId must be positive");
		}
		if (projectVersionId == null || projectVersionId <= 0) {
			throw new IllegalArgumentException("projectVersionId must be positive");
		}
		if (datasourceId == null || datasourceId <= 0) {
			throw new IllegalArgumentException("datasourceId must be positive");
		}
		if (domainCode == null || domainCode.isBlank()) {
			throw new IllegalArgumentException("domainCode is required");
		}
		if (domainName == null || domainName.isBlank()) {
			throw new IllegalArgumentException("domainName is required");
		}
		if (responsibility == null || responsibility.isBlank()) {
			throw new IllegalArgumentException("responsibility is required");
		}
		int resolvedPriority = priority == null ? 100 : priority;
		if (resolvedPriority < 0) {
			throw new IllegalArgumentException("priority cannot be negative");
		}
		List<String> normalizedTables = normalizeTables(exposedTables);
		LocalDateTime now = LocalDateTime.now();
		return ProjectDatasourceBinding.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.datasourceId(datasourceId)
			.domainCode(domainCode.trim())
			.domainName(domainName.trim())
			.responsibility(responsibility.trim())
			.priority(resolvedPriority)
			.exposedTables(normalizedTables)
			.createTime(now)
			.updateTime(now)
			.build();
	}

	public ProjectDatasourceBinding copyTo(Long targetVersionId) {
		return create(projectId, targetVersionId, datasourceId, domainCode, domainName, responsibility, priority,
				exposedTables);
	}

	private static List<String> normalizeTables(List<String> exposedTables) {
		if (exposedTables == null || exposedTables.isEmpty()) {
			throw new IllegalArgumentException("At least one exposed table is required");
		}
		List<String> normalized = exposedTables.stream()
			.filter(table -> table != null && !table.isBlank())
			.map(String::trim)
			.distinct()
			.sorted()
			.toList();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("At least one exposed table is required");
		}
		return normalized;
	}

}
