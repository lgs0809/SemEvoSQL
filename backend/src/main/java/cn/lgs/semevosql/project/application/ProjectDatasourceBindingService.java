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

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.service.datasource.DatasourceService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectDatasourceBindingService {

	private final SemanticProjectRepository repository;

	private final DatasourceService datasourceService;

	private final LocalOperatorService authorization;

	public List<ProjectDatasourceBinding> listBindings(Long projectId, Long versionId) {
		requireVersion(projectId, versionId);
		return repository.findDatasourceBindings(versionId);
	}

	@Transactional
	public ProjectDatasourceBinding saveBinding(Long projectId, Long versionId, Integer datasourceId, String domainCode,
			String domainName, String responsibility, Integer priority, List<String> exposedTables,
			OperatorContext operator) {
		authorization.require(operator, "edit Project datasource binding");
		SemanticProjectVersion version = requireMutableDraft(projectId, versionId);
		if (datasourceService.getDatasourceById(datasourceId) == null) {
			throw new IllegalArgumentException("Datasource not found: " + datasourceId);
		}
		ProjectDatasourceBinding binding = ProjectDatasourceBinding.create(projectId, version.getId(), datasourceId,
				domainCode, domainName, responsibility, priority, exposedTables);
		validateExposedTables(datasourceId, binding.getExposedTables());
		repository.saveDatasourceBinding(binding);
		return binding;
	}

	@Transactional
	public void deleteBinding(Long projectId, Long versionId, Integer datasourceId, OperatorContext operator) {
		authorization.require(operator, "delete Project datasource binding");
		requireMutableDraft(projectId, versionId);
		repository.deleteDatasourceBinding(versionId, datasourceId);
	}

	@Transactional
	public void saveBindings(Long projectId, Long versionId, List<BindingDefinition> definitions,
			OperatorContext operator) {
		authorization.require(operator, "edit Project datasource bindings");
		if (definitions == null) {
			return;
		}
		Set<Integer> datasourceIds = new HashSet<>();
		for (BindingDefinition definition : definitions) {
			if (definition == null || definition.datasourceId() == null) {
				throw new IllegalArgumentException("Datasource binding definition is incomplete");
			}
			if (!datasourceIds.add(definition.datasourceId())) {
				throw new IllegalArgumentException("Datasource is bound more than once: " + definition.datasourceId());
			}
		}
		for (BindingDefinition definition : definitions) {
			saveBinding(projectId, versionId, definition.datasourceId(), definition.domainCode(),
					definition.domainName(), definition.responsibility(), definition.priority(),
					definition.exposedTables(), operator);
		}
	}

	@Transactional
	public void cloneBindings(Long projectId, Long sourceVersionId, Long targetVersionId, OperatorContext operator) {
		authorization.require(operator, "clone Project datasource bindings");
		requireVersion(projectId, sourceVersionId);
		requireMutableDraft(projectId, targetVersionId);
		for (ProjectDatasourceBinding binding : repository.findDatasourceBindings(sourceVersionId)) {
			repository.saveDatasourceBinding(binding.copyTo(targetVersionId));
		}
	}

	private void validateExposedTables(Integer datasourceId, List<String> exposedTables) {
		try {
			Set<String> availableTables = new HashSet<>(datasourceService.getDatasourceTables(datasourceId));
			List<String> unknownTables = exposedTables.stream()
				.filter(table -> !availableTables.contains(table))
				.toList();
			if (!unknownTables.isEmpty()) {
				throw new IllegalArgumentException("Unknown exposed tables for datasource " + datasourceId + ": "
						+ String.join(", ", unknownTables));
			}
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to inspect datasource tables: " + datasourceId, ex);
		}
	}

	private SemanticProjectVersion requireMutableDraft(Long projectId, Long versionId) {
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		version.assertMutable();
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Datasource bindings can only be changed on a DRAFT version");
		}
		return version;
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long versionId) {
		if (projectId == null || versionId == null) {
			throw new IllegalArgumentException("projectId and versionId are required");
		}
		SemanticProjectVersion version = repository.findVersion(versionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + versionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	public record BindingDefinition(Integer datasourceId, String domainCode, String domainName, String responsibility,
			Integer priority, List<String> exposedTables) {
	}

}
