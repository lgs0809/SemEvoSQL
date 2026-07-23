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
package cn.lgs.semevosql.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticResultReviewerTest {

	@Test
	void advancedReviewerPlanDropsPlannerOwnedShapeButKeepsGovernedSemantics() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("effective_paid_amount")
				.modelCode("orders")
				.expression("paid_amount - refund_amount")
				.aggregation("SUM")
				.build()))
			.groupBy(List.of(SemanticBlueprint.GroupSelection.builder()
				.modelCode("orders")
				.columnName("paid_at")
				.expression("DATE(paid_at)")
				.alias("paid_at_day")
				.build()))
			.orderBy(List.of(SemanticBlueprint.OrderSelection.builder()
				.expression("effective_paid_amount")
				.direction("DESC")
				.build()))
			.limit(1)
			.expectedResult(SemanticBlueprint.ExpectedResultShape.builder().maxRows(1).tabular(true).build())
			.build();

		var advanced = JsonUtil.getObjectMapper().valueToTree(SemanticResultReviewer.reviewerPlan(plan, true));
		assertTrue(advanced.has("metrics"));
		assertTrue(advanced.has("groupBy"));
		assertFalse(advanced.has("orderBy"));
		assertFalse(advanced.has("limit"));
		assertFalse(advanced.has("expectedResult"));

		var strict = JsonUtil.getObjectMapper().valueToTree(SemanticResultReviewer.reviewerPlan(plan, false));
		assertTrue(strict.has("orderBy"));
		assertTrue(strict.has("limit"));
		assertTrue(strict.has("expectedResult"));
	}

}
