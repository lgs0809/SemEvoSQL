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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticProject {

	private Long id;

	private String projectCode;

	private String name;

	private String businessDomain;

	private String description;

	private ProjectStatus status;

	private Long activeVersionId;

	private String createdBy;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	@Builder.Default
	private Long revision = 0L;

	public static SemanticProject initialize(String projectCode, String name, String businessDomain, String description,
			String createdBy) {
		return SemanticProject.builder()
			.projectCode(projectCode)
			.name(name)
			.businessDomain(businessDomain)
			.description(description)
			.status(ProjectStatus.INITIALIZING)
			.createdBy(createdBy)
			.createTime(LocalDateTime.now())
			.updateTime(LocalDateTime.now())
			.build();
	}

	@JsonIgnore
	public Long getActiveVersionId() {
		return activeVersionId;
	}

	@JsonProperty("activePublishedVersionId")
	public Long getActivePublishedVersionId() {
		return activeVersionId;
	}

	public void activatePublishedVersion(Long versionId) {
		if (versionId == null) {
			throw new IllegalArgumentException("active published version id is required");
		}
		this.activeVersionId = versionId;
		this.status = ProjectStatus.READY;
		this.updateTime = LocalDateTime.now();
	}

}
