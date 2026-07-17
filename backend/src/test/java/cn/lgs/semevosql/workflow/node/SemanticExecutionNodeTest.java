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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.ADVANCED_EXECUTION_FALLBACK;
import static cn.lgs.semevosql.constant.Constant.FORCED_DATASOURCE_ID;
import static cn.lgs.semevosql.constant.Constant.FORCED_PHYSICAL_TABLES;
import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticExecutionNodeTest {

	@Test
	void advancedFallbackPinsSingleSelectedSourceInsideMultiSourceProject() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder()
				.datasourceId(2)
				.physicalTables(List.of("orders"))
				.build()))
			.build();

		Map<String, Object> fallback = SemanticExecutionNode.advancedFallback(plan);

		assertThat(fallback).containsEntry(ADVANCED_EXECUTION_FALLBACK, true)
			.containsEntry(FORCED_DATASOURCE_ID, 2);
		assertThat(fallback.get(FORCED_PHYSICAL_TABLES)).isEqualTo(List.of("orders"));
	}

	@Test
	void advancedFallbackDoesNotInventSingleSourceForTrueMultiSourcePlan() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).physicalTables(List.of("orders")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).physicalTables(List.of("payments")).build()))
			.build();

		Map<String, Object> fallback = SemanticExecutionNode.advancedFallback(plan);

		assertThat(fallback).containsEntry(ADVANCED_EXECUTION_FALLBACK, true)
			.doesNotContainKeys(FORCED_DATASOURCE_ID, FORCED_PHYSICAL_TABLES);
	}
    @org.junit.jupiter.api.Test
    void compiledTimeoutKeepsFailedSqlAndForcesConstrainedAdjustment() throws Exception {
        var service=org.mockito.Mockito.mock(cn.lgs.semevosql.semantic.application.VerifiedQueryExecutionService.class);
        var failure=new cn.lgs.semevosql.semantic.application.SourceSqlExecutionException("SELECT amount FROM orders", "trace-1",
            new java.sql.SQLException("statement timeout","57014"));
        org.mockito.Mockito.when(service.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("frozen-catalog-hash"))).thenThrow(failure);
        var node=new SemanticExecutionNode(service,new cn.lgs.semevosql.sql.application.SqlValidationClassifier(),new cn.lgs.semevosql.review.QueryRepairPolicy());
        var plan=SemanticBlueprint.builder().executable(true).sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).physicalTables(List.of("orders")).build())).build();
        var state=new com.alibaba.cloud.ai.graph.OverAllState(Map.of(cn.lgs.semevosql.constant.Constant.CATALOG_HASH,"frozen-catalog-hash",cn.lgs.semevosql.constant.Constant.PROJECT_ID,2L,cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID,2L,cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN,plan));
        var update=node.apply(state);
        assertThat(update).containsEntry(FORCED_DATASOURCE_ID,2).containsEntry(cn.lgs.semevosql.constant.Constant.SQL_GENERATE_OUTPUT,failure.sql());
        var retry=(cn.lgs.semevosql.dto.datasource.SqlRetryDto)update.get(cn.lgs.semevosql.constant.Constant.SQL_REGENERATE_REASON);
        assertThat(retry.sqlExecuteFail()).isTrue();assertThat(retry.reason()).contains("SQL_TIMEOUT","trace-1","57014");
        var budget=(cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget)update.get(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET);
        assertThat(budget.sqlRepairsUsed()).isEqualTo(1);
        var twoUsed=new cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget(2,0,0,0,0,2);
        state.updateState(Map.of(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET,twoUsed));
        var replanned=node.apply(state);
        assertThat(replanned).containsKey(cn.lgs.semevosql.constant.Constant.PLAN_VALIDATION_ERROR);
        assertThat(((cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget)replanned.get(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET)).semanticReplansUsed()).isEqualTo(1);
        state.updateState(Map.of(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET,new cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget(2,2,0,0,0,4)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->node.apply(state));
    }

    @org.junit.jupiter.api.Test
    void uncertainCleanupCannotTriggerCompiledFallback() throws Exception {
        var service=org.mockito.Mockito.mock(cn.lgs.semevosql.semantic.application.VerifiedQueryExecutionService.class);
        var failure=new cn.lgs.semevosql.semantic.application.SourceSqlExecutionException("SELECT amount FROM orders","trace-2",
            new cn.lgs.semevosql.connector.JdbcQueryCleanupException(new java.sql.SQLException("connection lost","08006")));
        org.mockito.Mockito.when(service.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("frozen-catalog-hash"))).thenThrow(failure);
        var node=new SemanticExecutionNode(service,new cn.lgs.semevosql.sql.application.SqlValidationClassifier(),new cn.lgs.semevosql.review.QueryRepairPolicy());
        var state=new com.alibaba.cloud.ai.graph.OverAllState(Map.of(cn.lgs.semevosql.constant.Constant.CATALOG_HASH,"frozen-catalog-hash",cn.lgs.semevosql.constant.Constant.PROJECT_ID,2L,cn.lgs.semevosql.constant.Constant.PROJECT_VERSION_ID,2L,cn.lgs.semevosql.constant.Constant.TYPED_SEMANTIC_PLAN,SemanticBlueprint.builder().executable(true).build()));
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,()->node.apply(state))).isSameAs(failure);
    }

    @org.junit.jupiter.api.Test
    void executionIdentitySurvivesIndependentJvmAndDtoRoundTrips() throws Exception {
        var identities=new java.util.HashSet<String>();var legacy=new java.util.HashSet<String>();
        for(int i=0;i<8;i++) {
            var log=java.nio.file.Files.createTempFile("semantic-key-probe-",".log");
            try {
                var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin/java").toString(),
                    "-cp",System.getProperty("surefire.test.class.path"),SemanticExecutionKeyProbe.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();}
                finally {if(process.isAlive())process.destroyForcibly();}
                var lines=java.nio.file.Files.readAllLines(log).stream().filter(line->line.startsWith("simple:")).toList();
                assertThat(lines).hasSize(3);identities.addAll(lines);
                java.nio.file.Files.readAllLines(log).stream().filter(line->line.startsWith("legacy:")).forEach(legacy::add);
            }finally {java.nio.file.Files.deleteIfExists(log);}
        }
        assertThat(identities).hasSize(1);
        System.out.println("CROSS_JVM_IDENTITY processes=8 legacyDistinct="+legacy.size()+" canonicalDistinct="+identities.size()+" checkpointRoundTrip=true");
        var state=new com.alibaba.cloud.ai.graph.OverAllState(Map.of());
        var plan=SemanticExecutionKeyProbe.plan();String before=SemanticExecutionNode.executionKey(state,plan);
        state.updateState(Map.of(cn.lgs.semevosql.constant.Constant.QUERY_REPAIR_BUDGET,
            new cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget(1,0,0,0,0,1)));
        assertThat(SemanticExecutionNode.executionKey(state,plan)).isNotEqualTo(before);
    }

}
