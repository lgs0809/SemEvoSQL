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
package cn.lgs.semevosql.learning;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Requirement;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidatedSemanticSqlPatternServiceTest {

	@Test
	void storedAdvancedPatternShapeRemovesRuntimeLiterals() {
		String shape = ValidatedSemanticSqlPatternService.parameterizedSqlShape("""
				WITH monthly AS (
				  SELECT channel, SUM(paid_amount) amount
				  FROM pay_order
				  WHERE paid_at >= '2026-07-01 00:00:00' AND status = 1
				  GROUP BY channel
				)
				SELECT * FROM monthly WHERE amount > 1000.50
				""");

		assertThat(shape).doesNotContain("2026-07-01", "1000.50", "status = 1");
		assertThat(shape).contains("WITH monthly", "status = ?", "amount > ?");
	}

	@Test
	void parameterizedComputationRequirementsPreventTopNShapeCollisions() {
		ValidatedSemanticSqlPatternService service = new ValidatedSemanticSqlPatternService(null);
		SemanticBlueprint top3 = advancedPlan(3, "HIGHEST");
		SemanticBlueprint top10 = advancedPlan(10, "HIGHEST");
		SemanticBlueprint bottom3 = advancedPlan(3, "LOWEST");
		SemanticBlueprint top3DifferentRequirementOrder = advancedPlan(3, "HIGHEST");
		top3DifferentRequirementOrder.setComputationIntent(new ComputationIntent(top3.getComputationIntent().capabilities(),
				List.of(top3.getComputationIntent().requirements().get(2), top3.getComputationIntent().requirements().get(0),
						top3.getComputationIntent().requirements().get(1))));

		assertThat(service.computationShapeHash(top3)).isNotEqualTo(service.computationShapeHash(top10));
		assertThat(service.computationShapeHash(top3)).isNotEqualTo(service.computationShapeHash(bottom3));
		assertThat(service.computationShapeHash(top3)).isEqualTo(service.computationShapeHash(top3DifferentRequirementOrder));
	}

	@Test
	void repeatedBadRecallQuarantinesPattern() {
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(10, 1, 1)).isFalse();
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(10, 2, 2)).isTrue();
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(3, 3, 1)).isTrue();
	}

	private SemanticBlueprint advancedPlan(int limit, String rankingMode) {
		return SemanticBlueprint.builder()
			.models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("pay_order").build()))
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder()
				.metricCode("paid_amount")
				.modelCode("pay_order")
				.build()))
			.computationIntent(new ComputationIntent(Set.of(Capability.AGGREGATION, Capability.PERIOD_COMPARISON,
					Capability.ORDERING, Capability.LIMIT), List.of(
							new Requirement(Capability.PERIOD_COMPARISON, "paid_amount", "MONTH", "PREVIOUS_PERIOD_RATE", null,
									null, null),
							new Requirement(Capability.ORDERING, null, null, rankingMode, null, null, "PERIOD_COMPARISON"),
							new Requirement(Capability.LIMIT, null, null, null, limit, "GLOBAL", "ORDERING"))))
			.executable(true)
			.build();
	}
}
