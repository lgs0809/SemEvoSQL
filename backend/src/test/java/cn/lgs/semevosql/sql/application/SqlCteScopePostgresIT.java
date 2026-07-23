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
package cn.lgs.semevosql.sql.application;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import cn.lgs.semevosql.semantic.compiler.QueryPreflightService;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Isolated, non-empty real PostgreSQL. Neither the existing acceptance DB nor user data is touched. */
@Testcontainers
class SqlCteScopePostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    final SqlExecutionGuard guard=new SqlExecutionGuard();
    @BeforeAll static void seed(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        jdbc.execute("CREATE TABLE orders(id integer,paid_amount numeric,restricted_note text)");
        jdbc.execute("INSERT INTO orders VALUES(1,150,'synthetic private'),(2,120,'synthetic private')");
        jdbc.execute("ALTER TABLE orders ADD COLUMN paid_at timestamp");
        jdbc.execute("UPDATE orders SET paid_at = CASE WHEN id=1 THEN TIMESTAMP '2026-01-31 12:00:00' ELSE TIMESTAMP '2026-02-01 00:00:00' END");
        jdbc.execute("CREATE TABLE adjustments(id integer PRIMARY KEY,order_id integer,amount numeric,adjusted_at timestamp)");
        jdbc.execute("INSERT INTO adjustments VALUES(1,1,10,'2026-01-01'),(2,1,20,'2026-01-31'),(3,2,90,'2026-02-01'),(4,1,NULL,'2026-01-02')");
        jdbc.execute("CREATE TABLE \"Orders\"(id integer)");jdbc.execute("INSERT INTO \"Orders\" VALUES(888)");
        jdbc.execute("CREATE SCHEMA restricted");jdbc.execute("CREATE TABLE restricted.secret(id integer)");
        jdbc.execute("INSERT INTO restricted.secret VALUES(999)");
        jdbc.execute("CREATE ROLE guard_reader LOGIN PASSWORD 'synthetic-container-only'");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO guard_reader");
        jdbc.execute("GRANT SELECT(id,paid_amount,paid_at) ON orders TO guard_reader");
        jdbc.execute("GRANT SELECT(amount,adjusted_at) ON adjustments TO guard_reader");
    }
    @Test void qualifiedSameNameCteMustBeRejectedBeforeDriverExecution(){
        String sql="WITH secret AS (SELECT id FROM orders) SELECT id FROM restricted.secret";
        // Establish that this is valid SQL reaching a real different table, rather than a parser-only example.
        assertEquals(999,jdbc.queryForObject(sql,Integer.class));
        assertThrows(SqlGuardViolationException.class,()->guard.validate(sql,"postgresql",List.of("orders"),"public"));
    }
    @Test void quotedCaseSensitivePhysicalTableDoesNotBorrowTheLowercaseAllowance(){
        String sql="SELECT id FROM \"Orders\"";
        assertEquals(888,jdbc.queryForObject(sql,Integer.class));
        assertThrows(SqlGuardViolationException.class,()->guard.validate(sql,"postgresql",List.of("orders"),"public"));
    }
    @Test void legitimateNestedAndRecursiveCtesExecuteWithTheRestrictedAccount() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),"guard_reader","synthetic-container-only")){
            for(String sql:List.of(
                "WITH paid AS (SELECT paid_amount FROM orders) SELECT sum(paid_amount)::int FROM paid",
                "WITH outer_query AS (WITH paid(v) AS (SELECT paid_amount FROM orders) SELECT sum(v)::int AS amount FROM paid) SELECT amount FROM outer_query")){
                guard.validate(sql,"postgresql",List.of("orders"),"public");
                try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());assertEquals(270,rows.getInt(1));}
            }
            String recursive="WITH RECURSIVE r(id) AS (SELECT id FROM orders WHERE id=1 UNION ALL SELECT id+1 FROM r WHERE id<3) SELECT sum(id) FROM r";
            guard.validate(recursive,"postgresql",List.of("orders"),"public");
            try(var statement=connection.createStatement();var rows=statement.executeQuery(recursive)){assertTrue(rows.next());assertEquals(6,rows.getInt(1));}
        }
    }
    @Test void databasePrivilegesIndependentlyDenyOtherSchemaRestrictedColumnAndWriteCtes() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),"guard_reader","synthetic-container-only")){
            for(String sql:List.of("SELECT id FROM restricted.secret","SELECT * FROM orders",
                "SELECT restricted_note FROM orders","WITH w AS (UPDATE orders SET paid_amount=0 RETURNING id) SELECT id FROM w",
                "WITH w AS (INSERT INTO orders(id) VALUES(3) RETURNING id) SELECT id FROM w")){
                SQLException failure=assertThrows(SQLException.class,()->{
                    try(var statement=connection.createStatement()){statement.executeQuery(sql);}
                });assertEquals("42501",failure.getSQLState());
            }
        }
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders",Integer.class));
        assertEquals(270,jdbc.queryForObject("SELECT sum(paid_amount)::int FROM orders",Integer.class));
        System.out.println("REAL_PG_LEAST_PRIVILEGE validAggregate=270 recursiveSum=6 deniedSchemaColumnWrites=42501 rowsUnchanged=2");
    }

    @Test void governedIndependentScalarAggregatesExecuteWithoutRelaxingTheCartesianGate() throws Exception {
        var catalog = scalarCatalog();
        var plan = SemanticBlueprint.builder().executable(true)
            .models(catalog.getModels().stream().map(m -> SemanticBlueprint.ModelSelection.builder()
                .modelCode(m.getModelCode()).physicalTable(m.getPhysicalTable()).datasourceId(7).build()).toList())
            .metrics(catalog.getMetrics().stream().map(m -> SemanticBlueprint.MetricSelection.builder()
                .modelCode(m.getModelCode()).metricCode(m.getMetricCode()).expression(m.getExpression())
                .aggregation(m.getAggregation()).build()).toList()).build();
        String semantic = """
            WITH a AS (
                SELECT COALESCE(METRIC('orders.total'),0) AS value FROM orders AS o
                WHERE o.paid_at >= TIMESTAMP '2026-01-01' AND o.paid_at < TIMESTAMP '2026-02-01'
            ), b AS (
                SELECT COALESCE(METRIC('adjustments.total'),0) AS value FROM adjustments
                WHERE adjusted_at >= TIMESTAMP '2026-01-01' AND adjusted_at < TIMESTAMP '2026-02-01'
            )
            SELECT (SELECT value FROM a) AS first_value, (SELECT value FROM b) AS second_value,
                   (SELECT value FROM a) - (SELECT value FROM b) AS difference,
                   (SELECT value FROM a) / NULLIF((SELECT value FROM b),0) AS ratio
            """;
        var lowered = new QueryPreflightService().preflight(semantic,catalog,plan,7,"postgresql");
        var reviewPrompt=cn.lgs.semevosql.prompt.PromptHelper.buildSemanticConsistenPrompt(
            cn.lgs.semevosql.dto.prompt.SemanticConsistencyDTO.builder().dialect("postgresql")
                .sql(semantic).loweredSql(lowered.physicalSql()).executionDescription("分别独立汇总，计算差额和比值")
                .schemaInfo("orders(paid_amount,paid_at); adjustments(amount,adjusted_at)")
                .semanticModel("两个独立金额指标").semanticPlan(cn.lgs.semevosql.util.JsonUtil.getObjectMapper().writeValueAsString(plan))
                .userQuery("分别独立汇总，计算差额和比值").evidence("合成受限 PostgreSQL 组件").build());
        assertTrue(reviewPrompt.contains(semantic));
        assertTrue(reviewPrompt.contains(lowered.physicalSql()));
        var tables = List.of("public.orders","public.adjustments");
        guard.validate(lowered.physicalSql(),"postgresql",tables,"public");
        var policy = new SemEvoSQLProperties.SqlExecutionPolicy();
        policy.setRequireTimeFilter(true);
        var cost = new SqlCostGuard(List.of());
        cost.validateSql(lowered.physicalSql(),tables,List.of("paid_at","adjusted_at"),policy);
        assertThrows(SqlGuardViolationException.class, () -> cost.validateSql(
            "SELECT o.id FROM public.orders o CROSS JOIN public.adjustments a",tables,List.of(),policy));
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(),"guard_reader","synthetic-container-only");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(lowered.physicalSql())) {
                assertTrue(rows.next());
                assertEquals(0,rows.getBigDecimal("first_value").compareTo(new BigDecimal("150")));
                assertEquals(0,rows.getBigDecimal("second_value").compareTo(new BigDecimal("30")));
                assertEquals(0,rows.getBigDecimal("difference").compareTo(new BigDecimal("120")));
                assertEquals(0,rows.getBigDecimal("ratio").compareTo(new BigDecimal("5")));
                assertFalse(rows.next());
            }
            // Empty branches still yield one aggregate row; a zero denominator remains NULL.
            try (var rows = statement.executeQuery(lowered.physicalSql().replace("2026-01-01","2026-03-01")
                    .replace("2026-02-01","2026-04-01"))) {
                assertTrue(rows.next());
                assertEquals(0,rows.getBigDecimal("difference").compareTo(BigDecimal.ZERO));
                assertNull(rows.getBigDecimal("ratio"));
                assertFalse(rows.next());
            }
        }
    }


    @Test void constantTimestampCastsPassTheUnchangedTimePolicyAndExecuteWithTheRestrictedReader() throws Exception {
        var policy = new SemEvoSQLProperties.SqlExecutionPolicy();
        policy.setRequireTimeFilter(true);
        var cost = new SqlCostGuard(List.of());
        var tables = List.of("public.orders");
        for (String sql : List.of(
                "SELECT SUM(paid_amount) AS amount FROM public.orders WHERE paid_at >= '2026-01-01'::timestamp AND paid_at < '2026-02-01'::timestamp",
                "SELECT SUM(paid_amount) AS amount FROM public.orders WHERE paid_at >= CAST('2026-01-01' AS TIMESTAMP) AND paid_at < CAST('2026-02-01' AS TIMESTAMP)")) {
            guard.validate(sql, "postgresql", tables, "public");
            cost.validateSql(sql, tables, List.of("paid_at"), policy);
            try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), "guard_reader", "synthetic-container-only");
                 var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                assertEquals(0, rows.getBigDecimal("amount").compareTo(new BigDecimal("150")));
                assertFalse(rows.next());
            }
        }
        String bound = "SELECT SUM(paid_amount) AS amount FROM public.orders WHERE paid_at >= CAST(? AS TIMESTAMP) AND paid_at < ?::timestamp";
        guard.validate(bound, "postgresql", tables, "public");
        cost.validateSql(bound, tables, List.of("paid_at"), policy);
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), "guard_reader", "synthetic-container-only");
             var statement = connection.prepareStatement(bound)) {
            statement.setString(1, "2026-01-01"); statement.setString(2, "2026-02-01");
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getBigDecimal("amount").compareTo(new BigDecimal("150")));
                assertFalse(rows.next());
            }
        }
        assertThrows(SqlGuardViolationException.class, () -> cost.validateSql(
                "SELECT SUM(paid_amount) FROM public.orders WHERE paid_at >= paid_at::timestamp", tables, List.of("paid_at"), policy));
        assertThrows(SqlGuardViolationException.class, () -> cost.validateSql(
                "SELECT SUM(paid_amount) FROM public.orders WHERE paid_at >= NULL::timestamp", tables, List.of("paid_at"), policy));
    }

    private SemanticCatalogSnapshot scalarCatalog() {
        var columns = new java.util.ArrayList<SemanticCatalogSnapshot.Column>();
        Map.of("orders",List.of("paid_amount","paid_at"),"adjustments",List.of("amount","adjusted_at"))
            .forEach((model,names) -> names.forEach(name -> columns.add(SemanticCatalogSnapshot.Column.builder()
                .modelCode(model).columnName(name).expression(name).allowProjection(true).allowAggregation(true)
                .allowFilter(true).status(SemanticAssetStatus.ENABLED).build())));
        return SemanticCatalogSnapshot.builder().columns(columns)
            .models(List.of("orders","adjustments").stream().map(model -> SemanticCatalogSnapshot.Model.builder()
                .modelCode(model).physicalTable("public."+model).datasourceId(7)
                .status(SemanticAssetStatus.ENABLED).build()).toList())
            .metrics(Map.of("orders","paid_amount","adjustments","amount").entrySet().stream()
                .map(entry -> SemanticCatalogSnapshot.Metric.builder().modelCode(entry.getKey()).metricCode("total")
                    .expression("SUM("+entry.getValue()+")").aggregation("SUM")
                    .status(SemanticAssetStatus.ENABLED).build()).toList()).build();
    }

    @Test void compilerExecutesFrozenScalarArithmeticWithIndependentTimeAxesAndUnchangedEmptySemantics() throws Exception {
        var catalog = scalarCatalog();
        catalog.getColumns().stream().filter(c -> c.getColumnName().endsWith("_at")).forEach(c -> c.setDataType("datetime"));
        catalog.getMetrics().forEach(m -> m.setMetricCode(m.getModelCode()+"_total"));
        var compiler = new cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler();
        for (var example : Map.of("difference=orders_total-adjustments_total",new BigDecimal("120"),
                "difference=adjustments_total-orders_total",new BigDecimal("-120"),
                "difference=ABS(adjustments_total-orders_total)",new BigDecimal("120"),
                "difference=orders_total+adjustments_total",new BigDecimal("180")).entrySet()) {
            var plan = compiledScalarPlan(catalog,example.getKey(),"2026-01-01","2026-02-01");
            var compiled = compiler.compile(plan,catalog,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0);
            guard.validate(compiled.sql(),"postgresql",compiled.physicalTables(),"public");
            new SqlCostGuard(List.of()).validateSql(compiled.sql(),compiled.physicalTables(),List.of("paid_at","adjusted_at"),
                new SemEvoSQLProperties.SqlExecutionPolicy());
            assertFalse(compiled.sql().toUpperCase().contains(" JOIN "));
            try (var connection=DriverManager.getConnection(PG.getJdbcUrl(),"guard_reader","synthetic-container-only");
                 var statement=connection.prepareStatement(compiled.sql())) {
                for (int i=0;i<compiled.parameters().size();i++) statement.setObject(i+1,compiled.parameters().get(i));
                try(var rows=statement.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(0,example.getValue().compareTo(rows.getBigDecimal("difference")));
                    assertFalse(rows.next());
                }
            }
        }
        var empty = compiler.compile(compiledScalarPlan(catalog,"difference=orders_total-adjustments_total",
            "2026-03-01","2026-04-01"),catalog,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0);
        try (var connection=DriverManager.getConnection(PG.getJdbcUrl(),"guard_reader","synthetic-container-only");
             var statement=connection.prepareStatement(empty.sql())) {
            for(int i=0;i<empty.parameters().size();i++) statement.setObject(i+1,empty.parameters().get(i));
            try(var rows=statement.executeQuery()) {assertTrue(rows.next());assertNull(rows.getBigDecimal("difference"));assertFalse(rows.next());}
        }
    }

    @Test void scalarCompilerRejectsChangedPublishedFormulaAndUnapprovedResultIdentity() {
        var catalog=scalarCatalog();catalog.getMetrics().forEach(m ->m.setMetricCode(m.getModelCode()+"_total"));
        var compiler=new cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler();
        var plan=compiledScalarPlan(catalog,"difference=orders_total-adjustments_total","2026-01-01","2026-02-01");
        plan.getMetrics().get(0).setExpression("SUM(amount)+100");
        assertThrows(IllegalArgumentException.class,()->compiler.compile(plan,catalog,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
        var wrongOutput=compiledScalarPlan(catalog,"difference=orders_total-adjustments_total","2026-01-01","2026-02-01");
        wrongOutput.getExpectedResult().setColumns(List.of("another_result"));
        assertThrows(IllegalArgumentException.class,()->compiler.compile(wrongOutput,catalog,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
        var rowMetric=compiledScalarPlan(catalog,"difference=orders_total-adjustments_total","2026-01-01","2026-02-01");
        rowMetric.getMetrics().get(0).setExpression("amount");rowMetric.getMetrics().get(0).setAggregation("NONE");
        catalog.getMetrics().get(0).setExpression("amount");catalog.getMetrics().get(0).setAggregation("NONE");
        assertThrows(cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler.ConstrainedGenerationRequiredException.class,
            ()->compiler.compile(rowMetric,catalog,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
    }

    private SemanticBlueprint compiledScalarPlan(SemanticCatalogSnapshot catalog,String expression,String start,String end) {
        var filters=new java.util.ArrayList<SemanticBlueprint.FilterSelection>();
        Map.of("orders","paid_at","adjustments","adjusted_at").forEach((model,time) -> {
            filters.add(SemanticBlueprint.FilterSelection.builder().modelCode(model).columnName(time).expression(time)
                .operator("GTE").value(java.time.LocalDate.parse(start).atStartOfDay()).valueType("LITERAL").build());
            filters.add(SemanticBlueprint.FilterSelection.builder().modelCode(model).columnName(time).expression(time)
                .operator("LT").value(java.time.LocalDate.parse(end).atStartOfDay()).valueType("LITERAL").build());
        });
        return SemanticBlueprint.builder().executable(true).compilerMode("DETERMINISTIC")
            .models(catalog.getModels().stream().map(m ->SemanticBlueprint.ModelSelection.builder().modelCode(m.getModelCode())
                .physicalTable(m.getPhysicalTable()).datasourceId(7).build()).toList())
            .metrics(catalog.getMetrics().stream().map(m ->SemanticBlueprint.MetricSelection.builder().modelCode(m.getModelCode())
                .metricCode(m.getMetricCode()).expression(m.getExpression()).aggregation(m.getAggregation()).filterExpression(m.getFilterExpression()).build()).toList())
            .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(7)
                .modelCodes(List.of("orders","adjustments")).physicalTables(List.of("public.orders","public.adjustments")).build()))
            .filters(filters).expectedResult(SemanticBlueprint.ExpectedResultShape.builder().grain("SCALAR").columns(List.of("difference")).build())
            .scalarCalculation(cn.lgs.semevosql.semantic.domain.ScalarCalculation.parse(expression,
                java.util.Set.of("orders_total","adjustments_total"))).build();
    }
}
