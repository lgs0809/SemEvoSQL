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
package cn.lgs.semevosql.project.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocumentBuildService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.application.SemanticCatalogFingerprint;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;

/** Request validation must never compete with the durable embedding worker. */
@ExtendWith(MockitoExtension.class)
class ProjectValidationIndexPreparationTest {
    @Mock SemanticProjectRepository repository;
    @Mock ProjectVersionReleaseGate releaseGate;
    @Mock SemanticRetrievalDocumentBuildService retrieval;
    @Mock LocalOperatorService authorization;
    @Mock SemanticCatalogRepository catalogs;
    @Mock TransactionTemplate transactions;
    @Mock ProjectVersionActivityService activities;
    String hash;
    @InjectMocks ProjectInitializationApplicationService service;

    private SemanticProjectVersion fixture() {
        hash=SemanticCatalogFingerprint.fingerprint(SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).build());
        var version=SemanticProjectVersion.firstDraft(1L,"1.0.0","synthetic test");
        version.setId(2L);version.startAnalysis();version.completeAnalysis();
        when(repository.findProject(1L)).thenReturn(Optional.of(SemanticProject.builder().id(1L).build()));
        when(repository.findVersion(2L)).thenReturn(Optional.of(version));
        when(releaseGate.validate(1L,null,2L)).thenReturn(new ProjectVersionReleaseGate.ReleaseReport(
            1L,null,2L,hash,true,List.of(),List.of(),List.of(),List.of(),0,0,0,true,LocalDateTime.now()));
        return version;
    }
    @Test void pendingIndexKeepsDraftAndOnlyEnqueuesCompleteText() {
        var version=fixture();
        doThrow(new IllegalStateException("INDEX_BUILDING")).when(retrieval).assertReady(1L,2L,hash);
        assertThrows(IllegalStateException.class,()->service.validateVersion(1L,2L,OperatorContext.system("test")));
        assertEquals(ProjectVersionStatus.DRAFT,version.getStatus());
        verify(retrieval).prepare(1L,2L,hash);verify(retrieval,never()).build(any(),any(),any());
        verify(repository,never()).updateVersion(any());
    }
    @Test void readyIndexAllowsNormalValidationWithoutReencoding() {
        var version=fixture();
        when(catalogs.loadCatalog(1L,2L)).thenReturn(SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).build());
        when(transactions.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        service.validateVersion(1L,2L,OperatorContext.system("test"));
        assertEquals(ProjectVersionStatus.VALIDATED,version.getStatus());
        var ordered=inOrder(retrieval,repository);
        ordered.verify(retrieval).prepare(1L,2L,hash);ordered.verify(retrieval,times(2)).assertReady(1L,2L,hash);
        ordered.verify(repository).updateVersion(version);
        verify(retrieval,never()).build(any(),any(),any());
    }

    @Test void publicationRunsReleaseChecksBeforeEnteringItsShortCommit() {
        var version=fixture();version.setCatalogHash(hash);version.validateVersion();
        var project=SemanticProject.builder().id(1L).activeVersionId(99L).build();
        when(repository.findProject(1L)).thenReturn(Optional.of(project));
        when(catalogs.loadCatalog(1L,2L)).thenReturn(SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).build());
        when(transactions.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        service.publishVersion(1L,2L,OperatorContext.system("test"));
        assertEquals(ProjectVersionStatus.PUBLISHED,version.getStatus());
        var order=inOrder(releaseGate,transactions,repository);
        order.verify(releaseGate).validate(1L,null,2L);
        order.verify(transactions).execute(any());
        order.verify(repository).lockProject(1L);
        order.verify(repository).lockVersion(1L,2L);
        verify(releaseGate,times(1)).validate(1L,null,2L);
    }

    @Test void changedVersionBetweenPreparationAndCommitCannotBePublished() {
        var version=fixture();version.setCatalogHash(hash);version.validateVersion();
        var prepared=service.preparePublication(1L,2L,OperatorContext.system("test"));
        version.setRevision(version.getRevision()+1);
        assertThrows(IllegalStateException.class,()->service.commitPublication(prepared,OperatorContext.system("test")));
        assertEquals(ProjectVersionStatus.VALIDATED,version.getStatus());
        verify(repository,never()).updateVersion(any());
    }

    @Test void catalogChangedAfterPreparationCannotUseTheOldReleaseReport() {
        var version=fixture();version.setCatalogHash(hash);version.validateVersion();
        var prepared=service.preparePublication(1L,2L,OperatorContext.system("test"));
        when(catalogs.loadCatalog(1L,2L)).thenReturn(SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("changed").build())).build());
        assertThrows(IllegalStateException.class,()->service.commitPublication(prepared,OperatorContext.system("test")));
        assertEquals(ProjectVersionStatus.VALIDATED,version.getStatus());
        verify(repository,never()).updateVersion(any());
    }
}
