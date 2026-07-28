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
package cn.lgs.semevosql.service.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.connector.accessor.AccessorFactory;
import cn.lgs.semevosql.connector.ddl.Ddl;
import cn.lgs.semevosql.connector.ddl.DdlFactory;
import cn.lgs.semevosql.connector.pool.DBConnectionPool;
import cn.lgs.semevosql.connector.pool.DBConnectionPoolFactory;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.exception.InvalidInputException;
import cn.lgs.semevosql.service.datasource.handler.impl.MysqlDatasourceTypeHandler;
import cn.lgs.semevosql.service.datasource.handler.impl.OracleDatasourceTypeHandler;
import cn.lgs.semevosql.service.datasource.handler.impl.PostgreSqlDatasourceTypeHandler;
import cn.lgs.semevosql.service.datasource.handler.registry.DatasourceTypeHandlerRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticQueryDatasourceCapabilitiesTest {

	@Test
	void onlyEndToEndSupportedDatasourceTypesAreAdvertised() {
		assertThat(SemanticQueryDatasourceCapabilities.supportedTypes())
			.containsExactly(BizDataSourceTypeEnum.MYSQL, BizDataSourceTypeEnum.POSTGRESQL);
		assertThat(SemanticQueryDatasourceCapabilities.supports("mysql")).isTrue();
		assertThat(SemanticQueryDatasourceCapabilities.supports("postgresql")).isTrue();
	}

	@Test
	void runtimeHandlerRegistryCannotAccidentallyReExposeLegacyConnectorTypes() {
		DatasourceTypeHandlerRegistry registry = new DatasourceTypeHandlerRegistry(List.of(new MysqlDatasourceTypeHandler(),
				new PostgreSqlDatasourceTypeHandler(), new OracleDatasourceTypeHandler()));

		assertThat(registry.isRegistered("mysql")).isTrue();
		assertThat(registry.isRegistered("postgresql")).isTrue();
		assertThat(registry.isRegistered("oracle")).isFalse();
	}

	@Test
	void connectorFactoriesCannotAccidentallyRegisterLegacyImplementations() {
		Accessor oracleAccessor = mock(Accessor.class);
		when(oracleAccessor.getAccessorType()).thenReturn("Oracle_Accessor");
		when(oracleAccessor.supportedDataSourceType("oracle")).thenReturn(true);
		AccessorFactory accessorFactory = new AccessorFactory(List.of(oracleAccessor));

		Ddl oracleDdl = mock(Ddl.class);
		when(oracleDdl.getDataSourceType()).thenReturn(BizDataSourceTypeEnum.ORACLE);
		DdlFactory ddlFactory = new DdlFactory(List.of(oracleDdl));

		DBConnectionPool oraclePool = mock(DBConnectionPool.class);
		when(oraclePool.getConnectionPoolType()).thenReturn("oracle");
		DBConnectionPoolFactory poolFactory = new DBConnectionPoolFactory(List.of(oraclePool));

		assertThat(accessorFactory.isRegistered("Oracle_Accessor")).isFalse();
		assertThatThrownBy(() -> ddlFactory.getDdlExecutorByDbType(BizDataSourceTypeEnum.ORACLE))
			.isInstanceOf(IllegalStateException.class);
		assertThat(poolFactory.isRegistered("oracle")).isFalse();
	}

	@Test
	void connectOnlyDialectsAreRejectedUntilSemanticCompilerSupportsThem() {
		for (String type : new String[] { "dameng", "sqlserver", "oracle", "hive" }) {
			assertThatThrownBy(() -> SemanticQueryDatasourceCapabilities.requireSupported(type))
				.isInstanceOf(InvalidInputException.class)
				.hasMessage("当前版本仅支持 MySQL 和 PostgreSQL 数据源的端到端问数能力");
		}
	}

}
