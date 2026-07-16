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
package cn.lgs.semevosql.semantic.domain;

import java.util.*;
import java.util.regex.Pattern;

/** The same visibility boundary protects complete model documents and LLM planning candidates. */
public final class SemanticCatalogVisibility {
    private final SemanticCatalogSnapshot catalog;
    private final Map<String,List<String>> hidden=new HashMap<>();
    public SemanticCatalogVisibility(SemanticCatalogSnapshot catalog) {
        this.catalog=catalog;
        for(var column:catalog.getColumns())if(column.getStatus()!=SemanticAssetStatus.ENABLED || !Boolean.TRUE.equals(column.getAllowSendToLlm()))
            hidden.computeIfAbsent(column.getModelCode(),key->new ArrayList<>()).add(column.getColumnName());
    }
    public List<String> hiddenColumns(String model) {return List.copyOf(hidden.getOrDefault(model,List.of()));}
    public boolean maySendColumn(SemanticCatalogSnapshot.Column column) {
        return column.getStatus()==SemanticAssetStatus.ENABLED && Boolean.TRUE.equals(column.getAllowSendToLlm())
            && safe(column.getModelCode(),column.getExpression()) && safeBinding(column.getDefinitionBinding());
    }
    public boolean maySendMetric(SemanticCatalogSnapshot.Metric metric) {
        return metric.getStatus()==SemanticAssetStatus.ENABLED && safe(metric.getModelCode(),metric.getExpression(),metric.getFilterExpression(),metric.getTimeColumn())
            && safeBinding(metric.getDefinitionBinding());
    }
    public boolean maySendDimension(SemanticCatalogSnapshot.Dimension dimension) {
        return dimension.getStatus()==SemanticAssetStatus.ENABLED && safe(dimension.getModelCode(),dimension.getColumnName(),dimension.getExpression())
            && safeBinding(dimension.getDefinitionBinding());
    }
    public boolean maySendEnum(SemanticCatalogSnapshot.EnumValue value) {return value.getStatus()==SemanticAssetStatus.ENABLED && safe(value.getModelCode(),value.getColumnName());}
    public boolean maySendRelationship(SemanticCatalogSnapshot.Relationship relationship) {
        return relationship.getStatus()==SemanticAssetStatus.ENABLED && safe(relationship.getSourceModelCode(),relationship.getJoinCondition())
            && safe(relationship.getTargetModelCode(),relationship.getJoinCondition());
    }
    public boolean safe(String model,String... expressions) {
        // Conservative identifier matching omits a dependent definition rather than sending private fields.
        for(String name:hidden.getOrDefault(model,List.of())) {
            if(name==null)continue;
            var identifier=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])"+Pattern.quote(name)+"(?![\\p{L}\\p{N}_])");
            for(String expression:expressions)if(expression!=null && identifier.matcher(expression).find())return false;
        }
        return true;
    }
    private boolean safeBinding(SemanticDefinitionBinding ref) {
        if(ref==null)return true;
        var role=catalog.getModelBindings().stream().filter(b->ref.modelCode().equals(b.path("model").asText()) && ref.bindingCode().equals(b.path("code").asText())).findFirst();
        if(role.isEmpty()) {
            var legacy=catalog.getLegacyModelBindings().stream().filter(b->ref.modelCode().equals(b.path("model").asText())
                && ref.bindingCode().equals(b.path("code").asText()) && ref.definitionCode().equals(b.path("definition").asText())
                && ref.definitionRevision()==b.path("definitionRevision").asInt()
                && Objects.equals(ref.roleName(),b.path("roleName").asText()) && "ATTRIBUTE".equals(b.path("assetType").asText())
                && b.path("assetKey").equals(b.path("legacyAssetKey"))).toList();
            if(legacy.size()!=1)return false;
            String field=legacy.get(0).path("assetKey").asText();
            return safe(ref.modelCode(),field) && catalog.getColumns().stream().anyMatch(c->ref.modelCode().equals(c.getModelCode())
                && field.equals(c.getColumnName()) && c.getStatus()==SemanticAssetStatus.ENABLED
                && Boolean.TRUE.equals(c.getAllowProjection()) && Boolean.TRUE.equals(c.getAllowSendToLlm()));
        }
        var values=role.get().path("attributeMappings").elements();
        while(values.hasNext())if(hidden.getOrDefault(ref.modelCode(),List.of()).contains(values.next().asText()))return false;
        return true;
    }
}
