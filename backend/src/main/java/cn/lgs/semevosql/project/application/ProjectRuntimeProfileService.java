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

import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectRuntimeProfileService {

	private final SemanticProjectRepository repository;

	@Transactional
	public ProjectRuntimeProfile resolveOrCreate(SemanticProject project) {
		return repository.findRuntimeProfileByProject(project.getId()).orElseGet(() -> create(project));
	}

	public ProjectRuntimeProfile require(Long projectId) {
		return repository.findRuntimeProfileByProject(projectId)
			.orElseThrow(() -> new IllegalStateException("Project runtime profile not found: " + projectId));
	}

	private ProjectRuntimeProfile create(SemanticProject project) {
		LocalDateTime now = LocalDateTime.now();
		ProjectRuntimeProfile profile = ProjectRuntimeProfile.builder()
			.projectId(project.getId())
			.runtimeProfileId("project-runtime-" + UUID.randomUUID())
			.status("ACTIVE")
			.revision(0L)
			.createTime(now)
			.updateTime(now)
			.build();
		repository.saveRuntimeProfile(profile);
		return profile;
	}

}
