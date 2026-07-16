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
package cn.lgs.semevosql.evolution.application;

import cn.lgs.semevosql.evolution.SemanticPatch.Operation;
import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Computes a side-effect-free business-semantic diff between the Active Catalog and a newly parsed
 * corpus fragment.
 *
 * <p>Corpus material is never allowed to remap datasources/tables, introduce physical columns, or
 * mutate security/governance attributes. Such differences are returned as blocking issues instead
 * of becoming automatic SemanticPatch operations.
 */
@Service
public class SemanticCatalogDiffService {

    private final SemanticCatalogPatchAnalyzer fingerprint;

    public SemanticCatalogDiffService(SemanticCatalogPatchAnalyzer fingerprint) {
        this.fingerprint = fingerprint;
    }

    public DiffResult diff(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming) {
        if (current == null || incoming == null) {
            return DiffResult.empty();
        }
        List<Operation> operations = new ArrayList<>();
        List<BlockedChange> blocked = new ArrayList<>();

        diffModels(current, incoming, operations, blocked);
        diffColumns(current, incoming, operations, blocked);
        diffMetrics(current, incoming, operations);
        diffDimensions(current, incoming, operations);
        diffRelationships(current, incoming, operations);
        diffGrains(current, incoming, operations);
        diffEnums(current, incoming, operations);
        diffRules(current, incoming, operations);
        return new DiffResult(List.copyOf(operations), List.copyOf(blocked));
    }

    private void diffModels(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out,
            List<BlockedChange> blocked) {
        Map<String, SemanticCatalogSnapshot.Model> existing = index(current.getModels(),
                SemanticCatalogSnapshot.Model::getModelCode);
        for (SemanticCatalogSnapshot.Model candidate : safe(incoming.getModels())) {
            String key = candidate.getModelCode();
            SemanticCatalogSnapshot.Model before = existing.get(key);
            if (before == null) {
                blocked.add(new BlockedChange("MODEL", key, "NEW_MODEL_REQUIRES_DATASOURCE_SCHEMA_SYNC",
                        "Corpus material cannot introduce a new physical model mapping"));
                continue;
            }
            if (explicitlyChanged(before.getDatasourceId(), candidate.getDatasourceId())
                    || explicitlyChanged(before.getPhysicalTable(), candidate.getPhysicalTable())
                    || explicitlyChanged(before.getStatus(), candidate.getStatus())) {
                blocked.add(new BlockedChange("MODEL", key, "PROTECTED_MODEL_MAPPING_CHANGE",
                        "datasourceId, physicalTable and status are governed outside Semantic Evolution"));
                continue;
            }
            Map<String, Object> values = changedValues(map("businessName", before.getBusinessName(), "modelType",
                    before.getModelType(), "description", before.getDescription()), map("businessName",
                    candidate.getBusinessName(), "modelType", candidate.getModelType(), "description",
                    candidate.getDescription()));
            addUpdate(out, OperationType.UPDATE_MODEL, AssetType.MODEL, key, before, values);
        }
    }

    private void diffColumns(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out,
            List<BlockedChange> blocked) {
        Map<String, SemanticCatalogSnapshot.Column> existing = index(current.getColumns(), this::columnKey);
        for (SemanticCatalogSnapshot.Column candidate : safe(incoming.getColumns())) {
            String key = columnKey(candidate);
            SemanticCatalogSnapshot.Column before = existing.get(key);
            if (before == null) {
                blocked.add(new BlockedChange("COLUMN", key, "NEW_COLUMN_REQUIRES_SCHEMA_SYNC",
                        "Corpus material cannot introduce a physical column; refresh datasource schema first"));
                continue;
            }
            if (explicitlyChanged(before.getDataType(), candidate.getDataType())
                    || explicitlyChanged(before.getNullable(), candidate.getNullable())
                    || explicitlyChanged(before.getSensitivityLevel(), candidate.getSensitivityLevel())
                    || explicitlyChanged(before.getMaskingPolicy(), candidate.getMaskingPolicy())
                    || explicitlyChanged(before.getAllowAggregation(), candidate.getAllowAggregation())
                    || explicitlyChanged(before.getAllowFilter(), candidate.getAllowFilter())
                    || explicitlyChanged(before.getAllowProjection(), candidate.getAllowProjection())
                    || explicitlyChanged(before.getAllowExport(), candidate.getAllowExport())
                    || explicitlyChanged(before.getAllowSendToLlm(), candidate.getAllowSendToLlm())
                    || explicitlyChanged(before.getStatus(), candidate.getStatus())) {
                blocked.add(new BlockedChange("COLUMN", key, "PROTECTED_COLUMN_SCHEMA_OR_POLICY_CHANGE",
                        "Physical type/nullability and data-governance fields require schema/policy administration"));
                continue;
            }
            Map<String, Object> values = changedValues(map("businessName", before.getBusinessName(), "role",
                    before.getRole(), "expression", before.getExpression(), "synonyms", before.getSynonyms(),
                    "description", before.getDescription()), map("businessName", candidate.getBusinessName(), "role",
                    candidate.getRole(), "expression", candidate.getExpression(), "synonyms", candidate.getSynonyms(),
                    "description", candidate.getDescription()));
            addUpdate(out, OperationType.UPDATE_COLUMN, AssetType.COLUMN, key, before, values);
        }
    }

    private void diffMetrics(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.Metric> existing = index(current.getMetrics(),
                SemanticCatalogSnapshot.Metric::getMetricCode);
        for (SemanticCatalogSnapshot.Metric candidate : safe(incoming.getMetrics())) {
            String key = candidate.getMetricCode();
            SemanticCatalogSnapshot.Metric before = existing.get(key);
            Map<String, Object> candidateValues = map("modelCode", candidate.getModelCode(), "metricCode",
                    candidate.getMetricCode(), "businessName", candidate.getBusinessName(), "expression",
                    candidate.getExpression(), "aggregation", candidate.getAggregation(), "unit", candidate.getUnit(),
                    "timeColumn", candidate.getTimeColumn(), "filterExpression", candidate.getFilterExpression(),
                    "additiveType", candidate.getAdditiveType(), "description", candidate.getDescription(), "evidence",
                    candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_METRIC, "METRIC", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("businessName", before.getBusinessName(), "expression",
                        before.getExpression(), "aggregation", before.getAggregation(), "unit", before.getUnit(),
                        "timeColumn", before.getTimeColumn(), "filterExpression", before.getFilterExpression(),
                        "additiveType", before.getAdditiveType(), "description", before.getDescription());
                Map<String, Object> updateValues = changedValues(beforeValues,
                        select(candidateValues, beforeValues.keySet()));
                addUpdate(out, OperationType.UPDATE_METRIC, AssetType.METRIC, key, before, updateValues);
            }
        }
    }

    private void diffDimensions(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming,
            List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.Dimension> existing = index(current.getDimensions(),
                SemanticCatalogSnapshot.Dimension::getDimensionCode);
        for (SemanticCatalogSnapshot.Dimension candidate : safe(incoming.getDimensions())) {
            String key = candidate.getDimensionCode();
            SemanticCatalogSnapshot.Dimension before = existing.get(key);
            Map<String, Object> candidateValues = map("modelCode", candidate.getModelCode(), "dimensionCode",
                    candidate.getDimensionCode(), "businessName", candidate.getBusinessName(), "columnName",
                    candidate.getColumnName(), "expression", candidate.getExpression(), "dimensionType",
                    candidate.getDimensionType(), "hierarchy", candidate.getHierarchy(), "description",
                    candidate.getDescription(), "evidence", candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_DIMENSION, "DIMENSION", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("businessName", before.getBusinessName(), "columnName",
                        before.getColumnName(), "expression", before.getExpression(), "dimensionType",
                        before.getDimensionType(), "hierarchy", before.getHierarchy(), "description",
                        before.getDescription());
                addUpdate(out, OperationType.UPDATE_DIMENSION, AssetType.DIMENSION, key, before,
                        changedValues(beforeValues, select(candidateValues, beforeValues.keySet())));
            }
        }
    }

    private void diffRelationships(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming,
            List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.Relationship> existing = index(current.getRelationships(),
                SemanticCatalogSnapshot.Relationship::getRelationshipCode);
        for (SemanticCatalogSnapshot.Relationship candidate : safe(incoming.getRelationships())) {
            String key = candidate.getRelationshipCode();
            SemanticCatalogSnapshot.Relationship before = existing.get(key);
            Map<String, Object> candidateValues = map("relationshipCode", candidate.getRelationshipCode(),
                    "sourceModelCode", candidate.getSourceModelCode(), "targetModelCode", candidate.getTargetModelCode(),
                    "cardinality", candidate.getCardinality(), "joinType", candidate.getJoinType(), "joinCondition",
                    candidate.getJoinCondition(), "description", candidate.getDescription(), "evidence",
                    candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_RELATIONSHIP, "RELATIONSHIP", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("sourceModelCode", before.getSourceModelCode(),
                        "targetModelCode", before.getTargetModelCode(), "cardinality", before.getCardinality(),
                        "joinType", before.getJoinType(), "joinCondition", before.getJoinCondition(), "description",
                        before.getDescription());
                addUpdate(out, OperationType.UPDATE_RELATIONSHIP, AssetType.RELATIONSHIP, key, before,
                        changedValues(beforeValues, select(candidateValues, beforeValues.keySet())));
            }
        }
    }

    private void diffGrains(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.Grain> existing = index(current.getGrains(), this::grainKey);
        for (SemanticCatalogSnapshot.Grain candidate : safe(incoming.getGrains())) {
            String key = grainKey(candidate);
            SemanticCatalogSnapshot.Grain before = existing.get(key);
            Map<String, Object> candidateValues = map("modelCode", candidate.getModelCode(), "grainCode",
                    candidate.getGrainCode(), "keyColumns", candidate.getKeyColumns(), "timeColumn",
                    candidate.getTimeColumn(), "uniquenessRule", candidate.getUniquenessRule(), "description",
                    candidate.getDescription(), "evidence", candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_GRAIN, "GRAIN", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("keyColumns", before.getKeyColumns(), "timeColumn",
                        before.getTimeColumn(), "uniquenessRule", before.getUniquenessRule(), "description",
                        before.getDescription());
                addUpdate(out, OperationType.UPDATE_GRAIN, AssetType.GRAIN, key, before,
                        changedValues(beforeValues, select(candidateValues, beforeValues.keySet())));
            }
        }
    }

    private void diffEnums(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.EnumValue> existing = index(current.getEnumValues(), this::enumKey);
        for (SemanticCatalogSnapshot.EnumValue candidate : safe(incoming.getEnumValues())) {
            String key = enumKey(candidate);
            SemanticCatalogSnapshot.EnumValue before = existing.get(key);
            Map<String, Object> candidateValues = map("modelCode", candidate.getModelCode(), "columnName",
                    candidate.getColumnName(), "valueCode", candidate.getValueCode(), "businessName",
                    candidate.getBusinessName(), "aliases", candidate.getAliases(), "description",
                    candidate.getDescription(), "sortOrder", candidate.getSortOrder(), "evidence", candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_ENUM_VALUE, "ENUM_VALUE", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("businessName", before.getBusinessName(), "aliases",
                        before.getAliases(), "description", before.getDescription(), "sortOrder", before.getSortOrder());
                addUpdate(out, OperationType.UPDATE_ENUM_VALUE, AssetType.ENUM_VALUE, key, before,
                        changedValues(beforeValues, select(candidateValues, beforeValues.keySet())));
            }
        }
    }

    private void diffRules(SemanticCatalogSnapshot current, SemanticCatalogSnapshot incoming, List<Operation> out) {
        Map<String, SemanticCatalogSnapshot.Rule> existing = index(current.getRules(),
                SemanticCatalogSnapshot.Rule::getRuleCode);
        for (SemanticCatalogSnapshot.Rule candidate : safe(incoming.getRules())) {
            String key = candidate.getRuleCode();
            SemanticCatalogSnapshot.Rule before = existing.get(key);
            Map<String, Object> candidateValues = map("modelCode", candidate.getModelCode(), "ruleCode",
                    candidate.getRuleCode(), "ruleType", candidate.getRuleType(), "businessName",
                    candidate.getBusinessName(), "expression", candidate.getExpression(), "severity",
                    candidate.getSeverity(), "description", candidate.getDescription(), "evidence", candidate.getEvidence());
            if (before == null) {
                out.add(add(OperationType.ADD_RULE, "RULE", key, candidateValues));
            }
            else {
                Map<String, Object> beforeValues = map("ruleType", before.getRuleType(), "businessName",
                        before.getBusinessName(), "expression", before.getExpression(), "severity", before.getSeverity(),
                        "description", before.getDescription());
                addUpdate(out, OperationType.UPDATE_RULE, AssetType.RULE, key, before,
                        changedValues(beforeValues, select(candidateValues, beforeValues.keySet())));
            }
        }
    }

    private void addUpdate(List<Operation> out, OperationType type, AssetType assetType, String key, Object before,
            Map<String, Object> changedValues) {
        if (changedValues.isEmpty()) {
            return;
        }
        out.add(new Operation(type, assetType.name(), key, fingerprint.fingerprintAsset(assetType, before), changedValues,
                List.of()));
    }

    private Operation add(OperationType type, String assetType, String key, Map<String, Object> values) {
        return new Operation(type, assetType, key, null, values, List.of());
    }

    private Map<String, Object> changedValues(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!Objects.equals(normalize(before.get(entry.getKey())), normalize(entry.getValue()))) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(changed);
    }

    private Object normalize(Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : value;
    }

    private Map<String, Object> select(Map<String, Object> source, java.util.Set<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        keys.forEach(key -> {
            if (source.containsKey(key)) {
                result.put(key, source.get(key));
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) {
                values.put(Objects.toString(pairs[index]), normalize(value));
            }
        }
        return Map.copyOf(values);
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : safe(values)) {
            result.put(key.apply(value), value);
        }
        return result;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private boolean explicitlyChanged(Object before, Object after) {
        return after != null && !Objects.equals(normalize(before), normalize(after));
    }

    private String columnKey(SemanticCatalogSnapshot.Column value) {
        return value.getModelCode() + ":" + value.getColumnName();
    }

    private String grainKey(SemanticCatalogSnapshot.Grain value) {
        return value.getModelCode() + ":" + value.getGrainCode();
    }

    private String enumKey(SemanticCatalogSnapshot.EnumValue value) {
        return value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode();
    }

    public record BlockedChange(String assetType, String assetKey, String code, String reason) {
    }

    public record DiffResult(List<Operation> operations, List<BlockedChange> blockedChanges) {
        public DiffResult {
            operations = operations == null ? List.of() : List.copyOf(operations);
            blockedChanges = blockedChanges == null ? List.of() : List.copyOf(blockedChanges);
        }

        public boolean semanticDiffDetected() {
            return !operations.isEmpty() || !blockedChanges.isEmpty();
        }

        public boolean releasable() {
            return !operations.isEmpty() && blockedChanges.isEmpty();
        }

        public static DiffResult empty() {
            return new DiffResult(List.of(), List.of());
        }
    }
}
