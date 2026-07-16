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
package cn.lgs.semevosql.semantic.retrieval;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Predicate;

/** Complete, deterministic model projection. Audit metadata never changes the encoded business text. */
final class SemanticModelDocumentAssembler {
    static final String VERSION = "whole-model-v2";
    private static final Set<String> AUDIT_FIELDS = Set.of("id", "projectId", "projectVersionId", "createTime",
        "updateTime", "evidence", "status");
    private final CanonicalJson json = new CanonicalJson();

    SemanticRetrievalDocument assemble(SemanticCatalogSnapshot catalog, SemanticCatalogSnapshot.Model model,
            String catalogHash) {
        String code = model.getModelCode();
        var visibility=new cn.lgs.semevosql.semantic.domain.SemanticCatalogVisibility(catalog);
        var hidden=visibility.hiddenColumns(code);
        var modelCodes = catalog.getModels().stream().filter(SemanticCatalogSnapshot.Model::isEnabled)
            .map(SemanticCatalogSnapshot.Model::getModelCode).collect(java.util.stream.Collectors.toSet());
        Map<String,Object> content = new TreeMap<>();
        var modelContent=business(model);
        // Physical mappings must not re-expose hidden logical fields through the model header.
        if(!hidden.isEmpty())modelContent.remove("sourceJson");
        content.put("model",modelContent);
        content.put("columns", select(catalog.getColumns(), c -> code.equals(c.getModelCode())
            && visibility.maySendColumn(c)));
        content.put("metrics", select(catalog.getMetrics(), m -> code.equals(m.getModelCode())
            && visibility.maySendMetric(m)));
        content.put("dimensions", select(catalog.getDimensions(), d -> code.equals(d.getModelCode())
            && visibility.maySendDimension(d)));
        content.put("enumValues", select(catalog.getEnumValues(), e -> code.equals(e.getModelCode())
            && visibility.maySendEnum(e)));
        content.put("grains", select(catalog.getGrains(), g -> code.equals(g.getModelCode())
            && g.getStatus() == SemanticAssetStatus.ENABLED && visibility.safe(code, g.getKeyColumns(), g.getTimeColumn())));
        content.put("rules", select(catalog.getRules(), r -> code.equals(r.getModelCode())
            && r.getStatus() == SemanticAssetStatus.ENABLED && visibility.safe(code, r.getExpression())));
        // A relationship advertises an edge, never mounts the other model's assets into this model.
        content.put("relationships", select(catalog.getRelationships(), r -> r.getStatus() == SemanticAssetStatus.ENABLED
            && modelCodes.contains(r.getSourceModelCode()) && modelCodes.contains(r.getTargetModelCode())
            && (code.equals(r.getSourceModelCode()) || code.equals(r.getTargetModelCode()))
            && visibility.maySendRelationship(r)));
        // Only definitions used by visible roles are encoded; sharing does not mount another model.
        var bindingKeys=new HashSet<String>();
        for(String group:List.of("columns","metrics","dimensions"))for(var asset:(List<?>)content.get(group)) {
            var ref=((ObjectNode)asset).path("definitionBinding");
            if(ref.isObject())bindingKeys.add(ref.path("modelCode").asText()+"/"+ref.path("bindingCode").asText());
        }
        if(!bindingKeys.isEmpty()) {
            var roles=catalog.getModelBindings().stream().filter(b->bindingKeys.contains(b.path("model").asText()+"/"+b.path("code").asText())).toList();
            var definitionKeys=roles.stream().map(b->b.path("definition").asText()+"@"+b.path("definitionRevision").asInt()).collect(java.util.stream.Collectors.toSet());
            var dictionaryKeys=roles.stream().filter(b->b.has("dictionary")).map(b->b.path("dictionary").path("code").asText()+"@"+b.path("dictionary").path("revision").asInt()).collect(java.util.stream.Collectors.toSet());
            content.put("sharedDefinitions",catalog.getSharedDefinitions().stream().filter(d->definitionKeys.contains(d.path("code").asText()+"@"+d.path("revision").asInt())).toList());
            content.put("modelBindings",roles);
            content.put("enumDictionaries",catalog.getEnumDictionaries().stream().filter(d->dictionaryKeys.contains(d.path("code").asText()+"@"+d.path("revision").asInt())).toList());
        }
        String text = json.write(content);
        String assetKey = "model:" + code;
        String id = json.hash(Map.of("projectVersionId", catalog.getProjectVersionId(), "documentType", "MODEL",
            "assetKey", assetKey));
        return new SemanticRetrievalDocument(id, catalog.getProjectId(), catalog.getProjectVersionId(), catalogHash,
            SemanticRetrievalDocument.DocumentType.MODEL, "MODEL", assetKey, model.getDatasourceId(), code,
            model.getPhysicalTable(), text, text, json.hash(Map.of("builder", VERSION, "content", content)),
            json.hash(text), "OFFLINE_CATALOG", VERSION, "CATALOG_DESCRIPTION");
    }

    private <T> List<ObjectNode> select(List<T> values, Predicate<T> included) {
        return values.stream().filter(Objects::nonNull).filter(included).map(this::business)
            .sorted(Comparator.comparing(json::write)).toList();
    }

    private ObjectNode business(Object value) {
        ObjectNode node = (ObjectNode) json.canonicalNode(value);
        node.remove(AUDIT_FIELDS);
        return node;
    }

}
