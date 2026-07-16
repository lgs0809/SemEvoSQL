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

import cn.lgs.semevosql.learning.QueryCaseHints.FilterBindingHint;
import cn.lgs.semevosql.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.TemporalFilterValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Normalize model-selected observation intervals into the existing governed predicates.
 * Each interval applies to the rows of its model; per-measure comparison periods remain
 * execution-owned computation requirements, not conjunctive row filters.
 */
final class SemanticObservationIntervals {
    private static final Set<String> FIELDS = Set.of("modelCode", "columnName", "startInclusive", "endExclusive");

    private SemanticObservationIntervals() { }

    static List<FilterBindingHint> resolve(String query, JsonNode node, SemanticCandidateSet candidates,
            double confidence, List<FilterBindingHint> literalFilters, TimeBindingHint legacyBinding) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > candidates.timeColumns().size())
            throw new IllegalArgumentException("timeIntervals must be an array with at most one interval per governed time axis");
        var seen = new HashSet<String>();
        var result = new ArrayList<FilterBindingHint>();
        for (var item : node) {
            if (!item.isObject()) throw new IllegalArgumentException("timeIntervals entries must be objects");
            item.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown timeIntervals field: " + field);
            });
            String model = text(item, "modelCode"), name = text(item, "columnName");
            var column = candidates.timeColumns().stream()
                .filter(c -> Objects.equals(model, c.getModelCode()) && Objects.equals(name, c.getColumnName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("timeIntervals contains non-candidate time axis: " + model + "." + name));
            if (!seen.add(model + ":" + name)
                    || literalFilters.stream().anyMatch(f -> Objects.equals(model, f.modelCode()) && Objects.equals(name, f.columnName()))
                    || legacyBinding != null && legacyBinding.startInclusive() != null
                        && Objects.equals(model, legacyBinding.modelCode()) && Objects.equals(name, legacyBinding.columnName()))
                throw new IllegalArgumentException("Observation interval duplicates another predicate for " + model + "." + name);
            var interval = new TimeBindingHint(query, model, name, "LLM_SEMANTIC_PLANNER", confidence, null,
                text(item, "startInclusive"), text(item, "endExclusive"));
            result.add(new FilterBindingHint(query, model, name, "GTE", boundary(column, interval.startInclusive()),
                interval.sourceExampleId(), confidence));
            result.add(new FilterBindingHint(query, model, name, "LT", boundary(column, interval.endExclusive()),
                interval.sourceExampleId(), confidence));
        }
        return List.copyOf(result);
    }

    private static String boundary(SemanticCatalogSnapshot.Column column, String value) {
        if ("DATE".equalsIgnoreCase(column.getDataType())) {
            var dateTime = LocalDateTime.parse(value);
            if (!dateTime.toLocalTime().equals(LocalTime.MIDNIGHT))
                throw new IllegalArgumentException("A date observation interval must use whole-day boundaries");
            value = dateTime.toLocalDate().toString();
        }
        TemporalFilterValue.normalize(column.getDataType(), value);
        return value;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("timeIntervals requires text field " + field);
        return value.textValue().trim();
    }
}
