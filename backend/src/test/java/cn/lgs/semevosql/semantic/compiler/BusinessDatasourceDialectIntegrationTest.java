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
