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
import static org.junit.jupiter.api.Assertions.*;

/** Regression for actual natural-language pagination: sort the governed dimension, never guess the first metric. */
class OrderingBindingTest {
    ComputationIntent intent(String requirements) throws Exception {
        var mapper = JsonUtil.getObjectMapper();
        return SemanticBlueprintGenerationService.computationIntent(mapper.readTree("[]"), mapper.readTree(requirements),
            Set.of("paid_amount"), Set.of("customer_id"));
    }
    SemanticBlueprint plan() {
        return SemanticBlueprint.builder()
            .dimensions(List.of(SemanticBlueprint.DimensionSelection.builder().dimensionCode("customer_id").build()))
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("paid_amount").build()))
            .projections(List.of(
                SemanticBlueprint.ProjectionSelection.builder().alias("customer_id").projectionType("DIMENSION").build(),
                SemanticBlueprint.ProjectionSelection.builder().alias("paid_amount").projectionType("METRIC").build()))
            .orderBy(List.of(SemanticBlueprint.OrderSelection.builder().expression("paid_amount").direction("DESC").build())).build();
    }
    @Test void dimensionOrderingOverridesLexicalMetricGuessBeforePaginationAndFreezesAcrossJson() throws Exception {
        var semantics = intent("""
            [{"capability":"ORDERING","dimensionCode":"customer_id","mode":"LOWEST"},
             {"capability":"OFFSET","offset":1},{"capability":"LIMIT","limit":2}]
            """);
        var plan = plan();
        SemanticBlueprintPipeline.reconcileComputationIntent(plan,semantics);
        var restored = JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),SemanticBlueprint.class);
        assertEquals(1L,restored.getOffset()); assertEquals(2,restored.getLimit());
        assertEquals("customer_id",restored.getOrderBy().get(0).getExpression());
        assertEquals("ASC",restored.getOrderBy().get(0).getDirection());
        assertEquals(semantics,restored.getComputationIntent());
    }
    @Test void multipleSortTargetsPreservePriorityAndCanonicalIdentity() throws Exception {
        var first = intent("""
            [{"capability":"ORDERING","metricCode":"paid_amount","mode":"HIGHEST"},
             {"capability":"ORDERING","dimensionCode":"customer_id","mode":"LOWEST"}]
            """);
        var reverse = intent("""
            [{"capability":"ORDERING","dimensionCode":"customer_id","mode":"LOWEST"},
             {"capability":"ORDERING","metricCode":"paid_amount","mode":"HIGHEST"}]
            """);
        var plan=plan();SemanticBlueprintPipeline.reconcileComputationIntent(plan,first);
        assertEquals(List.of("paid_amount","customer_id"),plan.getOrderBy().stream().map(SemanticBlueprint.OrderSelection::getExpression).toList());
        assertNotEquals(first.canonicalRequirements(),reverse.canonicalRequirements());
    }
    @Test void unavailableAmbiguousAndUnprojectedTargetsCannotExecute() throws Exception {
        for(String invalid:List.of(
            "{\"capability\":\"ORDERING\",\"dimensionCode\":\"private_id\",\"mode\":\"LOWEST\"}",
            "{\"capability\":\"ORDERING\",\"dimensionCode\":\"customer_id\",\"metricCode\":\"paid_amount\",\"mode\":\"LOWEST\"}",
            "{\"capability\":\"GROUPING\",\"dimensionCode\":\"customer_id\"}"))
            assertThrows(IllegalArgumentException.class,()->intent("["+invalid+"]"));
        var valid=intent("[{\"capability\":\"ORDERING\",\"dimensionCode\":\"customer_id\",\"mode\":\"LOWEST\"}]");
        var hidden=plan();hidden.setProjections(List.of());
        assertThrows(IllegalArgumentException.class,()->SemanticBlueprintPipeline.reconcileComputationIntent(hidden,valid));
        var unknown=intent("[{\"capability\":\"ORDERING\",\"dimensionCode\":\"customer_id\",\"mode\":\"RANDOM\"}]");
        assertThrows(IllegalArgumentException.class,()->SemanticBlueprintPipeline.reconcileComputationIntent(plan(),unknown));
    }
}
