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
package cn.lgs.semevosql.trajectory;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import org.junit.jupiter.api.Test;

class TrajectoryAnalysisServiceTest {

	@Test
	void ordinarySqlTimeJoinAndWindowFailuresStayExecutionDefects() {
		assertThat(TrajectoryAnalysisService.classifySqlIssue("DATE_FORMAT syntax error near WEEK"))
			.isEqualTo(SemanticIssueType.LLM_SQL_GENERATION_DEFECT);
		assertThat(TrajectoryAnalysisService.classifySqlIssue("unknown column in JOIN alias"))
			.isEqualTo(SemanticIssueType.LLM_SQL_GENERATION_DEFECT);
		assertThat(TrajectoryAnalysisService.classifySqlIssue("QUERY_COST_EXCEEDED sort rows"))
			.isEqualTo(SemanticIssueType.LLM_SQL_GENERATION_DEFECT);
	}

	@Test
	void onlyExplicitGovernedSemanticCodesCanPromoteSqlRepair() {
		assertThat(TrajectoryAnalysisService.classifySqlIssue("SEMANTIC_METRIC_NOT_FOUND: revenue"))
			.isEqualTo(SemanticIssueType.METRIC_MISSING);
		assertThat(TrajectoryAnalysisService.classifySqlIssue("SEMANTIC_RELATIONSHIP_NOT_FOUND: order_customer"))
			.isEqualTo(SemanticIssueType.RELATIONSHIP_MISSING);
		assertThat(TrajectoryAnalysisService.classifySqlIssue("ENUM_MAPPING_MISSING: status"))
			.isEqualTo(SemanticIssueType.ENUM_MAPPING_MISSING);
	}

	@Test
	void exactSemanticPlanEventCanRestoreBlueprintWhenInitialExecutionSnapshotHadNoPlan() {
		String payload = """
				{"canonicalQuery":"monthly growth","executable":true,"sourceSubPlans":[{"datasourceId":2,"modelCodes":["orders"],"physicalTables":["orders"]}]}
				""";

		var restored = TrajectoryAnalysisService.decodeSemanticPlanSnapshot(payload);

		assertThat(restored).isPresent();
		assertThat(restored.orElseThrow().getCanonicalQuery()).isEqualTo("monthly growth");
		assertThat(restored.orElseThrow().getSourceSubPlans()).hasSize(1);
		assertThat(TrajectoryAnalysisService.decodeSemanticPlanSnapshot("not-json")).isEmpty();
	}
}
