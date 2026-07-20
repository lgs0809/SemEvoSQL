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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalResultExplanationTest {
    @Test void confirmedScalarUsesTheUsersFrozenBusinessNameInsteadOfItsInternalFormula() {
        var result=new cn.lgs.semevosql.semantic.domain.SemanticResultContract.PersonalMeasure("p_7_2","确认结果",7L,2,"hash");
        var plan=SemanticBlueprint.builder().resultContract(new cn.lgs.semevosql.semantic.domain.SemanticResultContract(java.util.Set.of(),List.of(result)))
            .scalarCalculation(new cn.lgs.semevosql.semantic.domain.ScalarCalculation("p_7_2","input_a","-","input_b",false)).build();
        assertEquals("确认结果",new QueryExecutionExplanationService(null,null)
            .explainTask(plan,"查询确认结果",List.of("p_7_2")).resultColumns().get(0).get("label"));
    }
    @Test void derivedOutputNamesComeFromTheFrozenCalculationAndItsMetricNames() {
        var plan=SemanticBlueprint.builder().metrics(List.of(
            SemanticBlueprint.MetricSelection.builder().metricCode("input_a").businessName("一类金额").build(),
            SemanticBlueprint.MetricSelection.builder().metricCode("input_b").businessName("另一类金额").build()))
            .scalarCalculation(cn.lgs.semevosql.semantic.domain.ScalarCalculation.parse(
                "answer=ABS(input_a-input_b)",java.util.Set.of("input_a","input_b"))).build();
        var explanation=new QueryExecutionExplanationService(null,null).explainTask(plan,"比较两类金额",List.of("answer"));
        assertEquals("绝对值（一类金额 - 另一类金额）",explanation.resultColumns().get(0).get("label"));
        assertEquals("结果字段1",new QueryExecutionExplanationService(null,null)
            .explainTask(plan,"比较两类金额",List.of("unbound_output")).resultColumns().get(0).get("label"));
    }
    @Test void textCalculationUsesConfirmedMeaningAndStructuredOutputUsesFrozenMetricName() {
        var definition=SemanticBlueprint.BindingDependency.builder().source("USER").sourceRevision(2).assetType("TEXT_DEFINITION")
            .phrase("结算贡献额").definitionText("已完成收入减去退款和费用。单位元。按结算日归属。").build();
        var metric=SemanticBlueprint.MetricSelection.builder().metricCode("base_income").businessName("订单收入").build();
        var plan=SemanticBlueprint.builder().metrics(List.of(metric)).bindingDependencies(List.of(definition)).build();
        var explanation=new QueryExecutionExplanationService(null,null).explainTask(plan,"统计结算贡献额",List.of("base_income"));
        assertEquals("结算贡献额",explanation.resultColumns().get(0).get("label"));
        assertTrue(explanation.businessDefinitions().stream().anyMatch(d->definition.getDefinitionText().equals(d.get("definition"))));
        metric.setMetricCode("p_44_2");metric.setBusinessName("结算贡献额");definition.setRepresentationCode("p_44_2");
        assertEquals("结算贡献额",new QueryExecutionExplanationService(null,null).explainTask(plan,"统计结算贡献额",List.of("p_44_2")).resultColumns().get(0).get("label"));
    }
}
