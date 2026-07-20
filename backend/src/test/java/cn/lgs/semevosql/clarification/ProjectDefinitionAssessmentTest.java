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

import cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectDefinitionAssessmentTest {
    private SemanticCatalogSnapshot.Metric metric(String code,String name,String formula) {
        return SemanticCatalogSnapshot.Metric.builder().metricCode(code).modelCode("orders").businessName(name)
            .expression(formula).aggregation("EXPRESSION").timeColumn("created_at").unit("元").build();
    }
    @Test void nameCollisionCannotBeCalledANewPublicMeaning() {
        var answer=OfflineCatalogProtocol.parse("{\"relation\":\"NEW\",\"targets\":[],\"reason\":\"new metric\",\"differences\":[]}");
        var result=ProjectDefinitionAssessor.validatedAlignment(answer,metric("candidate","净收入","SUM(amount)/2"),
            List.of(metric("net","净收入","SUM(amount)")));
        assertEquals("CONFLICT",result.path("relation").asText());assertEquals("net",result.path("targets").get(0).asText());
        assertEquals("NEW",answer.path("relation").asText());
    }
    @Test void modelEquivalenceAlsoRequiresCalculationPopulationTimeAndUnitIdentity() {
        var answer=OfflineCatalogProtocol.parse("{\"relation\":\"EQUIVALENT\",\"targets\":[\"net\"],\"reason\":\"same calculation\",\"differences\":[]}");
        var candidate=metric("candidate","分摊额","SUM(amount) / 2");var existing=metric("net","费用分摊","sum(amount)/2");
        assertEquals("EQUIVALENT",ProjectDefinitionAssessor.validatedAlignment(answer,candidate,List.of(existing)).path("relation").asText());
        for(String dimension:List.of("formula","unit","time","population")) {
            var different=metric("net","费用分摊","sum(amount)/2");
            switch(dimension) {
                case "formula" -> different.setExpression("SUM(amount)*2");
                case "unit" -> different.setUnit("万元");
                case "time" -> different.setTimeColumn("paid_at");
                case "population" -> different.setFilterExpression("status='PAID'");
            }
            assertEquals("UNCERTAIN",ProjectDefinitionAssessor.validatedAlignment(answer,candidate,List.of(different)).path("relation").asText(),dimension);
        }
        assertThrows(IllegalArgumentException.class,()->ProjectDefinitionAssessor.validatedAlignment(answer,candidate,List.of()));
    }
    @Test void unavailableBackgroundModelPersistsFailureWithoutEscapingToInteractiveCaller() {
        var repository=mock(ProjectDefinitionAssessmentRepository.class);var contributions=mock(ProjectDefinitionContributions.class);
        var assessor=mock(ProjectDefinitionAssessor.class);
        var candidate=new ProjectDefinitionCandidateRepository.Candidate(1,2,1,"name","full text","hash",3,1,"name full text");
        var work=new ProjectDefinitionAssessmentRepository.Work(candidate,4,5,"catalog","lease",1);
        when(repository.claim(any())).thenReturn(Optional.of(work));
        when(contributions.totals(1,1,2)).thenReturn(new ProjectDefinitionContributions.Totals(1,0,0,"fingerprint"));
        when(assessor.assess(work)).thenThrow(new RuntimeException("provider secret must not be stored"));
        var worker=new ProjectDefinitionAssessmentWorker(repository,contributions,assessor,Runnable::run);
        assertTrue(worker.processOne());verify(repository).fail(work,"ASSESSMENT_UNAVAILABLE");verify(repository,never()).complete(any(),any(),any(),any(),any());
    }
    @Test void withdrawnSharingDoesNotInvokeTheModelOrDiscardPersonalDefinition() {
        var repository=mock(ProjectDefinitionAssessmentRepository.class);var contributions=mock(ProjectDefinitionContributions.class);
        var assessor=mock(ProjectDefinitionAssessor.class);
        var candidate=new ProjectDefinitionCandidateRepository.Candidate(1,2,1,"name","full text","hash",3,1,"name full text");
        var work=new ProjectDefinitionAssessmentRepository.Work(candidate,4,5,"catalog","lease",1);
        when(repository.claim(any())).thenReturn(Optional.of(work));
        var totals=new ProjectDefinitionContributions.Totals(0,0,0,"withdrawal");when(contributions.totals(1,1,2)).thenReturn(totals);
        assertTrue(new ProjectDefinitionAssessmentWorker(repository,contributions,assessor,Runnable::run).processOne());
        verifyNoInteractions(assessor);verify(repository).complete(eq(work),eq(totals),isNull(),any(),argThat(d->
            "SHARING_WITHDRAWN".equals(d.blockedReason())&&!d.automaticPublicationAllowed()));
    }
    @Test void governanceFactsRequireServerAdministratorAndProjectScope() {
        var security=new cn.lgs.semevosql.common.LocalSecurityProperties();security.setEnabled(true);
        for(String user:List.of("member","admin")) {
            var account=new cn.lgs.semevosql.common.LocalSecurityProperties.Account();account.setProjectIds(List.of(2L));
            account.setAdministrator(user.equals("admin"));security.getAccounts().put(user,account);
        }
        var scope=new cn.lgs.semevosql.project.application.ProjectScopeService(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new cn.lgs.semevosql.common.LocalOperatorService(security));
        var repository=mock(ProjectDefinitionAssessmentRepository.class);
        var controller=new ProjectDefinitionGovernanceController(repository,scope,new cn.lgs.semevosql.common.OperatorContext.Resolver(
            new cn.lgs.semevosql.common.OperatorContextProperties(),security),mock(ProjectDefinitionDecisionService.class));
        var spoofed=new org.springframework.http.HttpHeaders();spoofed.set("X-User-ID","admin");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.list(2,spoofed,()->"member"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.list(3,spoofed,()->"admin"));
        verifyNoInteractions(repository);
        when(repository.list(2)).thenReturn(List.of(Map.of("id",1,"threshold_reached",false)));
        assertEquals(1,controller.list(2,spoofed,()->"admin").size());
        assertTrue(cn.lgs.semevosql.model.ModelCallPurpose.PROJECT_DEFINITION_ALIGNMENT.background());
        assertFalse(cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING.background());
    }
}
