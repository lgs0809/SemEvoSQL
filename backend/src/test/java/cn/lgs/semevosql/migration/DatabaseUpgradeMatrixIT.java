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
package cn.lgs.semevosql.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * Real PostgreSQL upgrade matrix. This class intentionally ends with {@code IT} so the normal
 * unit-test suite does not require Docker/PostgreSQL. CI invokes it explicitly after starting an
 * ephemeral PostgreSQL instance.
 */
class DatabaseUpgradeMatrixIT {

	private static final List<String> CHECKPOINTS = List.of("1", "8", "18", "28");

	private static final String MIGRATION_LOCATION = "classpath:db/migration/postgresql";

	@Test
	void historicalSchemaCheckpointsUpgradeToCurrentRelease() throws Exception {
		String jdbcUrl = required("SEMEVOSQL_UPGRADE_MATRIX_JDBC_URL");
		String user = required("SEMEVOSQL_UPGRADE_MATRIX_DB_USER");
		String password = System.getenv().getOrDefault("SEMEVOSQL_UPGRADE_MATRIX_DB_PASSWORD", "");

		for (String checkpoint : CHECKPOINTS) {
			String schema = "upgrade_v" + checkpoint;
			resetSchema(jdbcUrl, user, password, schema);

			Flyway.configure()
				.dataSource(jdbcUrl, user, password)
				.locations(MIGRATION_LOCATION)
				.schemas(schema)
				.defaultSchema(schema)
				.createSchemas(true)
				.target(MigrationVersion.fromVersion(checkpoint))
				.load()
				.migrate();

			assertThat(appliedVersion(jdbcUrl, user, password, schema)).isEqualTo(Integer.parseInt(checkpoint));

			Flyway.configure()
				.dataSource(jdbcUrl, user, password)
				.locations(MIGRATION_LOCATION)
				.schemas(schema)
				.defaultSchema(schema)
				.createSchemas(true)
				.load()
				.migrate();

			assertLatestSchema(jdbcUrl, user, password, schema);
		}
	}

	private void assertLatestSchema(String jdbcUrl, String user, String password, String schema) throws Exception {
		assertThat(appliedVersion(jdbcUrl, user, password, schema))
			.isEqualTo(latestKnownVersion(jdbcUrl, user, password, schema));
		assertThat(tableExists(jdbcUrl, user, password, schema, "qw_semantic_change_set")).isTrue();
		assertThat(tableExists(jdbcUrl, user, password, schema, "qw_episode_turn")).isTrue();
		assertThat(tableExists(jdbcUrl, user, password, schema, "qw_query_case_binding_dependency")).isTrue();
		assertThat(tableExists(jdbcUrl, user, password, schema, "qw_semantic_sql_pattern")).isTrue();
		assertThat(columnExists(jdbcUrl, user, password, schema, "qw_project_version", "semantic_major")).isTrue();
		assertThat(columnExists(jdbcUrl, user, password, schema, "qw_external_query_handle", "conversation_id")).isTrue();
		assertThat(columnExists(jdbcUrl, user, password, schema, "qw_query_run", "deadline_epoch_millis")).isTrue();
		assertThat(indexExists(jdbcUrl, user, password, schema, "uk_qw_project_version_semver")).isTrue();
	}

	private int latestKnownVersion(String jdbcUrl, String user, String password, String schema) {
		Flyway flyway = Flyway.configure()
			.dataSource(jdbcUrl, user, password)
			.locations(MIGRATION_LOCATION)
			.schemas(schema)
			.defaultSchema(schema)
			.load();
		return Arrays.stream(flyway.info().all())
			.map(MigrationInfo::getVersion)
			.filter(java.util.Objects::nonNull)
			.mapToInt(version -> Integer.parseInt(version.getVersion()))
			.max()
			.orElseThrow(() -> new IllegalStateException("No versioned PostgreSQL migrations were discovered"));
	}

	private int appliedVersion(String jdbcUrl, String user, String password, String schema) throws Exception {
		String sql = "SELECT MAX(version::integer) FROM " + quoted(schema) + ".flyway_schema_history WHERE success = true";
		try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private boolean tableExists(String jdbcUrl, String user, String password, String schema, String table) throws Exception {
		return exists(jdbcUrl, user, password,
				"SELECT 1 FROM information_schema.tables WHERE table_schema = '" + literal(schema)
						+ "' AND table_name = '" + literal(table) + "'");
	}

	private boolean columnExists(String jdbcUrl, String user, String password, String schema, String table, String column)
			throws Exception {
		return exists(jdbcUrl, user, password,
				"SELECT 1 FROM information_schema.columns WHERE table_schema = '" + literal(schema)
						+ "' AND table_name = '" + literal(table) + "' AND column_name = '" + literal(column) + "'");
	}

	private boolean indexExists(String jdbcUrl, String user, String password, String schema, String index) throws Exception {
		return exists(jdbcUrl, user, password,
				"SELECT 1 FROM pg_indexes WHERE schemaname = '" + literal(schema) + "' AND indexname = '"
						+ literal(index) + "'");
	}

	private boolean exists(String jdbcUrl, String user, String password, String sql) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			return result.next();
		}
	}

	private void resetSchema(String jdbcUrl, String user, String password, String schema) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
				Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS " + quoted(schema) + " CASCADE");
		}
	}

	private String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required for the database upgrade matrix");
		}
		return value;
	}

	private String quoted(String identifier) {
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

	private String literal(String value) {
		return value.replace("'", "''");
	}

}
