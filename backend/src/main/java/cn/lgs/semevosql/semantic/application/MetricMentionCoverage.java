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

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Checks that a planner accounts for catalog names in the actual question, including negated mentions. */
final class MetricMentionCoverage {
    private MetricMentionCoverage() { }
    record Mention(String businessName, Set<String> metricCodes) { }
    private record Occurrence(String name, int start, int end, String code) { }

    static List<Mention> mentions(String query, List<SemanticCatalogSnapshot.Metric> metrics) {
        List<Occurrence> occurrences=new ArrayList<>();
        for (var metric:metrics) {
            String name=metric.getBusinessName();
            if (name==null || name.isBlank()) continue;
            String boundary=name.matches("[A-Za-z0-9_ ]+") ? "(?<![A-Za-z0-9_])" : "";
            String suffix=boundary.isEmpty() ? "" : "(?![A-Za-z0-9_])";
            var matcher=Pattern.compile(boundary+Pattern.quote(name)+suffix,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(query);
            while(matcher.find()) occurrences.add(new Occurrence(name,matcher.start(),matcher.end(),metric.getMetricCode()));
        }
        Map<String,Set<String>> matched=new LinkedHashMap<>();
        for(var occurrence:occurrences) {
            boolean nested=occurrences.stream().anyMatch(other -> other.start()<=occurrence.start()
                && other.end()>=occurrence.end() && other.end()-other.start()>occurrence.end()-occurrence.start());
            if (!nested) matched.computeIfAbsent(occurrence.name(),ignored -> new LinkedHashSet<>()).add(occurrence.code());
        }
        return matched.entrySet().stream().map(e -> new Mention(e.getKey(),Set.copyOf(e.getValue()))).toList();
    }

    static void validate(String query,List<SemanticCatalogSnapshot.Metric> metrics,Set<String> selected,JsonNode exclusions) {
        validate(query,metrics,selected,exclusions,Set.of());
    }
    static void validate(String query,List<SemanticCatalogSnapshot.Metric> metrics,Set<String> selected,JsonNode exclusions,
            Set<String> confirmedPersonalResultNames) {
        var mentions=mentions(query,metrics);
        Set<String> accounted=new HashSet<>();
        if (!exclusions.isMissingNode() && !exclusions.isNull()) {
            if (!exclusions.isArray()) throw new IllegalArgumentException("metricExclusions must be an array");
            for(var exclusion:exclusions) {
                String name=exclusion.path("businessName").asText("");
                var mention=mentions.stream().filter(m -> m.businessName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("metricExclusions must refer to a supplied namedMetricMention"));
                String usage=exclusion.path("usage").asText("");
                String evidence=exclusion.path("evidence").asText("");
                if (!Set.of("EXCLUDED","CONTEXT").contains(usage) || evidence.isBlank() || evidence.length()>500
                        || !query.contains(evidence) || !evidence.contains(name) || evidence.trim().equals(name))
                    throw new IllegalArgumentException("Excluded/context metric requires a verbatim contextual question excerpt");
                if (!Collections.disjoint(mention.metricCodes(),selected) || confirmedPersonalResultNames.contains(name) || !accounted.add(name))
                    throw new IllegalArgumentException("Metric mention cannot be both selected and excluded, or excluded twice");
            }
        }
        for(var mention:mentions) {
            if (Collections.disjoint(mention.metricCodes(),selected) && !accounted.contains(mention.businessName())
                    && !confirmedPersonalResultNames.contains(mention.businessName()))
                throw new IllegalArgumentException("Unaccounted metric explicitly named in the question: "+mention.businessName()
                    +"; select its requested catalog meaning, or provide metricExclusions with EXCLUDED/CONTEXT and a verbatim contextual excerpt."
                    +" A time field or row filter must not substitute a different metric.");
        }
    }
}
