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
package cn.lgs.semevosql.multisource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.SqlTraceRequest;
import cn.lgs.semevosql.semantic.application.SemanticCatalogReadService;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.nl2sql.Nl2SqlService;
import cn.lgs.semevosql.sql.application.SensitiveResultSanitizer;
import cn.lgs.semevosql.sql.application.SqlCostGuard;
import cn.lgs.semevosql.sql.application.SqlExecutionAdmissionControl;
import cn.lgs.semevosql.sql.application.SqlExecutionGuard;
import cn.lgs.semevosql.sql.application.SqlPreflightPlanner;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.util.DatabaseUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MultiSourceSqlExecutionServiceTest {

	@Test
	void deterministicGovernedExecutionPersistsGuardExplainPreviewAndResultTrace() throws Exception {
		DatabaseUtil databaseUtil = mock(DatabaseUtil.class);
		Nl2SqlService nl2SqlService = mock(Nl2SqlService.class);
		SemEvoSQLProperties properties = new SemEvoSQLProperties();
		SqlExecutionGuard executionGuard = mock(SqlExecutionGuard.class);
		SqlExecutionAdmissionControl admissionControl = mock(SqlExecutionAdmissionControl.class);
		SqlCostGuard costGuard = mock(SqlCostGuard.class);
		SqlPreflightPlanner preflightPlanner = mock(SqlPreflightPlanner.class);
		SensitiveResultSanitizer sanitizer = mock(SensitiveResultSanitizer.class);
		SqlResultValidator resultValidator = mock(SqlResultValidator.class);
		SemanticCatalogReadService catalogCache = mock(SemanticCatalogReadService.class);
		SemEvoSQLProductionService productionService = mock(SemEvoSQLProductionService.class);
		MultiSourceSqlExecutionService service = new MultiSourceSqlExecutionService(databaseUtil, nl2SqlService, properties,
				executionGuard, admissionControl, costGuard, preflightPlanner, sanitizer, resultValidator, catalogCache,
				productionService, new cn.lgs.semevosql.sql.application.SqlExecutionAttemptService(
                    mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(org.springframework.transaction.PlatformTransactionManager.class),
                    mock(cn.lgs.semevosql.run.RunExecutionFenceService.class)));

		String sql = "SELECT province, SUM(amount) AS paid_amount FROM orders GROUP BY province";
		DbConfigBO dbConfig = DbConfigBO.builder().schema("trade").dialectType("mysql").build();
		Accessor accessor = mock(Accessor.class);
		SqlExecutionAdmissionControl.Permit permit = mock(SqlExecutionAdmissionControl.Permit.class);
		SemanticBlueprint plan = SemanticBlueprint.builder().compilerMode("DETERMINISTIC").build();
		ResultSetBO explain = result("EXPLAIN", "{}");
		ResultSetBO preview = result("province", "A");
		ResultSetBO finalResult = result("province", "A");
		SqlCostGuard.CostAssessment staticCost = new SqlCostGuard.CostAssessment(1, 0, 0, 0, 0, 0, 0, false,
				List.of(), List.of());
		SqlCostGuard.CostAssessment explainCost = new SqlCostGuard.CostAssessment(1, 10, 5, 0, 5, 5, 12.5, false,
				List.of("AGGREGATE"), List.of());

		when(nl2SqlService.sqlTrim(sql)).thenReturn(sql);
		when(databaseUtil.getDatasourceDbConfig(7)).thenReturn(dbConfig);
		when(databaseUtil.getDatasourceAccessor(7)).thenReturn(accessor);
		when(catalogCache.getForModels(eq(1L), eq(2L), anySet())).thenReturn(SemanticCatalogSnapshot.builder().build());
		when(admissionControl.acquire(1L, 7, "principal")).thenReturn(permit);
		when(executionGuard.validate(sql, "mysql", Set.of("orders"), "trade"))
			.thenReturn(new SqlExecutionGuard.GuardResult("mysql", Set.of("orders")));
		when(costGuard.validateSql(eq(sql), eq(Set.of("orders")), anySet(), eq(properties.getSqlExecution())))
			.thenReturn(staticCost);
		when(preflightPlanner.explainSql(sql, "mysql")).thenReturn(Optional.of("EXPLAIN FORMAT=JSON " + sql));
		when(accessor.executeSqlAndReturnObject(eq(dbConfig), any())).thenReturn(explain, preview, finalResult);
		when(costGuard.validateExplain(explain, 1, properties.getSqlExecution(), "mysql")).thenReturn(explainCost);
		when(resultValidator.validate(finalResult, plan, properties.getSqlExecution().getMaxRows()))
			.thenReturn(SqlResultValidator.ValidationResult.accepted(List.of()));

		ResultSetBO result = service.execute(1L, 2L, "principal", "run:source:1", 7, Set.of("orders"), sql, List.of(),
				plan, "attempt-1", "semantic-source:key:subrun-1");

		assertEquals(finalResult, result);
		ArgumentCaptor<SqlTraceRequest> trace = ArgumentCaptor.forClass(SqlTraceRequest.class);
		verify(productionService).recordSqlTrace(eq("attempt-1"), trace.capture());
		assertEquals("SUCCEEDED", trace.getValue().status());
		assertEquals(Set.of("orders"), trace.getValue().guardSummary().get("referencedTables"));
		assertEquals("DETERMINISTIC", trace.getValue().explainSummary().get("compilerMode"));
		assertEquals(10L, trace.getValue().explainSummary().get("estimatedScanRows"));
		assertEquals("PASS", trace.getValue().previewSummary().get("decision"));
		assertEquals("PASS", trace.getValue().resultSummary().get("decision"));
	}

	private ResultSetBO result(String column, String value) {
		return ResultSetBO.builder().column(List.of(column)).data(List.of(Map.of(column, value))).build();
	}

}
