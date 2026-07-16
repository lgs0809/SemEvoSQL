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

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Shared definitions are authoritative; legacy-shaped assets are checked deterministic projections. */
final class SharedCatalogSnapshotValidator {
    private SharedCatalogSnapshotValidator() {}
    static void validate(SemanticCatalogSnapshot snapshot) {
        var json=JsonUtil.getObjectMapper();var catalog=json.createObjectNode();
        catalog.set("definitions",json.valueToTree(snapshot.getSharedDefinitions()));
        catalog.set("bindings",json.valueToTree(snapshot.getModelBindings()));
        catalog.set("enumDictionaries",json.valueToTree(snapshot.getEnumDictionaries()));
        OfflineCatalogProtocol.validateSharedMetadata(catalog);
        if(snapshot.getSharedDefinitions().isEmpty() && snapshot.getModelBindings().isEmpty() && snapshot.getEnumDictionaries().isEmpty()) {
            snapshot.getMetrics().forEach(m->checkReference(m.getDefinitionBinding(),null));
            snapshot.getDimensions().forEach(d->checkReference(d.getDefinitionBinding(),null));
            snapshot.getColumns().forEach(c->checkReference(c.getDefinitionBinding(),null));return;
        }
        var attrs=new LinkedHashMap<String,Map<String,JsonNode>>();var entities=catalog.putArray("entities");
        for(var model:snapshot.getModels()) {
            var entity=entities.addObject();entity.put("code",model.getModelCode());var columns=entity.putArray("attributes");
            var modelAttrs=new LinkedHashMap<String,JsonNode>();attrs.put(model.getModelCode(),modelAttrs);
            for(var column:snapshot.getColumns())if(model.getModelCode().equals(column.getModelCode())) {
                var a=columns.addObject();a.put("code",column.getColumnName());a.put("dataType",SourceSchemaExportService.protocolType(column.getDataType()));
                a.put("name",column.getBusinessName());a.put("description",column.getDescription());
                if(column.getUnit()!=null)a.put("unit",column.getUnit());if(column.getRetrieval()!=null)a.set("retrieval",json.valueToTree(column.getRetrieval()));
                var enums=a.putArray("enumValues");
                for(var value:snapshot.getEnumValues())if(model.getModelCode().equals(value.getModelCode()) && column.getColumnName().equals(value.getColumnName())) {
                    var row=enums.addObject();row.set("value",typedValue(value.getValueCode(),SourceSchemaExportService.protocolType(column.getDataType())));row.put("label",value.getBusinessName());
                }
                require(modelAttrs.putIfAbsent(column.getColumnName(),a)==null,"DUPLICATE_BINDING_ATTRIBUTE");
            }
            var grains=snapshot.getGrains().stream().filter(g->model.getModelCode().equals(g.getModelCode()) && g.getStatus()==SemanticAssetStatus.ENABLED).toList();
            var key=entity.putArray("primaryKey");
            if(!grains.isEmpty() && snapshot.getModelBindings().stream().anyMatch(b->model.getModelCode().equals(b.path("model").asText()))) {
                String grain=grains.get(0).getKeyColumns();
                require(grains.stream().allMatch(g->Objects.equals(grain,g.getKeyColumns())),"AMBIGUOUS_BINDING_GRAIN");
                if(grain!=null)for(String field:grain.split(","))key.add(field.trim());
            }
        }
        catalog.putArray("metrics");catalog.putArray("dimensions");
        var pack=json.createObjectNode();pack.set("catalog",catalog);pack.putArray("evidence");pack.putArray("unresolvedIssues");
        var expanded=SharedCatalogProtocol.expand(pack,false);var expected=expanded.legacy().path("catalog");
        var metrics=new LinkedHashMap<String,JsonNode>();expected.path("metrics").forEach(m->metrics.put(m.path("code").asText(),m));
        for(var definition:metrics.values()) {
            var wanted=OfflineCatalogProtocol.projectMetric(definition,attrs,metrics);
            var actual=snapshot.getMetrics().stream().filter(m->wanted.getModelCode().equals(m.getModelCode()) && wanted.getMetricCode().equals(m.getMetricCode())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("MISSING_BOUND_METRIC"));
            same(wanted,actual,List.of("businessName","expression","aggregation","unit","timeColumn","filterExpression","description","retrieval",
                "minimumValue","maximumValue","minimumInclusive","maximumInclusive"));
        }
        for(var d:expected.path("dimensions")) {
            var actual=snapshot.getDimensions().stream().filter(a->d.path("entity").asText().equals(a.getModelCode()) && d.path("code").asText().equals(a.getDimensionCode())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("MISSING_BOUND_DIMENSION"));
            var wanted=SemanticCatalogSnapshot.Dimension.builder().businessName(d.path("name").asText()).columnName(d.path("attribute").asText())
                .description(d.path("description").asText()).dimensionType("ATTRIBUTE").build();
            if(d.has("retrieval"))wanted.setRetrieval(RetrievalHints.decode(d.get("retrieval").toString()));
            same(wanted,actual,List.of("businessName","columnName","description","dimensionType","expression","retrieval"));
        }
        for(var binding:snapshot.getModelBindings()) {
            var reference=expanded.references().values().stream().filter(r->r.modelCode().equals(binding.path("model").asText()) && r.bindingCode().equals(binding.path("code").asText())).findFirst().orElseThrow();
            var definition=snapshot.getSharedDefinitions().stream().filter(d->d.path("code").asText().equals(reference.definitionCode()) && d.path("revision").asInt()==reference.definitionRevision()).findFirst().orElseThrow();
            if(binding.has("dictionary")) {
                String field=binding.path("attributeMappings").path(definition.path("specification").path("attribute").asText()).asText();
                var wanted=new TreeMap<String,String>();var actual=new TreeMap<String,String>();
                for(var e:expected.path("entities"))if(reference.modelCode().equals(e.path("code").asText()))for(var c:e.path("attributes"))if(field.equals(c.path("code").asText()))
                    c.path("enumValues").forEach(v->wanted.put(v.path("value").asText(),v.path("label").asText()));
                snapshot.getEnumValues().stream().filter(v->reference.modelCode().equals(v.getModelCode()) && field.equals(v.getColumnName()) && v.getStatus()==SemanticAssetStatus.ENABLED)
                    .forEach(v->require(actual.putIfAbsent(v.getValueCode(),v.getBusinessName())==null,"DUPLICATE_DICTIONARY_PROJECTION"));
                require(wanted.equals(actual),"DICTIONARY_PROJECTION_MISMATCH");
            }
            if(definition.path("type").asText().equals("ATTRIBUTE")) {
                String field=binding.path("attributeMappings").path(definition.path("specification").path("attribute").asText()).asText();
                var a=snapshot.getColumns().stream().filter(c->reference.modelCode().equals(c.getModelCode()) && field.equals(c.getColumnName())).findFirst().orElseThrow();
                var projected=expected.path("entities");JsonNode wanted=null;
                for(var e:projected)if(reference.modelCode().equals(e.path("code").asText()))for(var c:e.path("attributes"))if(field.equals(c.path("code").asText()))wanted=c;
                require(wanted!=null && wanted.path("name").asText().equals(a.getBusinessName()) && wanted.path("description").asText().equals(a.getDescription()),"BOUND_ATTRIBUTE_PROJECTION_MISMATCH");
                if(definition.path("specification").has("unit"))require(Objects.equals(a.getUnit(),definition.path("specification").path("unit").asText()),"BOUND_ATTRIBUTE_PROJECTION_MISMATCH");
                if(definition.has("retrieval"))require(Objects.equals(a.getRetrieval(),RetrievalHints.decode(definition.get("retrieval").toString())),"BOUND_ATTRIBUTE_PROJECTION_MISMATCH");
            }
        }
        snapshot.getMetrics().forEach(m->checkReference(m.getDefinitionBinding(),expanded.references().get("METRIC:"+m.getModelCode()+":"+m.getMetricCode())));
        snapshot.getDimensions().forEach(d->checkReference(d.getDefinitionBinding(),expanded.references().get("DIMENSION:"+d.getModelCode()+":"+d.getDimensionCode())));
        snapshot.getColumns().forEach(c->checkReference(c.getDefinitionBinding(),expanded.references().get("ATTRIBUTE:"+c.getModelCode()+":"+c.getColumnName())));
    }
    private static void checkReference(SemanticDefinitionBinding actual,SemanticDefinitionBinding wanted) {
        require(actual==null || actual.equals(wanted),"FORGED_OR_STALE_DEFINITION_BINDING");
    }
    private static JsonNode typedValue(String value,String type) {
        try {return switch(type) {case "integer"->JsonNodeFactory.instance.numberNode(new java.math.BigInteger(value));
            case "decimal"->JsonNodeFactory.instance.numberNode(new java.math.BigDecimal(value));case "boolean"->{require(Set.of("true","false").contains(value),"ENUM_TYPE");yield BooleanNode.valueOf(Boolean.parseBoolean(value));}
            default->TextNode.valueOf(value);};}catch(NumberFormatException invalid){throw new IllegalArgumentException("ENUM_TYPE",invalid);}
    }
    private static void same(Object wanted,Object actual,List<String> fields) {
        var json=JsonUtil.getObjectMapper();var left=json.valueToTree(wanted);var right=json.valueToTree(actual);
        for(String field:fields) {
            var a=left.path(field);var b=right.path(field);
            boolean equal=a.isNumber() && b.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:Objects.equals(a,b);
            require(equal,"BOUND_PROJECTION_MISMATCH:"+field);
        }
    }
    private static void require(boolean valid,String reason) {if(!valid)throw new IllegalArgumentException(reason);}
}
