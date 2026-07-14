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
package cn.lgs.semevosql.sql.application;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.LinkedHashSet;
import java.util.Set;

/** Time axes already bound against the published Catalog, shared by both SQL execution paths. */
public final class SemanticTimeColumns {
    private SemanticTimeColumns() { }

    public static Set<String> from(SemanticBlueprint plan) {
        if (plan == null) return Set.of();
        Set<String> columns = new LinkedHashSet<>();
        plan.getMetrics().forEach(metric -> add(columns, metric.getTimeColumn()));
        plan.getGrains().forEach(grain -> add(columns, grain.getTimeColumn()));
        // An explicit governed time axis is independent of a metric's default time axis.
        // It is validated by semantic resolution before this execution policy runs.
        if (plan.getTimeRange() != null) add(columns, plan.getTimeRange().getTimeColumn());
        return Set.copyOf(columns);
    }

    private static void add(Set<String> columns, String column) {
        if (column != null && !column.isBlank()) columns.add(column);
    }
}
