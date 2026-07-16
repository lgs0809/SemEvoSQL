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
package cn.lgs.semevosql.semantic.infrastructure;

import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSemanticCatalogRepository implements SemanticCatalogRepository {

	private final SemEvoSQLSemanticCatalogMapper mapper;

    @Override
    public void lockVersion(Long projectId,Long projectVersionId) {
        if(mapper.lockCatalogVersion(projectId,projectVersionId)==null)
            throw new IllegalArgumentException("Unknown project catalog version");
    }

	@Override
	public void replaceCatalog(SemanticCatalogSnapshot snapshot) {
		Long projectId = snapshot.getProjectId();
		Long versionId = snapshot.getProjectVersionId();
        if(!"DRAFT".equals(mapper.lockCatalogVersion(projectId,versionId)))throw new IllegalStateException("Catalog replacement requires a mutable version");
        cn.lgs.semevosql.semantic.application.SharedCatalogProtocol.attachReferences(snapshot);
        mapper.deleteSharedBindings(projectId,versionId);
        mapper.deleteSharedDefinitionLinks(projectId,versionId);
        mapper.deleteSharedDictionaryLinks(projectId,versionId);
		mapper.deleteEnumValues(projectId, versionId);
		mapper.deleteRules(projectId, versionId);
		mapper.deleteRelationships(projectId, versionId);
		mapper.deleteMetrics(projectId, versionId);
		mapper.deleteDimensions(projectId, versionId);
		mapper.deleteGrains(projectId, versionId);
		mapper.deleteColumns(projectId, versionId);
		mapper.deleteModels(projectId, versionId);

		snapshot.getModels().forEach(mapper::insertModel);
		snapshot.getColumns().forEach(mapper::insertColumn);
		snapshot.getMetrics().forEach(mapper::insertMetric);
		snapshot.getDimensions().forEach(mapper::insertDimension);
		snapshot.getRelationships().forEach(mapper::insertRelationship);
		snapshot.getGrains().forEach(mapper::insertGrain);
		snapshot.getEnumValues().forEach(mapper::insertEnumValue);
		snapshot.getRules().forEach(mapper::insertRule);
        persistSharedMetadata(snapshot);
        mapper.registerLegacyAssets(projectId,versionId);
	}

	@Override
	public SemanticCatalogSnapshot loadCatalog(Long projectId, Long projectVersionId) {
		return sharedMetadata(SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.models(mapper.findModels(projectId, projectVersionId))
			.columns(mapper.findColumns(projectId, projectVersionId))
			.metrics(mapper.findMetrics(projectId, projectVersionId))
			.dimensions(mapper.findDimensions(projectId, projectVersionId))
			.relationships(mapper.findRelationships(projectId, projectVersionId))
			.grains(mapper.findGrains(projectId, projectVersionId))
			.enumValues(mapper.findEnumValues(projectId, projectVersionId))
			.rules(mapper.findRules(projectId, projectVersionId))
			.build(),null);
	}


    @Override public SemanticCatalogSnapshot loadModelSlice(Long projectId, Long versionId, java.util.Set<String> codes) {
        if(codes == null || codes.isEmpty()) throw new IllegalArgumentException("Model identities are required for a catalog slice");
        return sharedMetadata(SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(versionId)
            .models(mapper.findModelsForModels(projectId,versionId,codes))
            .columns(mapper.findColumnsForModels(projectId,versionId,codes))
            .metrics(mapper.findMetricsForModels(projectId,versionId,codes))
            .dimensions(mapper.findDimensionsForModels(projectId,versionId,codes))
            .relationships(mapper.findRelationshipsForModels(projectId,versionId,codes))
            .grains(mapper.findGrainsForModels(projectId,versionId,codes))
            .enumValues(mapper.findEnumValuesForModels(projectId,versionId,codes))
            .rules(mapper.findRulesForModels(projectId,versionId,codes)).build(),codes);
    }
    @Override public java.util.List<SemanticCatalogSnapshot.Model> findModelsByCodes(Long projectId, Long versionId, java.util.Set<String> codes) {
        return codes == null || codes.isEmpty() ? java.util.List.of() : mapper.findModelsForModels(projectId,versionId,codes);
    }
    @Override public java.util.List<SemanticCatalogSnapshot.Model> findModelsByTables(Long projectId, Long versionId, java.util.Set<String> tables,int limit) {
        return tables == null || tables.isEmpty() || limit <= 0 ? java.util.List.of() : mapper.findModelsForTables(projectId,versionId,tables,limit);
    }
    @Override public java.util.List<SemanticCatalogSnapshot.Relationship> findRelationshipsTouching(Long projectId, Long versionId, java.util.Set<String> codes,int limit) {
        return codes == null || codes.isEmpty() || limit <= 0 ? java.util.List.of() : mapper.findRelationshipsTouching(projectId,versionId,codes,limit);
    }
    @Override public String authoritativeCatalogHash(Long projectId, Long versionId) {
        return mapper.authoritativeCatalogHash(projectId,versionId);
    }

    @Override public java.util.List<SemanticCatalogSnapshot.Model> findEnabledModelSummaries(Long projectId,Long versionId,int limit) {
        return limit<=0 ? java.util.List.of() : mapper.findEnabledModelSummaries(projectId,versionId,limit);
    }
    @Override public java.util.Set<Integer> enabledDatasourceIds(Long projectId,Long versionId) {
        return mapper.enabledDatasourceIds(projectId,versionId);
    }
    @Override public java.util.Set<String> enabledPhysicalTables(Long projectId,Long versionId) {
        return mapper.enabledPhysicalTables(projectId,versionId);
    }
    @Override public java.util.List<AssetOwner> findAssetOwners(Long projectId,Long versionId,String type,java.util.Set<String> keys) {
        return keys==null || keys.isEmpty() ? java.util.List.of() : mapper.findAssetOwners(projectId,versionId,type,keys);
    }
    @Override public java.util.List<SemanticCatalogSnapshot.Rule> findGlobalRules(Long projectId,Long versionId) {
        return mapper.findGlobalRules(projectId,versionId);
    }
    @Override public java.util.List<SemanticCatalogSnapshot.Model> findModelSummaryPage(Long projectId,Long versionId,String afterModelCode,int limit) {
        return limit<=0 ? java.util.List.of() : mapper.findModelSummaryPage(projectId,versionId,afterModelCode,limit);
    }
    @Override public java.util.List<BindingTerm> findBindingTermPage(Long projectId,Long versionId,String type,long afterId,int limit) {
        return mapper.findBindingTermPage(projectId,versionId,type,afterId,limit,null);
    }

    @Override public java.util.List<BindingTerm> searchBindingTermPage(Long projectId,Long versionId,String type,
            String query,long afterId,int limit) {
        return mapper.findBindingTermPage(projectId,versionId,type,afterId,limit,query);
    }


    private void persistSharedMetadata(SemanticCatalogSnapshot snapshot) {
        var json=new cn.lgs.semevosql.common.json.CanonicalJson();
        var definitions=new java.util.HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for(var definition:snapshot.getSharedDefinitions()) {
            String code=definition.path("code").asText();int revision=definition.path("revision").asInt();
            var row=sharedRow(snapshot,code,revision);row.put("type",definition.path("type").asText());
            row.put("json",json.write(definition));row.put("hash",cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.hash(definition));
            mapper.insertSharedDefinition(row);
            if(!row.get("hash").equals(mapper.sharedDefinitionHash(row)))throw new IllegalArgumentException("Definition revision already has different immutable content");
            mapper.linkSharedDefinition(row);definitions.put(code+"@"+revision,definition);
        }
        for(var dictionary:snapshot.getEnumDictionaries()) {
            String code=dictionary.path("code").asText();int revision=dictionary.path("revision").asInt();
            var row=sharedRow(snapshot,code,revision);var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)dictionary.deepCopy();metadata.remove("entries");
            row.put("json",json.write(metadata));row.put("hash",cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.hash(dictionary));
            int created=mapper.insertSharedDictionary(row);
            if(!row.get("hash").equals(mapper.sharedDictionaryHash(row)))throw new IllegalArgumentException("Dictionary revision already has different immutable content");
            if(created==1) {
                int ordinal=0;for(var entry:dictionary.path("entries")) {
                    var item=sharedRow(snapshot,code,revision);item.put("ordinal",ordinal++);item.put("value",json.write(entry.path("value")));
                    item.put("json",json.write(entry));
                    if(mapper.insertSharedDictionaryEntry(item)!=1)throw new IllegalArgumentException("Duplicate dictionary entry identity");
                }
            }
            mapper.linkSharedDictionary(row);
        }
        for(var binding:snapshot.getModelBindings()) {
            String model=binding.path("model").asText(),code=binding.path("code").asText();
            var definition=definitions.get(binding.path("definition").asText()+"@"+binding.path("definitionRevision").asInt());
            if(definition==null)throw new IllegalArgumentException("Unknown binding definition revision");
            var row=sharedRow(snapshot,code,binding.path("definitionRevision").asInt());row.put("model",model);
            row.put("definition",binding.path("definition").asText());row.put("type",definition.path("type").asText());
            row.put("asset",definition.path("type").asText().equals("ATTRIBUTE")?
                binding.path("attributeMappings").path(definition.path("specification").path("attribute").asText()).asText():
                cn.lgs.semevosql.semantic.application.SharedCatalogProtocol.assetCode(model,code));
            row.put("dictionary",binding.has("dictionary")?binding.path("dictionary").path("code").asText():null);
            row.put("dictionaryRevision",binding.has("dictionary")?binding.path("dictionary").path("revision").asInt():null);
            row.put("json",json.write(binding));mapper.insertSharedBinding(row);
        }
    }
    private java.util.Map<String,Object> sharedRow(SemanticCatalogSnapshot snapshot,String code,int revision) {
        var row=new java.util.HashMap<String,Object>();row.put("project",snapshot.getProjectId());row.put("version",snapshot.getProjectVersionId());
        row.put("code",code);row.put("revision",revision);return row;
    }
    private SemanticCatalogSnapshot sharedMetadata(SemanticCatalogSnapshot snapshot,java.util.Set<String> models) {
        var project=snapshot.getProjectId();var version=snapshot.getProjectVersionId();
        snapshot.setSharedDefinitions(parseShared(mapper.findSharedDefinitions(project,version,models),false));
        snapshot.setModelBindings(parseShared(mapper.findSharedBindings(project,version,models),true));
        snapshot.setEnumDictionaries(parseShared(mapper.findSharedDictionaries(project,version,models),false));
        snapshot.setLegacyModelBindings(parseShared(mapper.findLegacyBindings(project,version,models),true));
        cn.lgs.semevosql.semantic.application.SharedCatalogProtocol.attachReferences(snapshot);return snapshot;
    }
    private java.util.List<com.fasterxml.jackson.databind.JsonNode> parseShared(java.util.List<String> rows,boolean binding) {
        return cn.lgs.semevosql.semantic.application.SharedCatalogProtocol.canonicalRows(rows.stream()
            .map(cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol::parse).toList(),binding);
    }
}
