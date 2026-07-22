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
import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

class ProjectDefinitionGovernanceControllerTest {
    @Test void publicationCheckUsesScopedAdministratorAndDoesNotWakeIncompleteDependencies() {
        var security=new LocalSecurityProperties();security.setEnabled(true);
        var member=new LocalSecurityProperties.Account();security.getAccounts().put("member",member);
        var admin=new LocalSecurityProperties.Account();admin.setAdministrator(true);security.getAccounts().put("admin",admin);
        var scope=mock(ProjectScopeService.class);
        var controller=new ProjectDefinitionGovernanceController(mock(ProjectDefinitionAssessmentRepository.class),scope,
            new OperatorContext.Resolver(new OperatorContextProperties(),security),mock(ProjectDefinitionDecisionService.class));
        var dependencies=mock(ProjectDefinitionIndexDependencyService.class);var worker=mock(ProjectDefinitionPublicationWorker.class);
        controller.setPublicationProgress(dependencies,worker);
        var request=new ProjectDefinitionIndexDependencyService.CheckRequest(10,1,"sha256:fixture");
        var spoof=new HttpHeaders();spoof.add("X-User-ID","admin");spoof.add("X-Role","ADMINISTRATOR");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()->controller.checkPublication(8,3,request,spoof,()->"member")).getStatusCode());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(scope).requireProject(eq(9L),any());
        assertThrows(ResponseStatusException.class,()->controller.checkPublication(9,3,request,spoof,()->"admin"));
        verifyNoInteractions(dependencies,worker);
        when(dependencies.check(eq(8L),eq(3L),eq(request),any())).thenReturn(new ProjectDefinitionIndexDependencyService.CheckResult("NOT_READY",6,5));
        assertEquals("NOT_READY",controller.checkPublication(8,3,request,HttpHeaders.EMPTY,()->"admin").status());
        verifyNoInteractions(worker);
        when(dependencies.check(eq(8L),eq(3L),eq(request),any())).thenReturn(new ProjectDefinitionIndexDependencyService.CheckResult("QUEUED",6,6));
        assertEquals("QUEUED",controller.checkPublication(8,3,request,HttpHeaders.EMPTY,()->"admin").status());
        verify(worker).scan();
    }
    @Test void contributionHistoryUsesAuthenticatedAdministratorAndServerProjectScope() {
        var security=new LocalSecurityProperties();security.setEnabled(true);
        var member=new LocalSecurityProperties.Account();security.getAccounts().put("member",member);
        var admin=new LocalSecurityProperties.Account();admin.setAdministrator(true);security.getAccounts().put("admin",admin);
        var resolver=new OperatorContext.Resolver(new OperatorContextProperties(),security);
        var scope=mock(ProjectScopeService.class);var repository=mock(ProjectDefinitionAssessmentRepository.class);
        var controller=new ProjectDefinitionGovernanceController(repository,scope,resolver,mock(ProjectDefinitionDecisionService.class));
        var spoof=new HttpHeaders();spoof.add("X-User-ID","admin");spoof.add("X-Role","ADMINISTRATOR");
        var denied=assertThrows(ResponseStatusException.class,()->controller.contributions(8,3,0,50,spoof,()->"member"));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());verifyNoInteractions(repository);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(scope).requireProject(eq(9L),any());
        assertThrows(ResponseStatusException.class,()->controller.contributions(9,3,0,50,spoof,()->"admin"));
        verifyNoInteractions(repository);
        var evidence=Map.<String,Object>of("candidateId",3L,"projectId",8L);
        when(repository.contributionEvidence(8,3,2,50)).thenReturn(evidence);
        assertSame(evidence,controller.contributions(8,3,2,50,HttpHeaders.EMPTY,()->"admin"));
        verify(repository).contributionEvidence(8,3,2,50);
    }
}
