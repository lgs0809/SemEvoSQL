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
}
