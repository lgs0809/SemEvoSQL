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
package cn.lgs.semevosql.external.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.project.application.*;
import cn.lgs.semevosql.project.domain.ProjectRuntimeContext;
import cn.lgs.semevosql.semantic.application.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.application.SemanticCatalogReadService;
import java.util.*;
import org.junit.jupiter.api.*;

/** Facade wiring and disclosure guards; real SQL/owner resolution is tested in PostgreSQL IT. */
class ExternalSemanticQueryFacadeTest {
    SemanticCatalogRepository repository;
    ProjectScopeService scope;
    SemanticCatalogApplicationService catalog;
    ExternalSemanticQueryFacade facade;
    ProjectMcpDeployment deployment;
    SemanticCatalogSnapshot snapshot;
    static final String HASH="a".repeat(64);

    @BeforeEach void setup() {
        repository=mock(SemanticCatalogRepository.class);scope=mock(ProjectScopeService.class);
        catalog=mock(SemanticCatalogApplicationService.class);
        var gate=mock(ProjectRuntimeGate.class);
        when(gate.requireReadyByProject(1L)).thenReturn(new ProjectRuntimeContext(1L,2L,HASH));
        when(repository.authoritativeCatalogHash(1L,2L)).thenReturn(HASH);
        snapshot=SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("orders").physicalTable("orders").status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName("private_note").allowSendToLlm(false).status(SemanticAssetStatus.ENABLED).build())).build();
        when(repository.loadModelSlice(1L,2L,Set.of("orders"))).thenReturn(snapshot);
        when(repository.findAssetOwners(1L,2L,"COLUMN",Set.of("orders:private_note")))
            .thenReturn(List.of(new SemanticCatalogRepository.AssetOwner("orders:private_note","orders",null)));
        when(repository.findAssetOwners(1L,2L,"MODEL",Set.of("orders")))
            .thenReturn(List.of(new SemanticCatalogRepository.AssetOwner("orders","orders",null)));
        facade=new ExternalSemanticQueryFacade(scope,gate,catalog,
            new SemanticCatalogLookupService(repository,new SemanticCatalogReadService(repository)),
            mock(VerifiedQueryExecutionService.class),mock(cn.lgs.semevosql.multisource.MultiSourceRunService.class),
            mock(cn.lgs.semevosql.run.QueryRunService.class),new cn.lgs.semevosql.run.QueryRunErrorPresenter(),
            mock(ProjectMcpRepository.class),new ProjectMcpProperties());
        deployment=new ProjectMcpDeployment("test",1L,"integration:test",ProjectMcpDeployment.Status.RUNNING,
            "http://127.0.0.1/mcp","test",null,null,null,null);
    }
    @Test void scopedLookupStillRejectsColumnsThatCannotBeSentToModels() {
        assertThatThrownBy(()->facade.context(deployment,List.of(new ExternalSemanticQueryFacade.SemanticAssetRef("COLUMN","orders:private_note"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        verify(repository,never()).loadCatalog(any(),any());verifyNoInteractions(catalog);
    }
    @Test void revokedAccessBeforeReturningDetailsCannotLeakTheSnapshot() {
        doNothing().doThrow(new SecurityException("revoked")).when(scope).requireProject(eq(1L),any());
        assertThatThrownBy(()->facade.context(deployment,List.of(new ExternalSemanticQueryFacade.SemanticAssetRef("MODEL","orders"))))
            .isInstanceOf(SecurityException.class).hasMessage("revoked");
    }
    @Test void explicitPlanUsesLoadedSnapshotAndFrozenHash() {
        when(catalog.buildBlueprint(eq(1L),eq(2L),same(snapshot),anyString(),eq(Set.of("orders")),any()))
            .thenReturn(SemanticBlueprint.builder().executable(true).build());
        var result=facade.validate(deployment,new ExternalSemanticQueryFacade.ExternalQueryPlan("orders",Set.of("orders"),null,null,null,null,null,null,null));
        assertThat(result.valid()).isTrue();
        verify(catalog,never()).getCatalog(any(),any());verify(repository,never()).loadCatalog(any(),any());
        when(repository.authoritativeCatalogHash(1L,2L)).thenReturn("b".repeat(64));
        assertThat(facade.validate(deployment,new ExternalSemanticQueryFacade.ExternalQueryPlan("orders",Set.of("orders"),null,null,null,null,null,null,null)).valid()).isFalse();
    }
}
