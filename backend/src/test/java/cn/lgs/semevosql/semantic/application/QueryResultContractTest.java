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
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryResultContractTest {
    private static final String ID="1c67f9d2-c51e-4809-9891-7bf54a831fbb";
    private static final String OUTPUT="q_1c67f9d2c51e480998917bf54a831fbb_1";
    private SemanticPlanningInput.DefinitionConfirmation receipt(String scope,long revision,String text) {
        return new SemanticPlanningInput.DefinitionConfirmation("本次度量",text,scope,ID,"alice",revision);
    }
    private SemanticPlanningInput.DefinitionConfirmation receipt() {return receipt("QUERY",1,"本次度量为基准金额的一半，按月统计，保留两位小数。");}
    private SemanticCandidateSet candidates() {
        return new SemanticCandidateSet(1L,2L,"hash",Set.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    private SemanticResultContract select(String ids,List<SemanticPlanningInput.DefinitionConfirmation> receipts) throws Exception {
        var root=JsonUtil.getObjectMapper().readTree("{\"resultSelection\":{\"metricCodes\":[],\"personalDefinitionIds\":[],\"queryDefinitionIds\":"+ids+"}}");
        return SemanticResultContractResolver.parse(root,Set.of("base"),List.of(),candidates(),null,receipts);
    }
    private SemanticBlueprint plan() {
        return SemanticBlueprint.builder().executable(true)
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("base").aggregation("SUM").build()))
            .projections(List.of(SemanticBlueprint.ProjectionSelection.builder().alias("base").projectionType("METRIC").build(),
                SemanticBlueprint.ProjectionSelection.builder().alias("month").projectionType("TIME_BUCKET").build()))
            .groupBy(List.of(SemanticBlueprint.GroupSelection.builder().alias("month").build()))
            .expectedResult(SemanticBlueprint.ExpectedResultShape.builder().columns(List.of("base","month")).grain("month").build()).build();
    }
    @Test void aGroupedTemporaryOutputKeepsFullSourceAndHidesOnlyUnrequestedBases() throws Exception {
        var contract=select("[\""+ID+"\"]",List.of(receipt()));var plan=plan();
        SemanticResultContractResolver.apply(plan,contract,List.of(receipt()));
        assertThat(plan.getMetrics()).hasSize(1);
        assertThat(plan.getBindingDependencies()).isEmpty();
        assertThat(contract.personalMeasures()).isEmpty();
        assertThat(contract.queryMeasures().get(0).definitionText()).isEqualTo(receipt().definitionText());
        assertThat(contract.queryMeasures().get(0).sourceContentHash()).matches("[a-f0-9]{64}");
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("month",OUTPUT);
        assertThat(plan.getOutputMetrics()).extracting(SemanticBlueprint.MetricSelection::getMetricCode).containsExactly(OUTPUT);
        assertThat(plan.getCompilerMode()).isEqualTo("CONSTRAINED_GENERATION");
        var validator=new SqlResultValidator();
        var counts=ResultSetBO.builder().column(List.of("base","month")).data(List.of(Map.of("base","10","month","2026-01"))).build();
        assertThat(validator.validate(counts,plan,100).errors()).contains("Expected result column is missing: "+OUTPUT);
        var valid=ResultSetBO.builder().column(List.of(OUTPUT,"month")).data(List.of(Map.of(OUTPUT,"5.00","month","2026-01"))).build();
        assertThat(validator.validate(valid,plan,100).valid()).isTrue();
    }
    @Test void pendingCrossQuerySavedAndDuplicateSourcesCannotAuthorizeTemporaryResults() throws Exception {
        for(var receipts:List.of(List.<SemanticPlanningInput.DefinitionConfirmation>of(),
                List.of(receipt("USER",1,receipt().definitionText())),List.of(receipt("PROJECT",1,receipt().definitionText()))))
            assertThatThrownBy(()->select("[\""+ID+"\"]",receipts)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("submitted scope");
        assertThatThrownBy(()->select("[\""+ID+"\",\""+ID+"\"]",List.of(receipt())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique submitted");
        assertThatThrownBy(()->select("[\"2c67f9d2-c51e-4809-9891-7bf54a831fbb\"]",List.of(receipt())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("submitted scope");
    }
    @Test void laterTextRevisionOrMissingReceiptCannotReplaceAnApprovedMeaning() throws Exception {
        var contract=select("[\""+ID+"\"]",List.of(receipt()));
        for(var later:List.of(List.<SemanticPlanningInput.DefinitionConfirmation>of(),
                List.of(receipt("QUERY",2,receipt().definitionText())),List.of(receipt("QUERY",1,"改为其他计算"))))
            assertThatThrownBy(()->SemanticResultContractResolver.apply(plan(),contract,later))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact submitted frozen source");
    }
    @Test void aNonNumericClarificationDoesNotForceAnExtraMeasure() throws Exception {
        var root=JsonUtil.getObjectMapper().readTree("{\"resultSelection\":{\"metricCodes\":[\"base\"],\"personalDefinitionIds\":[]}}");
        var c=receipt("QUERY",1,"采用下单时间，只统计第一季度。");
        var contract=SemanticResultContractResolver.parse(root,Set.of("base"),List.of(),candidates(),null,List.of(c));
        var plan=plan();SemanticResultContractResolver.apply(plan,contract,List.of(c));
        assertThat(contract.queryMeasures()).isEmpty();
        assertThat(plan.getOutputMetrics()).extracting(SemanticBlueprint.MetricSelection::getMetricCode).containsExactly("base");
    }
    @Test void oldJsonContractRemainsReadableAndDoesNotGainTemporaryOutputs() throws Exception {
        var old=JsonUtil.getObjectMapper().readValue("{\"metricCodes\":[\"base\"],\"personalMeasures\":[]}",SemanticResultContract.class);
        assertThat(old).isEqualTo(new SemanticResultContract(Set.of("base"),List.of()));
        assertThat(old.queryMeasures()).isEmpty();
    }
    @Test void theSoleSubmittedResultRebindsTheValidatedScalarRatherThanDuplicatingIt() throws Exception {
        var contract=select("[\""+ID+"\"]",List.of(receipt()));var plan=scalarPlan();
        var hash=contract.queryMeasures().get(0).sourceContentHash();
        SemanticResultContractResolver.apply(plan,contract,List.of(receipt()));
        assertThat(plan.getExpectedResult().getColumns()).containsExactly(OUTPUT);
        assertThat(plan.getScalarCalculation()).isEqualTo(new ScalarCalculation(OUTPUT,"base","-","other",true));
        assertThat(plan.getMergePlan().getCalculationExpression()).isEqualTo(OUTPUT+"= ABS(base - other)");
        assertThat(plan.getProjections()).extracting(SemanticBlueprint.ProjectionSelection::getAlias).containsExactly(OUTPUT);
        assertThat(plan.getMetrics()).extracting(SemanticBlueprint.MetricSelection::getMetricCode).containsExactly("base","other");
        assertThat(plan.getResultContract()).isEqualTo(contract);
        assertThat(contract.queryMeasures().get(0).sourceContentHash()).isEqualTo(hash);
        var copy=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),SemanticBlueprint.class);
        assertThat(copy.getScalarCalculation()).isEqualTo(plan.getScalarCalculation());
    }
    @Test void multipleConfirmedOutputsCannotGuessWhichOneOwnsTheScalar() throws Exception {
        var first=select("[\""+ID+"\"]",List.of(receipt())).queryMeasures().get(0);
        var ambiguous=new SemanticResultContract(Set.of(),List.of(
            new SemanticResultContract.PersonalMeasure("p_7_1","另一个结果",7L,1,"hash")),List.of(first));
        var plan=scalarPlan();
        plan.setBindingDependencies(List.of(SemanticBlueprint.BindingDependency.builder().source("USER")
            .sourceRecordId(7L).sourceRevision(1).sourceContentHash("hash").phrase("另一个结果").build()));
        assertThatThrownBy(()->SemanticResultContractResolver.apply(plan,ambiguous,List.of(receipt())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiple confirmed results");
        assertThat(plan.getScalarCalculation().outputCode()).isEqualTo("difference");
        assertThat(plan.getResultContract()).isNull();
    }
    @Test void groupedMissingAndConflictingScalarMappingsAreRejectedBeforeMutatingThePlan() throws Exception {
        var contract=select("[\""+ID+"\"]",List.of(receipt()));
        var grouped=scalarPlan();grouped.setGroupBy(List.of(SemanticBlueprint.GroupSelection.builder().alias("month").build()));
        var missing=scalarPlan();missing.getExpectedResult().setColumns(List.of("base","other"));
        var conflicting=scalarPlan();conflicting.getMergePlan().setCalculationExpression("difference=base+other");
        var unauthorized=scalarPlan();unauthorized.setScalarCalculation(new ScalarCalculation("difference","base","-","secret",false));
        for(var plan:List.of(grouped,missing,conflicting,unauthorized)) {
            assertThatThrownBy(()->SemanticResultContractResolver.apply(plan,contract,List.of(receipt())))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(plan.getScalarCalculation().outputCode()).isEqualTo("difference");
            assertThat(plan.getResultContract()).isNull();
        }
    }
    private SemanticBlueprint scalarPlan() {
        return SemanticBlueprint.builder().metrics(List.of(
            SemanticBlueprint.MetricSelection.builder().metricCode("base").aggregation("SUM").build(),
            SemanticBlueprint.MetricSelection.builder().metricCode("other").aggregation("SUM").build()))
            .projections(List.of(SemanticBlueprint.ProjectionSelection.builder().alias("base").projectionType("METRIC").build(),
                SemanticBlueprint.ProjectionSelection.builder().alias("other").projectionType("METRIC").build(),
                SemanticBlueprint.ProjectionSelection.builder().alias("difference").projectionType("CALCULATION").build()))
            .scalarCalculation(ScalarCalculation.parse("difference=ABS(base-other)",Set.of("base","other")))
            .mergePlan(SemanticBlueprint.MergePlan.builder().calculationExpression("difference= ABS(base - other)").build())
            .expectedResult(SemanticBlueprint.ExpectedResultShape.builder().columns(List.of("base","other","difference")).grain("SCALAR").build()).build();
    }
}
