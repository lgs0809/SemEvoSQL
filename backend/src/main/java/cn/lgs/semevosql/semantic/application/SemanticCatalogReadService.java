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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Query-time catalog reads. One authority check brackets the entire logical read, including
 * identity resolution and dependency expansion. Consumers explicitly requesting a whole
 * catalog retain the existing cache; scoped queries never populate or read that full cache.
 */
@Service
public class SemanticCatalogReadService {

    public static final int MAX_QUERY_MODELS = 24;

    private final SemanticCatalogRepository repository;

    public SemanticCatalogReadService(SemanticCatalogRepository repository) {
        this.repository = repository;
    }

    public <T> T readCurrent(Long projectId, Long versionId, Function<ReadScope, T> operation) {
        return read(projectId, versionId, currentHash(projectId, versionId), operation);
    }

    public <T> T readFrozen(Long projectId, Long versionId, String expectedHash, Function<ReadScope, T> operation) {
        if (!StringUtils.hasText(expectedHash) || !expectedHash.equals(currentHash(projectId, versionId))) {
            throw unavailableIdentity();
        }
        return read(projectId, versionId, expectedHash, operation);
    }

    public SemanticCatalogSnapshot getForModels(Long projectId, Long versionId, Set<String> modelCodes) {
        return readCurrent(projectId, versionId, scope -> scope.models(modelCodes));
    }

    public SemanticCatalogSnapshot getForModels(Long projectId, Long versionId, Set<String> modelCodes,
            String expectedHash) {
        return readFrozen(projectId, versionId, expectedHash, scope -> scope.models(modelCodes));
    }

    public SemanticCatalogSnapshot getForPhysicalTables(Long projectId, Long versionId, Collection<String> tables) {
        return readCurrent(projectId, versionId, scope -> scope.models(scope.resolvePhysicalModels(tables)));
    }

    private <T> T read(Long projectId, Long versionId, String hash, Function<ReadScope, T> operation) {
        T result = operation.apply(new ReadScope(projectId, versionId, hash));
        if (!hash.equals(repository.authoritativeCatalogHash(projectId, versionId))) {
            throw new SemanticPlanningRejectedException("CATALOG_IDENTITY_CHANGED",
                    "Catalog identity changed while reading the frozen catalog");
        }
        return result;
    }

    private String currentHash(Long projectId, Long versionId) {
        if (projectId == null || versionId == null) {
            throw new IllegalArgumentException("Project and version identities are required");
        }
        String hash = repository.authoritativeCatalogHash(projectId, versionId);
        if (!StringUtils.hasText(hash)) {
            throw unavailableIdentity();
        }
        return hash;
    }

    private static SemanticPlanningRejectedException unavailableIdentity() {
        return new SemanticPlanningRejectedException("CATALOG_IDENTITY_MISSING",
                "The frozen catalog identity is no longer available for this project");
    }

    public static void requireModelBudget(Collection<String> models) {
        if (models.size() > MAX_QUERY_MODELS) {
            throw new SemanticPlanningRejectedException("CANDIDATE_BUDGET_EXCEEDED",
                    "Model loading budget exceeded; required model identities cannot be silently truncated");
        }
    }

    /** Valid only inside a read operation; does not retain data or populate a cache. */
    public final class ReadScope {
        private final Long projectId;
        private final Long versionId;
        private final String catalogHash;

        private ReadScope(Long projectId, Long versionId, String catalogHash) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.catalogHash = catalogHash;
        }

        public String catalogHash() {
            return catalogHash;
        }

        public SemanticCatalogSnapshot models(Set<String> modelCodes) {
            if (modelCodes == null || modelCodes.isEmpty() || modelCodes.stream().anyMatch(code -> !StringUtils.hasText(code))) {
                throw new IllegalArgumentException("Executable model identities are required");
            }
            Set<String> requested = Set.copyOf(modelCodes);
            requireModelBudget(requested);
            SemanticCatalogSnapshot snapshot = repository.loadModelSlice(projectId, versionId, requested);
            Set<String> found = snapshot.getModels().stream()
                .filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
                .map(SemanticCatalogSnapshot.Model::getModelCode).collect(Collectors.toSet());
            if (!Objects.equals(projectId, snapshot.getProjectId()) || !Objects.equals(versionId, snapshot.getProjectVersionId())
                    || !found.equals(requested) || snapshot.getModels().size() != requested.size()) {
                throw new SemanticPlanningRejectedException("CANDIDATE_SCOPE_CHANGED",
                        "A required model is missing or disabled in the frozen catalog scope");
            }
            return GovernedAttributeDimensions.expand(snapshot);
        }

        public Set<String> resolvePhysicalModels(Collection<String> physicalTables) {
            if (physicalTables == null || physicalTables.isEmpty()
                    || physicalTables.stream().anyMatch(table -> !StringUtils.hasText(table))) {
                throw new SemanticPlanningRejectedException("CANDIDATE_IDENTITY_REQUIRED",
                        "Physical tables must identify enabled models");
            }
            Set<String> tables = new LinkedHashSet<>(physicalTables);
            requireModelBudget(tables);
            var models = repository.findModelsByTables(projectId, versionId, tables, MAX_QUERY_MODELS + 1);
            requireModelBudget(models.stream().map(SemanticCatalogSnapshot.Model::getModelCode).toList());
            var byTable = models.stream().collect(Collectors.groupingBy(SemanticCatalogSnapshot.Model::getPhysicalTable));
            if (!byTable.keySet().equals(tables) || byTable.values().stream().anyMatch(values -> values.size() != 1)
                    || models.stream().anyMatch(model -> model.getStatus() != SemanticAssetStatus.ENABLED)) {
                throw new SemanticPlanningRejectedException("CANDIDATE_IDENTITY_REQUIRED",
                        "Physical tables do not uniquely identify enabled models");
            }
            return models.stream().map(SemanticCatalogSnapshot.Model::getModelCode).collect(Collectors.toSet());
        }
    }
}
