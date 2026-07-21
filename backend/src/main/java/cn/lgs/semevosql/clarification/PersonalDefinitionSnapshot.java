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

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.*;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Captures meaning and its actual dependencies independently of mutable catalog pointers. */
public final class PersonalDefinitionSnapshot {
    private PersonalDefinitionSnapshot() {}
    public record Captured(Long versionId, JsonNode snapshot, String dependencyFingerprint) {}

    public static Captured capture(SemanticCatalogSnapshot catalog,String type,String key) {
        Object target;String modelCode;Set<String> dependencies=new TreeSet<>();
        switch(type) {
            case "METRIC" -> {
                var value=catalog.getMetrics().stream().filter(m->key.equals(m.getMetricCode())).findFirst().orElseThrow();
                target=value;modelCode=value.getModelCode();
                names(value.getExpression(),dependencies);names(value.getFilterExpression(),dependencies);
                if(value.getTimeColumn()!=null)dependencies.add(value.getTimeColumn());
            }
            case "DIMENSION" -> {
                var value=catalog.getDimensions().stream().filter(d->key.equals(d.getDimensionCode())).findFirst().orElseThrow();
                target=value;modelCode=value.getModelCode();names(value.getExpression(),dependencies);dependencies.add(value.getColumnName());
            }
            case "TIME_COLUMN" -> {
                String[] parts=key.replace('.',':').split(":",2);
                if(parts.length!=2)throw new IllegalArgumentException("Expected model:time-column identity");
                var value=catalog.getColumns().stream().filter(c->parts[0].equals(c.getModelCode())&&parts[1].equals(c.getColumnName())).findFirst().orElseThrow();
                target=value;modelCode=value.getModelCode();dependencies.add(value.getColumnName());
            }
            case "ENUM_VALUE" -> {
                String[] parts=key.split(":",3);if(parts.length!=3)throw new IllegalArgumentException("Expected model:column:value identity");
                var value=catalog.getEnumValues().stream().filter(e->parts[0].equals(e.getModelCode())&&parts[1].equals(e.getColumnName())&&parts[2].equals(e.getValueCode())).findFirst().orElseThrow();
                target=value;modelCode=value.getModelCode();dependencies.add(value.getColumnName());
            }
            default -> throw new IllegalArgumentException("Unsupported personal target: "+type);
        }
        var model=catalog.getModels().stream().filter(m->modelCode.equals(m.getModelCode())).findFirst().orElseThrow();
        var grains=catalog.getGrains().stream().filter(g->modelCode.equals(g.getModelCode())).sorted(Comparator.comparing(SemanticCatalogSnapshot.Grain::getGrainCode)).toList();
        grains.forEach(g->{names(g.getKeyColumns(),dependencies);names(g.getTimeColumn(),dependencies);});
        JsonNode source=model.getSourceJson()==null?NullNode.instance:read(model.getSourceJson());
        if(source.isObject()) {
            source.path("filters").forEach(f->dependencies.add(f.path("attribute").asText()));
            var projections=JsonNodeFactory.instance.arrayNode();source.path("projections").forEach(p->{if(dependencies.contains(p.path("code").asText()))projections.add(p);});
            ((ObjectNode)source).set("projections",projections);
        }
        var columns=catalog.getColumns().stream().filter(c->modelCode.equals(c.getModelCode())&&dependencies.contains(c.getColumnName()))
            .sorted(Comparator.comparing(SemanticCatalogSnapshot.Column::getColumnName)).toList();
        ObjectNode snapshot=JsonNodeFactory.instance.objectNode();snapshot.put("completeDefinitionRecorded",true);
        snapshot.put("assetType",type);snapshot.put("assetKey",key);snapshot.set("target",tree(target));
        snapshot.set("model",tree(model));snapshot.set("columns",tree(columns));snapshot.set("grains",tree(grains));
        snapshot.set("rules",tree(catalog.getRules().stream().filter(r->r.getModelCode()==null||modelCode.equals(r.getModelCode()))
            .sorted(Comparator.comparing(SemanticCatalogSnapshot.Rule::getRuleCode)).toList()));
        ObjectNode meaning=snapshot.deepCopy();
        scrub(meaning);((ObjectNode)meaning.path("model")).set("sourceJson",source);
        return new Captured(catalog.getProjectVersionId(),snapshot,hash(meaning));
    }
    // Druid parses the already governed expressions; identifiers in literals never become dependencies.
    private static void names(String expression,Set<String> names) {
        if(expression==null||expression.isBlank())return;
        SQLUtils.parseSingleStatement("SELECT "+expression,DbType.mysql).accept(new SQLASTVisitorAdapter(){
            @Override public boolean visit(SQLIdentifierExpr value){names.add(value.getName().replace("`",""));return false;}
            @Override public boolean visit(SQLPropertyExpr value){names.add(value.getName().replace("`",""));return false;}
        });
    }
    private static void scrub(JsonNode node) {
        if(node.isObject()) {
            ((ObjectNode)node).remove(List.of("id","projectId","projectVersionId","createTime","updateTime","businessName",
                "description","evidence","synonyms","aliases","confirmedAliases","retrieval","definitionBinding","sortOrder"));
            node.elements().forEachRemaining(PersonalDefinitionSnapshot::scrub);
        } else if(node.isArray())node.forEach(PersonalDefinitionSnapshot::scrub);
    }
    private static JsonNode tree(Object value){return JsonUtil.getObjectMapper().valueToTree(value);}
    private static JsonNode read(String value){try{return JsonUtil.getObjectMapper().readTree(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    public static String hash(Object value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalArgumentException(e);}}
}
