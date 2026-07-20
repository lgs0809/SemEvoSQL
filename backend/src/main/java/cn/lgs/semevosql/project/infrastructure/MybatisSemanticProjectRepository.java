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
package cn.lgs.semevosql.project.infrastructure;

import cn.lgs.semevosql.common.OptimisticLockingFailureException;
import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticGapStatus;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSemanticProjectRepository implements SemanticProjectRepository {

	private final SemEvoSQLProjectMapper mapper;

    @Override public void lockProject(Long projectId) {
        if(mapper.lockProject(projectId)==null)throw new IllegalArgumentException("Unknown publication project");
    }

    @Override public void lockVersion(Long projectId,Long versionId) {
        if(mapper.lockVersion(projectId,versionId)==null)throw new IllegalArgumentException("Unknown publication version");
    }

	@Override
	public void insertProject(SemanticProject project) {
		mapper.insertProject(project);
	}

	@Override
	public void updateProject(SemanticProject project) {
		long expectedRevision = project.getRevision() == null ? 0L : project.getRevision();
		if (mapper.updateProject(project) != 1) {
			Long currentRevision = mapper.findProjectRevision(project.getId());
			throw new OptimisticLockingFailureException("SemanticProject", String.valueOf(project.getId()),
					currentRevision == null ? -1L : currentRevision);
		}
		project.setRevision(expectedRevision + 1);
	}

	@Override
	public List<SemanticProject> findProjects() {
		return mapper.findProjects();
	}

	@Override
	public Optional<SemanticProject> findProject(Long projectId) {
		return Optional.ofNullable(mapper.findProject(projectId));
	}

	@Override
	public void insertVersion(SemanticProjectVersion version) {
		mapper.insertVersion(version);
	}

	@Override
	public void updateVersion(SemanticProjectVersion version) {
		long expectedRevision = version.getRevision() == null ? 0L : version.getRevision();
		if (mapper.updateVersion(version) != 1) {
			Long currentRevision = mapper.findVersionRevision(version.getId());
			throw new OptimisticLockingFailureException("SemanticProjectVersion", String.valueOf(version.getId()),
					currentRevision == null ? -1L : currentRevision);
		}
		version.setRevision(expectedRevision + 1);
	}

	@Override
	public Optional<SemanticProjectVersion> findVersion(Long versionId) {
		return Optional.ofNullable(mapper.findVersion(versionId));
	}

	@Override
	public Optional<SemanticProjectVersion> findVersionByNumber(Long projectId, String versionNumber) {
		return Optional.ofNullable(mapper.findVersionByNumber(projectId, versionNumber));
	}

	@Override
	public Optional<SemanticProjectVersion> findLatestVersion(Long projectId) {
		return Optional.ofNullable(mapper.findLatestVersion(projectId));
	}

	@Override
	public List<SemanticProjectVersion> findVersions(Long projectId) {
		return mapper.findVersions(projectId);
	}

	@Override
	public void saveDatasourceBinding(ProjectDatasourceBinding binding) {
		ProjectDatasourceBinding existing = mapper.findDatasourceBinding(binding.getProjectVersionId(),
				binding.getDatasourceId());
		if (existing == null) {
			mapper.insertDatasourceBinding(binding);
		}
		else {
			binding.setId(existing.getId());
			binding.setCreateTime(existing.getCreateTime());
			mapper.updateDatasourceBinding(binding);
		}
		mapper.deleteDatasourceTables(binding.getId());
		for (String tableName : binding.getExposedTables()) {
			mapper.insertDatasourceTable(binding.getId(), tableName);
		}
	}

	@Override
	public Optional<ProjectDatasourceBinding> findDatasourceBinding(Long projectVersionId, Integer datasourceId) {
		ProjectDatasourceBinding binding = mapper.findDatasourceBinding(projectVersionId, datasourceId);
		return Optional.ofNullable(enrichDatasourceTables(binding));
	}

	@Override
	public List<ProjectDatasourceBinding> findDatasourceBindings(Long projectVersionId) {
		return mapper.findDatasourceBindings(projectVersionId).stream().map(this::enrichDatasourceTables).toList();
	}

	@Override
	public void deleteDatasourceBinding(Long projectVersionId, Integer datasourceId) {
		mapper.deleteDatasourceBinding(projectVersionId, datasourceId);
	}

	@Override
	public void insertGap(SemanticGap gap) {
		if (gap.getGapKey() == null || gap.getGapKey().isBlank()) {
			mapper.insertGap(gap);
			return;
		}
		SemanticGap existing = mapper.findGapByKey(gap.getProjectVersionId(), gap.getGapKey());
		if (existing == null) {
			mapper.insertGap(gap);
			return;
		}
		gap.setId(existing.getId());
		gap.setCreateTime(existing.getCreateTime());
		if (existing.getStatus() == SemanticGapStatus.RESOLVED && gap.getStatus() == SemanticGapStatus.OPEN) {
			gap.reopen();
			mapper.updateGap(gap);
		}
		else {
			gap.setStatus(existing.getStatus());
			gap.setAnswer(existing.getAnswer());
			gap.setResolvedBy(existing.getResolvedBy());
			gap.setResolvedTime(existing.getResolvedTime());
		}
		mapper.updateGapDefinition(gap);
	}

	@Override
	public void updateGap(SemanticGap gap) {
		mapper.updateGap(gap);
	}

	@Override
	public void updateGapDefinition(SemanticGap gap) {
		mapper.updateGapDefinition(gap);
	}

	@Override
	public Optional<SemanticGap> findGap(Long gapId) {
		return Optional.ofNullable(mapper.findGap(gapId));
	}

	@Override
	public Optional<SemanticGap> findGapByKey(Long projectVersionId, String gapKey) {
		return Optional.ofNullable(mapper.findGapByKey(projectVersionId, gapKey));
	}

	@Override
	public Optional<SemanticGap> findNextOpenGap(Long projectId, Long projectVersionId) {
		return Optional.ofNullable(mapper.findNextOpenGap(projectId, projectVersionId));
	}

	@Override
	public List<SemanticGap> findOpenGaps(Long projectId, Long projectVersionId) {
		return mapper.findOpenGaps(projectId, projectVersionId);
	}

	@Override
	public List<SemanticGap> findOpenGapsByKeyPrefix(Long projectVersionId, String gapKeyPrefix) {
		return mapper.findOpenGapsByKeyPrefix(projectVersionId, gapKeyPrefix);
	}

	@Override
	public long countOpenGaps(Long projectId, Long projectVersionId) {
		return mapper.countOpenGaps(projectId, projectVersionId);
	}

	@Override
	public void saveRuntimeProfile(ProjectRuntimeProfile profile) {
		if (mapper.findRuntimeProfileByProject(profile.getProjectId()) == null) {
			mapper.insertRuntimeProfile(profile);
		}
		else {
			mapper.updateRuntimeProfile(profile);
		}
	}

	@Override
	public Optional<ProjectRuntimeProfile> findRuntimeProfileByProject(Long projectId) {
		return Optional.ofNullable(mapper.findRuntimeProfileByProject(projectId));
	}

	private ProjectDatasourceBinding enrichDatasourceTables(ProjectDatasourceBinding binding) {
		if (binding != null) {
			binding.setExposedTables(mapper.findDatasourceTables(binding.getId()));
		}
		return binding;
	}

}
