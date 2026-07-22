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
import cn.lgs.semevosql.semantic.application.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.retrieval.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Suggestions become executable only after the current reader confirms an immutable own definition. */
@Service
public class ProjectDefinitionCandidateService {
    private static final Pattern OPTION=Pattern.compile("PROJECT_CANDIDATE_([1-9][0-9]*)_([1-9][0-9]*)");
    private final ProjectDefinitionCandidateRepository candidates;
    private final PersonalSemanticDefinitionStore definitions;
    private final SemanticCatalogReadService catalogs;
    private final EmbeddingModel embedding;
    private final PersonalDefinitionRetrievalService personal;
    private final RerankModelProvider reranker;
    public ProjectDefinitionCandidateService(ProjectDefinitionCandidateRepository candidates,PersonalSemanticDefinitionStore definitions,
            SemanticCatalogReadService catalogs,Optional<EmbeddingModel> embedding,PersonalDefinitionRetrievalService personal,
            Optional<RerankModelProvider> reranker) {
        this.candidates=candidates;this.definitions=definitions;this.catalogs=catalogs;
        this.embedding=embedding.orElse(null);this.personal=personal;this.reranker=reranker.orElse(null);
    }
    public List<SemanticCandidateSet.ProjectSuggestion> retrieve(Long project,Long version,String principal,String query) {
        if(project==null||version==null||principal==null||principal.isBlank()||RuntimePrincipalResolver.ANONYMOUS.equals(principal))return List.of();
        var lexical=candidates.lexical(project,query,20);List<ProjectDefinitionCandidateRepository.Hit> vector=List.of();
        try {
            var identity=personal.identity();
            if(embedding!=null&&candidates.hasVectors(project,identity)) {
                var vectors=EmbeddingModelSupport.embedTexts(embedding,List.of(query));
                if(vectors.size()==1&&Objects.equals(identity,personal.identity()))vector=candidates.vector(project,vectors.get(0),identity,20);
            }
        }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shared suggestion vector recall unavailable after {}; using authorized text",unavailable.getClass().getSimpleName());}
        var hits=DefinitionCandidateRanking.rank(query,
            lexical.stream().map(h->new DefinitionCandidateRanking.Hit<>(h.candidate().id(),h)).toList(),
            vector.stream().map(h->new DefinitionCandidateRanking.Hit<>(h.candidate().id(),h)).toList(),h->h.candidate().indexText(),reranker);
        var result=new ArrayList<SemanticCandidateSet.ProjectSuggestion>();
        for(var hit:hits) {
            var visible=candidates.visible(project,hit.candidate().id());
            if(visible.isEmpty())continue;var c=visible.get();
            if(c.revision()!=hit.candidate().revision()||!c.hash().equals(hit.candidate().hash()))continue;
            if(candidates.alreadyConfirmed(project,principal,c.id(),c.revision()))continue;
            var source=definitions.require(c.preference(),c.sourceRevision());
            if(source.principal().equals(principal))try {
                if(definitions.current(source.preferenceId()).revision()==source.revision())continue;
            }catch(IllegalArgumentException archived){ /* Another shared contributor may still authorize this meaning. */ }
            try {
                String model=validateDependencies(c,version);
                result.add(new SemanticCandidateSet.ProjectSuggestion(c.id(),c.revision(),c.name(),c.text(),model,
                    source.structured()==null?"TEXT_ONLY":"STRUCTURED_REFERENCE"));
            }catch(IllegalArgumentException|IllegalStateException|SecurityException unsafe){ /* Invalid dependencies cannot be offered for adoption. */ }
        }
        return List.copyOf(result);
    }
    public String validateDependencies(ProjectDefinitionCandidateRepository.Candidate candidate,Long version) {
        var d=definitions.require(candidate.preference(),candidate.sourceRevision());
        if(d.projectId()!=candidate.project()||!d.text().equals(candidate.text()))throw new SecurityException("Shared source differs from confirmed meaning");
        if("NEEDS_RECONFIRMATION".equals(d.taskState()))throw new IllegalStateException("Shared dependencies invalid");
        if(!"TEXT_DEFINITION".equals(d.assetType())) {
            String model=d.snapshot().path("model").path("modelCode").asText();
            var current=catalogs.getForModels(candidate.project(),version,Set.of(model));
            if(!Objects.equals(d.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(current,d.assetType(),d.assetKey()).dependencyFingerprint()))
                throw new IllegalStateException("Shared dependencies changed");
            return model;
        }
        if(d.structured()==null)return null;
        var structure=d.structured();
        if(!"personal-metric-1.1".equals(structure.path("protocol").asText()))throw new IllegalArgumentException("Unsupported shared structure");
        if(!d.contentHash().equals(structure.path("sourceContentHash").asText())||d.revision()!=structure.path("sourceRevision").asInt())
            throw new SecurityException("Shared structure source mismatch");
        String model=structure.path("metric").path("entity").asText();
        var current=catalogs.getForModels(candidate.project(),version,Set.of(model)).detachedCopy();
        var metric=OfflineCatalogProtocol.projectPrivateMetric(structure.path("metric"),current);current.getMetrics().add(metric);
        if(!PersonalDefinitionSnapshot.capture(current,"METRIC",metric.getMetricCode()).dependencyFingerprint()
                .equals(structure.path("dependencyFingerprint").asText()))throw new IllegalStateException("Shared dependencies changed");
        return model;
    }
    public static String option(long id,int revision){return "PROJECT_CANDIDATE_"+id+"_"+revision;}
    public static boolean candidateOption(String option){return option!=null&&OPTION.matcher(option).matches();}
    public void freezeOptions(String question,Long project,Long version,String principal,List<SemanticPlanningOutcome.Option> options) {
        for(var option:options)if(candidateOption(option.code())) {
            var match=OPTION.matcher(option.code());match.matches();
            var candidate=candidates.visible(project,Long.parseLong(match.group(1))).orElseThrow(()->conflict("Shared suggestion is no longer visible"));
            if(candidate.revision()!=Integer.parseInt(match.group(2))||!candidate.text().equals(option.label()))
                throw conflict("Shared suggestion content changed before question creation");
            validateDependencies(candidate,version);candidates.freeze(question,option.code(),candidate,principal);
        }
    }
    public Optional<ProjectDefinitionCandidateRepository.Candidate> accepted(String question,String option,Long project,Long version,String principal) {
        if(!candidateOption(option))return Optional.empty();
        var base=candidates.questionBase(question,option,project,principal).orElseThrow(()->conflict("Shared confirmation base is missing"));
        candidates.lock(base.id());
        var current=candidates.visible(project,base.id()).orElseThrow(()->conflict("Shared suggestion was withdrawn or rejected"));
        if(base.revision()!=current.revision()||!base.hash().equals(current.hash())||!base.text().equals(current.text()))
            throw conflict("Shared definition changed; refresh and confirm the new meaning");
        validateDependencies(current,version);return Optional.of(current);
    }
    public void adopt(ProjectDefinitionCandidateRepository.Candidate candidate,Long version,String principal,String phrase,
            String sourceId,PersonalSemanticDefinitionStore.Sharing sharing,int expectedRevision) {
        var original=definitions.require(candidate.preference(),candidate.sourceRevision());
        var snapshot=original.snapshot().deepCopy();
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot;
        object.set("projectCandidate",JsonUtil.getObjectMapper().valueToTree(Map.of("id",candidate.id(),"contentRevision",candidate.revision())));
        definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(candidate.project(),principal,phrase,candidate.text(),
            original.assetType(),"TEXT_DEFINITION".equals(original.assetType())?UserSemanticPreferenceService.normalizePhrase(phrase):original.assetKey(),
            phrase,"PROJECT_ADOPTION",sourceId,version,snapshot,original.dependencyFingerprint(),sharing,expectedRevision));
    }
    private static ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
}
