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
package cn.lgs.semevosql.semantic.compiler;

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryPaginationTest {
    static SemanticBlueprint plan(long offset) {
        return SemanticBlueprint.builder().projectId(1L).projectVersionId(1L).limit(2).offset(offset).executable(true)
            .models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("sales").physicalTable("orders").datasourceId(1).build()))
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("sales")).build()))
            .projections(List.of(SemanticBlueprint.ProjectionSelection.builder().modelCode("sales").columnName("id").alias("id").projectionType("DIMENSION").build()))
            .orderBy(List.of(SemanticBlueprint.OrderSelection.builder().expression("id").direction("ASC").build())).build();
    }
    static SemanticCatalogSnapshot catalog() {
        return SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(1L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("sales").physicalTable("orders").datasourceId(1).status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("sales").columnName("id").dataType("bigint").allowProjection(true).status(SemanticAssetStatus.ENABLED).build())).build();
    }
    @Test void normalAndDeepPagesCompileWithoutChangingExecutionRouteAndSurviveJson() throws Exception {
        for(long offset:List.of(0L,2L,2_000_000_000L)) {
            var plan=plan(offset);
            var restored=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),SemanticBlueprint.class);
            assertEquals(offset,restored.getOffset());
            for(var dialect:List.of(SqlDialect.MYSQL,SqlDialect.POSTGRESQL)) {
                var compiled=new SemanticSqlCompiler().compile(restored,catalog(),dialect).sources().get(0);
                assertTrue(compiled.sql().endsWith(offset==0?"LIMIT 2":"LIMIT 2 OFFSET "+offset));
            }
        }
        assertEquals(0L,JsonUtil.getObjectMapper().readValue("{}",SemanticBlueprint.class).getOffset());
    }
    @Test void malformedPaginationCannotEscapeThroughTheGenerationRoute() {
        for(long offset:List.of(-1L,Long.MAX_VALUE)) {
            var plan=plan(offset);plan.setCompilerMode("GENERATED");
            assertEquals(LoweringCapabilityProbe.Status.INVALID,LoweringCapabilityProbe.probe(plan).status());
        }
        var plan=plan(2);plan.setMergePlan(SemanticBlueprint.MergePlan.builder().build());
        assertThrows(IllegalArgumentException.class,()->QueryPagination.validate(plan));
        plan.setMergePlan(null);plan.setOrderBy(List.of());
        assertThrows(IllegalArgumentException.class,()->QueryPagination.validate(plan));
    }
    @Test void generatedSqlMustRetainOuterOffsetPageSizeAndOrdering() {
        var preflight=new QueryPreflightService();
        for(String dialect:List.of("mysql","postgresql")) {
            assertDoesNotThrow(()->preflight.preflight("SELECT id FROM sales ORDER BY id LIMIT 2 OFFSET 2",catalog(),plan(2),1,dialect));
            for(String sql:List.of("SELECT id FROM sales ORDER BY id LIMIT 2", "SELECT id FROM sales ORDER BY id LIMIT 3 OFFSET 2",
                    "SELECT id FROM sales LIMIT 2 OFFSET 2", "SELECT id FROM sales ORDER BY id LIMIT 2 OFFSET 3",
                    "SELECT id FROM (SELECT id FROM sales ORDER BY id LIMIT 2 OFFSET 2) p"))
                assertThrows(QueryPreflightException.class,()->preflight.preflight(sql,catalog(),plan(2),1,dialect),sql);
        }
    }
}
