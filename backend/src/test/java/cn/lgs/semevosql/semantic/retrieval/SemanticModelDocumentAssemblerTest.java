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
package cn.lgs.semevosql.semantic.retrieval;

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class SemanticModelDocumentAssemblerTest {
    private Model model(String code) { return Model.builder().modelCode(code).physicalTable(code).datasourceId(1)
        .businessName(code).status(SemanticAssetStatus.ENABLED).build(); }
    @Test void fullModelIncludesLateColumnsAndAllApplicableDefinitionsWithoutAdjacentModelPollution() {
        var order=model("orders");var other=model("customers");
        var columns=new ArrayList<Column>();
        for(int n=0;n<251;n++)columns.add(Column.builder().modelCode("orders").columnName("col_"+n)
            .businessName("属性"+n).synonyms("别名"+n).status(SemanticAssetStatus.ENABLED).build());
        var metric=Metric.builder().modelCode("orders").metricCode("paid").expression("SUM(col_250)")
            .timeColumn("col_249").unit("元").filterExpression("col_248='PAID'").status(SemanticAssetStatus.ENABLED).build();
        metric.setRetrieval(new RetrievalHints(List.of("已付款订单实际收了多少钱"),List.of("已确认的订单销售场景")));
        var snapshot=SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).models(List.of(order,other))
            .columns(columns).metrics(List.of(metric,Metric.builder().modelCode("customers").metricCode("private_other")
                .status(SemanticAssetStatus.ENABLED).build()))
            .grains(List.of(Grain.builder().modelCode("orders").grainCode("one_order").keyColumns("col_0")
                .status(SemanticAssetStatus.ENABLED).build()))
            .enumValues(List.of(EnumValue.builder().modelCode("orders").columnName("col_248").valueCode("PAID")
                .businessName("已付款").aliases("支付完成").status(SemanticAssetStatus.ENABLED).build()))
            .relationships(List.of(Relationship.builder().relationshipCode("customer_edge").sourceModelCode("orders")
                .targetModelCode("customers").cardinality(RelationshipCardinality.MANY_TO_ONE)
                .joinCondition("orders.col_0=customers.id").status(SemanticAssetStatus.ENABLED).build())).build();
        var assembler=new SemanticModelDocumentAssembler();var doc=assembler.assemble(snapshot,order,"hash");
        for(String expected:List.of("col_250","别名250","SUM(col_250)","PAID","支付完成","one_order","customer_edge","已付款订单实际收了多少钱","已确认的订单销售场景"))
            assertTrue(doc.semanticText().contains(expected),expected);
        assertFalse(doc.semanticText().contains("private_other"));
        assertEquals(SemanticRetrievalDocument.DocumentType.MODEL,doc.documentType());
        Collections.reverse(columns);order.setId(91L);order.setUpdateTime(java.time.LocalDateTime.now());
        var unchanged=assembler.assemble(snapshot,order,"different-version-hash");
        assertEquals(doc.contentHash(),unchanged.contentHash());assertEquals(doc.sourceFingerprint(),unchanged.sourceFingerprint());
        metric.setUnit("万元");assertNotEquals(doc.contentHash(),assembler.assemble(snapshot,order,"hash").contentHash());
        var after=assembler.assemble(snapshot,order,"hash");
        metric.setRetrieval(new RetrievalHints(List.of("本月实际收到的销售款"),List.of("已确认的订单销售场景")));
        assertNotEquals(after.contentHash(),assembler.assemble(snapshot,order,"hash").contentHash());
    }
    @Test void sharedDefinitionsAndDictionaryRolesAreScopedBeforeEncoding() throws Exception {
        var pack=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.parse(java.nio.file.Files.readString(java.nio.file.Path.of("../skills/semantic-catalog-init/examples/shared-catalog-1.2.json")));
        var source=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.parse(java.nio.file.Files.readString(java.nio.file.Path.of("../skills/semantic-catalog-init/examples/source-schema.json")));
        var catalog=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.convert(pack,source,List.of(cn.lgs.semevosql.project.domain.ProjectDatasourceBinding.builder()
            .datasourceId(1).domainCode("mall").exposedTables(List.of("t_order")).build()));
        catalog.setProjectId(1L);catalog.setProjectVersionId(2L);
        var model=catalog.getModels().stream().filter(m->m.getModelCode().equals("paid_order")).findFirst().orElseThrow();
        var assembler=new SemanticModelDocumentAssembler();var document=assembler.assemble(catalog,model,"hash");
        for(String included:List.of("sharedDefinitions","payment_amount","支付记录付款状态","已付订单到账金额","已付款","valueMappings"))assertTrue(document.semanticText().contains(included),included);
        assertFalse(document.semanticText().contains("订单付款状态"));assertFalse(document.semanticText().contains("订单实付分值"));
        catalog.getColumns().stream().filter(c->c.getModelCode().equals("paid_order") && c.getColumnName().equals("payment_state")).findFirst().orElseThrow().setAllowSendToLlm(false);
        var hidden=assembler.assemble(catalog,model,"hash");
        for(String forbidden:List.of("payment_state","state_concept","已付订单到账金额","已付款","支付记录付款状态"))assertFalse(hidden.semanticText().contains(forbidden),forbidden);
        var visibility=new SemanticCatalogVisibility(catalog);
        assertFalse(visibility.maySendMetric(catalog.getMetrics().stream().filter(m->m.getModelCode().equals("paid_order")).findFirst().orElseThrow()));
        assertTrue(visibility.maySendMetric(catalog.getMetrics().stream().filter(m->m.getModelCode().equals("order")).findFirst().orElseThrow()));
    }
    @Test void forbiddenColumnsAndDependentDefinitionsNeverEnterEitherRetrievalChannel() {
        var order=model("orders");
        var snapshot=SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).models(List.of(order))
            .columns(List.of(Column.builder().modelCode("orders").columnName("salary").businessName("保密工资")
                .retrievalJson(RetrievalHints.encode(new RetrievalHints(List.of("私密工资问法"),List.of())))
                .allowSendToLlm(false).status(SemanticAssetStatus.ENABLED).build()))
            .metrics(List.of(Metric.builder().modelCode("orders").metricCode("secret_payroll").expression("SUM(\"salary\")")
                .status(SemanticAssetStatus.ENABLED).build()))
            .dimensions(List.of(Dimension.builder().modelCode("orders").dimensionCode("secret_group").columnName("salary")
                .status(SemanticAssetStatus.ENABLED).build()))
            .enumValues(List.of(EnumValue.builder().modelCode("orders").columnName("salary").valueCode("SECRET")
                .status(SemanticAssetStatus.ENABLED).build())).build();
        var doc=new SemanticModelDocumentAssembler().assemble(snapshot,order,"hash");
        for(String forbidden:List.of("salary","保密工资","secret_payroll","secret_group","SECRET","私密工资问法")) {
            assertFalse(doc.semanticText().contains(forbidden));assertFalse(doc.lexicalText().contains(forbidden));
        }
    }
}
