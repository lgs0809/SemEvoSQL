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

import java.util.List;
import java.util.Optional;

public interface SemanticProjectRepository {

	void insertProject(SemanticProject project);

	void updateProject(SemanticProject project);

	List<SemanticProject> findProjects();

	Optional<SemanticProject> findProject(Long projectId);

    default void lockProject(Long projectId) {
        throw new UnsupportedOperationException("Project publication coordination is required");
    }

    default void lockVersion(Long projectId,Long versionId) {
        throw new UnsupportedOperationException("Version publication coordination is required");
    }

	void insertVersion(SemanticProjectVersion version);

	void updateVersion(SemanticProjectVersion version);

	Optional<SemanticProjectVersion> findVersion(Long versionId);

	Optional<SemanticProjectVersion> findVersionByNumber(Long projectId, String versionNumber);

	Optional<SemanticProjectVersion> findLatestVersion(Long projectId);

	List<SemanticProjectVersion> findVersions(Long projectId);

	void saveDatasourceBinding(ProjectDatasourceBinding binding);

	Optional<ProjectDatasourceBinding> findDatasourceBinding(Long projectVersionId, Integer datasourceId);

	List<ProjectDatasourceBinding> findDatasourceBindings(Long projectVersionId);

	void deleteDatasourceBinding(Long projectVersionId, Integer datasourceId);

	void insertGap(SemanticGap gap);

	void updateGap(SemanticGap gap);

	void updateGapDefinition(SemanticGap gap);

	Optional<SemanticGap> findGap(Long gapId);

	Optional<SemanticGap> findGapByKey(Long projectVersionId, String gapKey);

	Optional<SemanticGap> findNextOpenGap(Long projectId, Long projectVersionId);

	List<SemanticGap> findOpenGaps(Long projectId, Long projectVersionId);

	List<SemanticGap> findOpenGapsByKeyPrefix(Long projectVersionId, String gapKeyPrefix);

	long countOpenGaps(Long projectId, Long projectVersionId);

	void saveRuntimeProfile(ProjectRuntimeProfile profile);

	Optional<ProjectRuntimeProfile> findRuntimeProfileByProject(Long projectId);

}
