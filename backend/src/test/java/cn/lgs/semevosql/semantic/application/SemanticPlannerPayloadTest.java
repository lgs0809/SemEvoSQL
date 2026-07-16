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

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPlannerPayloadTest {

    @Test
    void repeatedIdentityRetainsEveryFieldLiteralScopeAndCandidateAfterDereferencing() throws Exception {
        var exact = binding(3, "customer");
        var payload = Map.<String, Object>of(
            "question", "请保留完整定义，按客户分组。",
            "metrics", List.of(Map.of("metricCode", "public-total", "definitionScope", "PUBLISHED_PROJECT",
                "authoritativeExpression", "COUNT(*)", "authoritativeFilter", "status='PAID'")),
            "dimensions", List.of(Map.of("dimensionCode", "dim-customer", "definitionBinding", exact,
                "description", "身份\n不得合并，NULL 仍保留。")),
            "filterableColumns", List.of(Map.of("columnName", "customer_id", "definitionBinding", exact,
                "dataType", "BIGINT")),
            "confirmedPersonalDefinitions", List.of(Map.of("definitionId", 9, "sourceRevision", 2,
                "completeDefinition", "原始全部含义；只属于本人。", "applicability", "USER_DEFAULT_CANDIDATE")));
        JsonNode original = JsonUtil.getObjectMapper().valueToTree(payload);
        ObjectNode compact = (ObjectNode) JsonUtil.getObjectMapper().readTree(SemanticPlannerPayload.write(payload));
        assertThat(compact.has("definitionBindings")).isTrue();
        assertThat(compact.path("definitionBindings").size()).isEqualTo(1);
        assertThat(SemanticPlannerPayload.write(payload).length()).isLessThan(original.toString().length());
        assertThat(expand(compact)).isEqualTo(original);
        JsonNode after = JsonUtil.getObjectMapper().valueToTree(payload);
        assertThat(after).isEqualTo(original);
    }

    @Test
    void differentRevisionRoleAndModelNeverShareOneIdentity() throws Exception {
        var identities = List.of(binding(3, "customer"), binding(4, "customer"), binding(3, "billing customer"),
            new SemanticDefinitionBinding("customer-definition", 3, "other-model", "binding-customer", "customer",
                List.of("客户"), null, null));
        var fields = identities.stream().flatMap(binding -> java.util.stream.Stream.of(
            Map.of("definitionBinding", binding, "columnName", "customer_id"),
            Map.of("definitionBinding", binding, "columnName", "customer_id"))).toList();
        var payload = Map.<String, Object>of("fields", fields);
        ObjectNode compact = (ObjectNode) JsonUtil.getObjectMapper().readTree(SemanticPlannerPayload.write(payload));
        assertThat(compact.path("definitionBindings").size()).isEqualTo(4);
        assertThat(expand(compact)).isEqualTo(JsonUtil.getObjectMapper().valueToTree(payload));
    }

    @Test
    void singleOrTinyBindingsStayInlineRatherThanAddingLargerReferenceInput() throws Exception {
        var payload = Map.<String, Object>of("fields", List.of(Map.of("definitionBinding", Map.of("r", 1)),
            Map.of("definitionBinding", Map.of("r", 1)), Map.of("definitionBinding", binding(4, "other"))));
        var raw = JsonUtil.getObjectMapper().writeValueAsString(payload);
        assertThat(SemanticPlannerPayload.write(payload)).isEqualTo(raw);
    }

    private SemanticDefinitionBinding binding(int revision, String role) {
        return new SemanticDefinitionBinding("customer-definition", revision, "customer-model", "binding-customer",
            role, List.of("客户", "完整确认别名"), null, null);
    }

    private JsonNode expand(ObjectNode payload) {
        var dictionary = payload.remove("definitionBindings");
        expandNode(payload, dictionary);
        return payload;
    }

    private void expandNode(JsonNode node, JsonNode dictionary) {
        if (node instanceof ObjectNode object && object.has("definitionBindingRef")) {
            String reference = object.remove("definitionBindingRef").asText();
            assertThat(dictionary.has(reference)).isTrue();
            object.set("definitionBinding", dictionary.get(reference));
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> expandNode(child, dictionary));
    }

}
