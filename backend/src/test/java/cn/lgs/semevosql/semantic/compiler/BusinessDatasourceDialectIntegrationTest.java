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

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real-engine acceptance for the two officially supported business datasource dialects. */
@Testcontainers(disabledWithoutDocker = true)
class BusinessDatasourceDialectIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("business")
		.withUsername("business")
		.withPassword("business");

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14")
		.withDatabaseName("business")
		.withUsername("business")
		.withPassword("business");

	private final SemanticSqlCompiler compiler = new SemanticSqlCompiler();

	@Test
	void mysqlMetadataCompileExplainAndExecuteUseTheSameGovernedPlan() throws Exception {
		verifyEngine(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), SqlDialect.MYSQL);
	}

	@Test
	void postgresqlMetadataCompileExplainAndExecuteUseTheSameGovernedPlan() throws Exception {
		verifyEngine(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), SqlDialect.POSTGRESQL);
	}

	private void verifyEngine(String jdbcUrl, String username, String password, SqlDialect dialect) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			prepareFixture(connection);
			assertMetadataVisible(connection);
            verifyPagination(connection,dialect);
            verifyControlledCompositeMetrics(connection,dialect);

			SemanticCatalogSnapshot catalog = catalog();
			SemanticBlueprint blueprint = blueprint();
			LoweringCapabilityProbe.Decision decision = LoweringCapabilityProbe.probe(blueprint, catalog,
					Map.of(1, dialect));
			assertThat(decision.status()).isEqualTo(LoweringCapabilityProbe.Status.SUPPORTED);

			CompiledSemanticQuery.CompiledSourceQuery compiled = compiler
				.compile(blueprint, catalog, Map.of(1, dialect), Clock.systemUTC(), ZoneId.of("UTC"))
				.sources()
				.get(0);

			try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + compiled.sql())) {
				bind(explain, compiled.parameters());
				assertThat(explain.execute()).isTrue();
			}
			try (PreparedStatement query = connection.prepareStatement(compiled.sql())) {
				bind(query, compiled.parameters());
				try (ResultSet rows = query.executeQuery()) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getBigDecimal("paid_total")).isEqualByComparingTo("30.00");
				}
			}
		}
	}

    private void verifyControlledCompositeMetrics(Connection connection,SqlDialect dialect) throws Exception {
        var json=cn.lgs.semevosql.util.JsonUtil.getObjectMapper();
        var sum=Map.of("op","sum","arg",Map.of("attribute","paid_amount"));
        var expressions=List.of(
            Map.of("op","divide","left",sum,"right",Map.of("literal",2),"onZero","null"),
            Map.of("op","multiply","left",sum,"right",Map.of("literal",new BigDecimal("0.5"))),
            Map.of("op","divide","left",sum,"right",Map.of("op","count_rows"),"onZero","null"),
            Map.of("op","subtract","left",sum,"right",Map.of("literal",5)));
        var expected=List.of("15","15","15","25");
        for(int i=0;i<expressions.size();i++) {
            var catalog=catalog();catalog.getColumns().get(0).setDataType("DECIMAL(18,2)");catalog.getColumns().get(0).setAllowFilter(true);catalog.getColumns().get(0).setAllowSendToLlm(true);
            catalog.setColumns(new java.util.ArrayList<>(catalog.getColumns()));
            catalog.getColumns().add(SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName("id").dataType("BIGINT")
                .businessName("Order ID").description("Synthetic fixture primary key").allowProjection(true).allowFilter(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build());
            catalog.setGrains(List.of(SemanticCatalogSnapshot.Grain.builder().modelCode("orders").grainCode("order").keyColumns("id").status(SemanticAssetStatus.ENABLED).build()));
            var ast=json.valueToTree(Map.of("code","derived_total","name","Derived metric","description","Synthetic controlled AST fixture",
                "entity","orders","expression",expressions.get(i),"filters",List.of(),"unit","元"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)ast).putNull("timeAttribute");
            var metric=cn.lgs.semevosql.semantic.application.OfflineCatalogProtocol.projectPrivateMetric(ast,catalog);
            catalog.setMetrics(new java.util.ArrayList<>(catalog.getMetrics()));catalog.getMetrics().add(metric);
            var selected=SemanticBlueprint.MetricSelection.builder().modelCode(metric.getModelCode()).metricCode(metric.getMetricCode())
                .expression(metric.getExpression()).aggregation(metric.getAggregation()).businessName(metric.getBusinessName()).build();
            var models=List.of(SemanticBlueprint.ModelSelection.builder().modelCode("orders").datasourceId(1).physicalTable("orders").build());
            var details=new cn.lgs.semevosql.semantic.application.SemanticBlueprintEnricher().enrich(catalog,"Derived metric",models,List.of(selected),List.of(),List.of());
            var plan=SemanticBlueprint.builder().executable(true).models(models).metrics(List.of(selected)).projections(details.projections())
                .sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("orders")).physicalTables(List.of("orders")).build())).build();
            var compiled=compiler.compile(plan,catalog,dialect).sources().get(0);
            assertThat(compiled.sql()).doesNotContain("EXPRESSION(");
            try(var statement=connection.prepareStatement(compiled.sql());var rows=statement.executeQuery()) {
                assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal("derived_total")).isEqualByComparingTo(expected.get(i));
                assertThat(rows.next()).isFalse();
            }
            // The same AST must also survive the public 1.2 definition + explicit model-binding materialization.
            var base=catalog.detachedCopy();base.getMetrics().removeIf(m->"derived_total".equals(m.getMetricCode()));
            var structure=json.createObjectNode();structure.set("metric",ast);
            var publicCatalog=cn.lgs.semevosql.clarification.ProjectDefinitionCatalogMaterializer.add(i+1,1,"Published derived total",
                "Synthetic controlled AST fixture",structure,base);
            String publicCode=cn.lgs.semevosql.clarification.ProjectDefinitionCatalogMaterializer.assetCode(i+1,structure);
            var publicMetric=publicCatalog.getMetrics().stream().filter(m->m.getMetricCode().equals(publicCode)).findFirst().orElseThrow();
            var publicSelection=SemanticBlueprint.MetricSelection.builder().modelCode("orders").metricCode(publicCode)
                .businessName(publicMetric.getBusinessName()).expression(publicMetric.getExpression()).aggregation(publicMetric.getAggregation())
                .definitionBinding(publicMetric.getDefinitionBinding()).build();
            var publicDetails=new cn.lgs.semevosql.semantic.application.SemanticBlueprintEnricher().enrich(publicCatalog,"Published derived total",models,List.of(publicSelection),List.of(),List.of());
            var publicPlan=SemanticBlueprint.builder().executable(true).models(models).metrics(List.of(publicSelection)).projections(publicDetails.projections())
                .sourceSubPlans(plan.getSourceSubPlans()).build();
            var publicQuery=compiler.compile(publicPlan,publicCatalog,dialect).sources().get(0);
            try(var statement=connection.prepareStatement(publicQuery.sql());var rows=statement.executeQuery()) {
                assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal(publicCode)).isEqualByComparingTo(expected.get(i));assertThat(rows.next()).isFalse();
            }
            assertThat(publicMetric.getDefinitionBinding().bindingCode()).isEqualTo("candidate_"+(i+1));
            assertThat(publicCatalog.getMetrics()).noneMatch(m->m.getMetricCode().equals("derived_total"));
            for(var oldCatalog:List.of(base,publicCatalog)) {
                var oldMetric=oldCatalog.getMetrics().get(oldCatalog.getMetrics().size()-1);
                String stableCode=oldMetric.getMetricCode();
                var replacement=structure.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)replacement.path("metric")).set("expression",json.valueToTree(sum));
                var approved=new cn.lgs.semevosql.clarification.ProjectDefinitionPublicationRepository.Work(1,1,100+i,1,1,"synthetic-inputs",1,"synthetic-baseline",replacement,
                    "synthetic-representation",stableCode,oldMetric.getBusinessName(),"Synthetic approved replacement",null,null,null,"synthetic-token",1,
                    1L,"OVERWRITE",stableCode,oldMetric.getBusinessName(),"fixture-admin","AUTHENTICATED");
                var changed=cn.lgs.semevosql.clarification.ProjectDefinitionCatalogMaterializer.materialize(approved,oldCatalog);
                var changedMetric=changed.getMetrics().stream().filter(m->m.getMetricCode().equals(stableCode)).findFirst().orElseThrow();
                var replacementSelection=SemanticBlueprint.MetricSelection.builder().modelCode("orders").metricCode(stableCode).businessName(changedMetric.getBusinessName())
                    .expression(changedMetric.getExpression()).aggregation(changedMetric.getAggregation()).definitionBinding(changedMetric.getDefinitionBinding()).build();
                var replacementDetails=new cn.lgs.semevosql.semantic.application.SemanticBlueprintEnricher().enrich(changed,"Synthetic replacement",models,List.of(replacementSelection),List.of(),List.of());
                var replacementPlan=SemanticBlueprint.builder().executable(true).models(models).metrics(List.of(replacementSelection)).projections(replacementDetails.projections()).sourceSubPlans(plan.getSourceSubPlans()).build();
                var compiledReplacement=compiler.compile(replacementPlan,changed,dialect).sources().get(0);
                try(var statement=connection.prepareStatement(compiledReplacement.sql());var rows=statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal(stableCode)).isEqualByComparingTo("30");assertThat(rows.next()).isFalse();
                }
                assertThat(oldMetric.getExpression()).isNotNull();
                assertThat(changed.getMetrics().size()).isEqualTo(oldCatalog.getMetrics().size());
                if(oldMetric.getDefinitionBinding()!=null) {
                    var before=oldMetric.getDefinitionBinding();var after=changedMetric.getDefinitionBinding();
                    assertThat(after.bindingCode()).isEqualTo(before.bindingCode());
                    assertThat(after.definitionCode()).isEqualTo(before.definitionCode());
                    assertThat(after.definitionRevision()).isEqualTo(before.definitionRevision()+1);
                    assertThat(changed.getSharedDefinitions()).anyMatch(d->before.definitionCode().equals(d.path("code").asText())&&d.path("revision").asInt()==before.definitionRevision());
                    assertThat(oldCatalog.getSharedDefinitions()).noneMatch(d->before.definitionCode().equals(d.path("code").asText())&&d.path("revision").asInt()==after.definitionRevision());
                    // A second candidate must revise the same public definition again, independently of its own content revision.
                    var second=cn.lgs.semevosql.clarification.ProjectDefinitionCatalogMaterializer.materialize(approved,changed);
                    var latest=second.getMetrics().stream().filter(m->m.getMetricCode().equals(stableCode)).findFirst().orElseThrow().getDefinitionBinding();
                    assertThat(latest.definitionCode()).isEqualTo(before.definitionCode());
                    assertThat(latest.definitionRevision()).isEqualTo(after.definitionRevision()+1);
                }
            }
        }
    }

    private void verifyPagination(Connection connection,SqlDialect dialect) throws Exception {
        // Independent expected IDs and a derived-table strategy over the same transaction snapshot.
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try(var statement=connection.createStatement()) {
            statement.execute("INSERT INTO orders(id,paid_amount,order_time) VALUES (3,30,'2026-08-03'),(4,40,'2026-08-04'),(5,50,'2026-08-05')");
            var compiled=compiler.compile(QueryPaginationTest.plan(2),QueryPaginationTest.catalog(),dialect).sources().get(0);
            var ids=new java.util.ArrayList<Long>();
            try(var rows=statement.executeQuery(compiled.sql())) {while(rows.next())ids.add(rows.getLong(1));}
            assertThat(ids).containsExactly(3L,4L);
            var delayed=new java.util.ArrayList<Long>();
            try(var rows=statement.executeQuery("SELECT o.id FROM (SELECT id FROM orders ORDER BY id LIMIT 2 OFFSET 2) p JOIN orders o ON o.id=p.id ORDER BY o.id")) {
                while(rows.next())delayed.add(rows.getLong(1));
            }
            assertThat(delayed).isEqualTo(ids);
        } finally {connection.rollback();connection.setAutoCommit(true);}
    }

	private void prepareFixture(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS orders");
			statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, paid_amount DECIMAL(18,2) NOT NULL, "
					+ "order_time TIMESTAMP NOT NULL)");
			statement.execute("INSERT INTO orders(id, paid_amount, order_time) VALUES "
					+ "(1, 10.00, '2026-08-01 10:00:00'), (2, 20.00, '2026-08-02 11:00:00')");
		}
	}

	private void assertMetadataVisible(Connection connection) throws Exception {
		boolean foundPaidAmount = false;
		try (ResultSet columns = connection.getMetaData().getColumns(null, null, "orders", null)) {
			while (columns.next()) {
				if ("paid_amount".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
					foundPaidAmount = true;
				}
			}
		}
		assertThat(foundPaidAmount).isTrue();
	}

	private SemanticCatalogSnapshot catalog() {
		return SemanticCatalogSnapshot.builder()
			.models(List.of(SemanticCatalogSnapshot.Model.builder()
				.modelCode("orders")
				.physicalTable("orders")
				.datasourceId(1)
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.columns(List.of(SemanticCatalogSnapshot.Column.builder()
				.modelCode("orders")
				.columnName("paid_amount")
				.allowProjection(true)
				.allowAggregation(true)
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.metrics(List.of(SemanticCatalogSnapshot.Metric.builder()
				.modelCode("orders")
                .metricCode("paid_total")
                .businessName("Payment total")
				.expression("paid_amount")
				.aggregation("SUM")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.build();
	}

	private SemanticBlueprint blueprint() {
		return SemanticBlueprint.builder()
			.canonicalQuery("governed paid amount total")
			.compilerMode("DETERMINISTIC")
			.models(List.of(SemanticBlueprint.ModelSelection.builder()
				.modelCode("orders")
				.physicalTable("orders")
				.datasourceId(1)
				.build()))
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("paid_total")
				.modelCode("orders")
				.expression("paid_amount")
				.aggregation("SUM")
				.build()))
			.projections(List.of(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode("orders")
				.expression("SUM(paid_amount)")
				.alias("paid_total")
				.projectionType("METRIC")
				.build()))
			.sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder()
				.datasourceId(1)
				.modelCodes(List.of("orders"))
				.physicalTables(List.of("orders"))
				.build()))
			.limit(100)
			.executable(true)
			.validationErrors(List.of())
			.build();
	}

	private void bind(PreparedStatement statement, List<Object> parameters) throws Exception {
		for (int i = 0; i < parameters.size(); i++) {
			Object value = parameters.get(i);
			if (value instanceof BigDecimal decimal) {
				statement.setBigDecimal(i + 1, decimal);
			}
			else {
				statement.setObject(i + 1, value);
			}
		}
	}
}
