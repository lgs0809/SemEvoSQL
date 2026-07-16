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
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticResultContractTest {
    @Test void aSoleSavedUserResultRebindsItsExistingScalarAndRetainsTheExactSource() throws Exception {
        var contract=selection("{\"metricCodes\":[\"paid_amount\"],\"personalDefinitionIds\":[7]}");
        var plan=plan();plan.setScalarCalculation(ScalarCalculation.parse("difference=paid_amount-base_count",Set.of("base_count","paid_amount")));
        plan.getExpectedResult().setColumns(List.of("base_count","paid_amount","difference"));
        SemanticResultContractResolver.apply(plan,contract);
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("paid_amount","p_7_2");
        assertThat(plan.getScalarCalculation()).isEqualTo(new ScalarCalculation("p_7_2","paid_amount","-","base_count",false));
        assertThat(plan.getBindingDependencies()).containsExactly(ref());
        assertThat(plan.getResultContract()).isEqualTo(contract);
    }
    @Test void customMeasureKeepsAuthorizedDependenciesButHasItsOwnFrozenOutputContract() throws Exception {
        var contract=selection("{\"metricCodes\":[],\"personalDefinitionIds\":[7]}");
        var plan=plan();SemanticResultContractResolver.apply(plan,contract);
        assertThat(plan.getMetrics()).extracting(SemanticBlueprint.MetricSelection::getMetricCode).containsExactly("base_count","paid_amount");
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("p_7_2");
        assertThat(plan.getProjections().get(0).getExpression()).isNull();
        assertThat(plan.getCompilerMode()).isEqualTo("CONSTRAINED_GENERATION");
        assertThat(plan.getOutputMetrics()).extracting(SemanticBlueprint.MetricSelection::getBusinessName).containsExactly("自定义结果");
        assertThat(contract.personalMeasures().get(0).sourceContentHash()).isEqualTo("source-hash");
        var roundTrip=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),SemanticBlueprint.class);
        assertThat(roundTrip.getResultContract()).isEqualTo(contract);
    }

    @Test void resultValidationStillRejectsMissingOrInvalidCustomOutputsWithoutApplyingBaseCountConstraints() throws Exception {
        var plan=plan();SemanticResultContractResolver.apply(plan,selection("{\"metricCodes\":[],\"personalDefinitionIds\":[7]}"));
        var validator=new SqlResultValidator();
        assertThat(validator.validate(rows("p_7_2","0.5"),plan,100).valid()).isTrue();
        assertThat(validator.validate(rows("base_count","1"),plan,100).errors()).contains("Expected result column is missing: p_7_2");
        assertThat(validator.validate(rows("p_7_2","not-a-number"),plan,100).valid()).isFalse();
    }

    @Test void explicitlyRequestedPublicOutputRetainsItsPublishedRangeAlongsideDerivedResult() throws Exception {
        var plan=plan();plan.getMetrics().get(1).setNumericRange(new NumericValueRange(new java.math.BigDecimal("0"),new java.math.BigDecimal("100"),true,true));
        SemanticResultContractResolver.apply(plan,selection("{\"metricCodes\":[\"paid_amount\"],\"personalDefinitionIds\":[7]}"));
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("paid_amount","p_7_2");
        var result=ResultSetBO.builder().column(List.of("paid_amount","p_7_2"))
            .data(List.of(Map.of("paid_amount","101","p_7_2","0.5"))).build();
        assertThat(new SqlResultValidator().validate(result,plan,100).errors())
            .anyMatch(e->e.contains("published numeric range: paid_amount"));
    }

    @Test void resultSelectionPreservesAnAlreadyMaterializedScalarCalculationWhileHidingItsDependencies() throws Exception {
        var plan=plan();
        plan.getExpectedResult().setColumns(List.of("base_count","paid_amount","difference"));
        SemanticResultContractResolver.apply(plan,selection("{\"metricCodes\":[\"paid_amount\"],\"personalDefinitionIds\":[]}"));
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("paid_amount","difference");
        assertThat(plan.getProjections()).extracting(SemanticBlueprint.ProjectionSelection::getAlias).containsExactly("paid_amount");
        assertThat(plan.getMetrics()).extracting(SemanticBlueprint.MetricSelection::getMetricCode).containsExactly("base_count","paid_amount");
        var missing=ResultSetBO.builder().column(List.of("paid_amount"))
            .data(List.of(Map.of("paid_amount","1"))).build();
        assertThat(new SqlResultValidator().validate(missing,plan,100).errors())
            .contains("Expected result column is missing: difference");
        var complete=ResultSetBO.builder().column(List.of("paid_amount","difference"))
            .data(List.of(Map.of("paid_amount","1","difference","-2"))).build();
        assertThat(new SqlResultValidator().validate(complete,plan,100).valid()).isTrue();
    }

    @Test void aDerivedOnlyAnswerNeedsAValidatedCalculationAndStillEnforcesItsActualOutput() throws Exception {
        var root=JsonUtil.getObjectMapper().createObjectNode();
        root.set("resultSelection",JsonUtil.getObjectMapper().readTree("{\"metricCodes\":[],\"personalDefinitionIds\":[]}"));
        var bound=Set.of("base_count","paid_amount");
        var calculation=SemanticBlueprintGenerationService.validateResultComposition(
            "SCALAR","difference=paid_amount-base_count",bound);
        var contract=SemanticResultContractResolver.parse(root,bound,List.of(),candidates(),calculation);
        var plan=plan();plan.getExpectedResult().setColumns(List.of("base_count","paid_amount","difference"));
        SemanticResultContractResolver.apply(plan,contract);
        assertThat(plan.getExpectedResult().getColumns()).containsExactly("difference");
        assertThat(plan.getProjections()).isEmpty();
        assertThat(plan.getMetrics()).hasSize(2);
        assertThat(plan.getOutputMetrics()).isEmpty();
        assertThat(new SqlResultValidator().validate(rows("difference","-2"),plan,100).valid()).isTrue();
        assertThat(new SqlResultValidator().validate(rows("paid_amount","1"),plan,100).errors())
            .contains("Expected result column is missing: difference");
        assertThatThrownBy(()->SemanticResultContractResolver.parse(root,bound,List.of(),candidates()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requested measure");
        assertThatThrownBy(()->SemanticResultContractResolver.parse(root,bound,List.of(),candidates(),
            SemanticBlueprintGenerationService.validateResultComposition("SCALAR",null,bound)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requested measure");
    }

    @Test void unknownOrUnusedPrivateIdentityCannotAuthorizeAnOutputAndFrozenSourceMustMatch() throws Exception {
        for(String value:List.of("{\"metricCodes\":[],\"personalDefinitionIds\":[8]}",
                "{\"metricCodes\":[\"secret_amount\"],\"personalDefinitionIds\":[7]}",
                "{\"metricCodes\":[],\"personalDefinitionIds\":[7,7]}"))
            assertThatThrownBy(()->selection(value)).isInstanceOf(IllegalArgumentException.class);
        var contract=selection("{\"metricCodes\":[],\"personalDefinitionIds\":[7]}");
        var plan=plan();plan.getBindingDependencies().get(0).setSourceContentHash("changed-hash");
        assertThatThrownBy(()->SemanticResultContractResolver.apply(plan,contract)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact frozen source");
    }

    @Test void textOnlySelectionMustDeclareResultIdentityWhileOldPlansRemainCompatible() throws Exception {
        var empty=JsonUtil.getObjectMapper().createObjectNode();
        assertThatThrownBy(()->SemanticResultContractResolver.parse(empty,Set.of("base_count"),List.of(7L),candidates()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires resultSelection");
        assertThat(SemanticResultContractResolver.parse(empty,Set.of("base_count"),List.of(),candidates())).isNull();
        assertThat(plan().getOutputMetrics()).hasSize(2);
    }

    private static SemanticResultContract selection(String value) throws Exception {
        var root=JsonUtil.getObjectMapper().createObjectNode();root.set("resultSelection",JsonUtil.getObjectMapper().readTree(value));
        return SemanticResultContractResolver.parse(root,Set.of("base_count","paid_amount"),List.of(7L),candidates());
    }
    private static SemanticBlueprint.BindingDependency ref() {
        return SemanticBlueprint.BindingDependency.builder().source("USER").assetType("TEXT_DEFINITION")
            .sourceRecordId(7L).sourceRevision(2).sourceContentHash("source-hash").phrase("自定义结果")
            .definitionText("自定义结果是订单数的一半。").build();
    }
    private static SemanticCandidateSet candidates() {
        return new SemanticCandidateSet(1L,2L,"hash",Set.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(ref()));
    }
    private static SemanticBlueprint plan() {
        return SemanticBlueprint.builder().bindingDependencies(new ArrayList<>(List.of(ref())))
            .metrics(new ArrayList<>(List.of(SemanticBlueprint.MetricSelection.builder().metricCode("base_count").aggregation("COUNT").build(),
                SemanticBlueprint.MetricSelection.builder().metricCode("paid_amount").aggregation("SUM").build())))
            .projections(new ArrayList<>(List.of(SemanticBlueprint.ProjectionSelection.builder().alias("base_count").projectionType("METRIC").build(),
                SemanticBlueprint.ProjectionSelection.builder().alias("paid_amount").projectionType("METRIC").build())))
            .expectedResult(SemanticBlueprint.ExpectedResultShape.builder().columns(List.of("base_count","paid_amount")).maxRows(100).build()).build();
    }
    private static ResultSetBO rows(String column,String value) {
        return ResultSetBO.builder().column(List.of(column)).data(List.of(Map.of(column,value))).build();
    }
}
