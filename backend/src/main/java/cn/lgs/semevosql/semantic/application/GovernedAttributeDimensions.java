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
import java.util.*;

/** Query-only projections of existing shared ATTRIBUTE roles; never a catalog publication. */
public final class GovernedAttributeDimensions {
    private GovernedAttributeDimensions() {}

    public static String identity(String model, String binding) {
        return "a_" + SharedCatalogProtocol.assetCode(model, binding).substring(2);
    }

    public static SemanticCatalogSnapshot expand(SemanticCatalogSnapshot catalog) {
        var enabledModels = new HashSet<String>();
        catalog.getModels().stream().filter(SemanticCatalogSnapshot.Model::isEnabled)
            .forEach(model -> enabledModels.add(model.getModelCode()));
        var visibility = new SemanticCatalogVisibility(catalog);
        var additions = new ArrayList<SemanticCatalogSnapshot.Dimension>();
        for (var column : catalog.getColumns()) {
            var ref = reference(catalog, column);
            if (ref == null || !enabledModels.contains(column.getModelCode())
                    || !Objects.equals(column.getModelCode(), ref.modelCode())
                    || !Boolean.TRUE.equals(column.getAllowProjection()) || !visibility.maySendColumn(column)
                    || column.getRole() == null
                    || !Set.of("integer", "decimal", "string", "boolean", "date", "datetime")
                        .contains(SourceSchemaExportService.protocolType(column.getDataType()))
                    || !direct(column.getColumnName(), column.getExpression())) continue;
            if (!authorized(catalog, column, ref)) continue;
            // A declared direct dimension supplies its own business identity and remains preferred.
            if (catalog.getDimensions().stream().anyMatch(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED
                    && column.getModelCode().equals(dimension.getModelCode())
                    && column.getColumnName().equals(dimension.getColumnName())
                    && direct(column.getColumnName(), dimension.getExpression()))) continue;
            String code = identity(ref.modelCode(), ref.bindingCode());
            if (catalog.getDimensions().stream().anyMatch(dimension -> code.equals(dimension.getDimensionCode())))
                throw new SemanticPlanningRejectedException("CANDIDATE_SCOPE_CHANGED", "A governed attribute projection identity is occupied");
            additions.add(SemanticCatalogSnapshot.Dimension.builder().projectId(catalog.getProjectId())
                .projectVersionId(catalog.getProjectVersionId()).modelCode(column.getModelCode()).dimensionCode(code)
                .businessName(ref.roleName()).columnName(column.getColumnName())
                .dimensionType(column.getRole() == SemanticColumnRole.TIME ? "TIME" : "ATTRIBUTE")
                .description(column.getDescription()).evidence(column.getEvidence()).definitionBinding(ref)
                .retrievalJson(column.getRetrievalJson()).status(SemanticAssetStatus.ENABLED).build());
        }
        if (additions.isEmpty()) return catalog;
        if (additions.stream().map(SemanticCatalogSnapshot.Dimension::getDimensionCode).distinct().count() != additions.size())
            throw new SemanticPlanningRejectedException("CANDIDATE_SCOPE_CHANGED", "Governed attribute projection identities are ambiguous");
        var view = catalog.detachedCopy();
        var dimensions = new ArrayList<>(view.getDimensions());
        dimensions.addAll(additions);
        view.setDimensions(dimensions);
        return view;
    }

    private static SemanticDefinitionBinding reference(SemanticCatalogSnapshot catalog, SemanticCatalogSnapshot.Column column) {
        if (column.getDefinitionBinding() != null) return column.getDefinitionBinding();
        var matches = legacyBindings(catalog, column);
        if (matches.size() != 1) return null;
        var binding = matches.get(0);
        return new SemanticDefinitionBinding(binding.path("definition").asText(), binding.path("definitionRevision").asInt(),
            column.getModelCode(), binding.path("code").asText(), binding.path("roleName").asText(), List.of(), null, null);
    }

    private static List<JsonNode> legacyBindings(SemanticCatalogSnapshot catalog, SemanticCatalogSnapshot.Column column) {
        return catalog.getLegacyModelBindings().stream().filter(binding -> "ATTRIBUTE".equals(binding.path("assetType").asText())
            && column.getModelCode().equals(binding.path("model").asText())
            && column.getColumnName().equals(binding.path("assetKey").asText())).toList();
    }

    private static boolean authorized(SemanticCatalogSnapshot catalog, SemanticCatalogSnapshot.Column column, SemanticDefinitionBinding ref) {
        if (column.getDefinitionBinding() == null) {
            var bindings = legacyBindings(catalog, column);
            return bindings.size() == 1 && boundLegacyAttribute(column, ref, bindings.get(0));
        }
        var bindings = catalog.getModelBindings().stream().filter(binding -> ref.modelCode().equals(binding.path("model").asText())
            && ref.bindingCode().equals(binding.path("code").asText())).toList();
        var definitions = catalog.getSharedDefinitions().stream().filter(definition -> ref.definitionCode().equals(definition.path("code").asText())
            && ref.definitionRevision() == definition.path("revision").asInt()).toList();
        return bindings.size() == 1 && definitions.size() == 1 && boundAttribute(column, ref, bindings.get(0), definitions.get(0));
    }

    /** Existing imports register immutable, exact model-local attribute projections; no new business meaning is inferred. */
    private static boolean boundLegacyAttribute(SemanticCatalogSnapshot.Column column, SemanticDefinitionBinding ref, JsonNode binding) {
        var definition = binding.path("definitionJson");
        var projection = definition.path("specification").path("legacyProjection");
        if (ref.definitionRevision() <= 0 || !ref.definitionCode().equals(definition.path("code").asText())
                || ref.definitionRevision() != definition.path("revision").asInt()
                || !"ATTRIBUTE".equals(definition.path("type").asText())
                || !Objects.equals(column.getBusinessName(), ref.roleName())
                || !ref.roleName().equals(definition.path("name").asText())
                || !column.getColumnName().equals(binding.path("legacyAssetKey").asText())
                || !binding.path("attributeMappings").isObject() || !binding.path("attributeMappings").isEmpty()
                || !binding.path("aliases").isArray() || !binding.path("aliases").isEmpty()
                || !definition.path("aliases").isArray() || !definition.path("aliases").isEmpty()) return false;
        var expected = new LinkedHashMap<String,Object>();
        expected.put("model_code", column.getModelCode()); expected.put("column_name", column.getColumnName());
        expected.put("business_name", column.getBusinessName()); expected.put("data_type", column.getDataType());
        expected.put("role", column.getRole()); expected.put("expression", column.getExpression());
        expected.put("synonyms", column.getSynonyms()); expected.put("description", column.getDescription());
        expected.put("unit", column.getUnit()); expected.put("nullable_flag", column.getNullable());
        expected.put("sensitivity_level", column.getSensitivityLevel()); expected.put("masking_policy", column.getMaskingPolicy());
        expected.put("allow_aggregation", column.getAllowAggregation()); expected.put("allow_filter", column.getAllowFilter());
        expected.put("allow_projection", column.getAllowProjection()); expected.put("allow_export", column.getAllowExport());
        expected.put("allow_send_to_llm", column.getAllowSendToLlm()); expected.put("status", column.getStatus());
        expected.put("retrieval_json", column.getRetrievalJson());
        return projection.equals(JsonUtil.getObjectMapper().valueToTree(expected));
    }

    private static boolean boundAttribute(SemanticCatalogSnapshot.Column column, SemanticDefinitionBinding ref,
            JsonNode binding, JsonNode definition) {
        var specification = definition.path("specification");
        String parameter = specification.path("attribute").asText();
        var mappings = binding.path("attributeMappings");
        var aliases = new LinkedHashSet<String>();
        definition.path("aliases").forEach(alias -> aliases.add(alias.asText()));
        binding.path("aliases").forEach(alias -> aliases.add(alias.asText()));
        return "ATTRIBUTE".equals(definition.path("type").asText()) && ref.definitionRevision() > 0
            && ref.definitionCode().equals(binding.path("definition").asText())
            && ref.definitionRevision() == binding.path("definitionRevision").asInt()
            && ref.roleName() != null && !ref.roleName().isBlank() && ref.roleName().equals(binding.path("roleName").asText())
            && ref.confirmedAliases().equals(List.copyOf(aliases))
            && Objects.equals(ref.dictionaryCode(), binding.has("dictionary") ? binding.path("dictionary").path("code").asText() : null)
            && Objects.equals(ref.dictionaryRevision(), binding.has("dictionary") ? binding.path("dictionary").path("revision").asInt() : null)
            && mappings.isObject() && mappings.size() == 1 && !parameter.isBlank()
            && column.getColumnName().equals(mappings.path(parameter).asText());
    }

    private static boolean direct(String column, String expression) {
        return column != null && column.matches("[\\p{L}_][\\p{L}\\p{N}_]*")
            && (expression == null || expression.isBlank() || column.equals(expression.trim()));
    }
}
