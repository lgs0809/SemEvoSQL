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

import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.*;
import org.springframework.stereotype.Service;

/** Resolve explicitly requested asset identities before loading their model details. */
@Service
public class SemanticCatalogLookupService {
    private static final Set<String> TYPES=Set.of("MODEL","METRIC","DIMENSION","RELATIONSHIP","GRAIN","RULE","ENUM_VALUE","COLUMN","TIME_COLUMN");
    private final SemanticCatalogRepository repository;
    private final SemanticCatalogReadService catalogReader;

    public SemanticCatalogLookupService(SemanticCatalogRepository repository, SemanticCatalogReadService catalogReader) {
        this.repository=repository;
        this.catalogReader=catalogReader;
    }

    public record AssetRef(String assetType,String assetKey) {
        public AssetRef {
            if(assetType==null || assetKey==null || assetKey.isBlank())
                throw new IllegalArgumentException("Asset type and key are required");
            assetType=assetType.trim().toUpperCase(Locale.ROOT);
            assetKey=assetKey.trim();
            if(!TYPES.contains(assetType)) throw new IllegalArgumentException("Unsupported asset type: "+assetType);
        }
    }

    public SemanticCatalogSnapshot loadAssets(Long projectId,Long versionId,String expectedHash,Collection<AssetRef> references) {
        if(references==null || references.isEmpty()) throw new IllegalArgumentException("Explicit asset identities are required");
        var refs=new LinkedHashSet<>(references);
        if(refs.size()>200) throw new IllegalArgumentException("Too many requested assets; split the detail request");
        return catalogReader.readFrozen(projectId, versionId, expectedHash,
            scope -> loadAssets(projectId, versionId, refs, scope));
    }

    public SemanticCatalogSnapshot loadCurrentAssets(Long projectId,Long versionId,Collection<AssetRef> refs) {
        if(refs==null || refs.isEmpty() || refs.size()>200) throw new IllegalArgumentException("Bounded explicit assets are required");
        return catalogReader.readCurrent(projectId,versionId,scope -> loadAssets(projectId,versionId,new LinkedHashSet<>(refs),scope));
    }

    public record BindingRef(AssetRef asset, String phrase) {}

    /** A personal text name has no public asset identity; use its phrase only to find explicit terms. */
    public record BindingPhrase(String assetType, String phrase) {
        public BindingPhrase {
            if (!Set.of("METRIC", "DIMENSION", "ENUM_VALUE").contains(assetType)
                    || phrase == null || phrase.isBlank()) throw new IllegalArgumentException("Invalid binding phrase");
        }
    }

    /** Stale personal aliases may be absent. Only resolved owners and explicit longer terms load details. */
    public SemanticCatalogSnapshot loadRuntimeBindings(Long projectId,Long versionId,Collection<BindingRef> refs,String query) {
        return loadRuntimeBindings(projectId,versionId,refs,List.of(),query);
    }

    public SemanticCatalogSnapshot loadRuntimeBindings(Long projectId,Long versionId,Collection<BindingRef> refs,
            Collection<BindingPhrase> textPhrases,String query) {
        if(refs==null || textPhrases==null || refs.size()+textPhrases.size()>200)
            throw new IllegalArgumentException("Bounded binding references are required");
        return catalogReader.readCurrent(projectId,versionId,scope -> {
            var grouped=new LinkedHashMap<String,Set<String>>();
            var phrases=new LinkedHashMap<String,Set<String>>();
            refs.forEach(ref -> {
                grouped.computeIfAbsent(ref.asset().assetType(),ignored->new LinkedHashSet<>()).add(ref.asset().assetKey());
                phrases.computeIfAbsent(ref.asset().assetType(),ignored->new LinkedHashSet<>()).add(normalizeTerm(ref.phrase()));
            });
            textPhrases.forEach(ref -> phrases.computeIfAbsent(ref.assetType(),ignored->new LinkedHashSet<>())
                .add(normalizeTerm(ref.phrase())));
            var models=new LinkedHashSet<String>();
            grouped.forEach((type,keys) -> repository.findAssetOwners(projectId,versionId,type,keys).forEach(owner -> models.add(owner.modelCode())));
            // Preserve the existing Unicode matching semantics. Page only names/identities;
            // no complete schema, expression, description or model body is read for unrelated assets.
            String normalized=normalizeTerm(query);
            for(String type:phrases.keySet()) {
                if(!Set.of("METRIC","DIMENSION","ENUM_VALUE").contains(type)) continue;
                long cursor=0;
                while(true) {
                    var page=repository.findBindingTermPage(projectId,versionId,type,cursor,256);
                    for(var term:page) {
                        if(java.util.stream.Stream.of(term.businessName(),term.termCode()).map(SemanticCatalogLookupService::normalizeTerm)
                                .anyMatch(text->!text.isBlank() && normalized.contains(text) && phrases.get(type).stream()
                                    .anyMatch(phrase->!phrase.isBlank() && text.length()>phrase.length() && text.contains(phrase)))) models.add(term.modelCode());
                    }
                    SemanticCatalogReadService.requireModelBudget(models);
                    if(page.size()<256) break;
                    long next=page.get(page.size()-1).id();
                    if(next<=cursor) throw new IllegalStateException("Binding summary cursor did not advance");
                    cursor=next;
                }
            }
            return models.isEmpty()?SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(versionId).build():scope.models(models);
        });
    }

    /** Pre-planning checks read compact identities first, then details only for relevant models. */
    public SemanticCatalogSnapshot loadClarificationContext(Long projectId, Long versionId, String query,
            Collection<String> physicalTables, Collection<String> boundModels, Set<String> genericTerms) {
        return catalogReader.readCurrent(projectId, versionId, scope -> {
            var models = new LinkedHashSet<String>(boundModels);
            if (physicalTables != null && !physicalTables.isEmpty()) models.addAll(scope.resolvePhysicalModels(physicalTables));
            if (models.isEmpty()) {
                String normalized = normalizeTerm(query);
                var genericModels = new LinkedHashSet<String>();
                for (String type : List.of("MODEL", "METRIC", "DIMENSION", "COLUMN", "ENUM_VALUE")) {
                    long cursor = 0;
                    while (true) {
                        var page = repository.findBindingTermPage(projectId, versionId, type, cursor, 256);
                        for (var term : page) {
                            var names = java.util.stream.Stream.of(term.businessName(),term.termCode())
                                .map(SemanticCatalogLookupService::normalizeTerm).filter(name->!name.isBlank()).toList();
                            if (names.stream().anyMatch(normalized::contains)) models.add(term.modelCode());
                            if (genericModels.size() <= SemanticCatalogReadService.MAX_QUERY_MODELS && names.stream()
                                    .anyMatch(name->genericTerms.stream().anyMatch(t->normalized.contains(t) && name.contains(t))))
                                genericModels.add(term.modelCode());
                        }
                        SemanticCatalogReadService.requireModelBudget(models);
                        if (page.size() < 256) break;
                        long next = page.get(page.size()-1).id();
                        if (next <= cursor) throw new IllegalStateException("Binding summary cursor did not advance");
                        cursor = next;
                    }
                }
                if (models.isEmpty()) models.addAll(genericModels);
                if (models.isEmpty()) {
                    var only = repository.findModelSummaryPage(projectId,versionId,null,2);
                    if (only.size()==1) models.add(only.get(0).getModelCode());
                }
            }
            SemanticCatalogReadService.requireModelBudget(models);
            return models.isEmpty() ? SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(versionId)
                .rules(repository.findGlobalRules(projectId,versionId)).build() : scope.models(models);
        });
    }

    public record BindingPage(List<SemanticCatalogRepository.BindingTerm> terms, boolean hasMore, Long nextAfterId) {}

    public BindingPage bindingPage(Long projectId, Long versionId, String type, String query, long afterId, int size) {
        if (!Set.of("METRIC","DIMENSION","ENUM_VALUE","TIME_COLUMN").contains(type)
                || afterId < 0 || size < 1 || size > 100 || query != null && query.length() > 200)
            throw new IllegalArgumentException("Invalid binding search type, cursor, query or page size");
        return catalogReader.readCurrent(projectId,versionId,scope -> {
            var found = repository.searchBindingTermPage(projectId,versionId,type,query==null?null:query.trim(),afterId,size+1);
            boolean more = found.size() > size;
            var page = found.stream().limit(size).toList();
            return new BindingPage(page,more,more?page.get(page.size()-1).id():null);
        });
    }

    private static String normalizeTerm(String value) {
        return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+","").trim();
    }

    private SemanticCatalogSnapshot loadAssets(Long projectId, Long versionId, Set<AssetRef> refs,
            SemanticCatalogReadService.ReadScope scope) {
        Map<String,Set<String>> grouped=new LinkedHashMap<>();
        for(var ref:refs) {
            Objects.requireNonNull(ref,"Asset reference is required");
            grouped.computeIfAbsent(ref.assetType(),key->new LinkedHashSet<>()).add(ref.assetKey());
        }
        Set<String> models=new LinkedHashSet<>();
        grouped.forEach((type,keys)->{
            var owners=repository.findAssetOwners(projectId,versionId,type,keys);
            var found=owners.stream().map(SemanticCatalogRepository.AssetOwner::assetKey).collect(java.util.stream.Collectors.toSet());
            if(!found.equals(keys)) throw new IllegalArgumentException("Unknown or disabled requested "+type+" asset");
            if(owners.size()!=found.size()) throw new IllegalArgumentException("Ambiguous "+type+" asset identity within this version");
            for(var owner:owners) {
                if(owner.modelCode()!=null && !owner.modelCode().isBlank()) models.add(owner.modelCode());
                if(owner.relatedModelCode()!=null && !owner.relatedModelCode().isBlank()) models.add(owner.relatedModelCode());
            }
        });
        SemanticCatalogReadService.requireModelBudget(models);
        return models.isEmpty()
            ? SemanticCatalogSnapshot.builder().projectId(projectId).projectVersionId(versionId)
                .rules(repository.findGlobalRules(projectId,versionId)).build()
            : scope.models(models);
    }

    public record ModelSummary(String modelCode,String businessName,String modelType) {}
    public record ModelPage(Long projectId,Long projectVersionId,String catalogHash,List<ModelSummary> models,
            boolean hasMore,String nextModelCode) {}

    public ModelPage modelPage(Long projectId,Long versionId,String expectedHash,String afterModelCode,int pageSize) {
        if(pageSize<1 || pageSize>100) throw new IllegalArgumentException("Model page size must be between 1 and 100");
        return catalogReader.readFrozen(projectId, versionId, expectedHash, scope -> {
        var rows=repository.findModelSummaryPage(projectId,versionId,afterModelCode,pageSize+1);
        boolean more=rows.size()>pageSize;
        var page=rows.stream().limit(pageSize).map(model->new ModelSummary(model.getModelCode(),model.getBusinessName(),model.getModelType())).toList();
        return new ModelPage(projectId,versionId,expectedHash,page,more,more?page.get(page.size()-1).modelCode():null);
        });
    }

}
