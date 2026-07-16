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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergeType;
import cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler.ConstrainedGenerationRequiredException;
import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticSqlCompilerCapabilityTest {

	private final SemanticSqlCompiler compiler = new SemanticSqlCompiler();

	@Test
	void advancedAnalyticIntentEscalatesByDeclaredCapabilityInsteadOfNaturalLanguageKeyword() {
		for (Capability capability : List.of(Capability.PERIOD_COMPARISON, Capability.WINDOW_ANALYTICS,
				Capability.PARTITION_RANKING)) {
			SemanticBlueprint plan = SemanticBlueprint.builder()
				.canonicalQuery("language independent governed computation")
				.compilerMode("DETERMINISTIC")
				.computationIntent(new ComputationIntent(java.util.Set.of(capability)))
				.executable(true)
				.validationErrors(List.of())
				.build();

			ConstrainedGenerationRequiredException error = assertThrows(ConstrainedGenerationRequiredException.class,
					() -> compiler.compile(plan, SemanticCatalogSnapshot.builder().build(), Map.of(), Clock.systemUTC(),
							ZoneId.of("UTC")));

			assertTrue(error.getMessage().contains(capability.name()));
		}
	}

	@Test
	void queryWordingNeverChangesLoweringCapabilityDecision() {
		SemanticBlueprint keywordPlan = SemanticBlueprint.builder()
			.canonicalQuery("同比 环比 LAG rolling 留存 every week")
			.compilerMode("DETERMINISTIC")
			.executable(true)
			.validationErrors(List.of())
			.projections(List.of(SemanticBlueprint.ProjectionSelection.builder()
				.modelCode("orders").columnName("id").alias("id").projectionType("DIMENSION").build()))
			.sourceSubPlans(List.of(SemanticBlueprint.SourceSubPlan.builder().datasourceId(1)
				.modelCodes(List.of("orders")).physicalTables(List.of("orders")).build()))
			.build();
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("orders").physicalTable("orders")
				.datasourceId(1).status(SemanticAssetStatus.ENABLED).build()))
			.columns(List.of(SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName("id")
				.allowProjection(true).status(SemanticAssetStatus.ENABLED).build()))
			.build();

		CompiledSemanticQuery compiled = compiler.compile(keywordPlan, catalog, Map.of(1, SqlDialect.MYSQL),
				Clock.systemUTC(), ZoneId.of("UTC"));

		assertEquals(1, compiled.sources().size());
	}

	@Test
	void crossSourceMergeRelationshipIsNeverCompiledAsPhysicalJoin() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.models(List.of(
					SemanticCatalogSnapshot.Model.builder().modelCode("left_model").physicalTable("left_table")
						.datasourceId(1).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Model.builder().modelCode("right_model").physicalTable("right_table")
						.datasourceId(2).status(SemanticAssetStatus.ENABLED).build()))
			.columns(List.of(
					SemanticCatalogSnapshot.Column.builder().modelCode("left_model").columnName("left_value")
						.allowProjection(true).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Column.builder().modelCode("right_model").columnName("right_value")
						.allowProjection(true).status(SemanticAssetStatus.ENABLED).build()))
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.canonicalQuery("combine governed cross-source values")
			.compilerMode("DETERMINISTIC")
			.models(List.of(
					SemanticBlueprint.ModelSelection.builder().modelCode("left_model").physicalTable("left_table")
						.datasourceId(1).build(),
					SemanticBlueprint.ModelSelection.builder().modelCode("right_model").physicalTable("right_table")
						.datasourceId(2).build()))
			.projections(List.of(
					SemanticBlueprint.ProjectionSelection.builder().modelCode("left_model").columnName("left_value")
						.alias("left_value").projectionType("DIMENSION").build(),
					SemanticBlueprint.ProjectionSelection.builder().modelCode("right_model").columnName("right_value")
						.alias("right_value").projectionType("DIMENSION").build()))
			.relationships(List.of(SemanticBlueprint.RelationshipSelection.builder()
				.relationshipCode("governed_cross_source")
				.sourceModelCode("left_model")
				.targetModelCode("right_model")
				.joinType("CROSS_SOURCE_MERGE")
				.joinCondition("left_model.left_id = right_model.right_id")
				.build()))
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left_model"))
						.physicalTables(List.of("left_table")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right_model"))
						.physicalTables(List.of("right_table")).build()))
			.executable(true)
			.validationErrors(List.of())
			.build();

		CompiledSemanticQuery compiled = compiler.compile(plan, catalog,
				Map.of(1, SqlDialect.MYSQL, 2, SqlDialect.POSTGRESQL), Clock.systemUTC(), ZoneId.of("UTC"));

		assertEquals(2, compiled.sources().size());
		for (CompiledSemanticQuery.CompiledSourceQuery source : compiled.sources()) {
			assertFalse(source.sql().toUpperCase().contains(" JOIN "));
		}
	}

	@Test
	void governedMergeKeysAreCompiledAsInternalGroupedInputsWithoutOpeningUserProjection() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.models(List.of(
					SemanticCatalogSnapshot.Model.builder().modelCode("left_model").physicalTable("left_table")
						.datasourceId(1).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Model.builder().modelCode("right_model").physicalTable("right_table")
						.datasourceId(2).status(SemanticAssetStatus.ENABLED).build()))
			.columns(List.of(
					SemanticCatalogSnapshot.Column.builder().modelCode("left_model").columnName("left_id")
						.allowProjection(false).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Column.builder().modelCode("left_model").columnName("left_value")
						.allowProjection(true).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Column.builder().modelCode("right_model").columnName("right_id")
						.allowProjection(false).status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Column.builder().modelCode("right_model").columnName("right_value")
						.allowProjection(true).status(SemanticAssetStatus.ENABLED).build()))
			.dimensions(List.of(
					SemanticCatalogSnapshot.Dimension.builder().modelCode("left_model").dimensionCode("left_merge_key")
						.columnName("left_id").status(SemanticAssetStatus.ENABLED).build(),
					SemanticCatalogSnapshot.Dimension.builder().modelCode("right_model").dimensionCode("right_merge_key")
						.columnName("right_id").status(SemanticAssetStatus.ENABLED).build()))
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.canonicalQuery("aggregate through governed merge")
			.compilerMode("DETERMINISTIC")
			.models(List.of(
					SemanticBlueprint.ModelSelection.builder().modelCode("left_model").physicalTable("left_table")
						.datasourceId(1).build(),
					SemanticBlueprint.ModelSelection.builder().modelCode("right_model").physicalTable("right_table")
						.datasourceId(2).build()))
			.metrics(List.of(
					SemanticBlueprint.MetricSelection.builder().metricCode("left_total").modelCode("left_model")
						.aggregation("SUM").expression("left_value").build(),
					SemanticBlueprint.MetricSelection.builder().metricCode("right_total").modelCode("right_model")
						.aggregation("SUM").expression("right_value").build()))
			.projections(List.of(
					SemanticBlueprint.ProjectionSelection.builder().modelCode("left_model").expression("SUM(left_value)")
						.alias("left_total").projectionType("METRIC").build(),
					SemanticBlueprint.ProjectionSelection.builder().modelCode("right_model").expression("SUM(right_value)")
						.alias("right_total").projectionType("METRIC").build()))
			.relationships(List.of(SemanticBlueprint.RelationshipSelection.builder()
				.relationshipCode("governed_cross_source")
				.sourceModelCode("left_model")
				.targetModelCode("right_model")
				.joinType("CROSS_SOURCE_MERGE")
				.joinCondition("left_model.left_id = right_model.right_id")
				.build()))
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left_model"))
						.physicalTables(List.of("left_table")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right_model"))
						.physicalTables(List.of("right_table")).build()))
			.mergePlan(SemanticBlueprint.MergePlan.builder()
				.policyCode("merge_policy")
				.mergeType(MergeType.LOOKUP_ENRICHMENT)
				.relationshipCode("governed_cross_source")
				.leftInputKey("left_merge_key")
				.rightInputKey("right_merge_key")
				.outputKey("internal_join_key")
				.maxRows(1000)
				.build())
			.limit(100)
			.executable(true)
			.validationErrors(List.of())
			.build();

		CompiledSemanticQuery compiled = compiler.compile(plan, catalog,
				Map.of(1, SqlDialect.MYSQL, 2, SqlDialect.MYSQL), Clock.systemUTC(), ZoneId.of("UTC"));

		String leftSql = compiled.sources().stream().filter(source -> source.datasourceId() == 1).findFirst().orElseThrow().sql();
		String rightSql = compiled.sources().stream().filter(source -> source.datasourceId() == 2).findFirst().orElseThrow().sql();
		assertTrue(leftSql.contains("`left_id` AS `left_merge_key`"));
		assertTrue(leftSql.contains("GROUP BY t0.`left_id`"));
		assertTrue(rightSql.contains("`right_id` AS `right_merge_key`"));
		assertTrue(rightSql.contains("GROUP BY t0.`right_id`"));
		assertFalse(leftSql.toUpperCase().contains(" JOIN "));
		assertFalse(rightSql.toUpperCase().contains(" JOIN "));
	}
}
