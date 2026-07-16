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

import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.semantic.compiler.GovernedModelSourceRenderer;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared versioned JSON Schema plus semantic, mapping and dependency checks. Never executes input. */
public final class OfflineCatalogProtocol {
    private static final ObjectMapper JSON=JsonUtil.getObjectMapper().copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Map<String,Schema> PACKAGES=Map.of("1.0",schema("semantic-catalog.schema.json"),
        "1.1",schema("semantic-catalog-1.1.schema.json"),"1.2",schema("semantic-catalog-1.2.schema.json"));
    private static final Schema SOURCE=schema("source-schema.schema.json");
    private static final Schema SHARED_METADATA=sharedMetadataSchema();
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(256).maxStringLength(65536).build()); }
    private OfflineCatalogProtocol() {}
    private static Schema schema(String resource) {
        try(var input=OfflineCatalogProtocol.class.getResourceAsStream("/semantic-catalog-protocol/"+resource)) {
            if(input==null)throw new IllegalStateException("Missing shared protocol resource");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(JSON.readTree(input));
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    private static Schema sharedMetadataSchema() {
        try(var input=OfflineCatalogProtocol.class.getResourceAsStream("/semantic-catalog-protocol/semantic-catalog-1.2.schema.json")) {
            var original=JSON.readTree(input);var root=JSON.createObjectNode();
            root.put("type","object");root.put("additionalProperties",false);root.set("$defs",original.get("$defs"));
            var properties=root.putObject("properties");var required=root.putArray("required");
            for(String key:List.of("definitions","bindings","enumDictionaries")) {
                properties.set(key,original.path("properties").path("catalog").path("properties").get(key));required.add(key);
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(root);
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    static void validateSharedMetadata(JsonNode metadata) { validate(SHARED_METADATA,metadata); }
    /** The same controlled metric AST is used for private representations; no second formula language. */
    public static SemanticCatalogSnapshot.Metric projectPrivateMetric(JsonNode metric,SemanticCatalogSnapshot catalog) {
        try(var input=OfflineCatalogProtocol.class.getResourceAsStream("/semantic-catalog-protocol/semantic-catalog-1.1.schema.json")) {
            var source=JSON.readTree(input);var schema=JSON.createObjectNode();schema.set("$defs",source.get("$defs"));
            schema.put("$ref","#/$defs/metric");validate(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema),metric);
        }catch(java.io.IOException error){throw new IllegalStateException("Metric contract unavailable",error);}
        var visibility=new SemanticCatalogVisibility(catalog);var attrs=new LinkedHashMap<String,Map<String,JsonNode>>();
        for(var model:catalog.getModels())if(model.isEnabled())attrs.put(model.getModelCode(),new LinkedHashMap<>());
        for(var c:catalog.getColumns())if(visibility.maySendColumn(c)&&Boolean.TRUE.equals(c.getAllowAggregation())&&Boolean.TRUE.equals(c.getAllowFilter())) {
            var row=JSON.createObjectNode();row.put("code",c.getColumnName());row.put("dataType",SourceSchemaExportService.protocolType(c.getDataType()));
            if(attrs.containsKey(c.getModelCode()))attrs.get(c.getModelCode()).put(c.getColumnName(),row);
        }
        var result=projectMetric(metric,attrs,Map.of(metric.path("code").asText(),metric));
        require(catalog.getModels().stream().anyMatch(m->m.isEnabled()&&m.getModelCode().equals(result.getModelCode())),"PRIVATE_MODEL_UNAVAILABLE");
        return result;
    }

    public static JsonNode parse(String raw) {
        if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IllegalArgumentException("Catalog import exceeds 4 MiB");
        try {return JSON.readTree(raw);}catch(Exception error){throw new IllegalArgumentException("Catalog import must be strict UTF-8 JSON",error);}
    }
    public static String hash(Object value) {
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalArgumentException("Cannot fingerprint protocol",error);}
    }
    public static SemanticCatalogSnapshot convert(JsonNode pack,JsonNode source,List<ProjectDatasourceBinding> bindings) {
        var contract=PACKAGES.get(pack.path("formatVersion").asText());
        require(contract!=null,"UNSUPPORTED_FORMAT_VERSION");validate(contract,pack);validate(SOURCE,source);
        if(pack.path("formatVersion").asText().equals("1.2")) {
            var expanded=SharedCatalogProtocol.expand(pack);
            var snapshot=convert(expanded.legacy(),source,bindings);
            snapshot.setSharedDefinitions(SharedCatalogProtocol.canonicalRows(expanded.definitions(),false));
            snapshot.setModelBindings(SharedCatalogProtocol.canonicalRows(expanded.bindings(),true));
            snapshot.setEnumDictionaries(SharedCatalogProtocol.canonicalRows(expanded.dictionaries(),false));
            SharedCatalogProtocol.attachReferences(snapshot);return snapshot;
        }
        require(pack.path("sourceSchemaFingerprint").asText().equals(hash(source)),"SCHEMA_FINGERPRINT_MISMATCH");
        var cat=pack.path("catalog");var entities=index(cat.path("entities"));var metrics=index(cat.path("metrics"));
        var dimensions=index(cat.path("dimensions"));var relationships=index(cat.path("relationships"));var rules=index(cat.path("rules"));
        var physical=new HashMap<String,JsonNode>();
        for(var table:source.path("tables"))require(physical.putIfAbsent(tableKey(table),table)==null,"DUPLICATE_TABLE");
        Map<String,ProjectDatasourceBinding> domains=new HashMap<>();
        for(var binding:bindings)require(domains.putIfAbsent(binding.getDomainCode(),binding)==null,"DUPLICATE_DATASOURCE_DOMAIN");
        var evidence=new HashMap<String,List<JsonNode>>();
        for(var row:pack.path("evidence"))evidence.computeIfAbsent(row.path("target").asText(),k->new ArrayList<>()).add(row);
        Set<String> targets=new HashSet<>(Set.of("catalog"));
        for(var group:Map.of("entity",entities,"metric",metrics,"dimension",dimensions,"relationship",relationships,"rule",rules).entrySet())
            group.getValue().keySet().forEach(code->targets.add(group.getKey()+":"+code));
        var result=SemanticCatalogSnapshot.builder().build();
        var attrs=new HashMap<String,Map<String,JsonNode>>();
        for(var entry:entities.entrySet()) {
            String code=entry.getKey();var entity=entry.getValue();var attributes=index(entity.path("attributes"));attrs.put(code,attributes);
            var tables=new ArrayList<GovernedModelSource.Table>();var byAlias=new HashMap<String,JsonNode>();
            Integer datasource=null;
            for(var table:entity.path("source").path("tables")) {
                String alias=table.path("alias").asText();var actual=physical.get(tableKey(table));require(actual!=null,"UNKNOWN_TABLE");
                require(byAlias.putIfAbsent(alias,actual)==null,"DUPLICATE_SOURCE_ALIAS");
                var binding=domains.get(table.path("datasource").asText());require(binding!=null,"UNBOUND_DATASOURCE");
                require(datasource==null || datasource.equals(binding.getDatasourceId()),"CROSS_DATASOURCE_ENTITY");datasource=binding.getDatasourceId();
                String schema=table.path("schema").asText(),name=table.path("table").asText();
                require(binding.getExposedTables().contains(name) || binding.getExposedTables().contains(schema+"."+name),"TABLE_NOT_EXPOSED");
                tables.add(new GovernedModelSource.Table(alias,schema,name));
            }
            String base=entity.path("source").path("base").asText();require(byAlias.containsKey(base),"UNKNOWN_BASE");
            var projections=new ArrayList<GovernedModelSource.Projection>();
            for(var attribute:attributes.entrySet()) {
                String field=attribute.getKey();var a=attribute.getValue();var mapping=mapping(a.path("mapping"));var column=column(byAlias,mapping);
                require(a.path("dataType").asText().equals(column.path("dataType").asText()),"MAPPING_TYPE");
                projections.add(new GovernedModelSource.Projection(field,mapping));targets.add("entity:"+code+"/attribute:"+field);
                result.getColumns().add(SemanticCatalogSnapshot.Column.builder().modelCode(code).columnName(field).businessName(a.path("name").asText())
                    .dataType(a.path("dataType").asText()).unit(a.has("unit")?a.path("unit").asText():null).role(list(entity.path("primaryKey")).contains(field)?SemanticColumnRole.IDENTIFIER:role(a)).description(a.path("description").asText()).nullable(column.path("nullable").asBoolean())
                    .evidence(evidence(evidence,"entity:"+code+"/attribute:"+field)).retrievalJson(retrieval(a)).status(SemanticAssetStatus.ENABLED).build());
                Set<String> values=new HashSet<>();
                for(var value:a.path("enumValues")) {
                    require(compatible(value.path("value"),a.path("dataType").asText()),"ENUM_TYPE");require(values.add(value.path("value").toString()),"DUPLICATE_ENUM");
                    result.getEnumValues().add(SemanticCatalogSnapshot.EnumValue.builder().modelCode(code).columnName(field)
                        .valueCode(value.path("value").asText()).businessName(value.path("label").asText())
                        .description(a.path("description").asText()).evidence(evidence(evidence,"entity:"+code+"/attribute:"+field)).status(SemanticAssetStatus.ENABLED).build());
                }
            }
            var pk=list(entity.path("primaryKey"));require(new HashSet<>(pk).size()==pk.size(),"DUPLICATE_GRAIN_KEY");
            var physicalKeys=new ArrayList<String>();
            for(var field:pk) {
                var attr=attributes.get(field);require(attr!=null,"UNKNOWN_GRAIN_ATTRIBUTE");var mapping=mapping(attr.path("mapping"));
                require(base.equals(mapping.source()) && !column(byAlias,mapping).path("nullable").asBoolean(),"UNPROVEN_GRAIN");physicalKeys.add(mapping.column());
            }
            require(unique(byAlias.get(base),physicalKeys),"UNPROVEN_GRAIN");
            var joins=new ArrayList<GovernedModelSource.Join>();
            for(var join:entity.path("source").path("joins")) {
                var pairs=new ArrayList<GovernedModelSource.Pair>();var rightColumns=new ArrayList<String>();
                for(var pair:join.path("on")) {
                    var left=mapping(pair.path("left"));var right=mapping(pair.path("right"));
                    require(sameType(column(byAlias,left).path("dataType").asText(),column(byAlias,right).path("dataType").asText()),"JOIN_TYPE");
                    pairs.add(new GovernedModelSource.Pair(left,right));rightColumns.add(right.column());
                }
                require(unique(byAlias.get(join.path("right").asText()),rightColumns),"UNPROVEN_JOIN_UNIQUENESS");
                joins.add(new GovernedModelSource.Join(join.path("type").asText(),join.path("right").asText(),pairs));
            }
            var filters=filters(entity.path("filters"),attributes);var governed=new GovernedModelSource(tables,base,joins,projections,filters);
            var baseTable=tables.stream().filter(t->t.alias().equals(base)).findFirst().orElseThrow();
            result.getModels().add(SemanticCatalogSnapshot.Model.builder().datasourceId(datasource).modelCode(code).physicalTable(baseTable.schema()+"."+baseTable.table())
                .sourceJson(json(governed)).businessName(entity.path("name").asText()).modelType("ENTITY").description(entity.path("description").asText())
                .evidence(evidence(evidence,"entity:"+code)).retrievalJson(retrieval(entity)).status(SemanticAssetStatus.ENABLED).build());
            result.getGrains().add(SemanticCatalogSnapshot.Grain.builder().modelCode(code).grainCode(code+"_grain").keyColumns(String.join(",",pk))
                .description(entity.path("grain").asText()).uniquenessRule("EXPORTED_NON_NULL_UNIQUE_KEY").evidence(evidence(evidence,"entity:"+code)).status(SemanticAssetStatus.ENABLED).build());
        }
        for(var entry:metrics.entrySet()) {
            var projected=projectMetric(entry.getValue(),attrs,metrics);
            projected.setEvidence(evidence(evidence,"metric:"+entry.getKey()));result.getMetrics().add(projected);
        }
        for(var entry:dimensions.entrySet()) {
            var d=entry.getValue();String entity=d.path("entity").asText(),attribute=d.path("attribute").asText();require(attribute(attrs,entity,attribute)!=null,"UNKNOWN_ATTRIBUTE");
            result.getDimensions().add(SemanticCatalogSnapshot.Dimension.builder().modelCode(entity).dimensionCode(entry.getKey()).columnName(attribute)
                .businessName(d.path("name").asText()).description(d.path("description").asText()).dimensionType("ATTRIBUTE")
                .evidence(evidence(evidence,"dimension:"+entry.getKey())).retrievalJson(retrieval(d)).status(SemanticAssetStatus.ENABLED).build());
        }
        for(var entry:relationships.entrySet()) {
            var rel=entry.getValue();String from=rel.path("fromEntity").asText(),to=rel.path("toEntity").asText(),cardinality=rel.path("cardinality").asText();
            var pairs=new ArrayList<String>();Set<String> leftKeys=new HashSet<>(),rightKeys=new HashSet<>();
            for(var pair:rel.path("pairs")) {
                String left=pair.path("from").asText(),right=pair.path("to").asText();
                require(sameType(attribute(attrs,from,left).path("dataType").asText(),attribute(attrs,to,right).path("dataType").asText()),"JOIN_TYPE");
                require(leftKeys.add(left) && rightKeys.add(right),"DUPLICATE_RELATIONSHIP_KEY");pairs.add(from+"."+left+" = "+to+"."+right);
            }
            if(Set.of("one_to_one","one_to_many").contains(cardinality))require(leftKeys.containsAll(list(entities.get(from).path("primaryKey"))),"UNPROVEN_CARDINALITY");
            if(Set.of("one_to_one","many_to_one").contains(cardinality))require(rightKeys.containsAll(list(entities.get(to).path("primaryKey"))),"UNPROVEN_CARDINALITY");
            result.getRelationships().add(SemanticCatalogSnapshot.Relationship.builder().relationshipCode(entry.getKey()).sourceModelCode(from).targetModelCode(to)
                .cardinality(RelationshipCardinality.valueOf(cardinality.toUpperCase(Locale.ROOT))).joinType("INNER").joinCondition(String.join(" AND ",pairs))
                .description(rel.path("description").asText()).evidence(evidence(evidence,"relationship:"+entry.getKey())).retrievalJson(retrieval(rel)).status(SemanticAssetStatus.ENABLED).build());
        }
        // Attribute ranges are definition metadata, not a query filter. Never drop out-of-range rows to hide bad data.
        for(var entry:rules.entrySet()) {
            var rule=entry.getValue();require(Set.of("integer","decimal").contains(attribute(attrs,rule.path("entity").asText(),rule.path("attribute").asText()).path("dataType").asText()),"RULE_TYPE");
            require(rule.path("minimum").decimalValue().compareTo(rule.path("maximum").decimalValue())<=0,"INVALID_RANGE");
            result.getRules().add(SemanticCatalogSnapshot.Rule.builder().modelCode(rule.path("entity").asText()).ruleCode(entry.getKey())
                .ruleType("ATTRIBUTE_RANGE").businessName(entry.getKey()).expression(json(rule)).severity("ERROR")
                .evidence(evidence(evidence,"rule:"+entry.getKey())).retrievalJson(retrieval(rule)).status(SemanticAssetStatus.ENABLED).build());
        }
        require(targets.containsAll(evidence.keySet()),"UNKNOWN_EVIDENCE_TARGET");
        for(String target:targets)if(!target.equals("catalog"))require(evidence.containsKey(target),"MISSING_EVIDENCE");
        for(var issue:pack.path("unresolvedIssues")) {
            require(targets.contains(issue.path("target").asText()),"UNKNOWN_ISSUE_TARGET");require(!issue.path("blocking").asBoolean(),"UNRESOLVED_DEFINITION");
        }
        return result;
    }
    static SemanticCatalogSnapshot.Metric projectMetric(JsonNode metric,Map<String,Map<String,JsonNode>> attrs,Map<String,JsonNode> metrics) {
            String code=metric.path("code").asText();String entity=metric.path("entity").asText();var attributes=attrs.get(entity);require(attributes!=null,"UNKNOWN_ENTITY");
            var expr=expression(metric.path("expression"),metric,attrs,metrics,new LinkedHashSet<>(Set.of(code)));
            require(expr.level()==2,"METRIC_GRAIN");String time=metric.path("timeAttribute").isNull()?null:metric.path("timeAttribute").asText();
            if(time!=null)require(attributes.containsKey(time) && Set.of("date","datetime").contains(attributes.get(time).path("dataType").asText()),"TIME_TYPE");
            var predicates=filters(metric.path("filters"),attributes);
            var range=range(metric);
            return SemanticCatalogSnapshot.Metric.builder().modelCode(entity).metricCode(code).businessName(metric.path("name").asText())
                .expression(expr.sql()).aggregation("EXPRESSION").unit(metric.path("unit").asText()).timeColumn(time)
                .filterExpression(predicates.isEmpty()?null:GovernedModelSourceRenderer.predicates(predicates,Function.identity()))
                .description(metric.path("description").asText())
                .minimumValue(range==null?null:range.minimum()).maximumValue(range==null?null:range.maximum())
                .minimumInclusive(range==null || range.minimum()==null?null:range.minimumInclusive())
                .maximumInclusive(range==null || range.maximum()==null?null:range.maximumInclusive())
                .retrievalJson(retrieval(metric)).status(SemanticAssetStatus.ENABLED).build();
    }
    private static String retrieval(JsonNode asset) {
        if(!asset.has("retrieval"))return null;
        var hints=RetrievalHints.decode(asset.get("retrieval").toString());return RetrievalHints.encode(hints);
    }
    private static NumericValueRange range(JsonNode metric) {
        if(!metric.has("valueRange"))return null;
        var range=metric.get("valueRange");
        return new NumericValueRange(range.has("minimum")?range.get("minimum").decimalValue():null,
            range.has("maximum")?range.get("maximum").decimalValue():null,
            range.path("minimumInclusive").asBoolean(true),range.path("maximumInclusive").asBoolean(true));
    }
    private static Expression expression(JsonNode node,JsonNode root,Map<String,Map<String,JsonNode>> attrs,Map<String,JsonNode> metrics,Set<String> stack) {
        String entity=root.path("entity").asText();
        if(node.has("literal"))return new Expression(node.path("literal").decimalValue().toPlainString(),"decimal",0);
        if(node.has("attribute")) {String code=node.path("attribute").asText();var a=attribute(attrs,entity,code);return new Expression(GovernedModelSource.identifier(code),a.path("dataType").asText(),1);}
        if(node.has("metric")) {
            String code=node.path("metric").asText();var target=metrics.get(code);require(target!=null && target.path("entity").asText().equals(entity),"UNKNOWN_METRIC");
            require(stack.add(code),"DEPENDENCY_CYCLE");require(target.path("filters").isEmpty(),"FILTERED_METRIC_REFERENCE");
            require(root.path("timeAttribute").equals(target.path("timeAttribute")),"METRIC_TIME_MISMATCH");
            var result=expression(target.path("expression"),root,attrs,metrics,stack);stack.remove(code);return result;
        }
        String op=node.path("op").asText();if(op.equals("count_rows"))return new Expression("COUNT(*)","integer",2);
        if(node.has("arg")) {
            var arg=expression(node.path("arg"),root,attrs,metrics,stack);require(arg.level()==1,"NESTED_AGGREGATE");
            if(Set.of("sum","avg").contains(op))require(numeric(arg.type()),"EXPRESSION_TYPE");
            if(Set.of("min","max").contains(op))require(!arg.type().equals("boolean"),"EXPRESSION_TYPE");
            String sql=op.equals("count_distinct")?"COUNT(DISTINCT "+arg.sql()+")":op.toUpperCase(Locale.ROOT)+"("+arg.sql()+")";
            return new Expression(sql,op.equals("count_distinct")?"integer":op.equals("avg")?"decimal":arg.type(),2);
        }
        var left=expression(node.path("left"),root,attrs,metrics,stack);var right=expression(node.path("right"),root,attrs,metrics,stack);
        require(numeric(left.type()) && numeric(right.type()),"EXPRESSION_TYPE");require(!(left.level()==1 && right.level()==2 || left.level()==2 && right.level()==1),"AGGREGATION_LEVEL");
        String sql;
        if(op.equals("divide")) {
            require(!node.path("right").has("literal") || node.path("right").path("literal").decimalValue().signum()!=0,"ZERO_DIVISOR");
            sql="("+left.sql()+" * 1.0 / NULLIF("+right.sql()+", 0))";
        }else {String symbol=switch(op){case "add"->"+";case "subtract"->"-";case "multiply"->"*";default->throw new IllegalArgumentException("UNSUPPORTED_EXPRESSION");};sql="("+left.sql()+" "+symbol+" "+right.sql()+")";}
        return new Expression(sql,"decimal",Math.max(left.level(),right.level()));
    }
    private record Expression(String sql,String type,int level) {}
    private static List<JsonNode> filters(JsonNode rows,Map<String,JsonNode> attrs) {
        var result=new ArrayList<JsonNode>();
        for(var row:rows) {
            var attr=attrs.get(row.path("attribute").asText());require(attr!=null,"UNKNOWN_ATTRIBUTE");String type=attr.path("dataType").asText();
            if(row.has("value")) {
                if(row.path("value").isArray())for(var value:row.path("value"))require(compatible(value,type),"FILTER_TYPE");
                else require(compatible(row.path("value"),type),"FILTER_TYPE");
            }
            if(type.equals("boolean"))require(!Set.of("gt","gte","lt","lte").contains(row.path("operator").asText()),"FILTER_OPERATOR");
            result.add(row);
        }
        GovernedModelSourceRenderer.predicates(result,Function.identity());return result;
    }
    private static SemanticColumnRole role(JsonNode attribute){return Set.of("date","datetime").contains(attribute.path("dataType").asText())?SemanticColumnRole.TIME:numeric(attribute.path("dataType").asText())?SemanticColumnRole.MEASURE:SemanticColumnRole.ATTRIBUTE;}
    private static boolean compatible(JsonNode value,String type){return switch(type){case "integer"->value.isIntegralNumber();case "decimal"->value.isNumber();case "boolean"->value.isBoolean();default->value.isTextual();};}
    private static boolean numeric(String type){return Set.of("integer","decimal").contains(type);}
    private static boolean sameType(String left,String right){return left.equals(right) || numeric(left)&&numeric(right);}
    private static GovernedModelSource.Mapping mapping(JsonNode node){return new GovernedModelSource.Mapping(node.path("source").asText(),node.path("column").asText());}
    private static JsonNode column(Map<String,JsonNode> aliases,GovernedModelSource.Mapping mapping) {
        var table=aliases.get(mapping.source());require(table!=null,"UNKNOWN_SOURCE_ALIAS");
        for(var column:table.path("columns"))if(column.path("name").asText().equals(mapping.column()))return column;
        throw new IllegalArgumentException("UNKNOWN_COLUMN");
    }
    private static JsonNode attribute(Map<String,Map<String,JsonNode>> attrs,String entity,String code) {
        require(attrs.containsKey(entity) && attrs.get(entity).containsKey(code),"UNKNOWN_ATTRIBUTE");return attrs.get(entity).get(code);
    }
    private static boolean unique(JsonNode table,List<String> columns) {
        if(table==null || new HashSet<>(columns).size()!=columns.size())return false;
        Set<String> available=new HashSet<>(columns);var primary=list(table.path("primaryKey"));
        if(!primary.isEmpty() && available.containsAll(primary))return true;
        for(var key:table.path("uniqueKeys"))if(available.containsAll(list(key)))return true;
        return false;
    }
    private static Map<String,JsonNode> index(JsonNode rows) {var result=new LinkedHashMap<String,JsonNode>();for(var row:rows)require(result.putIfAbsent(row.path("code").asText(),row)==null,"DUPLICATE_ID");return result;}
    private static List<String> list(JsonNode rows){var result=new ArrayList<String>();rows.forEach(value->result.add(value.asText()));return result;}
    private static String tableKey(JsonNode row){return row.path("datasource").asText()+"|"+row.path("schema").asText()+"|"+row.path("table").asText();}
    private static String evidence(Map<String,List<JsonNode>> rows,String target){require(rows.containsKey(target),"MISSING_EVIDENCE");return json(rows.get(target));}
    private static String json(Object value){try{return CanonicalJson.write(value);}catch(Exception error){throw new IllegalArgumentException("Invalid asset serialization",error);}}
    private static void validate(Schema schema,JsonNode input){var errors=schema.validate(input);if(!errors.isEmpty())throw new IllegalArgumentException("PROTOCOL_SCHEMA: "+errors.stream().limit(12).map(Object::toString).collect(Collectors.joining("; ")));}
    private static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
}
