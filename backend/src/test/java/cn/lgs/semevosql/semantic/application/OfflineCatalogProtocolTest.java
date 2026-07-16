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

import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.semantic.compiler.*;
import cn.lgs.semevosql.util.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The actual frozen Skill sample is also accepted by the Java importer; malformed semantics fail closed. */
class OfflineCatalogProtocolTest {
    static ObjectNode sample() throws Exception {return (ObjectNode)OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/minimal-catalog.json")));}
    static ObjectNode source() throws Exception {return (ObjectNode)OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/source-schema.json")));}
    static ObjectNode retrievalSample() throws Exception {return (ObjectNode)OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/retrieval-catalog-1.1.json")));}
    static ObjectNode sharedSample() throws Exception {return (ObjectNode)OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/shared-catalog-1.2.json")));}
    static List<ProjectDatasourceBinding> bindings(){return List.of(ProjectDatasourceBinding.builder().datasourceId(17).domainCode("mall").exposedTables(List.of("t_order")).build());}
    static ObjectNode first(ObjectNode pack,String group){return (ObjectNode)pack.path("catalog").path(group).get(0);}
    @Test void publishedNativeTypesAdaptAtThePrivateASTBoundaryWithoutMutatingCatalogs() {
        for(var type:List.of("DECIMAL","NUMERIC(14,2)","DOUBLE PRECISION","decimal"))assertEquals("decimal",SourceSchemaExportService.protocolType(type));
        for(var type:List.of("BIGINT","integer","INT4"))assertEquals("integer",SourceSchemaExportService.protocolType(type));
        for(var type:List.of("TIMESTAMP","timestamp(6) with time zone","datetime"))assertEquals("datetime",SourceSchemaExportService.protocolType(type));
        assertEquals("string",SourceSchemaExportService.protocolType("TEXT"));assertEquals("unsupported",SourceSchemaExportService.protocolType("JSONB"));
    }
    @Test void sharedSamplePreservesMappedIdentityUnitFilterAndDecimalDivision() throws Exception {
        var sample=sample();var source=source();assertEquals(sample.path("sourceSchemaFingerprint").asText(),OfflineCatalogProtocol.hash(source));
        var converted=OfflineCatalogProtocol.convert(sample,source,bindings());
        assertEquals("shop.t_order",converted.getModels().get(0).getPhysicalTable());
        assertEquals("order_id",converted.getColumns().get(0).getColumnName());
        assertEquals("paid_at",converted.getMetrics().get(0).getTimeColumn());assertEquals("status = 1",converted.getMetrics().get(0).getFilterExpression());
        assertEquals("(SUM(paid_amount) * 1.0 / NULLIF(100, 0))",converted.getMetrics().get(0).getExpression());
        assertEquals("\u5143",converted.getMetrics().get(0).getUnit());
        String sql=GovernedModelSourceRenderer.relation(converted.getModels().get(0),SqlDialect.POSTGRESQL);
        assertTrue(sql.contains("\"o\".\"amt\" AS \"paid_amount\""));assertTrue(sql.contains("\"shop\".\"t_order\""));
    }
    @Test void importerAndExistingMcpSdkCanInitializeTheSameSchemaLibrary() {
        var validator=new io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier().get();
        assertNotNull(validator);
        assertDoesNotThrow(()->validator.validate(Map.of("type","object","required",List.of("customer")),Map.of("customer","synthetic")));
    }
    @Test void versionedRetrievalHintsAndExplicitRangeShareTheOfflineContract() throws Exception {
        var converted=OfflineCatalogProtocol.convert(retrievalSample(),source(),bindings());
        assertEquals(List.of("最近付款的订单有哪些"),converted.getModels().get(0).getRetrieval().queryExpressions());
        assertEquals(List.of("实际支付金额"),converted.getColumns().get(1).getRetrieval().queryExpressions());
        var metric=converted.getMetrics().get(0);
        assertEquals(List.of("实际收到多少钱"),metric.getRetrieval().queryExpressions());
        assertTrue(metric.numericRange().contains(java.math.BigDecimal.ZERO));
        assertFalse(metric.numericRange().contains(java.math.BigDecimal.valueOf(-1)));
        String json=JsonUtil.getObjectMapper().writeValueAsString(converted);
        assertFalse(json.contains("retrievalJson"));assertTrue(json.contains("queryContexts"));
        var restored=JsonUtil.getObjectMapper().readValue(json,cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.class);
        assertEquals(metric.getRetrieval(),restored.getMetrics().get(0).getRetrieval());
        String legacy=JsonUtil.getObjectMapper().writeValueAsString(OfflineCatalogProtocol.convert(sample(),source(),bindings()));
        assertFalse(legacy.contains("retrieval"));
    }
    @Test void pythonAndJavaRejectTheSameMalformedNewProtocolSamples() throws Exception {
        var cases=OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/retrieval-negative-cases-1.1.json")));
        for(var failure:cases) {
            var pack=retrievalSample();String path=failure.path("path").asText();int slash=path.lastIndexOf('/');
            var parent=pack.at(path.substring(0,slash));String key=path.substring(slash+1);
            if(parent instanceof ObjectNode object)object.set(key,failure.get("value"));
            else ((ArrayNode)parent).set(Integer.parseInt(key),failure.get("value"));
            assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source(),bindings()),failure.path("name").asText());
        }
    }
    @Test void parserRejectsDuplicateKeysNonstandardConstantsAndExcessiveDepth() {
        for(String json:List.of("{\"a\":1,\"a\":2}","{\"x\":NaN}","[".repeat(65)+"0"+"]".repeat(65)))
            assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.parse(json));
    }
    @Test void sharedMeaningPreservesDifferentModelRolesFieldsAndNoImplicitJoin() throws Exception {
        var converted=OfflineCatalogProtocol.convert(sharedSample(),source(),bindings());
        assertEquals(3,converted.getSharedDefinitions().size());assertEquals(6,converted.getModelBindings().size());
        assertEquals(2,converted.getMetrics().size());assertEquals(2,converted.getDimensions().size());assertTrue(converted.getRelationships().isEmpty());
        var original=converted.getMetrics().stream().filter(m->m.getModelCode().equals("order")).findFirst().orElseThrow();
        var paid=converted.getMetrics().stream().filter(m->m.getModelCode().equals("paid_order")).findFirst().orElseThrow();
        assertNotEquals(original.getMetricCode(),paid.getMetricCode());assertEquals("payment_amount",paid.getDefinitionBinding().definitionCode());
        assertEquals("settled_on",paid.getTimeColumn());assertEquals("payment_state = 1",paid.getFilterExpression());assertTrue(paid.getExpression().contains("SUM(amount_fen)"));
        assertEquals(List.of("实收金额","已付订单到账金额"),paid.getDefinitionBinding().confirmedAliases());
        var scoped=converted.filterByPhysicalTables(Set.of("shop.t_order"));assertEquals(6,scoped.getModelBindings().size());
        var restored=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(converted),cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.class);
        SharedCatalogProtocol.attachReferences(restored);assertEquals(paid.getDefinitionBinding(),restored.getMetrics().stream().filter(m->m.getModelCode().equals("paid_order")).findFirst().orElseThrow().getDefinitionBinding());
        original.setExpression("COUNT(*)");assertThrows(IllegalArgumentException.class,()->SharedCatalogProtocol.attachReferences(converted));
    }
    @Test void sharedProtocolRejectsSameNegativeCasesAsPythonAndForgedReferences() throws Exception {
        var cases=OfflineCatalogProtocol.parse(Files.readString(Path.of("../skills/semantic-catalog-init/examples/shared-negative-cases-1.2.json")));
        for(var failure:cases) {
            var pack=sharedSample();String path=failure.path("path").asText();int slash=path.lastIndexOf('/');var parent=pack.at(path.substring(0,slash));String key=path.substring(slash+1);
            if(parent instanceof ObjectNode object)object.set(key,failure.get("value"));else ((ArrayNode)parent).set(Integer.parseInt(key),failure.get("value"));
            assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source(),bindings()),failure.path("name").asText());
        }
        var pack=sharedSample();pack.withArray("unresolvedIssues").addObject().put("target","definition:payment_amount@1").put("question","以后补充示例").put("blocking",false);
        assertDoesNotThrow(()->OfflineCatalogProtocol.convert(pack,source(),bindings()));
        var converted=OfflineCatalogProtocol.convert(sharedSample(),source(),bindings());var metric=converted.getMetrics().get(0);
        metric.setDefinitionBinding(new cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding("ghost",1,metric.getModelCode(),"payment","Fake role",List.of(),null,null));
        assertThrows(IllegalArgumentException.class,()->SharedCatalogProtocol.attachReferences(converted));
    }
    @Test void staleUnauthorizedAndUnprovenPhysicalFactsCannotImport() throws Exception {
        var pack=sample();var source=source();
        assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source,List.of()));
        assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source,List.of(ProjectDatasourceBinding.builder().datasourceId(17).domainCode("mall").exposedTables(List.of("other")).build())));
        ((ObjectNode)source.path("tables").get(0)).putArray("primaryKey");pack.put("sourceSchemaFingerprint",OfflineCatalogProtocol.hash(source));
        assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source,bindings()));
        assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(sample(),source,bindings()));
    }
    @Test void schemaReferencesExpressionGrainTypesAndEvidenceAreChecked() throws Exception {
        List<Consumer<ObjectNode>> failures=List.of(
            pack->pack.put("formatVersion","999"),
            pack->first(pack,"entities").put("rawSql","select * from secret"),
            pack->first(pack,"metrics").put("entity","absent"),
            pack->((ObjectNode)first(pack,"entities").path("attributes").get(1)).put("dataType","string"),
            pack->((ObjectNode)first(pack,"metrics").path("expression").path("right")).put("literal",0),
            pack->first(pack,"metrics").set("expression",JsonUtil.getObjectMapper().valueToTree(Map.of("op","sum","arg",Map.of("op","sum","arg",Map.of("attribute","paid_amount"))))),
            pack->first(pack,"metrics").set("expression",JsonUtil.getObjectMapper().valueToTree(Map.of("op","add","left",Map.of("op","sum","arg",Map.of("attribute","paid_amount")),"right",Map.of("attribute","paid_amount")))),
            pack->first(pack,"metrics").set("expression",JsonUtil.getObjectMapper().valueToTree(Map.of("metric","payment_amount"))),
            pack->first(pack,"metrics").put("timeAttribute","status"),
            pack->((ObjectNode)first(pack,"metrics").path("filters").get(0)).put("value","untyped-number"),
            pack->pack.putArray("evidence"),
            pack->((ObjectNode)pack.path("evidence").get(0)).put("target","missing"),
            pack->((ArrayNode)pack.path("unresolvedIssues")).add(JsonUtil.getObjectMapper().valueToTree(Map.of("target","metric:payment_amount","question","Not confirmed","blocking",true)))
        );
        for(var fail:failures){var pack=sample();fail.accept(pack);assertThrows(IllegalArgumentException.class,()->OfflineCatalogProtocol.convert(pack,source(),bindings()),pack.toString());}
    }
}
