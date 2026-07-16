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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticDefinitionProposalTest {
    private static final String DEFINITION="可支配金额是所有状态订单金额合计减去五元，按下单时间统计，不扣退款。";
    private static final String INTENT="以后记住这一口径";
    private static final String MESSAGE="查询一月可支配金额。"+DEFINITION+INTENT;

    @Test void completeUserMeaningUsesExistingConfirmationWithoutSavingOrModelRewriting() throws Exception {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var question=(SemanticPlanningOutcome.ClarificationRequired)SemanticDefinitionProposal.confirmation(input,proposal(DEFINITION,INTENT),candidates(List.of()));
        assertThat(question.rawExpression()).isEqualTo("可支配金额");
        assertThat(question.options()).extracting(SemanticPlanningOutcome.Option::code).containsExactly("CONFIRM_DEFINITION","OTHER","CANCEL");
        assertThat(question.options().get(0).label()).isEqualTo(DEFINITION);
        assertThat(question.options().get(0).assetType()).isEqualTo("TEXT_DEFINITION");
        assertThat(question.options().get(0).assetKey()).isNull();
    }

    @Test void modelInventedFormulaOrHistoricalSaveInstructionCannotBecomeUserEvidence() throws Exception {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var invented=proposal(DEFINITION.replace("五元","六元"),INTENT);
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,invented,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verbatim current-message");
        var history=proposal(DEFINITION,"请允许项目成员使用");
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,history,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verbatim current-message");
    }

    @Test void previouslyConfirmedQueryOnlyMeaningDoesNotAskAgainOnResume() throws Exception {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of(new SemanticPlanningInput.DefinitionConfirmation("可支配金额",DEFINITION,"QUERY","submitted-question","owner")));
        assertThat(SemanticDefinitionProposal.confirmation(input,proposal(DEFINITION,INTENT),candidates(List.of()))).isNull();
    }

    @Test void anExplicitNewSaveIntentRequiresConfirmationEvenWhenAnOldMeaningHasTheSameFormula() throws Exception {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var own=SemanticBlueprint.BindingDependency.builder().source("USER").phrase("可支配金额").definitionText(DEFINITION).build();
        assertThat(SemanticDefinitionProposal.confirmation(input,proposal(DEFINITION,INTENT),candidates(List.of(own))))
            .isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
        own.setDefinitionText(DEFINITION.replace("五元","三元"));
        assertThat(SemanticDefinitionProposal.confirmation(input,proposal(DEFINITION,INTENT),candidates(List.of(own))))
            .isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
    }

    @Test void anotherTodoMeasureAndPublicWriteFieldsAreRejected() throws Exception {
        var another=new SemanticPlanningInput("统计订单数量","统计订单数量",MESSAGE,List.of());
        var value=proposal(DEFINITION,INTENT);
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(another,value,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requested in this question");
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("definitionProposal")).put("publish",true);
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,value,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
    }

    @Test void ordinaryOneOffQueryHasNoDefinitionProposal() throws Exception {
        var response=JsonUtil.getObjectMapper().readTree("{\"status\":\"RESOLVED\",\"definitionProposal\":null}");
        assertThat(SemanticDefinitionProposal.confirmation(SemanticPlanningInput.plain("统计订单数"),response,candidates(List.of()))).isNull();
    }

    @Test void sourceAnchorsCopyEveryInterveningQualifierWithoutModelParaphrasing() {
        String definition="可支配金额是订单金额合计，包含所有状态；金额单位为元。按下单时间统计，不扣退款。";
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",
            "📊查询一月可支配金额。"+definition+INTENT,List.of());
        var outcome=(SemanticPlanningOutcome.ClarificationRequired)SemanticDefinitionProposal.confirmation(
            input,span("可支配金额是订单金额合计","不扣退款。"),candidates(List.of()));
        assertThat(outcome.options().get(0).label()).isEqualTo(definition);
        assertThat(outcome.reason()).contains("原话");
    }

    @Test void anchorsCannotBorrowHistoryRewritePunctuationOrSelectAnAmbiguousOccurrence() {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,
            span("可支配金额是所有状态订单金额合计","不扣退款!"),candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verbatim current-message");
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,
            span("可支配金额","不扣退款。"),candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }

    @Test void reversedSourceAnchorsAndInventedSourceFieldsAreRejected() {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,
            span("不扣退款。","可支配金额是所有状态订单金额合计"),candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ordered excerpt");
        var response=span("可支配金额是所有状态订单金额合计","不扣退款。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("definitionProposal").path("definitionSpan"))
            .put("publish",true);
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,response,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported definition span");
    }

    @Test void conflictingFullTextAndAnchorsCannotSelectDifferentMeanings() {
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var response=span("可支配金额是所有状态订单金额合计","不扣退款。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("definitionProposal")).put("definitionText",DEFINITION);
        assertThatThrownBy(()->SemanticDefinitionProposal.confirmation(input,response,candidates(List.of())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    private static com.fasterxml.jackson.databind.JsonNode span(String start,String end) {
        var root=JsonUtil.getObjectMapper().createObjectNode().put("status","NEEDS_CLARIFICATION");
        root.putObject("definitionProposal").put("rawExpression","可支配金额").put("intentExcerpt",INTENT)
            .putObject("definitionSpan").put("startQuote",start).put("endQuote",end);
        return root;
    }


    @Test void existingPlannerCannotExecuteACompleteDefinitionProposalEvenIfModelClaimsResolved() {
        var client=org.mockito.Mockito.mock(SemanticDocumentExtractionClient.class);
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)proposal(DEFINITION,INTENT);
        response.put("status","RESOLVED");
        var call=new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("contract-call",
            cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING,response.toString(),1,1);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(call);
        var planner=new SemanticBlueprintGenerationService(org.mockito.Mockito.mock(
            cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.class),client);
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var decision=planner.planDecision(input,candidates(List.of()),List.of(),cn.lgs.semevosql.learning.QueryCaseHints.empty(),
            cn.lgs.semevosql.learning.QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertThat(decision.outcome()).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
        assertThat(decision.modelCalls()).hasSize(1);
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            prompt.capture(),org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        assertThat(prompt.getValue()).contains("currentUserMessage",MESSAGE,"definitionConfirmationReceipts");
    }

    @Test void inventedDefinitionIsRepairedInsideTheExistingBoundedPlannerSession() {
        var client=org.mockito.Mockito.mock(SemanticDocumentExtractionClient.class);
        var purpose=cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING;
        var first=new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("first",purpose,
            proposal(DEFINITION.replace("五元","六元"),INTENT).toString(),1,1);
        var repaired=new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("repair",purpose,
            proposal(DEFINITION,INTENT).toString(),1,1);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(first,repaired);
        var planner=new SemanticBlueprintGenerationService(org.mockito.Mockito.mock(
            cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.class),client);
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of());
        var decision=planner.planDecision(input,candidates(List.of()),List.of(),cn.lgs.semevosql.learning.QueryCaseHints.empty(),
            cn.lgs.semevosql.learning.QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertThat(decision.outcome()).isInstanceOf(SemanticPlanningOutcome.ClarificationRequired.class);
        assertThat(((SemanticPlanningOutcome.ClarificationRequired)decision.outcome()).options().get(0).label()).isEqualTo(DEFINITION);
        assertThat(decision.modelCalls()).hasSize(2);
    }

    @Test void repeatedConfirmedProposalFailsClosedWithoutCreatingAnotherHumanQuestion() {
        var client=org.mockito.Mockito.mock(SemanticDocumentExtractionClient.class);
        var call=new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("repeat",
            cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING,proposal(DEFINITION,INTENT).toString(),1,1);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(call);
        var planner=new SemanticBlueprintGenerationService(org.mockito.Mockito.mock(
            cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.class),client);
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of(new SemanticPlanningInput.DefinitionConfirmation("可支配金额",DEFINITION,"QUERY","submitted-question","owner")));
        var decision=planner.planDecision(input,candidates(List.of()),List.of(),cn.lgs.semevosql.learning.QueryCaseHints.empty(),
            cn.lgs.semevosql.learning.QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertThat(decision.outcome()).isInstanceOf(SemanticPlanningOutcome.Rejected.class);
        assertThat(decision.modelCalls()).hasSize(2);
    }


    @Test void rephrasedSaveQuestionCannotReopenAnActualSubmittedConfirmation() {
        var client=org.mockito.Mockito.mock(SemanticDocumentExtractionClient.class);
        String response="""
            {"status":"NEEDS_CLARIFICATION","clarification":{"issueType":"METRIC_MISSING",
              "question":"是否保存这个指标？","rawExpression":"可支配金额","options":[]}}
            """;
        var call=new cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult("repeat-generic",
            cn.lgs.semevosql.model.ModelCallPurpose.SEMANTIC_PLANNING,response,1,1);
        org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(call);
        var planner=new SemanticBlueprintGenerationService(org.mockito.Mockito.mock(
            cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.class),client);
        var receipt=new SemanticPlanningInput.DefinitionConfirmation("可支配金额",DEFINITION,"USER","actual-submitted-question","owner");
        var input=new SemanticPlanningInput("查询一月可支配金额","查询一月可支配金额",MESSAGE,List.of(receipt));
        var decision=planner.planDecision(input,candidates(List.of()),List.of(),cn.lgs.semevosql.learning.QueryCaseHints.empty(),
            cn.lgs.semevosql.learning.QueryCaseHints.empty(),SemanticBlueprintGenerationService.PlannerProfile.CONFIGURED,null,"");
        assertThat(decision.outcome()).isInstanceOf(SemanticPlanningOutcome.Rejected.class);
        assertThat(decision.modelCalls()).hasSize(2);
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            prompt.capture(),org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        assertThat(prompt.getAllValues().get(0)).contains("definitionConfirmationReceipts","actual-submitted-question","USER");
    }

    private static com.fasterxml.jackson.databind.JsonNode proposal(String definition,String intent) {
        var root=JsonUtil.getObjectMapper().createObjectNode().put("status","NEEDS_CLARIFICATION");
        root.putObject("definitionProposal").put("rawExpression","可支配金额").put("definitionText",definition).put("intentExcerpt",intent);
        return root;
    }
    private static SemanticCandidateSet candidates(List<SemanticBlueprint.BindingDependency> definitions) {
        return new SemanticCandidateSet(1L,2L,"hash",Set.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),definitions);
    }
}
