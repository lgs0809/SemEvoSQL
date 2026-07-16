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
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GovernedAttributeDimensionsTest {
    private SemanticCatalogSnapshot fixture(String model, String binding) {
        var definition = JsonUtil.getObjectMapper().createObjectNode();
        definition.put("code", "reference").put("revision", 3).put("type", "ATTRIBUTE");
        definition.putArray("aliases").add("确认编号");
        definition.putObject("specification").put("attribute", "key");
        var role = JsonUtil.getObjectMapper().createObjectNode();
        role.put("model", model).put("code", binding).put("definition", "reference")
            .put("definitionRevision", 3).put("roleName", "归属编号");
        role.putObject("attributeMappings").put("key", "entity_key");
        role.putArray("aliases").add("对象编号");
        return SemanticCatalogSnapshot.builder().projectId(7L).projectVersionId(8L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode(model).status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode(model).columnName("entity_key")
                .businessName("归属编号").dataType("integer").role(SemanticColumnRole.MEASURE).status(SemanticAssetStatus.ENABLED)
                .definitionBinding(new SemanticDefinitionBinding("reference", 3, model, binding, "归属编号",
                    List.of("确认编号", "对象编号"), null, null)).build()))
            .sharedDefinitions(List.of(definition)).modelBindings(List.of(role)).build();
    }

    @Test void directAttributeRoleHasStableDetachedProjectionWithoutChangingPublishedCatalog() {
        var stored = fixture("entity", "reference_role");
        String before = JsonUtil.getObjectMapper().valueToTree(stored).toString();
        var view = GovernedAttributeDimensions.expand(stored);
        var dimension = view.getDimensions().get(0);
        assertThat(dimension.getDimensionCode()).isEqualTo(GovernedAttributeDimensions.identity("entity", "reference_role"));
        assertThat(dimension.getDefinitionBinding()).isEqualTo(stored.getColumns().get(0).getDefinitionBinding());
        assertThat(dimension.getBusinessName()).isEqualTo("归属编号");
        assertThat(dimension.getColumnName()).isEqualTo("entity_key");
        assertThat(dimension.getExpression()).isNull();
        assertThat(dimension.getDimensionType()).isEqualTo("ATTRIBUTE");
        assertThat(GovernedAttributeDimensions.expand(view)).isSameAs(view);
        view.getColumns().get(0).setBusinessName("query view only");
        assertThat(JsonUtil.getObjectMapper().valueToTree(stored).toString()).isEqualTo(before);
    }

    @Test void explicitDirectDimensionIsPreferredAndCalculatedDimensionDoesNotClaimTheField() {
        var catalog = fixture("entity", "role");
        var explicit = SemanticCatalogSnapshot.Dimension.builder().modelCode("entity").columnName("entity_key")
            .dimensionCode("declared_axis").status(SemanticAssetStatus.ENABLED).build();
        catalog.setDimensions(List.of(explicit));
        assertThat(GovernedAttributeDimensions.expand(catalog)).isSameAs(catalog);
        explicit.setExpression("entity_key + 1");
        assertThat(GovernedAttributeDimensions.expand(catalog).getDimensions()).hasSize(2);
    }

    @Test void permissionsStatusTypeAndComputedFieldsNeverAcquireProjectionAuthority() {
        for (String denied : List.of("projection", "llm", "column", "model", "type", "expression", "role")) {
            var catalog = fixture("entity", "role");
            var column = catalog.getColumns().get(0);
            switch (denied) {
                case "projection" -> column.setAllowProjection(false);
                case "llm" -> column.setAllowSendToLlm(false);
                case "column" -> column.setStatus(SemanticAssetStatus.DISABLED);
                case "model" -> catalog.getModels().get(0).setStatus(SemanticAssetStatus.DISABLED);
                case "type" -> column.setDataType("jsonb");
                case "expression" -> column.setExpression("coalesce(entity_key,0)");
                case "role" -> column.setRole(null);
            }
            assertThat(GovernedAttributeDimensions.expand(catalog).getDimensions()).as(denied).isEmpty();
        }
    }

    @Test void missingDuplicateForgedAndStaleSharedMeaningIsRejectedWithoutGuessing() {
        for (String defect : List.of("revision", "model", "role", "mapping", "aliases", "type", "duplicate", "missing")) {
            var catalog = fixture("entity", "role");
            var binding = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.getModelBindings().get(0);
            switch (defect) {
                case "revision" -> binding.put("definitionRevision", 4);
                case "model" -> binding.put("model", "other");
                case "role" -> binding.put("roleName", "different meaning");
                case "mapping" -> binding.withObject("attributeMappings").put("key", "other_key");
                case "aliases" -> binding.withArray("aliases").add("new unconfirmed alias");
                case "type" -> ((com.fasterxml.jackson.databind.node.ObjectNode) catalog.getSharedDefinitions().get(0)).put("type", "DIMENSION");
                case "duplicate" -> catalog.setModelBindings(List.of(binding, binding.deepCopy()));
                case "missing" -> catalog.setSharedDefinitions(List.of());
            }
            assertThat(GovernedAttributeDimensions.expand(catalog).getDimensions()).as(defect).isEmpty();
        }
    }

    @Test void identitiesRetainLongModelRoleNamesWithoutCrossModelCollisions() {
        String role = "r".repeat(128);
        var one = fixture("x".repeat(128), role);
        var two = fixture("y".repeat(128), role);
        var first = GovernedAttributeDimensions.expand(one).getDimensions().get(0).getDimensionCode();
        var second = GovernedAttributeDimensions.expand(two).getDimensions().get(0).getDimensionCode();
        assertThat(first).hasSize(34).isNotEqualTo(second);
        assertThat(GovernedAttributeDimensions.expand(one).getDimensions().get(0).getDimensionCode()).isEqualTo(first);
    }

    @Test void confirmedTimeAttributePreservesBusinessTimeAxis() {
        var catalog = fixture("entity", "role");
        catalog.getColumns().get(0).setRole(SemanticColumnRole.TIME);
        catalog.getColumns().get(0).setDataType("datetime");
        assertThat(GovernedAttributeDimensions.expand(catalog).getDimensions().get(0).getDimensionType()).isEqualTo("TIME");
    }

    @Test void occupiedIdentityCannotBeReboundToAnotherPhysicalField() {
        var catalog = fixture("entity", "role");
        catalog.setDimensions(List.of(SemanticCatalogSnapshot.Dimension.builder().modelCode("entity").columnName("other_key")
            .dimensionCode(GovernedAttributeDimensions.identity("entity", "role")).status(SemanticAssetStatus.ENABLED).build()));
        assertThatThrownBy(() -> GovernedAttributeDimensions.expand(catalog)).isInstanceOf(SemanticPlanningRejectedException.class);
    }

    private SemanticCatalogSnapshot legacyFixture() {
        var catalog = fixture("entity", "role");
        var column = catalog.getColumns().get(0);
        column.setDefinitionBinding(null); column.setNullable(false); column.setDescription("已确认归属编号");
        catalog.setSharedDefinitions(List.of()); catalog.setModelBindings(List.of());
        var binding = JsonUtil.getObjectMapper().createObjectNode();
        binding.put("model", "entity").put("code", "legacy_role").put("definition", "legacy_definition")
            .put("definitionRevision", 2).put("roleName", "归属编号").put("assetType", "ATTRIBUTE")
            .put("assetKey", "entity_key").put("legacyAssetKey", "entity_key");
        binding.putArray("aliases"); binding.putObject("attributeMappings");
        var definition = binding.putObject("definitionJson");
        definition.put("code", "legacy_definition").put("revision", 2).put("type", "ATTRIBUTE").put("name", "归属编号");
        definition.putArray("aliases");
        var projection = definition.putObject("specification").putObject("legacyProjection");
        projection.put("model_code", "entity").put("column_name", "entity_key").put("business_name", "归属编号")
            .put("data_type", "integer").put("role", "MEASURE").put("description", "已确认归属编号")
            .put("nullable_flag", false).put("sensitivity_level", "PUBLIC").put("masking_policy", "NONE")
            .put("status", "ENABLED");
        for (String field : List.of("expression", "synonyms", "unit", "retrieval_json")) projection.putNull(field);
        for (String field : List.of("allow_aggregation", "allow_filter", "allow_projection", "allow_export", "allow_send_to_llm")) projection.put(field, true);
        catalog.setLegacyModelBindings(List.of(binding));
        return catalog;
    }

    @Test void existingImmutableLegacyAttributeUsesTheSameProjectionAndSerializedPlanIdentity() throws Exception {
        var catalog = legacyFixture();
        String hash = SemanticCatalogFingerprint.fingerprint(catalog);
        var dimension = GovernedAttributeDimensions.expand(catalog).getDimensions().get(0);
        assertThat(dimension.getDimensionCode()).isEqualTo(GovernedAttributeDimensions.identity("entity", "legacy_role"));
        assertThat(dimension.getDefinitionBinding()).isEqualTo(new SemanticDefinitionBinding(
            "legacy_definition", 2, "entity", "legacy_role", "归属编号", List.of(), null, null));
        var restored = JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(dimension), SemanticCatalogSnapshot.Dimension.class);
        assertThat(restored.getDefinitionBinding()).isEqualTo(dimension.getDefinitionBinding());
        assertThat(SemanticCatalogFingerprint.fingerprint(catalog)).isEqualTo(hash);
        assertThat(catalog.getColumns().get(0).getDefinitionBinding()).isNull();
        assertThat(catalog.getDimensions()).isEmpty();
    }

    @Test void legacyProjectionRejectsStaleRevisionRoleFieldPermissionMetadataAndDuplicateBindings() {
        for (String defect : List.of("revision", "definition", "type", "role", "asset", "legacyAsset", "model", "field", "permission", "aliases", "mapping", "duplicate", "missing")) {
            var catalog = legacyFixture();
            var binding = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.getLegacyModelBindings().get(0);
            var definition = (com.fasterxml.jackson.databind.node.ObjectNode) binding.get("definitionJson");
            var projection = (com.fasterxml.jackson.databind.node.ObjectNode) definition.path("specification").path("legacyProjection");
            switch (defect) {
                case "revision" -> definition.put("revision", 3);
                case "definition" -> definition.put("code", "other");
                case "type" -> definition.put("type", "DIMENSION");
                case "role" -> binding.put("roleName", "other");
                case "asset" -> binding.put("assetKey", "other");
                case "legacyAsset" -> binding.put("legacyAssetKey", "other");
                case "model" -> binding.put("model", "other");
                case "field" -> projection.put("column_name", "other");
                case "permission" -> projection.put("allow_projection", false);
                case "aliases" -> binding.withArray("aliases").add("未确认别名");
                case "mapping" -> binding.withObject("attributeMappings").put("key", "entity_key");
                case "duplicate" -> catalog.setLegacyModelBindings(List.of(binding, binding.deepCopy()));
                case "missing" -> binding.remove("definitionJson");
            }
            assertThat(GovernedAttributeDimensions.expand(catalog).getDimensions()).as(defect).isEmpty();
        }
    }
}
