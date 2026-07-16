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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SemanticCandidateLoadingTest {
    private SemanticCatalogSnapshot.Model model(String code, String table, int source) {
        return SemanticCatalogSnapshot.Model.builder().projectId(1L).projectVersionId(2L).modelCode(code)
            .physicalTable(table).datasourceId(source).businessName(code).status(SemanticAssetStatus.ENABLED).build();
    }
    private RetrievalHit hit(String code, String table) {
        return new RetrievalHit(DocumentType.MODEL,"MODEL","model:"+code,code,table,1d,Map.of(),Map.of());
    }
    @Test void aPhysicalTableNameCannotExpandAnExplicitModelHitIntoOtherDatasources() {
        var repository = mock(SemanticCatalogRepository.class);
        when(repository.authoritativeCatalogHash(1L,2L)).thenReturn("published-catalog-hash");
        when(repository.loadModelSlice(1L,2L,Set.of("sales_orders"))).thenReturn(SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(model("sales_orders","orders",1))).build());
        var candidates = new SemanticBlueprintGenerationService(repository,null)
            .candidates(1L,2L,List.of("orders"),List.of(hit("sales_orders","orders")));
        assertThat(candidates.modelCodes()).containsExactly("sales_orders");
        assertThat(candidates.catalogHash()).isEqualTo("published-catalog-hash");
        verify(repository,never()).loadCatalog(anyLong(),anyLong());
        verify(repository,never()).findModelsByTables(anyLong(),anyLong(),anySet(),anyInt());
    }
    @Test void exceedingTheExistingModelBudgetCannotSilentlyDropRequiredSeeds() {
        var repository = mock(SemanticCatalogRepository.class);
        var models = IntStream.range(0,25).mapToObj(n -> model("m"+n,"t"+n,1)).toList();
        when(repository.authoritativeCatalogHash(1L,2L)).thenReturn("published-catalog-hash");
        var hits = models.stream().map(m -> hit(m.getModelCode(),m.getPhysicalTable())).toList();
        assertThatThrownBy(() -> new SemanticBlueprintGenerationService(repository,null)
            .candidates(1L,2L,List.of(),hits)).isInstanceOf(SemanticPlanningRejectedException.class)
            .hasMessageContaining("budget");
    }
}
