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

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.domain.*;
import java.time.Duration;

class MetricMentionCoverageTest {
    SemanticCatalogSnapshot.Metric metric(String code,String name) {
        return SemanticCatalogSnapshot.Metric.builder().metricCode(code).businessName(name).modelCode("orders").build();
    }
    final List<SemanticCatalogSnapshot.Metric> metrics=List.of(metric("ordered","下单金额"),metric("paid","支付金额"));
    void validate(String query,Set<String> selected,String exclusions) throws Exception {
        MetricMentionCoverage.validate(query,metrics,selected,JsonUtil.getObjectMapper().readTree(exclusions));
    }
    @Test void observationTimeAndStatusDoNotJustifyReplacingAnExplicitlyNamedMetric() {
        assertThrows(IllegalArgumentException.class, () -> validate("按支付时间统计已支付订单的下单金额",Set.of("paid"),"[]"));
        assertDoesNotThrow(() -> validate("按支付时间统计已支付订单的下单金额",Set.of("ordered"),"[]"));
    }
    @Test void negationAndContextMustBeAccountedForWithoutSelectingBothMetrics() throws Exception {
        String query="不要支付金额，只要下单金额";
        validate(query,Set.of("ordered"),"[{\"businessName\":\"支付金额\",\"usage\":\"EXCLUDED\",\"evidence\":\"不要支付金额\"}]");
        assertThrows(IllegalArgumentException.class,() -> validate(query,Set.of("ordered"),"[]"));
        assertThrows(IllegalArgumentException.class,() -> validate(query,Set.of("ordered"),"[{\"businessName\":\"支付金额\",\"usage\":\"EXCLUDED\",\"evidence\":\"不查询支付金额\"}]"));
    }
    @Test void overlappingNamesAndSameNameAcrossModelsDoNotForceUnrelatedMetrics() {
        var candidates=List.of(metric("net","净收入"),metric("gross","收入"),metric("anotherNet","净收入"));
        var mentions=MetricMentionCoverage.mentions("净收入",candidates);
        assertEquals(1,mentions.size());assertEquals(Set.of("net","anotherNet"),mentions.get(0).metricCodes());
        assertDoesNotThrow(() -> MetricMentionCoverage.validate("净收入",candidates,Set.of("net"),JsonUtil.getObjectMapper().createArrayNode()));
    }
    @Test void sameNamePublicAndPrivateAlternativesRemainExplicitlyScopedAndOneOutputIsNotAnExclusion() throws Exception {
        var client=mock(SemanticDocumentExtractionClient.class);
        String publicResponse="{\"status\":\"RESOLVED\",\"metricCodes\":[\"public_amount\"],\"personalDefinitionIds\":[],\"confidence\":0.9}";
        var source=SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER_DEFAULT_CANDIDATE")
            .assetType("METRIC").assetKey("public_amount").sourceRecordId(8L).sourceRevision(1).principalId("alice")
            .representationCode("p_8_1").phrase("调整金额").definitionText("本人确认订单金额的一半。").build();
        var alternatives=List.of(metric("public_amount","调整金额"),metric("p_8_1","调整金额"));
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),alternatives,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(source));
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenReturn(new ModelCallResult("public",ModelCallPurpose.SEMANTIC_PLANNING,publicResponse,1,1));
        var planner=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client);
        String request="这次用项目公共口径的调整金额，不采用个人默认";
        var decision=planner.planDecision(request,candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED);
        var resolved=assertInstanceOf(SemanticPlanningOutcome.Resolved.class,decision.outcome());
        assertEquals(Set.of("public_amount"),resolved.binding().metricCodes());assertTrue(resolved.personalDefinitionIds().isEmpty());
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),prompts.capture(),any(Duration.class));
        var payload=JsonUtil.getObjectMapper().readTree(prompts.getValue());
        assertEquals("PUBLISHED_PROJECT",payload.path("metrics").get(0).path("definitionScope").asText());
        assertEquals("PERSONAL",payload.path("metrics").get(1).path("definitionScope").asText());
        assertEquals("USER_DEFAULT_CANDIDATE",payload.path("confirmedPersonalDefinitions").get(0).path("applicability").asText());
        assertThrows(IllegalArgumentException.class,()->MetricMentionCoverage.validate(request,alternatives,Set.of("public_amount"),
            JsonUtil.getObjectMapper().readTree("[{\"businessName\":\"调整金额\",\"usage\":\"EXCLUDED\",\"evidence\":\"项目公共口径的调整金额，不采用个人默认\"}]")));
        assertDoesNotThrow(()->MetricMentionCoverage.validate("调整金额",alternatives,Set.of("p_8_1"),JsonUtil.getObjectMapper().createArrayNode()));
    }
    @Test void synonymsAndEnglishTokenBoundariesRemainOrdinarySemanticBinding() throws Exception {
        validate("成交总额",Set.of("paid"),"[]");
        assertTrue(MetricMentionCoverage.mentions("unpaid users",List.of(metric("paid","paid"))).isEmpty());
        assertEquals(1,MetricMentionCoverage.mentions("Paid users",List.of(metric("paid","paid"))).size());
    }
    @Test void unaccountedNameUsesExistingBoundedPlannerRepairBeforeReturningAnyPlan() {
        var client=mock(SemanticDocumentExtractionClient.class);
        String wrong="{\"status\":\"RESOLVED\",\"metricCodes\":[\"paid\"],\"confidence\":0.9}";
        String corrected=wrong.replace("paid","ordered");
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenReturn(new ModelCallResult("wrong",ModelCallPurpose.SEMANTIC_PLANNING,wrong,1,1),
                new ModelCallResult("repaired",ModelCallPurpose.SEMANTIC_PLANNING,corrected,1,1));
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),metrics,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var decision=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client)
            .planDecision("按支付时间统计已支付订单的下单金额",candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),
                SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED);
        assertEquals(2,decision.modelCalls().size());
        var resolved=assertInstanceOf(SemanticPlanningOutcome.Resolved.class,decision.outcome());
        assertEquals(Set.of("ordered"),resolved.binding().metricCodes());
        verify(client).complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),contains("Unaccounted metric"),any(Duration.class));
    }

    @Test void supportingFormulaIsNotAnotherRequestedProjectionAndRepairKeepsThatBoundary() throws Exception {
        var client=mock(SemanticDocumentExtractionClient.class);
        String response="{\"status\":\"RESOLVED\",\"metricCodes\":[\"private_ratio\"],\"confidence\":0.9}";
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenReturn(new ModelCallResult("initial",ModelCallPurpose.SEMANTIC_PLANNING,response,1,1),
                new ModelCallResult("resolution-repair",ModelCallPurpose.SEMANTIC_PLANNING,response,1,1));
        var all=new ArrayList<>(metrics); all.add(metric("private_ratio","用户确认指标"));
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),all,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var planner=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client);
        String question="按订单时间统计用户确认指标";
        var input=new SemanticPlanningInput(question,question+"\n已确认：用户确认指标为下单金额的一半。支付金额不参与计算。");
        var decision=planner.planDecision(input,candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),
            SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertEquals(Set.of("private_ratio"),assertInstanceOf(SemanticPlanningOutcome.Resolved.class,decision.outcome()).binding().metricCodes());
        var repaired=planner.repairAfterResolutionFailure(input.confirmedRequest(),candidates,List.of(),QueryCaseHints.empty(),
            QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,"resolution fixture",decision.planningSession());
        assertInstanceOf(SemanticPlanningOutcome.Resolved.class,repaired.outcome());
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client,times(2)).complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),prompts.capture(),any(Duration.class));
        for(var prompt:prompts.getAllValues()) {
            var payload=JsonUtil.getObjectMapper().readTree(prompt.substring(0,prompt.indexOf("\n\n")<0?prompt.length():prompt.indexOf("\n\n")));
            assertEquals(question,payload.path("question").asText());
            assertEquals(input.confirmedRequest(),payload.path("confirmedRuntimeContext").asText());
            assertTrue(payload.path("namedMetricMentions").toString().contains("用户确认指标"));
            assertFalse(payload.path("namedMetricMentions").toString().contains("下单金额"));
            assertFalse(payload.path("namedMetricMentions").toString().contains("支付金额"));
        }
    }

    @Test void supportingContextCannotExcuseAnExplicitRequestedPublicMetric() {
        var client=mock(SemanticDocumentExtractionClient.class);
        String wrong="{\"status\":\"RESOLVED\",\"metricCodes\":[\"paid\"],\"confidence\":0.9}";
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenReturn(new ModelCallResult("wrong",ModelCallPurpose.SEMANTIC_PLANNING,wrong,1,1));
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),metrics,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var input=new SemanticPlanningInput("统计下单金额","统计下单金额\n个人习惯：一般使用支付金额。");
        var decision=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client)
            .planDecision(input,candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),
                SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertInstanceOf(SemanticPlanningOutcome.Rejected.class,decision.outcome());
    }

    @Test void exactConfirmedPersonalResultAccountsForItsNameButCannotHideAnotherPublicMetric() {
        var empty=JsonUtil.getObjectMapper().createArrayNode();
        assertDoesNotThrow(()->MetricMentionCoverage.validate("下单金额",metrics,Set.of("paid"),empty,Set.of("下单金额")));
        assertThrows(IllegalArgumentException.class,()->MetricMentionCoverage.validate("下单金额",metrics,Set.of("paid"),empty,Set.of("金额")));
        assertThrows(IllegalArgumentException.class,()->MetricMentionCoverage.validate("下单金额和支付金额",metrics,Set.of(),empty,Set.of("下单金额")));
    }
    @Test void textDefinitionSameAsPublicNameRequiresExactSelectedSourceAndActualRequestedResult() {
        var client=mock(SemanticDocumentExtractionClient.class);
        String response="""
            {"status":"RESOLVED","metricCodes":["paid"],"personalDefinitionIds":[7],
             "resultSelection":{"metricCodes":[],"personalDefinitionIds":[7]},"confidence":0.9}
            """;
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenAnswer(ignored->new ModelCallResult("personal",ModelCallPurpose.SEMANTIC_PLANNING,response,1,1));
        var source=SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER").assetType("TEXT_DEFINITION")
            .sourceRecordId(7L).sourceRevision(2).sourceContentHash("exact-source").phrase("下单金额")
            .definitionText("本人确认下单金额按订单支付金额的一半统计。").build();
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),metrics,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(source));
        var planner=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client);
        var decision=planner.planDecision("下单金额",candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED);
        var resolved=assertInstanceOf(SemanticPlanningOutcome.Resolved.class,decision.outcome());
        assertEquals(List.of(7L),resolved.personalDefinitionIds());assertEquals("p_7_2",resolved.resultContract().personalMeasures().get(0).outputCode());
        String unused=response.replace("\"metricCodes\":[],\"personalDefinitionIds\":[7]","\"metricCodes\":[\"paid\"],\"personalDefinitionIds\":[]");
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenAnswer(ignored->new ModelCallResult("unused",ModelCallPurpose.SEMANTIC_PLANNING,unused,1,1));
        assertInstanceOf(SemanticPlanningOutcome.Rejected.class,planner.planDecision("下单金额",candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED).outcome());
    }

    @Test void submittedQueryOnlyCalculationHasAnOutputIdentityWithoutSavingAPersonalDefinition() throws Exception {
        var client=mock(SemanticDocumentExtractionClient.class);
        String response="""
            {"status":"RESOLVED","metricCodes":["paid"],"personalDefinitionIds":[],
             "resultSelection":{"metricCodes":[],"personalDefinitionIds":[],
               "queryDefinitionIds":["1c67f9d2-c51e-4809-9891-7bf54a831fbb"]},"confidence":0.9}
            """;
        when(client.complete(eq(ModelCallPurpose.SEMANTIC_PLANNING),anyString(),anyString(),any(Duration.class)))
            .thenAnswer(ignored->new ModelCallResult("query-only",ModelCallPurpose.SEMANTIC_PLANNING,response,1,1));
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),List.of(),metrics,List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var receipt=new SemanticPlanningInput.DefinitionConfirmation("本次度量","本次度量是支付金额的一半。",
            "QUERY","1c67f9d2-c51e-4809-9891-7bf54a831fbb","alice");
        var input=new SemanticPlanningInput("查询本次度量","查询本次度量\n"+receipt.definitionText(),
            "查询本次度量",List.of(receipt));
        var decision=new SemanticBlueprintGenerationService(mock(SemanticCatalogRepository.class),client)
            .planDecision(input,candidates,List.of(),QueryCaseHints.empty(),QueryCaseHints.empty(),
                SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        var resolved=assertInstanceOf(SemanticPlanningOutcome.Resolved.class,decision.outcome());
        assertEquals(Set.of("paid"),resolved.binding().metricCodes());
        assertTrue(resolved.personalDefinitionIds().isEmpty());
        var frozen=JsonUtil.getObjectMapper().valueToTree(resolved.resultContract());
        assertEquals(receipt.definitionText(),frozen.path("queryMeasures").get(0).path("definitionText").asText());
        assertTrue(frozen.path("metricCodes").isEmpty());
    }

}
