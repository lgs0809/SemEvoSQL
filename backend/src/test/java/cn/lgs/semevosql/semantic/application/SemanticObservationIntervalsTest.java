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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticObservationIntervalsTest {
    static SemanticCatalogSnapshot.Column column(String model, String name, String type) {
        return SemanticCatalogSnapshot.Column.builder().modelCode(model).columnName(name).dataType(type)
            .role(SemanticColumnRole.TIME).allowFilter(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build();
    }

    static SemanticCandidateSet candidates() {
        return new SemanticCandidateSet(1L,1L,"hash",Set.of("credits","debits"),
            List.of(SemanticCatalogSnapshot.Model.builder().modelCode("credits").physicalTable("credits").status(SemanticAssetStatus.ENABLED).build(),
                SemanticCatalogSnapshot.Model.builder().modelCode("debits").physicalTable("debits").status(SemanticAssetStatus.ENABLED).build()),
            List.of(SemanticCatalogSnapshot.Metric.builder().metricCode("credit_amount").businessName("收入金额").modelCode("credits").status(SemanticAssetStatus.ENABLED).build(),
                SemanticCatalogSnapshot.Metric.builder().metricCode("debit_amount").businessName("支出金额").modelCode("debits").status(SemanticAssetStatus.ENABLED).build()),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            List.of(column("credits","received_at","timestamp"),column("debits","settled_on","date")),List.of(),List.of());
    }

    static String intervals() {
        return """
            [{"modelCode":"credits","columnName":"received_at","startInclusive":"2026-01-01","endExclusive":"2026-04-01"},
             {"modelCode":"debits","columnName":"settled_on","startInclusive":"2026-02-01","endExclusive":"2026-03-01"}]
            """;
    }

    @Test void planningResponseCarriesIndependentIntervalsThroughTheExistingPredicateContract() throws Exception {
        var client=mock(SemanticDocumentExtractionClient.class);
        String response="{\"status\":\"RESOLVED\",\"metricCodes\":[\"credit_amount\",\"debit_amount\"],"
            +"\"computationCapabilities\":[\"AGGREGATION\",\"TIME_FILTER\",\"SCALAR_COMPOSITION\"],"
            +"\"resultComposition\":{\"type\":\"SCALAR\",\"calculationExpression\":\"delta=credit_amount-debit_amount\"},"
            +"\"timeIntervals\":"+intervals()+"}";
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenReturn(new ModelCallResult("parser-contract",ModelCallPurpose.SEMANTIC_PLANNING,response,1,1));
        var planner=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client);
        var outcome=planner.planOutcome("两个已确认统计期间的收入金额减去支出金额",candidates(),List.of(),
            QueryCaseHints.empty(),QueryCaseHints.empty());
        var binding=assertInstanceOf(SemanticPlanningOutcome.Resolved.class,outcome).binding();
        assertNull(binding.timeBinding());assertEquals(4,binding.filterBindings().size());
        assertEquals(Set.of("credits","debits"),binding.modelCodes());
        assertTrue(binding.relationshipCodes().isEmpty());
        assertEquals("delta=credit_amount-debit_amount",binding.resultComposition().calculationExpression());
        var mapper=JsonUtil.getObjectMapper();
        assertEquals(binding,mapper.readValue(mapper.writeValueAsBytes(binding),QueryCaseHints.class));
        verify(client,times(1)).complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class));
    }

    @Test void invalidOrNonGovernedIntervalsCannotBeNormalizedIntoQueryPredicates() throws Exception {
        for (String input:List.of(intervals().replace("received_at","unknown"),intervals().replace("2026-01-01","2026-02-30"),
                intervals().replace("2026-04-01","2025-12-01"),intervals().replace("\"2026-01-01\"","null"),
                intervals().replace("\"2026-02-01\"","\"2026-02-01T12:00\""),
                intervals().replace("\"modelCode\":\"credits\"","\"sql\":\"DROP TABLE credits\",\"modelCode\":\"credits\""))) {
            var node=JsonUtil.getObjectMapper().readTree(input);
            assertThrows(IllegalArgumentException.class,()->SemanticObservationIntervals.resolve("期间",node,candidates(),1,List.of(),null));
        }
    }

    @Test void duplicateAxesAndConflictingLegacyOrLiteralPredicatesAreRejected() throws Exception {
        var mapper=JsonUtil.getObjectMapper();var first=mapper.readTree(intervals()).get(0);
        var duplicate=mapper.createArrayNode().add(first).add(first);
        assertThrows(IllegalArgumentException.class,()->SemanticObservationIntervals.resolve("期间",duplicate,candidates(),1,List.of(),null));
        var node=mapper.createArrayNode().add(first);
        var legacy=new QueryCaseHints.TimeBindingHint("期间","credits","received_at","MODEL",1,null,"2026-01-01","2026-04-01");
        assertThrows(IllegalArgumentException.class,()->SemanticObservationIntervals.resolve("期间",node,candidates(),1,List.of(),legacy));
        var filter=new QueryCaseHints.FilterBindingHint("期间","credits","received_at","GTE","2026-01-01","MODEL",1);
        assertThrows(IllegalArgumentException.class,()->SemanticObservationIntervals.resolve("期间",node,candidates(),1,List.of(filter),null));
        assertTrue(SemanticObservationIntervals.resolve("旧检查点",mapper.readTree("null"),candidates(),1,List.of(),legacy).isEmpty());
    }
}
