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

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.compiler.*;
import cn.lgs.semevosql.sql.application.*;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Missing extension rows must survive with the exact approved label on both SQL routes. */
@Testcontainers
class NullReplacementPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    @BeforeAll static void initialize() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        jdbc.execute("CREATE TABLE customer(id integer PRIMARY KEY)");
        jdbc.execute("CREATE TABLE customer_ext(id integer PRIMARY KEY,region text)");
        jdbc.execute("INSERT INTO customer VALUES(1),(2),(3)");
        jdbc.execute("INSERT INTO customer_ext VALUES(1,'EAST'),(2,'SOUTH')");
    }
    ComputationIntent intent(String label) throws Exception {
        var requirement=JsonUtil.getObjectMapper().createObjectNode().put("capability","NULL_REPLACEMENT")
            .put("dimensionCode","customer_region").put("nullReplacement",label);
        return SemanticBlueprintGenerationService.computationIntent(JsonUtil.getObjectMapper().createArrayNode(),
            JsonUtil.getObjectMapper().createArrayNode().add(requirement),Set.of("customer_count"),Set.of("customer_region"));
    }
    SemanticCatalogSnapshot catalog() throws Exception {
        var source=new GovernedModelSource(List.of(new GovernedModelSource.Table("c","public","customer"),new GovernedModelSource.Table("x","public","customer_ext")),"c",
            List.of(new GovernedModelSource.Join("left","x",List.of(new GovernedModelSource.Pair(new GovernedModelSource.Mapping("c","id"),new GovernedModelSource.Mapping("x","id"))))),
            List.of(new GovernedModelSource.Projection("id",new GovernedModelSource.Mapping("c","id")),new GovernedModelSource.Projection("region",new GovernedModelSource.Mapping("x","region"))),List.of());
        return SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("customers").datasourceId(1).physicalTable("public.customer")
                .sourceJson(JsonUtil.getObjectMapper().writeValueAsString(source)).status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("customers").columnName("id").dataType("INTEGER").role(SemanticColumnRole.IDENTIFIER).allowProjection(true).allowAggregation(true).allowFilter(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build(),
                SemanticCatalogSnapshot.Column.builder().modelCode("customers").columnName("region").dataType("TEXT").role(SemanticColumnRole.ATTRIBUTE).allowProjection(true).allowAggregation(true).allowFilter(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build())).build();
    }
    SemanticBlueprint plan() {
        return SemanticBlueprint.builder().projectId(1L).projectVersionId(2L).executable(true).compilerMode("DETERMINISTIC")
            .models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("customers").physicalTable("public.customer").datasourceId(1).build()))
            .dimensions(List.of(SemanticBlueprint.DimensionSelection.builder().dimensionCode("customer_region").modelCode("customers").columnName("region").build()))
            .metrics(List.of(SemanticBlueprint.MetricSelection.builder().modelCode("customers").metricCode("customer_count").expression("COUNT(*)").aggregation("EXPRESSION").build()))
            .projections(List.of(SemanticBlueprint.ProjectionSelection.builder().modelCode("customers").columnName("region").expression("region").alias("customer_region").projectionType("DIMENSION").build(),
                SemanticBlueprint.ProjectionSelection.builder().modelCode("customers").expression("COUNT(*)").alias("customer_count").projectionType("METRIC").build()))
            .groupBy(List.of(SemanticBlueprint.GroupSelection.builder().modelCode("customers").columnName("region").expression("region").alias("customer_region").build()))
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("customers")).physicalTables(List.of("public.customer","public.customer_ext")).build()))
            .limit(100).build();
    }
    @Test void approvedLabelSurvivesRecoveryAndBothSqlRoutesWithoutDroppingMissingRows() throws Exception {
        var plan=plan();var intent=intent("Unknown");SemanticBlueprintPipeline.reconcileComputationIntent(plan,intent);
        String first=plan.getProjections().get(0).getExpression();SemanticBlueprintPipeline.reconcileComputationIntent(plan,intent);
        assertEquals(first,plan.getProjections().get(0).getExpression());
        var restored=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),SemanticBlueprint.class);
        var catalog=catalog();var compiled=new SemanticSqlCompiler().compile(restored,catalog,SqlDialect.POSTGRESQL).sources().get(0);
        var generated=new QueryPreflightService().preflight("SELECT COALESCE(region,'Unknown') AS customer_region,COUNT(*) AS customer_count FROM customers GROUP BY COALESCE(region,'Unknown')",catalog,restored,1,"postgresql");
        for(String sql:List.of(compiled.sql(),generated.physicalSql())) {
            new SqlExecutionGuard().validate(sql,"postgresql",compiled.physicalTables(),"public");
            var rows=jdbc.queryForList(sql);assertEquals(3,rows.size());
            assertTrue(rows.contains(Map.of("customer_region","Unknown","customer_count",1L)));
        }
        var missing=new HashMap<String,String>();missing.put("customer_region",null);missing.put("customer_count","1");
        assertFalse(new SqlResultValidator().validate(ResultSetBO.builder().column(List.of("customer_region","customer_count")).data(List.of(missing)).build(),restored,100).valid());
    }
    @Test void quotesRemainLiteralDataAndCannotExecuteStatements() throws Exception {
        String label="Missing'); DROP TABLE customer; --";var plan=plan();SemanticBlueprintPipeline.reconcileComputationIntent(plan,intent(label));
        var sql=new SemanticSqlCompiler().compile(plan,catalog(),SqlDialect.POSTGRESQL).sources().get(0).sql();
        assertTrue(jdbc.queryForList(sql).contains(Map.of("customer_region",label,"customer_count",1L)));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM customer",Integer.class));
    }
    @Test void absentUnselectedConflictingOrUnsafeLabelsCannotBecomeExecutable() throws Exception {
        for(String invalid:List.of(""," ","x".repeat(129),"back\\slash","new\nline"))assertThrows(IllegalArgumentException.class,()->intent(invalid));
        var missing=plan();assertThrows(IllegalArgumentException.class,()->SemanticBlueprintPipeline.reconcileComputationIntent(missing,new ComputationIntent(Set.of(ComputationIntent.Capability.NULL_REPLACEMENT))));
        var unprojected=plan();unprojected.setProjections(List.of());assertThrows(IllegalArgumentException.class,()->SemanticBlueprintPipeline.reconcileComputationIntent(unprojected,intent("Missing")));
        var first=intent("Missing").requirements().get(0);var second=intent("Other").requirements().get(0);
        assertThrows(IllegalArgumentException.class,()->SemanticBlueprintPipeline.reconcileComputationIntent(plan(),new ComputationIntent(Set.of(),List.of(first,second))));
    }
    @Test void parsedExpressionsStillEnforceColumnGovernanceAndSingleExpressionBoundary() throws Exception {
        var compiler=new SemanticSqlCompiler();
        for(String invalid:List.of("region; DROP TABLE customer", "(SELECT region FROM customer_ext)",
                "pg_sleep(1)", "other.region", "@session_value")) {
            var plan=plan();plan.getProjections().get(0).setExpression(invalid);
            assertThrows(SemanticSqlCompiler.ConstrainedGenerationRequiredException.class,
                ()->compiler.compile(plan,catalog(),SqlDialect.POSTGRESQL),invalid);
        }
        var denied=catalog();denied.getColumns().get(1).setAllowProjection(false);
        var quoted=plan();quoted.getProjections().get(0).setExpression("COALESCE(\"region\", 'Missing')");
        assertThrows(IllegalArgumentException.class,()->compiler.compile(quoted,denied,SqlDialect.POSTGRESQL));
        var valid=compiler.compile(quoted,catalog(),SqlDialect.POSTGRESQL).sources().get(0).sql();
        assertTrue(valid.contains("t0.\"region\""));
        for(String wildcard:List.of("SELECT * FROM customers", "SELECT customers.* FROM customers"))
            assertThrows(QueryPreflightException.class,()->new QueryPreflightService().preflight(wildcard,catalog(),plan(),1,"postgresql"));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM customer",Integer.class));
    }
}
