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
package cn.lgs.semevosql.semantic.domain;

public interface SemanticCatalogRepository {

	void replaceCatalog(SemanticCatalogSnapshot snapshot);

	SemanticCatalogSnapshot loadCatalog(Long projectId, Long projectVersionId);

    /** Call within a transaction before reading a catalog for a content/projection commit. */
    default void lockVersion(Long projectId, Long projectVersionId) {
        throw new UnsupportedOperationException("Catalog coordination is required");
    }


    /** Query-path reads must not fall back to loading the entire catalog. */
    default SemanticCatalogSnapshot loadModelSlice(Long projectId, Long versionId, java.util.Set<String> modelCodes) {
        throw new UnsupportedOperationException("Model-scoped catalog reads are required");
    }
    default java.util.List<SemanticCatalogSnapshot.Model> findModelsByCodes(Long projectId, Long versionId, java.util.Set<String> modelCodes) {
        throw new UnsupportedOperationException("Model-scoped catalog reads are required");
    }
    default java.util.List<SemanticCatalogSnapshot.Model> findModelsByTables(Long projectId, Long versionId, java.util.Set<String> tables, int limit) {
        throw new UnsupportedOperationException("Bounded catalog reads are required");
    }
    default java.util.List<SemanticCatalogSnapshot.Relationship> findRelationshipsTouching(Long projectId, Long versionId, java.util.Set<String> modelCodes, int limit) {
        throw new UnsupportedOperationException("Relationship-scoped catalog reads are required");
    }
    default String authoritativeCatalogHash(Long projectId, Long versionId) {
        throw new UnsupportedOperationException("Authoritative catalog identity is required");
    }

    default java.util.List<SemanticCatalogSnapshot.Model> findEnabledModelSummaries(Long projectId,Long versionId,int limit) {
        throw new UnsupportedOperationException("Bounded model summaries are required");
    }
    default java.util.Set<Integer> enabledDatasourceIds(Long projectId,Long versionId) {
        throw new UnsupportedOperationException("Datasource summary reads are required");
    }
    default java.util.Set<String> enabledPhysicalTables(Long projectId,Long versionId) {
        throw new UnsupportedOperationException("Table identity reads are required");
    }
    record AssetOwner(String assetKey, String modelCode, String relatedModelCode) {}

    default java.util.List<AssetOwner> findAssetOwners(Long projectId, Long versionId, String assetType,
            java.util.Set<String> keys) {
        throw new UnsupportedOperationException("Exact asset identity reads are required");
    }

    default java.util.List<SemanticCatalogSnapshot.Rule> findGlobalRules(Long projectId, Long versionId) {
        throw new UnsupportedOperationException("Global rule reads are required");
    }
    default java.util.List<SemanticCatalogSnapshot.Model> findModelSummaryPage(Long projectId, Long versionId,
            String afterModelCode, int limit) {
        throw new UnsupportedOperationException("Paged model summaries are required");
    }
    record BindingTerm(long id, String assetKey, String modelCode, String businessName, String termCode) {}
    default java.util.List<BindingTerm> findBindingTermPage(Long projectId, Long versionId, String type, long afterId, int limit) {
        throw new UnsupportedOperationException("Paged binding term summaries are required");
    }

    default java.util.List<BindingTerm> searchBindingTermPage(Long projectId, Long versionId, String type,
            String query, long afterId, int limit) {
        throw new UnsupportedOperationException("Paged binding term search is required");
    }

}
