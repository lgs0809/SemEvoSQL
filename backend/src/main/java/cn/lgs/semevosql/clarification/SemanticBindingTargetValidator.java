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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Ensures durable language bindings only point at enabled assets in the pinned Project
 * Version.
 */
@Service
public class SemanticBindingTargetValidator {

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogLookupService catalogLookup;

	public SemanticBindingTargetValidator(SemanticProjectRepository projectRepository,
			SemanticCatalogLookupService catalogLookup) {
		this.projectRepository = projectRepository;
		this.catalogLookup = catalogLookup;
	}

    public Long activeVersionId(Long projectId) {
        var project=projectRepository.findProject(projectId).orElseThrow(()->new IllegalArgumentException("Unknown project"));
        if(project.getActiveVersionId()==null)throw new IllegalStateException("Project has no active catalog");
        return project.getActiveVersionId();
    }

    public PersonalDefinitionSnapshot.Captured captureAsset(Long projectId,Long versionId,String type,String key) {
        requireAsset(projectId,versionId,type,key);
        var version=projectRepository.findVersion(versionId).orElseThrow();
        var catalog=catalogLookup.loadAssets(projectId,versionId,version.getCatalogHash(),
            List.of(new SemanticCatalogLookupService.AssetRef(type,key)));
        return PersonalDefinitionSnapshot.capture(catalog,type,key);
    }

	public void requireActiveAsset(Long projectId, String assetType, String assetKey) {
		if (projectId == null) {
			throw new IllegalArgumentException("projectId is required");
		}
		var project = projectRepository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
		Long activeVersionId = project.getActiveVersionId();
		if (activeVersionId == null) {
			throw new IllegalStateException("Project has no active Semantic Catalog for durable language bindings");
		}
		requireAsset(projectId, activeVersionId, assetType, assetKey);
	}

	public void requireAsset(Long projectId, Long projectVersionId, String assetType, String assetKey) {
		if (projectId == null || projectVersionId == null) {
			throw new IllegalArgumentException("projectId and projectVersionId are required");
		}
		var version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + projectVersionId));
		if (!Objects.equals(projectId, version.getProjectId())) {
			throw new IllegalArgumentException("Semantic project version does not belong to project: " + projectId);
		}
		if (assetType == null || !Set.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN").contains(assetType)) {
			throw new IllegalArgumentException("Durable semantic binding target does not exist in Project Version "
					+ projectVersionId + ": " + assetType + " " + assetKey);
		}
		catalogLookup.loadAssets(projectId, projectVersionId, version.getCatalogHash(),
				List.of(new SemanticCatalogLookupService.AssetRef(assetType, assetKey)));
	}

}
