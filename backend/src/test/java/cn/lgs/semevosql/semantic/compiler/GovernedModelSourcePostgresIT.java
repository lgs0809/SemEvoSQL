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
package cn.lgs.semevosql.semantic.compiler;

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.sql.application.*;
import cn.lgs.semevosql.util.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real rows exercise both paths, missing LEFT extensions, fixed filters and physical authorization. */
@Testcontainers
class GovernedModelSourcePostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    @BeforeAll static void seed() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        jdbc.execute("CREATE TABLE party(id integer PRIMARY KEY,kind text,name text)");
        jdbc.execute("CREATE TABLE party_ext(party_id integer UNIQUE,region text)");
        jdbc.execute("INSERT INTO party VALUES(1,'customer','A'),(2,'customer','B'),(3,'supplier','C')");
        jdbc.execute("INSERT INTO party_ext VALUES(1,'EAST'),(3,'WEST')");
    }
    static SemanticCatalogSnapshot catalog() throws Exception {
        var source=new GovernedModelSource(List.of(new GovernedModelSource.Table("p","public","party"),new GovernedModelSource.Table("x","public","party_ext")),"p",
            List.of(new GovernedModelSource.Join("left","x",List.of(new GovernedModelSource.Pair(new GovernedModelSource.Mapping("p","id"),new GovernedModelSource.Mapping("x","party_id"))))),
            List.of(new GovernedModelSource.Projection("customer_id",new GovernedModelSource.Mapping("p","id")),new GovernedModelSource.Projection("region",new GovernedModelSource.Mapping("x","region")),new GovernedModelSource.Projection("kind",new GovernedModelSource.Mapping("p","kind"))),
            List.of(JsonUtil.getObjectMapper().valueToTree(Map.of("attribute","kind","operator","eq","value","customer"))));
        var model=SemanticCatalogSnapshot.Model.builder().modelCode("customers").datasourceId(1).physicalTable("public.party")
            .sourceJson(CanonicalJson.write(source)).status(SemanticAssetStatus.ENABLED).build();
        return SemanticCatalogSnapshot.builder().models(List.of(model))
            .columns(List.of("customer_id","region","kind").stream().map(code->SemanticCatalogSnapshot.Column.builder()
                .modelCode("customers").columnName(code).dataType(code.equals("customer_id")?"integer":"string")
                .status(SemanticAssetStatus.ENABLED).build()).toList()).build();
    }
    static SemanticBlueprint plan() {
        return SemanticBlueprint.builder().executable(true).limit(10)
            .models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("customers").physicalTable("public.party").datasourceId(1).build()))
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("customers")).physicalTables(List.of("public.party","public.party_ext")).build()))
            .projections(List.of("customer_id","region").stream().map(code->SemanticBlueprint.ProjectionSelection.builder()
                .modelCode("customers").columnName(code).alias(code).projectionType("DIMENSION").build()).toList())
            .orderBy(List.of(SemanticBlueprint.OrderSelection.builder().expression("customer_id").direction("ASC").build())).build();
    }
    @Test void loweringAndGenerationReturnIdenticalNonemptyLogicalRowsAndKeepMissingExtensions() throws Exception {
        var catalog=catalog();
        var compiled=new SemanticSqlCompiler().compile(plan(),catalog,SqlDialect.POSTGRESQL).sources().get(0);
        var generated=new QueryPreflightService().preflight("SELECT customer_id,region FROM customers ORDER BY customer_id LIMIT 10",catalog,plan(),1,"postgresql");
        assertEquals(List.of("public.party","public.party_ext"),compiled.physicalTables());
        assertEquals(Set.of("public.party","public.party_ext"),generated.physicalTables());
        var expected=jdbc.queryForList("SELECT p.id AS customer_id,x.region FROM party p LEFT JOIN party_ext x ON p.id=x.party_id WHERE p.kind='customer' ORDER BY p.id");
        assertEquals(2,expected.size());assertNull(expected.get(1).get("region"));
        for(String sql:List.of(compiled.sql(),generated.physicalSql())) {
            new SqlExecutionGuard().validate(sql,"postgresql",compiled.physicalTables(),"public");
            assertEquals(expected,jdbc.queryForList(sql));
            assertThrows(SqlGuardViolationException.class,()->new SqlExecutionGuard().validate(sql,"postgresql",List.of("public.party"),"public"));
        }
        assertEquals(Set.of("public.party","public.party_ext"),catalog.enabledPhysicalTables());
    }
    @Test void samePhysicalTableKeepsIndependentEntityFiltersAndCannotBypassMapping() throws Exception {
        var customers=catalog();var supplier=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(customers.getModels().get(0)),SemanticCatalogSnapshot.Model.class);
        supplier.setModelCode("suppliers");supplier.setSourceJson(supplier.getSourceJson().replace("customer\"","supplier\""));
        assertEquals(1,jdbc.queryForList("SELECT * FROM "+GovernedModelSourceRenderer.relation(supplier,SqlDialect.POSTGRESQL)+" t").size());
        assertThrows(QueryPreflightException.class,()->new QueryPreflightService().preflight("SELECT id FROM public.party",customers,plan(),1,"postgresql"));
    }
    @Test void derivedOutputReusesFilteredNativeMetricsOverTheGovernedSource() throws Exception {
        var catalog=catalog();var plan=plan();
        catalog.setMetrics(List.of(
            SemanticCatalogSnapshot.Metric.builder().modelCode("customers").metricCode("customer_count")
                .expression("COUNT(*)").aggregation("COUNT").status(SemanticAssetStatus.ENABLED).build(),
            SemanticCatalogSnapshot.Metric.builder().modelCode("customers").metricCode("east_customer_count")
                .expression("COUNT(DISTINCT customer_id)").aggregation("COUNT_DISTINCT").filterExpression("region = 'EAST'")
                .status(SemanticAssetStatus.ENABLED).build()));
        plan.setMetrics(catalog.getMetrics().stream().map(m->SemanticBlueprint.MetricSelection.builder()
            .modelCode(m.getModelCode()).metricCode(m.getMetricCode()).expression(m.getExpression())
            .aggregation(m.getAggregation()).filterExpression(m.getFilterExpression()).build()).toList());
        var generated=new QueryPreflightService().preflight("""
            WITH totals AS (
              SELECT METRIC('c.customer_count') AS base, METRIC('c.east_customer_count') AS selected
              FROM customers c
            )
            SELECT ROUND(selected * 100.0 / NULLIF(base,0),2) AS share_percent FROM totals LIMIT 10
            """,catalog,plan,1,"postgresql");
        assertTrue(new BlueprintSqlConstraintValidator().validate(generated.physicalSql(),plan).valid());
        new SqlExecutionGuard().validate(generated.physicalSql(),"postgresql",List.copyOf(generated.physicalTables()),"public");
        var expected=jdbc.queryForList("SELECT ROUND(100.0 * COUNT(DISTINCT CASE WHEN x.region='EAST' THEN p.id END) / NULLIF(COUNT(*),0),2) AS share_percent FROM party p LEFT JOIN party_ext x ON p.id=x.party_id WHERE p.kind='customer'");
        assertEquals(expected,jdbc.queryForList(generated.physicalSql()));
        assertEquals(new java.math.BigDecimal("50.00"),expected.get(0).get("share_percent"));
    }
    @Test void sameNamedLogicalModelStillAppliesSourceFiltersAndRejectsPhysicalBypass() throws Exception {
        var catalog=catalog();var plan=plan();
        catalog.getModels().get(0).setModelCode("party");
        catalog.getColumns().forEach(c->c.setModelCode("party"));
        plan.getModels().get(0).setModelCode("party");
        plan.getSourceSubPlans().get(0).setModelCodes(List.of("party"));
        var generated=new QueryPreflightService().preflight("SELECT customer_id FROM party ORDER BY customer_id LIMIT 10",catalog,plan,1,"postgresql");
        assertEquals(List.of(Map.of("customer_id",1),Map.of("customer_id",2)),jdbc.queryForList(generated.physicalSql()));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM public.party",Integer.class));
        var rejected=assertThrows(QueryPreflightException.class,()->new QueryPreflightService().preflight(
            "SELECT id FROM public.party",catalog,plan,1,"postgresql"));
        assertEquals("SEMANTIC_PHYSICAL_BYPASS_FORBIDDEN",rejected.code());
    }
    @Test void invalidSourceGraphIdentifiersAndFixedPredicatesFailClosed() throws Exception {
        String source=catalog().getModels().get(0).getSourceJson();
        for(String invalid:List.of(source.replace("party_ext","party_ext; DROP TABLE party"),source.replace("\"base\":\"p\"","\"base\":\"missing\""),source.replace("\"type\":\"left\"","\"type\":\"cross\"")))
            assertThrows(IllegalArgumentException.class,()->GovernedModelSource.parse(invalid));
        var model=catalog().getModels().get(0);model.setSourceJson(source.replace("\"operator\":\"eq\"","\"operator\":\"raw_sql\""));
        assertThrows(IllegalArgumentException.class,()->GovernedModelSourceRenderer.relation(model,SqlDialect.MYSQL));
    }
}
