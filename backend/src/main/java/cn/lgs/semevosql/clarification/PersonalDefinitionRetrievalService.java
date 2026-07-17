/*
 * Copyright 2026 the original author or authors.
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

import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.retrieval.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/** Soft owner-only candidates. A model must select the actual confirmed meaning before use. */
@Service
public class PersonalDefinitionRetrievalService {
    private static final Logger LOG=LoggerFactory.getLogger(PersonalDefinitionRetrievalService.class);
    private final PersonalDefinitionRetrievalRepository documents;
    private final PersonalSemanticDefinitionStore definitions;
    private final EmbeddingModel embedding;
    private final EmbeddingModelIdentityProvider identities;
    private final RerankModelProvider reranker;
    public PersonalDefinitionRetrievalService(PersonalDefinitionRetrievalRepository documents,PersonalSemanticDefinitionStore definitions,
            Optional<EmbeddingModel> embedding,Optional<EmbeddingModelIdentityProvider> identities,Optional<RerankModelProvider> reranker) {
        this.documents=documents;this.definitions=definitions;this.embedding=embedding.orElse(null);
        this.identities=identities.orElse(null);this.reranker=reranker.orElse(null);
    }
    public List<SemanticBlueprint.BindingDependency> retrieve(Long project,String principal,String query) {
        if(project==null||principal==null||principal.isBlank()||RuntimePrincipalResolver.ANONYMOUS.equals(principal)||query==null||query.isBlank())return List.of();
        var lexical=documents.lexical(project,principal,query,20);
        List<PersonalDefinitionRetrievalRepository.Hit> vector=List.of();
        try {
            var identity=identity();
            if(embedding!=null&&documents.hasVectors(project,principal,identity)) {
                var vectors=EmbeddingModelSupport.embedTexts(embedding,List.of(query));
                if(vectors.size()==1&&Objects.equals(identity,identity()))vector=documents.vector(project,principal,vectors.get(0),identity,20);
            }
        }catch(RuntimeException unavailable){LOG.warn("Private vector recall deferred after {}; owner-scoped text remains available",unavailable.getClass().getSimpleName());}
        var selected=DefinitionCandidateRanking.rank(query,
            lexical.stream().map(h->new DefinitionCandidateRanking.Hit<>(h.preferenceId(),h)).toList(),
            vector.stream().map(h->new DefinitionCandidateRanking.Hit<>(h.preferenceId(),h)).toList(),
            PersonalDefinitionRetrievalRepository.Hit::text,reranker);
        var result=new ArrayList<SemanticBlueprint.BindingDependency>();
        for(var hit:selected) {
            // Re-read current authority after network work; never feed a corrected/archived revision to a new plan.
            PersonalSemanticDefinitionStore.Definition d;
            try{d=definitions.current(hit.preferenceId());}catch(IllegalArgumentException stale){continue;}
            if(!Objects.equals(project,d.projectId())||!Objects.equals(principal,d.principal())||d.revision()!=hit.revision()||!Objects.equals(d.contentHash(),hit.hash()))continue;
            result.add(reference(d));
        }
        return List.copyOf(result);
    }
    public EmbeddingEncodingIdentity identity() {
        if(identities==null)return null;
        return identities.currentEmbeddingIdentity().map(i->EmbeddingEncodingIdentity.configured(i.model(),i.attributes())).orElse(null);
    }
    public static SemanticBlueprint.BindingDependency reference(PersonalSemanticDefinitionStore.Definition d) {
        return SemanticBlueprint.BindingDependency.builder().phrase(d.phrase()).assetType(d.assetType()).assetKey(d.assetKey())
            .source("USER").scope("USER_CANDIDATE").principalId(d.principal()).sourceRecordId(d.preferenceId()).sourceRevision(d.revision())
            .sourceContentHash(d.contentHash()).dependencyFingerprint(d.dependencyFingerprint()).definitionText(d.text()).build();
    }
}
