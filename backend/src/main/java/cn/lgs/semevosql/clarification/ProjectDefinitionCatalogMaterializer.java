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

import cn.lgs.semevosql.semantic.application.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Publishes through the existing shared-definition/model-binding contract, preserving private identities. */
public final class ProjectDefinitionCatalogMaterializer {
    public static String bindingCode(long candidate){return "candidate_"+candidate;}
    public static String assetCode(long candidate,JsonNode structure) {
        return SharedCatalogProtocol.assetCode(structure.path("metric").path("entity").asText(),bindingCode(candidate));
    }
    public static SemanticCatalogSnapshot add(long candidate,int contentRevision,String name,String text,
            JsonNode structure,SemanticCatalogSnapshot base) {
        return addBinding(candidate,name,text,structure,base,bindingCode(candidate),"project_definition_"+candidate,contentRevision);
    }
    public static SemanticCatalogSnapshot materialize(ProjectDefinitionPublicationRepository.Work work,SemanticCatalogSnapshot base) {
        String name=work.publicName()==null?work.name():work.publicName();
        if(!"OVERWRITE".equals(work.action()))return add(work.candidate(),work.contentRevision(),name,work.text(),work.structure(),base);
        var catalog=base.detachedCopy();
        var target=catalog.getMetrics().stream().filter(m->m.getMetricCode().equals(work.target())).findFirst()
            .orElseThrow(()->new IllegalStateException("PUBLIC_TARGET_MISSING"));
        if(!target.getModelCode().equals(work.structure().path("metric").path("entity").asText()))
            throw new IllegalStateException("PUBLIC_TARGET_MODEL_CHANGED");
        catalog.setMetrics(new ArrayList<>(catalog.getMetrics()));catalog.getMetrics().remove(target);
        var ref=target.getDefinitionBinding();
        if(ref!=null) {
            catalog.setModelBindings(catalog.getModelBindings().stream().filter(b->!b.path("model").asText().equals(ref.modelCode())
                ||!b.path("code").asText().equals(ref.bindingCode())).toList());
            int nextRevision=catalog.getSharedDefinitions().stream().filter(d->ref.definitionCode().equals(d.path("code").asText()))
                .mapToInt(d->d.path("revision").asInt()).max().orElse(ref.definitionRevision())+1;
            var result=addBinding(work.candidate(),name,work.text(),work.structure(),catalog,ref.bindingCode(),ref.definitionCode(),nextRevision);
            result.getModelBindings().stream().filter(b->b.path("model").asText().equals(ref.modelCode())&&b.path("code").asText().equals(ref.bindingCode()))
                .forEach(b->{var aliases=((ObjectNode)b).putArray("aliases");ref.confirmedAliases().forEach(aliases::add);});
            SharedCatalogProtocol.attachReferences(result);
            return result;
        }
        // Legacy assets keep their stable identity. Their controlled AST projection is registered as a new
        // immutable legacy definition by the existing repository; unrelated bindings and old versions stay intact.
        var projected=(ObjectNode)work.structure().path("metric").deepCopy();
        projected.put("code",target.getMetricCode());projected.put("name",name);projected.put("description",work.text());
        var metric=OfflineCatalogProtocol.projectPrivateMetric(projected,catalog);
        metric.setEvidence("Administrator approved controlled definition; candidate="+work.candidate()+",decision="+work.decision());
        catalog.getMetrics().add(metric);
        return catalog;
    }
    private static SemanticCatalogSnapshot addBinding(long candidate,String name,String text,
            JsonNode structure,SemanticCatalogSnapshot base,String roleBinding,String definitionCode,int definitionRevision) {
        var metric=structure.path("metric");String model=metric.path("entity").asText();
        var catalog=base.detachedCopy();
        if(catalog.getMetrics().stream().anyMatch(m->UserSemanticPreferenceService.normalizePhrase(name)
                .equals(UserSemanticPreferenceService.normalizePhrase(m.getBusinessName()))))
            throw new IllegalStateException("PUBLIC_NAME_COLLISION");
        var grains=catalog.getGrains().stream().filter(g->model.equals(g.getModelCode())&&g.getStatus()==SemanticAssetStatus.ENABLED).toList();
        if(grains.isEmpty()||grains.get(0).getKeyColumns()==null||grains.get(0).getKeyColumns().isBlank()
                ||grains.stream().anyMatch(g->!Objects.equals(g.getKeyColumns(),grains.get(0).getKeyColumns())))
            throw new IllegalStateException("PUBLIC_GRAIN_UNRESOLVED");
        var json=JsonUtil.getObjectMapper();var definition=json.createObjectNode();
        definition.put("code",definitionCode);definition.put("revision",definitionRevision);definition.put("type","METRIC");
        definition.put("name",name);definition.put("description",text);definition.putArray("aliases");
        var spec=(ObjectNode)metric.deepCopy();spec.remove(List.of("code","name","description","entity"));
        var keys=spec.putArray("grainKeys");for(String field:grains.get(0).getKeyColumns().split(","))keys.add(field.trim());
        var parameters=spec.putArray("parameters");catalog.getColumns().stream().filter(c->model.equals(c.getModelCode()))
            .sorted(Comparator.comparing(SemanticCatalogSnapshot.Column::getColumnName)).forEach(c->{
                var parameter=parameters.addObject();parameter.put("code",c.getColumnName());
                parameter.put("dataType",SourceSchemaExportService.protocolType(c.getDataType()));
            });
        definition.set("specification",spec);
        var binding=json.createObjectNode();binding.put("model",model);binding.put("code",roleBinding);
        binding.put("definition",definitionCode);binding.put("definitionRevision",definitionRevision);binding.put("roleName",name);binding.putArray("aliases");
        var mappings=binding.putObject("attributeMappings");catalog.getColumns().stream().filter(c->model.equals(c.getModelCode()))
            .forEach(c->mappings.put(c.getColumnName(),c.getColumnName()));
        catalog.setSharedDefinitions(new ArrayList<>(catalog.getSharedDefinitions()));catalog.getSharedDefinitions().add(definition);
        catalog.setModelBindings(new ArrayList<>(catalog.getModelBindings()));catalog.getModelBindings().add(binding);
        var projected=(ObjectNode)metric.deepCopy();projected.put("code",SharedCatalogProtocol.assetCode(model,roleBinding));projected.put("name",name);projected.put("description",text);
        var publicMetric=OfflineCatalogProtocol.projectPrivateMetric(projected,catalog);
        publicMetric.setDefinitionBinding(new SemanticDefinitionBinding(definitionCode,definitionRevision,model,roleBinding,name,List.of(),null,null));
        publicMetric.setEvidence("Explicitly shared confirmed definition; candidate="+candidate+",definitionRevision="+definitionRevision);
        catalog.setMetrics(new ArrayList<>(catalog.getMetrics()));catalog.getMetrics().add(publicMetric);
        SharedCatalogProtocol.attachReferences(catalog);
        return catalog;
    }
    private ProjectDefinitionCatalogMaterializer() {}
}
