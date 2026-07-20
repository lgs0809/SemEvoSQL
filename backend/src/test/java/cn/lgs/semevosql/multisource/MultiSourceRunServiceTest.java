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

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiSourceRunServiceTest {

	@Test
	void countDistinctCanBeRecombinedWhenDistinctColumnIsUniqueGrain() {
		SemanticBlueprint.MetricSelection metric = SemanticBlueprint.MetricSelection.builder()
			.metricCode("order_count")
			.modelCode("pay_order")
			.expression("COUNT(DISTINCT order_id)")
			.aggregation("COUNT_DISTINCT")
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.metrics(List.of(metric))
			.grains(List.of(SemanticBlueprint.GrainSelection.builder()
				.grainCode("one_row_per_order")
				.modelCode("pay_order")
				.keyColumns("order_id")
				.uniquenessRule("UNIQUE(order_id)")
				.build()))
			.build();

		assertThat(MultiSourceRunService.canSafelyRecombineCountDistinct(plan, metric)).isTrue();
	}

	@Test
	void countDistinctFailsClosedWithoutMatchingUniqueGrain() {
		SemanticBlueprint.MetricSelection metric = SemanticBlueprint.MetricSelection.builder()
			.metricCode("customer_count")
			.modelCode("pay_order")
			.expression("COUNT(DISTINCT customer_id)")
			.aggregation("COUNT_DISTINCT")
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.metrics(List.of(metric))
			.grains(List.of(SemanticBlueprint.GrainSelection.builder()
				.grainCode("one_row_per_order")
				.modelCode("pay_order")
				.keyColumns("order_id")
				.uniquenessRule("UNIQUE(order_id)")
				.build()))
			.build();

		assertThat(MultiSourceRunService.canSafelyRecombineCountDistinct(plan, metric)).isFalse();
	}

	@Test
	void countDistinctFailsClosedForCompositeGrain() {
		SemanticBlueprint.MetricSelection metric = SemanticBlueprint.MetricSelection.builder()
			.metricCode("order_count")
			.modelCode("pay_order")
			.expression("COUNT(DISTINCT order_id)")
			.aggregation("COUNT_DISTINCT")
			.build();
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.metrics(List.of(metric))
			.grains(List.of(SemanticBlueprint.GrainSelection.builder()
				.grainCode("composite")
				.modelCode("pay_order")
				.keyColumns("order_id,user_id")
				.uniquenessRule("UNIQUE(order_id,user_id)")
				.build()))
			.build();

		assertThat(MultiSourceRunService.canSafelyRecombineCountDistinct(plan, metric)).isFalse();
	}
}
