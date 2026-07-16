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

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler;
import cn.lgs.semevosql.semantic.compiler.SqlDialect;
import cn.lgs.semevosql.semantic.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Exercises generated placeholders with the real PostgreSQL driver; no LLM simulation claim. */
@Testcontainers
class TemporalFilterPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    @BeforeAll static void seed() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE TABLE orders(id integer, paid_at timestamp, settled_on date)");
        jdbc.execute("INSERT INTO orders VALUES (1,'2026-01-10 10:05','2026-02-15'),(2,'2026-02-10 10:05','2026-01-15')");
    }
    SemanticCatalogSnapshot catalog() {
        return SemanticCatalogSnapshot.builder()
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("orders").physicalTable("orders")
                .datasourceId(1).status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName("id")
                .dataType("integer").allowProjection(true).status(SemanticAssetStatus.ENABLED).build(),
                SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName("paid_at")
                .dataType("timestamp without time zone").role(SemanticColumnRole.TIME).allowFilter(true)
                .allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build())).build();
    }
    SemanticBlueprint plan(Object value, String operator) {
        return SemanticBlueprint.builder().executable(true)
            .projections(List.of(SemanticBlueprint.ProjectionSelection.builder().modelCode("orders")
                .columnName("id").alias("id").projectionType("DIMENSION").build()))
            .filters(List.of(SemanticBlueprint.FilterSelection.builder().modelCode("orders").columnName("paid_at")
                .operator(operator).value(value).valueType("LITERAL").build()))
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1)
                .modelCodes(List.of("orders")).physicalTables(List.of("orders")).build())).build();
    }
    @ParameterizedTest @ValueSource(strings={"2026年1月", "2026-01", "last month", "2026-02-30", "2026-01-10T25:00"})
    void compilerRejectsInvalidTemporalValuesBeforeDispatch(String value) {
        assertThrows(IllegalArgumentException.class, () -> new SemanticSqlCompiler().compile(plan(value,"EQ"),
            catalog(),Map.of(1,SqlDialect.POSTGRESQL),Clock.systemUTC(),ZoneId.of("UTC")));
    }
    @ParameterizedTest @ValueSource(strings={"EQ", "IN"})
    void exactTimestampLiteralUsesTypedJdbcParameter(String operator) {
        Object value = "IN".equals(operator) ? List.of("2026-01-10 10:05:00") : "2026-01-10T10:05:00";
        var source = new SemanticSqlCompiler().compile(plan(value,operator),catalog(),Map.of(1,SqlDialect.POSTGRESQL),
            Clock.systemUTC(),ZoneId.of("UTC")).sources().get(0);
        assertInstanceOf(LocalDateTime.class, source.parameters().get(0));
        assertEquals(List.of(1), jdbc.query(connection -> {
            var statement = connection.prepareStatement(source.sql());
            for (int i=0;i<source.parameters().size();i++) statement.setObject(i+1,source.parameters().get(i));
            return statement;
        }, (rs,n) -> rs.getInt(1)));
    }
    @Test void nonTemporalTextAndNumericValuesRemainLiteral() {
        assertEquals("2026年1月",TemporalFilterValue.normalize("varchar(40)","2026年1月"));
        assertEquals(202601,TemporalFilterValue.normalize("bigint",202601));
        assertInstanceOf(LocalDate.class,TemporalFilterValue.normalize("date","2026-02-28"));
        assertInstanceOf(OffsetDateTime.class,TemporalFilterValue.normalize("timestamp(6) with time zone","2026-01-10T10:05:00+08:00"));
        assertInstanceOf(LocalTime.class,TemporalFilterValue.normalize("time(6)","10:05:00"));
    }
    QueryCaseHints hints(List<QueryCaseHints.FilterBindingHint> filters, QueryCaseHints.TimeBindingHint time) {
        return new QueryCaseHints(Set.of("orders"),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),List.of(),
            filters,List.of(),time,true,"CURRENT_QUERY",List.of(),1.0,Map.of());
    }
    @Test void enrichmentRejectsInvalidHistoricalOrResolvedTemporalHints() {
        var hints = hints(List.of(new QueryCaseHints.FilterBindingHint("2026年1月","orders","paid_at","EQ",
            "2026年1月","TEST",1.0)),null);
        var details = new SemanticBlueprintEnricher().enrich(catalog(),"2026年1月的支付金额",List.of(SemanticBlueprint.ModelSelection.builder().modelCode("orders").build()),List.of(),List.of(),List.of(),hints);
        assertTrue(details.errors().stream().anyMatch(v->v.contains("timeBinding")));
        assertTrue(details.filters().isEmpty());
    }
    @Test void normalMonthTimeBindingStillProducesWholeMonthRange() {
        var hints = hints(List.of(),new QueryCaseHints.TimeBindingHint("2026年1月","orders","paid_at","TEST",1.0,"DAY"));
        var details = new SemanticBlueprintEnricher().enrich(catalog(),"2026年1月的支付金额",List.of(SemanticBlueprint.ModelSelection.builder().modelCode("orders").build()),List.of(),List.of(),List.of(),hints);
        assertEquals("2026-01-01T00:00",details.timeRange().getStartInclusive());
        assertEquals("2026-02-01T00:00",details.timeRange().getEndExclusive());
    }

    @Test void modelNormalizedIntervalSurvivesCheckpointAndExecutesHalfOpenJdbcRange() throws Exception {
        String question="按支付时间统计这段已确认的观察区间";
        var original=hints(List.of(),new QueryCaseHints.TimeBindingHint(question,"orders","paid_at","MODEL",1.0,
            null,"2026-01-10T10:05","2026-02-10T10:05"));
        var mapper=cn.lgs.semevosql.util.JsonUtil.getObjectMapper();
        var restored=mapper.readValue(mapper.writeValueAsBytes(original),QueryCaseHints.class);
        var details=new SemanticBlueprintEnricher().enrich(catalog(),question,
            List.of(SemanticBlueprint.ModelSelection.builder().modelCode("orders").build()),
            List.of(),List.of(),List.of(),restored);
        assertTrue(details.errors().isEmpty());
        var typedPlan=plan(null,"EQ");
        typedPlan.setFilters(List.of());typedPlan.setTimeRange(details.timeRange());
        var source=new SemanticSqlCompiler().compile(typedPlan,catalog(),Map.of(1,SqlDialect.POSTGRESQL),
            Clock.systemUTC(),ZoneId.of("UTC")).sources().get(0);
        assertEquals(List.of(1),jdbc.queryForList(source.sql(),Integer.class,source.parameters().toArray()));
        assertEquals(original.timeBinding(),restored.timeBinding());
    }

    @Test void invalidIntervalsCannotEnterDurableHints() {
        assertThrows(IllegalArgumentException.class,()->new QueryCaseHints.TimeBindingHint("period","orders","paid_at",
            "MODEL",1.0,null,"2026-01-01",null));
        assertThrows(IllegalArgumentException.class,()->new QueryCaseHints.TimeBindingHint("period","orders","paid_at",
            "MODEL",1.0,null,"2026-02-01","2026-01-01"));
        assertThrows(IllegalArgumentException.class,()->new QueryCaseHints.TimeBindingHint("period","orders","paid_at",
            "MODEL",1.0,null,"2026-02-30","2026-03-01"));
    }

    @Test void oldCheckpointWithoutExplicitIntervalRemainsReadable() throws Exception {
        String old="{\"rawText\":\"2026年1月\",\"modelCode\":\"orders\",\"columnName\":\"paid_at\","
            +"\"sourceExampleId\":\"QUERY\",\"confidence\":1.0,\"groupGranularity\":\"MONTH\"}";
        var restored=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().readValue(old,QueryCaseHints.TimeBindingHint.class);
        assertNull(restored.startInclusive());assertNull(restored.endExclusive());
        assertEquals("MONTH",restored.groupGranularity());
    }

    @Test void multipleObservationAxesSurviveCheckpointAndConstrainTheirOwnTypedJdbcColumns() throws Exception {
        var snapshot=catalog();
        var columns=new ArrayList<>(snapshot.getColumns());
        columns.add(SemanticObservationIntervalsTest.column("orders","settled_on","date"));
        snapshot.setColumns(columns);
        var candidates=new SemanticCandidateSet(1L,1L,"catalog",Set.of("orders"),snapshot.getModels(),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
            columns.stream().filter(c->c.getRole()==SemanticColumnRole.TIME).toList(),List.of(),List.of());
        String question="支付时间在一月且结算日期在二月的订单";
        var mapper=cn.lgs.semevosql.util.JsonUtil.getObjectMapper();
        var node=mapper.readTree("""
            [{"modelCode":"orders","columnName":"paid_at","startInclusive":"2026-01-01","endExclusive":"2026-02-01"},
             {"modelCode":"orders","columnName":"settled_on","startInclusive":"2026-02-01","endExclusive":"2026-03-01"}]
            """);
        var selected=hints(SemanticObservationIntervals.resolve(question,node,candidates,1,List.of(),null),null);
        var restored=mapper.readValue(mapper.writeValueAsBytes(selected),QueryCaseHints.class);
        var details=new SemanticBlueprintEnricher().enrich(snapshot,question,
            List.of(SemanticBlueprint.ModelSelection.builder().modelCode("orders").build()),List.of(),List.of(),List.of(),restored);
        assertTrue(details.errors().isEmpty());assertNull(details.timeRange());
        var typedPlan=plan(null,"EQ");typedPlan.setFilters(details.filters());
        typedPlan.setComputationIntent(new ComputationIntent(Set.of(ComputationIntent.Capability.TIME_FILTER)));
        SemanticBlueprintPipeline.requireResolvedTimeFilter(typedPlan,candidates);
        var source=new SemanticSqlCompiler().compile(typedPlan,snapshot,Map.of(1,SqlDialect.POSTGRESQL),
            Clock.systemUTC(),ZoneId.of("UTC")).sources().get(0);
        assertInstanceOf(LocalDateTime.class,source.parameters().get(0));
        assertInstanceOf(LocalDate.class,source.parameters().get(2));
        assertEquals(List.of(1),jdbc.queryForList(source.sql(),Integer.class,source.parameters().toArray()));
    }
}
