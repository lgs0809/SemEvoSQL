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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A shared physical table must never replace a frozen semantic model identity. */
class FrozenSchemaScopeTest {
    SemanticCatalogSnapshot catalog() {
        return SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("cancelled_orders").physicalTable("public.orders")
                .datasourceId(1).status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("cancelled_orders").columnName("amount")
                .dataType("DECIMAL").status(SemanticAssetStatus.ENABLED).allowSendToLlm(true).build())).build();
    }
    Map<String,Object> request() {
        var query=new QueryEnhanceOutputDTO();query.setCanonicalQuery("Business question");
        return new HashMap<>(Map.of(PROJECT_ID,1L,PROJECT_VERSION_ID,2L,QUERY_ENHANCE_NODE_OUTPUT,query));
    }
    SemanticCatalogApplicationService service() {
        var service=mock(SemanticCatalogApplicationService.class);
        when(service.requireSingleDatasource(1L,2L)).thenReturn(1);
        when(service.enabledPhysicalTables(1L,2L)).thenReturn(Set.of("public.orders"));
        when(service.getCatalogForModels(1L,2L,Set.of("cancelled_orders"))).thenReturn(catalog());return service;
    }
    @Test void constrainedFallbackLoadsOnlyTheApprovedModelFromASharedPhysicalTable() throws Exception {
        var service=service();var input=request();
        input.put(FORCED_DATASOURCE_ID,1);input.put(FORCED_PHYSICAL_TABLES,List.of("public.orders"));
        input.put(TYPED_SEMANTIC_PLAN,SemanticBlueprint.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("cancelled_orders").physicalTable("public.orders").datasourceId(1).build())).build());
        var result=new SchemaRecallNode(service,new SemEvoSQLProperties()).apply(new OverAllState(input));
        assertThat(result).containsEntry(DATASOURCE_ID,1);
        verify(service).getCatalogForModels(1L,2L,Set.of("cancelled_orders"));
        verify(service,never()).getCatalogForPhysicalTables(any(),any(),any());
        verify(service,never()).recallPlanning(any(),any(),any(),anyInt());
        var documents=PublishedCatalogSchemaDocumentFactory.create(catalog(),1,Set.of("public.orders"));
        assertThat(documents.tables()).hasSize(1);
        assertThat(documents.tables().get(0).getMetadata()).containsEntry("semanticModelCode","cancelled_orders");
        assertThat(documents.columns().get(0).getMetadata()).containsEntry("semanticModelCode","cancelled_orders");
    }
    @Test void firstRecallKeepsTheModelIdentityRatherThanReResolvingATableName() throws Exception {
        var service=service();when(service.recallPlanning(1L,2L,"Business question",10))
            .thenReturn(new SemanticCatalogApplicationService.PlanningRecall(List.of("public.orders"),List.of(
                new RetrievalHit(DocumentType.MODEL,"MODEL","model:cancelled_orders","cancelled_orders","public.orders",1d,Map.of(),Map.of()))));
        new SchemaRecallNode(service,new SemEvoSQLProperties()).apply(new OverAllState(request()));
        verify(service).getCatalogForModels(1L,2L,Set.of("cancelled_orders"));
        verify(service,never()).getCatalogForPhysicalTables(any(),any(),any());
    }
    @Test void aPlanFromAnotherProjectCannotSelectItsSchema() {
        var service=service();var input=request();input.put(TYPED_SEMANTIC_PLAN,
            SemanticBlueprint.builder().projectId(99L).projectVersionId(2L).build());
        assertThatThrownBy(()->new SchemaRecallNode(service,new SemEvoSQLProperties()).apply(new OverAllState(input)))
            .isInstanceOf(SecurityException.class);
        verify(service,never()).getCatalogForModels(any(),any(),any());
    }
}
