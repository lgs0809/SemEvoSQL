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

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lossless input references for exactly repeated governed identities; no candidate is removed. */
final class SemanticPlannerPayload {

    private SemanticPlannerPayload() {
    }

    static String write(Map<String, Object> payload) throws com.fasterxml.jackson.core.JsonProcessingException {
        var mapper = JsonUtil.getObjectMapper();
        ObjectNode original = mapper.valueToTree(payload);
        var counts = new LinkedHashMap<JsonNode, Integer>();
        count(original, counts);
        var references = new LinkedHashMap<JsonNode, String>();
        counts.forEach((binding, count) -> {
            if (count > 1) references.put(binding, "b" + (references.size() + 1));
        });
        String raw = mapper.writeValueAsString(original);
        if (references.isEmpty()) return raw;
        ObjectNode compact = original.deepCopy();
        replace(compact, references);
        ObjectNode dictionary = compact.putObject("definitionBindings");
        references.forEach((binding, key) -> dictionary.set(key, binding));
        String referenced = mapper.writeValueAsString(compact);
        return referenced.length() < raw.length() ? referenced : raw;
    }

    private static void count(JsonNode node, Map<JsonNode, Integer> counts) {
        if (node.isObject()) {
            var binding = node.get("definitionBinding");
            if (binding != null && binding.isObject()) counts.merge(binding, 1, Integer::sum);
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> count(child, counts));
    }

    private static void replace(JsonNode node, Map<JsonNode, String> references) {
        if (node instanceof ObjectNode object) {
            var binding = object.get("definitionBinding");
            var key = binding == null ? null : references.get(binding);
            if (key != null) {
                object.remove("definitionBinding");
                object.put("definitionBindingRef", key);
            }
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> replace(child, references));
    }

}
