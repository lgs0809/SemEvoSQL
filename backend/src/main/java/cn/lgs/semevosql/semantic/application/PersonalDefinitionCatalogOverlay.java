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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.springframework.stereotype.Service;

/** Per-operation, owner-scoped projection. Private metrics never enter public catalogs or caches. */
@Service
public class PersonalDefinitionCatalogOverlay {
    private final PersonalSemanticDefinitionStore definitions;
    private final SemanticCatalogReadService reader;
    private PersonalPublicDefinitionService publicDefinitions;
    @org.springframework.beans.factory.annotation.Autowired
    public void publicDefinitions(PersonalPublicDefinitionService service){this.publicDefinitions=service;}
    public PersonalDefinitionCatalogOverlay(PersonalSemanticDefinitionStore definitions,SemanticCatalogReadService reader) {
        this.definitions=definitions;this.reader=reader;
    }
    public Slice prepare(Long project,Long version,String principal,List<SemanticBlueprint.BindingDependency> references) {
        return prepare(project, version, principal, references, false);
    }
    /** Selection previews retain immutable provenance and physical authorization; consent is checked after selection. */
    public Slice prepare(Long project,Long version,String principal,List<SemanticBlueprint.BindingDependency> references,
            boolean selectionPending) {
        var frozen=new ArrayList<SemanticBlueprint.BindingDependency>();var metrics=new ArrayList<SemanticCatalogSnapshot.Metric>();
        var relatedModels=new java.util.LinkedHashSet<String>();
        for(var reference:references) {
            if(!"USER".equals(reference.getSource())||reference.getSourceRevision()==null)continue;
            if(reference.getSourceRecordId()==null||!Objects.equals(principal,reference.getPrincipalId()))throw new SecurityException("Private definition owner required");
            var d=definitions.require(reference.getSourceRecordId(),reference.getSourceRevision());
            requireSource(project,principal,reference,d);
            var copy=JsonUtil.getObjectMapper().convertValue(reference,SemanticBlueprint.BindingDependency.class);
            if(!"TEXT_DEFINITION".equals(d.assetType())) {
                String model=d.snapshot().path("model").path("modelCode").asText();
                if(model.isBlank())throw new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED");
                var catalog=reader.getForModels(project,version,Set.of(model));
                if(!Objects.equals(d.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(catalog,d.assetType(),d.assetKey()).dependencyFingerprint())) {
                    if("USER_CANDIDATE".equals(reference.getScope()) && !selectionPending)continue;
                    if(!"METRIC".equals(d.assetType()))throw new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED");
                    String current=PersonalDefinitionSnapshot.capture(catalog,d.assetType(),d.assetKey()).dependencyFingerprint();
                    if(!selectionPending && (publicDefinitions==null||!publicDefinitions.acknowledged(d,d.assetType(),d.assetKey(),current)))
                        throw new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED");
                    var metric=FrozenPersonalMetric.project(d,catalog);
                    copy.setRepresentationCode(metric.getMetricCode());copy.setRepresentationHash(d.contentHash());metrics.add(metric);
                }
                relatedModels.add(model);
            }
            if("TEXT_DEFINITION".equals(d.assetType())&&"STRUCTURED_ACTIVE".equals(d.representation())
                    &&d.structured()!=null&&"personal-metric-1.1".equals(d.structured().path("protocol").asText())) {
                var structure=definitions.currentStructure(d);var json=structure.content();
                if(json.path("sourceRevision").asInt()!=d.revision()||!d.contentHash().equals(json.path("sourceContentHash").asText()))
                    throw new SecurityException("Structured definition source mismatch");
                String model=json.path("metric").path("entity").asText();
                var catalog=reader.getForModels(project,version,Set.of(model));
                var metric=OfflineCatalogProtocol.projectPrivateMetric(json.path("metric"),catalog);
                if(!metric.getMetricCode().equals("p_"+d.preferenceId()+"_"+d.revision()))throw new SecurityException("Private metric identity mismatch");
                var scope=copyCatalog(catalog);scope.getMetrics().add(metric);
                String dependency=PersonalDefinitionSnapshot.capture(scope,"METRIC",metric.getMetricCode()).dependencyFingerprint();
                if(!dependency.equals(json.path("dependencyFingerprint").asText()))
                    throw new IllegalStateException("PERSONAL_DEFINITION_RECONFIRMATION_REQUIRED");
                copy.setRepresentationCode(metric.getMetricCode());copy.setRepresentationHash(structure.hash());metrics.add(metric);
            }
            frozen.add(copy);
        }
        return new Slice(List.copyOf(frozen),List.copyOf(metrics),Set.copyOf(relatedModels));
    }
    public static void requireSource(Long project,String principal,SemanticBlueprint.BindingDependency b,PersonalSemanticDefinitionStore.Definition d) {
        if(!Objects.equals(project,d.projectId())||!Objects.equals(principal,d.principal())
                ||!Objects.equals(d.contentHash(),b.getSourceContentHash())||!Objects.equals(d.assetType(),b.getAssetType())
                ||!Objects.equals(d.assetKey(),b.getAssetKey())||!Objects.equals(d.text(),b.getDefinitionText())
                ||!Objects.equals(d.dependencyFingerprint(),b.getDependencyFingerprint()))throw new SecurityException("Private semantic definition provenance mismatch");
    }
    public SemanticCatalogSnapshot readApproved(Long project,Long version,String expectedHash,String principal,SemanticBlueprint plan) {
        var models=plan.getModels().stream().map(SemanticBlueprint.ModelSelection::getModelCode).collect(java.util.stream.Collectors.toSet());
        var catalog=expectedHash==null?reader.getForModels(project,version,models):reader.getForModels(project,version,models,expectedHash);
        return frozenCatalog(catalog,principal,plan);
    }
    /** Restore the exact approved structures, including after a newer personal correction. */
    public SemanticCatalogSnapshot frozenCatalog(SemanticCatalogSnapshot publicCatalog,String principal,SemanticBlueprint plan) {
        var catalog=copyCatalog(publicCatalog);
        for(var reference:plan.getBindingDependencies()) {
            if("PROJECT_CANDIDATE".equals(reference.getSource()))throw new SecurityException("Unconfirmed project suggestion cannot execute");
            if(!"USER".equals(reference.getSource()))continue;
            if(reference.getSourceRecordId()==null||!Objects.equals(principal,reference.getPrincipalId()))throw new SecurityException("Private definition owner required");
            if(reference.getSourceRevision()==null) {
                if(reference.getRepresentationCode()!=null||"TEXT_DEFINITION".equals(reference.getAssetType()))throw new SecurityException("Private source revision required");
                var legacy=definitions.current(reference.getSourceRecordId());
                if(!Objects.equals(legacy.projectId(),catalog.getProjectId())||!Objects.equals(legacy.principal(),principal))throw new SecurityException("Legacy private owner mismatch");
                continue;
            }
            var definition=definitions.require(reference.getSourceRecordId(),reference.getSourceRevision());
            requireSource(catalog.getProjectId(),principal,reference,definition);
            if(reference.getRepresentationCode()==null)continue;
            if("METRIC".equals(definition.assetType())) {
                if(!definition.contentHash().equals(reference.getRepresentationHash()))throw new SecurityException("Frozen personal asset snapshot changed");
                var metric=FrozenPersonalMetric.project(definition,catalog);
                if(!metric.getMetricCode().equals(reference.getRepresentationCode()))throw new SecurityException("Frozen personal asset code changed");
                var selected=plan.getMetrics().stream().filter(m->metric.getMetricCode().equals(m.getMetricCode())).toList();
                if(selected.size()!=1||!sameMeaning(metric,selected.get(0)))throw new SecurityException("Approved private metric meaning changed");
                catalog.getMetrics().add(metric);continue;
            }
            var structure=definitions.structure(definition.preferenceId(),definition.revision(),reference.getRepresentationHash()).content();
            if(!Objects.equals(definition.contentHash(),structure.path("sourceContentHash").asText())
                ||definition.revision()!=structure.path("sourceRevision").asInt())throw new SecurityException("Private structure source mismatch");
            var metric=OfflineCatalogProtocol.projectPrivateMetric(structure.path("metric"),catalog);
            if(!Objects.equals(metric.getMetricCode(),reference.getRepresentationCode())
                ||!metric.getMetricCode().equals("p_"+definition.preferenceId()+"_"+definition.revision()))throw new SecurityException("Private metric identity mismatch");
            if(catalog.getMetrics().stream().anyMatch(m->Objects.equals(m.getMetricCode(),metric.getMetricCode())))
                throw new SecurityException("Private metric identity collision");
            catalog.getMetrics().add(metric);
            if(!Objects.equals(structure.path("dependencyFingerprint").asText(),PersonalDefinitionSnapshot.capture(catalog,"METRIC",metric.getMetricCode()).dependencyFingerprint()))
                throw new SecurityException("Private metric dependency changed");
            var selected=plan.getMetrics().stream().filter(m->Objects.equals(m.getMetricCode(),metric.getMetricCode())).toList();
            if(selected.size()!=1||!sameMeaning(metric,selected.get(0)))throw new SecurityException("Approved private metric meaning changed");
        }
        var privateCodes=catalog.getMetrics().stream().map(SemanticCatalogSnapshot.Metric::getMetricCode).collect(java.util.stream.Collectors.toSet());
        if(plan.getMetrics().stream().anyMatch(m->m.getMetricCode()!=null&&m.getMetricCode().matches("p_\\d+_\\d+")&&!privateCodes.contains(m.getMetricCode())))
            throw new SecurityException("Private metric has no confirmed source");
        return catalog;
    }
    private static boolean sameMeaning(SemanticCatalogSnapshot.Metric m,SemanticBlueprint.MetricSelection selected) {
        return Objects.equals(m.getModelCode(),selected.getModelCode())&&Objects.equals(m.getExpression(),selected.getExpression())
            &&Objects.equals(m.getFilterExpression(),selected.getFilterExpression())&&Objects.equals(m.getAggregation(),selected.getAggregation())
            &&Objects.equals(m.getTimeColumn(),selected.getTimeColumn())&&Objects.equals(m.getUnit(),selected.getUnit());
    }
    private static SemanticCatalogSnapshot copyCatalog(SemanticCatalogSnapshot source) {
        return source.detachedCopy();
    }
    public record Slice(List<SemanticBlueprint.BindingDependency> references,List<SemanticCatalogSnapshot.Metric> metrics,Set<String> relatedModels) {
        public Slice(List<SemanticBlueprint.BindingDependency> references,List<SemanticCatalogSnapshot.Metric> metrics){this(references,metrics,Set.of());}
        public static Slice empty(){return new Slice(List.of(),List.of());}
        public Set<String> modelCodes(){var result=new java.util.LinkedHashSet<>(relatedModels);metrics.stream().map(SemanticCatalogSnapshot.Metric::getModelCode).forEach(result::add);return Set.copyOf(result);}
        public SemanticCandidateSet candidates(SemanticCandidateSet publicCandidates) {
            var combined=new ArrayList<>(publicCandidates.metrics());
            for(var metric:metrics) {
                if(!publicCandidates.modelCodes().contains(metric.getModelCode()))throw new SecurityException("Private model outside candidate scope");
                if(combined.stream().anyMatch(m->m.getMetricCode().equals(metric.getMetricCode())))throw new SecurityException("Private metric code collides with public identity");
                combined.add(metric);
            }
            return new SemanticCandidateSet(publicCandidates.projectId(),publicCandidates.projectVersionId(),publicCandidates.catalogHash(),
                publicCandidates.physicalTables(),publicCandidates.models(),combined,publicCandidates.dimensions(),publicCandidates.enumValues(),
                publicCandidates.querySelectableRules(),publicCandidates.mandatoryGovernanceRules(),publicCandidates.planningPolicies(),
                publicCandidates.relationships(),publicCandidates.grains(),publicCandidates.timeColumns(),publicCandidates.filterableColumns(),
                publicCandidates.retrievalEvidence(),references);
        }
        public SemanticCatalogSnapshot catalog(SemanticCatalogSnapshot publicCatalog) {
            var copy=copyCatalog(publicCatalog);var models=copy.getModels().stream().map(SemanticCatalogSnapshot.Model::getModelCode).collect(java.util.stream.Collectors.toSet());
            metrics.stream().filter(m->models.contains(m.getModelCode())).forEach(copy.getMetrics()::add);return copy;
        }
        public List<SemanticBlueprint.BindingDependency> selected(QueryCaseHints binding,List<Long> textIds) {
            for(var b:references)if(textIds.contains(b.getSourceRecordId())&&!"TEXT_DEFINITION".equals(b.getAssetType())
                    &&!(b.getRepresentationCode()!=null&&binding.metricCodes().contains(b.getRepresentationCode()))&&!selectedAsset(b,binding))
                throw new IllegalArgumentException("Selected private mapping requires its actual governed target");
            return references.stream().filter(b->textIds.contains(b.getSourceRecordId())
                ||b.getRepresentationCode()!=null&&binding.metricCodes().contains(b.getRepresentationCode())
                ||"USER".equals(b.getScope())&&selectedAsset(b,binding))
                .map(b->{var frozen=JsonUtil.getObjectMapper().convertValue(b,SemanticBlueprint.BindingDependency.class);frozen.setScope("USER");return frozen;})
                .toList();
        }
        private static boolean selectedAsset(SemanticBlueprint.BindingDependency b,QueryCaseHints binding) {
            return "METRIC".equals(b.getAssetType())&&binding.metricCodes().contains(b.getAssetKey())
                ||"DIMENSION".equals(b.getAssetType())&&binding.dimensionCodes().contains(b.getAssetKey())
                ||"ENUM_VALUE".equals(b.getAssetType())&&binding.enumBindings().stream().anyMatch(e->b.getAssetKey().equals(e.modelCode()+":"+e.columnName()+":"+e.valueCode()))
                ||"TIME_COLUMN".equals(b.getAssetType())&&binding.timeBinding()!=null&&b.getAssetKey().equals(binding.timeBinding().modelCode()+":"+binding.timeBinding().columnName());
        }
    }
}
