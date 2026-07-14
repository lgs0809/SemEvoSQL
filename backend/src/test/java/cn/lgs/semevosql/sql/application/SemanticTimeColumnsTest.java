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
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticTimeColumnsTest {
    @Test void confirmedObservationTimeMayDifferFromMetricDefaultWithoutDisablingTheCostGuard() {
        var plan = SemanticBlueprint.builder()
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("ordered_amount")
                .timeColumn("ordered_at").build()))
            .timeRange(SemanticBlueprint.TimeRangeSelection.builder().modelCode("orders")
                .timeColumn("paid_at").build()).build();
        var columns=SemanticTimeColumns.from(plan);
        assertEquals(Set.of("ordered_at", "paid_at"), columns);
        var policy=new SemEvoSQLProperties().getSqlExecution();policy.setRequireTimeFilter(true);
        var guard=new SqlCostGuard(List.of());
        assertDoesNotThrow(() -> guard.validateSql(
            "SELECT SUM(amount) FROM orders WHERE paid_at >= ? AND paid_at < ?",Set.of("orders"),columns,policy));
        assertThrows(SqlGuardViolationException.class, () -> guard.validateSql(
            "SELECT SUM(amount) FROM orders WHERE unrelated_at >= ?",Set.of("orders"),columns,policy));
        assertThrows(SqlGuardViolationException.class, () -> guard.validateSql(
            "SELECT SUM(amount) FROM orders WHERE paid_at IS NOT NULL",Set.of("orders"),columns,policy));
    }
    @Test void absentPlanDoesNotInventTimeColumns() {
        assertTrue(SemanticTimeColumns.from(null).isEmpty());
        assertTrue(SemanticTimeColumns.from(SemanticBlueprint.builder().build()).isEmpty());
    }
}
