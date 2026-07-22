/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.clarification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import cn.lgs.semevosql.semantic.retrieval.*;

class ProjectDefinitionPublicationWorkerTest {
    @Test void knownIndexWaitUsesDependencyCauseAndUnknownFailureKeepsOrdinaryBackoff() {
        var jobs=mock(ProjectDefinitionPublicationRepository.class);
        var publisher=mock(ProjectDefinitionPublisher.class);
        var work=mock(ProjectDefinitionPublicationRepository.Work.class);
        var worker=new ProjectDefinitionPublicationWorker(jobs,publisher,Runnable::run);
        when(jobs.claim(Duration.ofMinutes(5))).thenReturn(Optional.of(work));
        var readiness=new SemanticRetrievalIndexService.IndexReadiness(SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_BUILDING,6,5,"controlled readiness fixture");
        doThrow(new SemanticIndexNotReadyException(4L,15L,readiness)).when(publisher).publish(work);
        assertTrue(worker.processOne());verify(jobs).failWaitingForIndex(work);verify(jobs,never()).fail(work,false);
        reset(publisher,jobs);when(jobs.claim(Duration.ofMinutes(5))).thenReturn(Optional.of(work));
        doThrow(new IllegalStateException("controlled unknown failure")).when(publisher).publish(work);
        assertTrue(worker.processOne());verify(jobs).fail(work,false);verify(jobs,never()).failWaitingForIndex(work);
    }
    @Test void failedReadinessCheckCannotStarveOtherDuePublications() {
        var jobs=mock(ProjectDefinitionPublicationRepository.class);var publisher=mock(ProjectDefinitionPublisher.class);
        var dependencies=mock(ProjectDefinitionIndexDependencyService.class);
        var waiting=mock(ProjectDefinitionPublicationRepository.Work.class);var due=mock(ProjectDefinitionPublicationRepository.Work.class);
        when(jobs.waitingForIndex()).thenReturn(java.util.List.of(waiting));
        when(jobs.claim(Duration.ofMinutes(5))).thenReturn(Optional.of(due));
        when(dependencies.wake(waiting)).thenThrow(new IllegalStateException("controlled database outage"));
        var worker=new ProjectDefinitionPublicationWorker(jobs,publisher,Runnable::run);worker.setIndexDependencies(dependencies);
        assertTrue(worker.processOne());verify(publisher).publish(due);
    }
}
