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

import java.util.List;

/** Frozen identity of a definition revision at a specific model role; sharing never implies a JOIN. */
public record SemanticDefinitionBinding(String definitionCode,int definitionRevision,String modelCode,
        String bindingCode,String roleName,List<String> confirmedAliases,String dictionaryCode,Integer dictionaryRevision) {
    public SemanticDefinitionBinding { confirmedAliases=List.copyOf(confirmedAliases); }

    public static SemanticDefinitionBinding resolve(SemanticCatalogSnapshot catalog,String type,String model,String asset,
            SemanticDefinitionBinding explicit) {
        if(explicit!=null)return explicit;
        return catalog.getLegacyModelBindings().stream().filter(b->type.equals(b.path("assetType").asText())
            && model.equals(b.path("model").asText()) && asset.equals(b.path("assetKey").asText())).findFirst()
            .map(b->new SemanticDefinitionBinding(b.path("definition").asText(),b.path("definitionRevision").asInt(),model,
                b.path("code").asText(),b.path("roleName").asText(),List.of(),null,null)).orElse(null);
    }
}
