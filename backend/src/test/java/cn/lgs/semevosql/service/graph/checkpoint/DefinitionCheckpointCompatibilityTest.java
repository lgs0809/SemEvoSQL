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
package cn.lgs.semevosql.service.graph.checkpoint;

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionCheckpointCompatibilityTest {
    private final DurableGraphStateSerializer serializer=new DurableGraphStateSerializer();
    private final SemanticBlueprint.MetricSelection metric=SemanticBlueprint.MetricSelection.builder()
        .metricCode("legacy_amount").modelCode("orders").expression("SUM(amount)").aggregation("EXPRESSION")
        .filterExpression("status='CANCELLED'").timeColumn("ordered_at").unit("元").build();
    private final SemanticBlueprint.DimensionSelection dimension=SemanticBlueprint.DimensionSelection.builder()
        .dimensionCode("legacy_region").modelCode("customers").columnName("region").build();

    byte[] oldShape(boolean malformed,boolean unknownField) throws Exception {
        byte[] bytes=serializer.dataToBytes(Map.of("metric",metric,"dimension",dimension));
        ObjectNode root;
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){
            root=(ObjectNode)JsonUtil.getObjectMapper().readTree(input.readNBytes(input.readInt()));
        }
        var metricFields=(ObjectNode)root.path("state").path("fields").path("metric").path("fields");
        metricFields.remove("definitionBinding");metricFields.remove("numericRange");
        ((ObjectNode)root.path("state").path("fields").path("dimension").path("fields")).remove("definitionBinding");
        if(malformed)metricFields.remove("expression");
        if(unknownField)metricFields.put("new_unapproved_fact","must fail");
        var output=new ByteArrayOutputStream();
        try(var stream=new ObjectOutputStream(output)){
            var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);
        }
        return output.toByteArray();
    }
    @Test void previousSelectionShapesRetainExactMeaningWithoutRebindingOrInventingBounds() throws Exception {
        var restored=serializer.dataFromBytes(oldShape(false,false));
        assertEquals(metric,restored.get("metric"));assertEquals(dimension,restored.get("dimension"));
        var actual=(SemanticBlueprint.MetricSelection)restored.get("metric");
        assertNull(actual.getDefinitionBinding());assertNull(actual.getNumericRange());
        assertEquals("SUM(amount)",actual.getExpression());assertEquals("status='CANCELLED'",actual.getFilterExpression());
    }
    @Test void explicitAdditiveCompatibilityDoesNotAcceptOtherMissingOrUnknownFields() throws Exception {
        assertThrows(IOException.class,()->serializer.dataFromBytes(oldShape(true,false)));
        assertThrows(IOException.class,()->serializer.dataFromBytes(oldShape(false,true)));
    }
    @Test void privateDefinitionProvenanceRoundTripsAndOldDependenciesRemainUnknown() throws Exception {
        var old=SemanticBlueprint.BindingDependency.builder().phrase("金额").assetType("METRIC").assetKey("total").source("USER")
            .scope("USER").principalId("owner").sourceRecordId(9L).build();
        var dependency=SemanticBlueprint.BindingDependency.builder().phrase("贡献额").assetType("TEXT_DEFINITION").assetKey("贡献额").source("USER")
            .scope("USER").principalId("owner").sourceRecordId(10L).sourceRevision(3).sourceContentHash("sha256:meaning")
            .dependencyFingerprint("sha256:dependencies").definitionText("已确认的完整定义")
            .representationCode("p_10_3").representationHash("sha256:frozen-structure").build();
        assertEquals(dependency,serializer.dataFromBytes(serializer.dataToBytes(Map.of("definition",dependency))).get("definition"));
        byte[] bytes=serializer.dataToBytes(Map.of("old",old));ObjectNode root;
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){root=(ObjectNode)JsonUtil.getObjectMapper().readTree(input.readNBytes(input.readInt()));}
        ((ObjectNode)root.path("state").path("fields").path("old").path("fields")).remove(List.of("sourceRevision","sourceContentHash","dependencyFingerprint","definitionText","representationCode","representationHash"));
        var output=new ByteArrayOutputStream();try(var stream=new ObjectOutputStream(output)){var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);}
        var restored=(SemanticBlueprint.BindingDependency)serializer.dataFromBytes(output.toByteArray()).get("old");
        assertEquals(old,restored);assertNull(restored.getSourceRevision());assertNull(restored.getDefinitionText());
    }

    @Test void referenceRevisionAliasesDictionaryAndScaleBoundsSurviveTypedRoundTrip() throws Exception {
        var binding=new SemanticDefinitionBinding("amount",3,"orders","cancelled_amount","取消金额",List.of("取消订单总额"),"states",2);
        metric.setDefinitionBinding(binding);metric.setNumericRange(new NumericValueRange(BigDecimal.ZERO,new BigDecimal("100.00"),true,false));
        var plan=SemanticBlueprint.builder().projectId(1L).projectVersionId(5L).metrics(List.of(metric)).dimensions(List.of(dimension)).build();
        assertEquals(plan,serializer.dataFromBytes(serializer.dataToBytes(Map.of("plan",plan))).get("plan"));
    }

    @Test void requestedPersonalOutputRoundTripsAndLegacyPlanRetainsItsOriginalOutputs() throws Exception {
        var contract=new SemanticResultContract(Set.of(),List.of(new SemanticResultContract.PersonalMeasure(
            "p_7_2","资源测算金额",7L,2,"sha256:confirmed-source")));
        var plan=SemanticBlueprint.builder().metrics(List.of(metric)).resultContract(contract).build();
        assertEquals(plan,serializer.dataFromBytes(serializer.dataToBytes(Map.of("plan",plan))).get("plan"));
        byte[] bytes=serializer.dataToBytes(Map.of("plan",plan));ObjectNode root;
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){root=(ObjectNode)JsonUtil.getObjectMapper().readTree(input.readNBytes(input.readInt()));}
        var fields=(ObjectNode)root.path("state").path("fields").path("plan").path("fields");
        fields.remove(List.of("resultContract","scalarCalculation"));
        var output=new ByteArrayOutputStream();
        try(var stream=new ObjectOutputStream(output)){var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);}
        var restored=(SemanticBlueprint)serializer.dataFromBytes(output.toByteArray()).get("plan");
        assertNull(restored.getResultContract());assertEquals(List.of(metric),restored.getOutputMetrics());
        assertNull(restored.getScalarCalculation());
        assertEquals("SUM(amount)",restored.getMetrics().get(0).getExpression());
    }

    @Test void frozenScalarCalculationSurvivesTheClosedFrameworkCheckpointCodec() throws Exception {
        var calculation=ScalarCalculation.parse("difference=ABS(first_total-second_total)",Set.of("first_total","second_total"));
        var plan=SemanticBlueprint.builder().scalarCalculation(calculation).build();
        var restored=(SemanticBlueprint)serializer.dataFromBytes(serializer.dataToBytes(Map.of("plan",plan))).get("plan");
        assertEquals(calculation,restored.getScalarCalculation());
        assertEquals(plan,restored);
    }

    @Test void enumsDeclaredByTrustedPlanFieldsKeepTheirExactTypeAndValue() throws Exception {
        for(var cardinality:RelationshipCardinality.values()){
            var relationship=SemanticBlueprint.RelationshipSelection.builder().relationshipCode("order_refunds")
                .sourceModelCode("orders").targetModelCode("refunds").cardinality(cardinality)
                .joinType("LEFT").joinCondition("orders.order_id=refunds.order_id").build();
            var plan=SemanticBlueprint.builder().projectId(3L).projectVersionId(11L)
                .relationships(List.of(relationship)).compilerMode("CONSTRAINED_GENERATION").build();
            var restored=(SemanticBlueprint)serializer.dataFromBytes(serializer.dataToBytes(Map.of("plan",plan))).get("plan");
            assertEquals(plan,restored);
            assertSame(cardinality,restored.getRelationships().get(0).getCardinality());
        }
    }

    @Test void registeringTrustedFieldEnumsDoesNotAuthorizeUnrelatedPersistedEnumNames() throws Exception {
        assertThrows(IOException.class,()->serializer.dataToBytes(Map.of("unrelated",Thread.State.NEW)));
        var root=JsonUtil.getObjectMapper().createObjectNode();root.put("schemaVersion",1);
        var state=root.putObject("state");state.put("type","map");
        var bad=state.putObject("fields").putObject("unrelated");
        bad.put("type",Thread.State.class.getName());bad.put("value","NEW");
        var output=new ByteArrayOutputStream();
        try(var stream=new ObjectOutputStream(output)){
            var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);
        }
        assertThrows(IOException.class,()->serializer.dataFromBytes(output.toByteArray()));
    }
    @Test void queryOnlyOutputsFreezeCompleteMeaningAndReadLegacyContractsWithoutNewFields() throws Exception {
        var query=new SemanticResultContract.QueryMeasure("q_1c67f9d2c51e480998917bf54a831fbb_1","临时测算",
            "1c67f9d2-c51e-4809-9891-7bf54a831fbb",1L,"完整口径与时间归属。","source-hash");
        var contract=new SemanticResultContract(Set.of(),List.of(),List.of(query));
        var plan=SemanticBlueprint.builder().metrics(List.of(metric)).resultContract(contract).build();
        assertEquals(plan,serializer.dataFromBytes(serializer.dataToBytes(Map.of("plan",plan))).get("plan"));
        var old=new SemanticResultContract(Set.of(metric.getMetricCode()),List.of());
        var bytes=serializer.dataToBytes(Map.of("contract",old));ObjectNode root;
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){root=(ObjectNode)JsonUtil.getObjectMapper().readTree(input.readNBytes(input.readInt()));}
        ((ObjectNode)root.path("state").path("fields").path("contract").path("fields")).remove("queryMeasures");
        var output=new ByteArrayOutputStream();
        try(var stream=new ObjectOutputStream(output)){var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);}
        assertEquals(old,serializer.dataFromBytes(output.toByteArray()).get("contract"));
    }

}
