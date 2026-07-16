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
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Deterministically lowers shared meaning plus explicit model roles into governed existing assets. */
public final class SharedCatalogProtocol {
    private SharedCatalogProtocol() {}
    public record Expansion(ObjectNode legacy,List<JsonNode> definitions,List<JsonNode> bindings,
            List<JsonNode> dictionaries,Map<String,SemanticDefinitionBinding> references) {}
    public static String assetCode(String model,String binding) {
        return "b_"+OfflineCatalogProtocol.hash(List.of(model,binding)).substring(7,39);
    }
    public static Expansion expand(JsonNode input) {
        return expand(input,true);
    }
    static Expansion expand(JsonNode input,boolean requireEvidence) {
        var pack=(ObjectNode)input.deepCopy();var catalog=(ObjectNode)pack.path("catalog");
        var definitions=index(catalog.path("definitions"),true);var dictionaries=index(catalog.path("enumDictionaries"),true);
        var models=index(catalog.path("entities"),false);var references=new LinkedHashMap<String,SemanticDefinitionBinding>();
        var targets=new HashSet<String>();var bindingIds=new HashSet<String>();
        definitions.keySet().forEach(k->targets.add("definition:"+k));dictionaries.keySet().forEach(k->targets.add("dictionary:"+k));
        var evidence=new HashMap<String,List<JsonNode>>();
        input.path("evidence").forEach(e->evidence.computeIfAbsent(e.path("target").asText(),k->new ArrayList<>()).add(e));
        if(requireEvidence)for(String target:targets)require(evidence.containsKey(target),"MISSING_SHARED_EVIDENCE");
        for(var binding:catalog.path("bindings")) {
            String model=binding.path("model").asText(),code=binding.path("code").asText(),key=model+"/"+code;
            require(bindingIds.add(key),"DUPLICATE_MODEL_BINDING");String target="binding:"+key;targets.add(target);
            if(requireEvidence)require(evidence.containsKey(target),"MISSING_SHARED_EVIDENCE");
            var entity=models.get(model);require(entity!=null,"UNKNOWN_BINDING_MODEL");
            String identity=binding.path("definition").asText()+"@"+binding.path("definitionRevision").asInt();
            var definition=definitions.get(identity);require(definition!=null,"UNKNOWN_DEFINITION_REVISION");
            var spec=definition.path("specification");String type=definition.path("type").asText();
            var attrs=index(entity.path("attributes"),false);var mappings=binding.path("attributeMappings");
            var parameters=index(spec.path("parameters"),false);var names=new HashSet<String>();mappings.fieldNames().forEachRemaining(names::add);
            require(names.equals(parameters.keySet()),"BINDING_PARAMETER_SET");
            for(var parameter:parameters.entrySet()) {
                var actual=attrs.get(mappings.path(parameter.getKey()).asText());require(actual!=null,"UNKNOWN_BINDING_ATTRIBUTE");
                require(actual.path("dataType").equals(parameter.getValue().path("dataType")),"BINDING_PARAMETER_TYPE");
            }
            String asset=assetCode(model,code),role=binding.path("roleName").asText();
            var aliases=new LinkedHashSet<String>();definition.path("aliases").forEach(a->aliases.add(a.asText()));binding.path("aliases").forEach(a->aliases.add(a.asText()));
            String attribute=spec.has("attribute")?mapped(mappings,spec.path("attribute").asText()):null;
            ObjectNode row;
            if(type.equals("METRIC")) {
                require(!binding.has("dictionary"),"METRIC_DICTIONARY_UNSUPPORTED");
                Set<String> keys=new HashSet<>();spec.path("grainKeys").forEach(k->keys.add(mapped(mappings,k.asText())));
                require(keys.equals(new HashSet<>(list(entity.path("primaryKey")))),"SHARED_METRIC_GRAIN_MISMATCH");
                row=(ObjectNode)rewrite(spec,mappings);row.remove(List.of("parameters","grainKeys"));
                row.put("code",asset);row.put("entity",model);row.put("name",role);row.put("description",definition.path("description").asText());
                if(definition.has("retrieval"))row.set("retrieval",definition.get("retrieval"));catalog.withArray("metrics").add(row);
            } else if(type.equals("DIMENSION")) {
                row=JsonUtil.getObjectMapper().createObjectNode();row.put("code",asset);row.put("entity",model);row.put("attribute",attribute);
                row.put("name",role);row.put("description",definition.path("description").asText());
                if(definition.has("retrieval"))row.set("retrieval",definition.get("retrieval"));catalog.withArray("dimensions").add(row);
            } else {
                row=(ObjectNode)attrs.get(attribute);row.put("name",role);row.put("description",definition.path("description").asText());
                if(spec.has("unit"))row.set("unit",spec.get("unit"));
                if(definition.has("retrieval"))row.set("retrieval",definition.get("retrieval"));asset=attribute;
            }
            String dictionary=null;Integer dictionaryRevision=null;
            if(binding.has("dictionary")) {
                var use=binding.get("dictionary");dictionary=use.path("code").asText();dictionaryRevision=use.path("revision").asInt();
                var dictionaryRecord=dictionaries.get(dictionary+"@"+dictionaryRevision);require(dictionaryRecord!=null,"UNKNOWN_DICTIONARY_REVISION");
                attachDictionary((ObjectNode)attrs.get(attribute),dictionaryRecord,use);
            }
            require(references.putIfAbsent(type+":"+model+":"+asset,new SemanticDefinitionBinding(definition.path("code").asText(),
                definition.path("revision").asInt(),model,code,role,List.copyOf(aliases),dictionary,dictionaryRevision))==null,"DUPLICATE_BINDING_TARGET");
            String legacyTarget=type.equals("ATTRIBUTE")?"entity:"+model+"/attribute:"+attribute:type.toLowerCase(Locale.ROOT)+":"+asset;
            for(var original:evidence.getOrDefault(target,List.of())) {
                var mapped=original.deepCopy();((ObjectNode)mapped).put("target",legacyTarget);pack.withArray("evidence").add(mapped);
            }
        }
        // Only known canonical evidence is projected. Unknown targets still fail the ordinary semantic checker.
        var kept=JsonUtil.getObjectMapper().createArrayNode();pack.path("evidence").forEach(e->{if(!targets.contains(e.path("target").asText()))kept.add(e);});pack.set("evidence",kept);
        for(var issue:pack.path("unresolvedIssues"))if(targets.contains(issue.path("target").asText())) {
            require(!issue.path("blocking").asBoolean(),"UNRESOLVED_SHARED_DEFINITION");((ObjectNode)issue).put("target","catalog");
        }
        catalog.remove(List.of("definitions","bindings","enumDictionaries"));pack.put("formatVersion","1.1");
        return new Expansion(pack,List.copyOf(definitions.values()),nodes(input.path("catalog").path("bindings")),
            List.copyOf(dictionaries.values()),Map.copyOf(references));
    }
    public static List<JsonNode> canonicalRows(List<JsonNode> rows,boolean modelBinding) {
        var json=new cn.lgs.semevosql.common.json.CanonicalJson();
        return rows.stream().map(json::canonicalNode).sorted(Comparator.comparing(row->modelBinding?
            row.path("model").asText()+"/"+row.path("code").asText():row.path("code").asText()+"@"+row.path("revision").asInt())).toList();
    }
    public static void attachReferences(SemanticCatalogSnapshot snapshot) {
        SharedCatalogSnapshotValidator.validate(snapshot);
        var definitions=new HashMap<String,JsonNode>();snapshot.getSharedDefinitions().forEach(d->definitions.put(d.path("code").asText()+"@"+d.path("revision").asInt(),d));
        var models=new HashSet<String>();snapshot.getModels().forEach(m->models.add(m.getModelCode()));
        for(var binding:snapshot.getModelBindings()) {
            String model=binding.path("model").asText();require(models.contains(model),"BINDING_MODEL_OUTSIDE_SNAPSHOT");
            var definition=definitions.get(binding.path("definition").asText()+"@"+binding.path("definitionRevision").asInt());require(definition!=null,"UNKNOWN_DEFINITION_REVISION");
            String type=definition.path("type").asText(),asset=assetCode(model,binding.path("code").asText());
            var aliases=new LinkedHashSet<String>();definition.path("aliases").forEach(a->aliases.add(a.asText()));binding.path("aliases").forEach(a->aliases.add(a.asText()));
            var ref=new SemanticDefinitionBinding(definition.path("code").asText(),definition.path("revision").asInt(),model,
                binding.path("code").asText(),binding.path("roleName").asText(),List.copyOf(aliases),
                binding.has("dictionary")?binding.path("dictionary").path("code").asText():null,
                binding.has("dictionary")?binding.path("dictionary").path("revision").asInt():null);
            if(type.equals("METRIC")) {
                var row=snapshot.getMetrics().stream().filter(m->model.equals(m.getModelCode()) && asset.equals(m.getMetricCode())).findFirst().orElseThrow(()->new IllegalArgumentException("MISSING_BOUND_METRIC"));row.setDefinitionBinding(ref);
            } else if(type.equals("DIMENSION")) {
                var row=snapshot.getDimensions().stream().filter(d->model.equals(d.getModelCode()) && asset.equals(d.getDimensionCode())).findFirst().orElseThrow(()->new IllegalArgumentException("MISSING_BOUND_DIMENSION"));row.setDefinitionBinding(ref);
            } else {
                String field=binding.path("attributeMappings").path(definition.path("specification").path("attribute").asText()).asText();
                var row=snapshot.getColumns().stream().filter(c->model.equals(c.getModelCode()) && field.equals(c.getColumnName())).findFirst().orElseThrow(()->new IllegalArgumentException("MISSING_BOUND_ATTRIBUTE"));row.setDefinitionBinding(ref);
            }
            if(binding.has("dictionary")) {
                String field=binding.path("attributeMappings").path(definition.path("specification").path("attribute").asText()).asText();
                var dictionary=snapshot.getEnumDictionaries().stream().filter(d->ref.dictionaryCode().equals(d.path("code").asText()) && ref.dictionaryRevision()==d.path("revision").asInt()).findFirst().orElseThrow();
                var mappings=binding.path("dictionary").path("valueMappings");var enumAliases=new HashMap<String,List<String>>();
                for(var entry:dictionary.path("entries")) {
                    if(mappings.isEmpty())enumAliases.put(entry.path("value").asText(),list(entry.path("aliases")));
                    else for(var mapping:mappings)if(mapping.path("target").equals(entry.path("value")))enumAliases.put(mapping.path("source").asText(),list(entry.path("aliases")));
                }
                for(var value:snapshot.getEnumValues())if(model.equals(value.getModelCode()) && field.equals(value.getColumnName())) {
                    var expected=enumAliases.getOrDefault(value.getValueCode(),List.of());
                    require(value.getConfirmedAliases().isEmpty() || value.getConfirmedAliases().equals(expected),"DICTIONARY_ALIAS_PROJECTION_MISMATCH");value.setConfirmedAliases(expected);
                }
            }
        }
    }
    private static void attachDictionary(ObjectNode attribute,JsonNode dictionary,JsonNode use) {
        var entries=new LinkedHashMap<String,JsonNode>();
        for(var entry:dictionary.path("entries"))require(entries.putIfAbsent(entry.path("value").toString(),entry)==null,"DUPLICATE_DICTIONARY_CODE");
        var mappings=use.path("valueMappings");var values=JsonUtil.getObjectMapper().createArrayNode();var seen=new HashSet<String>();
        if(mappings.isEmpty())for(var entry:entries.values())values.add(enumValue(entry.path("value"),entry,attribute.path("dataType").asText()));
        else for(var mapping:mappings) {
            require(seen.add(mapping.path("source").toString()),"DUPLICATE_DICTIONARY_MAPPING");
            var entry=entries.get(mapping.path("target").toString());require(entry!=null,"UNKNOWN_DICTIONARY_CODE");
            values.add(enumValue(mapping.path("source"),entry,attribute.path("dataType").asText()));
        }
        // Existing hand-authored enum facts cannot be silently contradicted by a reusable dictionary.
        if(attribute.has("enumValues") && !attribute.path("enumValues").isEmpty()) {
            var actual=new HashMap<String,String>();values.forEach(v->actual.put(v.path("value").toString(),v.path("label").asText()));
            for(var old:attribute.path("enumValues"))require(Objects.equals(actual.get(old.path("value").toString()),old.path("label").asText()),"DICTIONARY_ENUM_CONFLICT");
        }
        attribute.set("enumValues",values);
    }
    private static ObjectNode enumValue(JsonNode value,JsonNode entry,String type) {
        boolean valid=switch(type){case "integer"->value.isIntegralNumber();case "decimal"->value.isNumber();case "boolean"->value.isBoolean();default->value.isTextual();};
        require(valid,"DICTIONARY_SOURCE_TYPE");var row=JsonUtil.getObjectMapper().createObjectNode();row.set("value",value);row.set("label",entry.get("label"));return row;
    }
    private static JsonNode rewrite(JsonNode input,JsonNode mappings) {
        if(input.isObject()) {
            var result=JsonUtil.getObjectMapper().createObjectNode();input.fields().forEachRemaining(e->{
                require(!e.getKey().equals("metric"),"SHARED_METRIC_REFERENCE_REQUIRES_EXPLICIT_BINDING");
                if(Set.of("attribute","timeAttribute").contains(e.getKey()) && !e.getValue().isNull())result.put(e.getKey(),mapped(mappings,e.getValue().asText()));
                else result.set(e.getKey(),rewrite(e.getValue(),mappings));});return result;
        }
        if(input.isArray()){var result=JsonUtil.getObjectMapper().createArrayNode();input.forEach(v->result.add(rewrite(v,mappings)));return result;}
        return input.deepCopy();
    }
    private static String mapped(JsonNode mappings,String parameter) {require(mappings.has(parameter),"UNKNOWN_DEFINITION_PARAMETER");return mappings.get(parameter).asText();}
    private static Map<String,JsonNode> index(JsonNode rows,boolean revisioned) {
        var result=new LinkedHashMap<String,JsonNode>();for(var row:rows){String key=row.path("code").asText()+(revisioned?"@"+row.path("revision").asInt():"");require(result.putIfAbsent(key,row)==null,"DUPLICATE_SHARED_ID");}return result;
    }
    private static List<JsonNode> nodes(JsonNode rows){var result=new ArrayList<JsonNode>();rows.forEach(result::add);return List.copyOf(result);}
    private static List<String> list(JsonNode rows){var result=new ArrayList<String>();rows.forEach(v->result.add(v.asText()));return result;}
    private static void require(boolean valid,String reason){if(!valid)throw new IllegalArgumentException(reason);}
}
