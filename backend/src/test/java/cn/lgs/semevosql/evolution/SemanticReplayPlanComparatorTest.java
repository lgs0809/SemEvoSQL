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
package cn.lgs.semevosql.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Requirement;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticReplayPlanComparatorTest {

	private final SemanticReplayPlanComparator comparator = new SemanticReplayPlanComparator();

	@Test
	void ordinaryReplayRequiresPreviouslySelectedSemanticAssets() {
		SemanticBlueprint source = plan("paid_amount", "channel", true);
		SemanticBlueprint target = plan(null, "channel", true);

		assertThat(comparator.comparePlans(source, target)).contains("Missing metric: paid_amount");
	}

	@Test
	void ordinaryReplayRejectsChangedParameterizedComputationSemantics() {
		SemanticBlueprint source = plan("paid_amount", "channel", true);
		source.setComputationIntent(advancedIntent(3));
		SemanticBlueprint target = plan("paid_amount", "channel", true);
		target.setComputationIntent(advancedIntent(10));

		assertThat(comparator.comparePlans(source, target))
			.anyMatch(value -> value.contains("Computation requirements changed"));
	}

	@Test
	void ordinaryReplayRebindPreservesTheGovernedComputationNeed() {
		SemanticBlueprint source = plan("paid_amount", "channel", true);
		source.setComputationIntent(advancedIntent(3));
		SemanticBlueprint rebound = plan("paid_amount", "channel", true);

		comparator.preserveComputationIntent(source, rebound);

		assertThat(rebound.getComputationIntent()).isEqualTo(source.getComputationIntent());
		assertThat(comparator.comparePlans(source, rebound)).isEmpty();
	}

	@Test
	void planningPolicyReplayDetectsShapeChangesButIgnoresResolvedDatesForSameRelativeWindow() {
		SemanticBlueprint source = plan("paid_amount", "channel", true);
		source.setTimeRange(relativeTime("last_7_days", "2026-08-01", "2026-08-08"));
		SemanticBlueprint equivalent = plan("paid_amount", "channel", true);
		equivalent.setTimeRange(relativeTime("last_7_days", "2026-08-12", "2026-08-19"));

		assertThat(comparator.comparePlanningPolicyPlans(source, equivalent)).isEmpty();

		SemanticBlueprint changed = plan("paid_amount", "channel", true);
		changed.setLimit(10);
		changed.setTimeRange(relativeTime("last_7_days", "2026-08-12", "2026-08-19"));
		assertThat(comparator.comparePlanningPolicyPlans(source, changed))
			.anyMatch(value -> value.contains("Planning policy changed limit"));
	}

	@Test
	void goldenReplayFailsClosedForMissingAssetsAndNonExecutablePlan() {
		Map<String, Object> expected = Map.of(
				"modelCodes", List.of("pay_order"),
				"metricCodes", List.of("paid_amount"),
				"dimensionCodes", List.of("channel"));
		SemanticBlueprint plan = plan(null, "channel", false);

		assertThat(comparator.compareGoldenPlan(expected, plan))
			.contains("Missing metric: paid_amount", "Golden IR is not executable");
	}

	@Test
	void hintsCarryLiteralFilterAndTimeBindingIntoDeterministicReplay() {
		SemanticBlueprint plan = plan("paid_amount", "channel", true);
		plan.getFilters().add(SemanticBlueprint.FilterSelection.builder()
			.modelCode("pay_order")
			.columnName("status")
			.operator("=")
			.value("PAID")
			.valueType("LITERAL")
			.build());
		plan.setTimeRange(SemanticBlueprint.TimeRangeSelection.builder()
			.modelCode("pay_order")
			.timeColumn("pay_time")
			.startInclusive("2026-08-01")
			.endExclusive("2026-08-02")
			.build());

		var hints = comparator.hints(plan, "case-1");
		assertThat(hints.metricCodes()).containsExactly("paid_amount");
		assertThat(hints.filterBindings()).hasSize(1);
		assertThat(hints.timeBinding()).isNotNull();
		assertThat(hints.timeBinding().columnName()).isEqualTo("pay_time");
	}

	@Test
	void missingAssetChecksAreExplicitAndTypeScoped() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("pay_order").build()))
			.metrics(List.of(SemanticCatalogSnapshot.Metric.builder().metricCode("paid_amount").build()))
			.build();

		assertThat(comparator.missingAssets(catalog,
				List.of(Map.of("asset_type", "MODEL", "asset_key", "pay_order"),
						Map.of("asset_type", "METRIC", "asset_key", "refund_amount"))))
			.containsExactly("METRIC:refund_amount");
		assertThat(comparator.missingExpected(catalog,
				Map.of("modelCodes", List.of("pay_order"), "metricCodes", List.of("refund_amount"))))
			.containsExactly("METRIC:refund_amount");
	}

	private SemanticBlueprint plan(String metric, String dimension, boolean executable) {
		SemanticBlueprint.SemanticBlueprintBuilder builder = SemanticBlueprint.builder()
			.models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("pay_order").build()))
			.executable(executable);
		if (metric != null) {
			builder.metrics(List.of(SemanticBlueprint.MetricSelection.builder().metricCode(metric).modelCode("pay_order").build()));
		}
		if (dimension != null) {
			builder.dimensions(List.of(SemanticBlueprint.DimensionSelection.builder()
				.dimensionCode(dimension)
				.modelCode("pay_order")
				.build()));
		}
		return builder.build();
	}

	private ComputationIntent advancedIntent(int limit) {
		return new ComputationIntent(
				Set.of(Capability.AGGREGATION, Capability.PERIOD_COMPARISON, Capability.ORDERING, Capability.LIMIT),
				List.of(new Requirement(Capability.PERIOD_COMPARISON, "paid_amount", "MONTH", "PREVIOUS_PERIOD_RATE", null,
						null, null),
						new Requirement(Capability.ORDERING, null, null, "HIGHEST", null, null, "PERIOD_COMPARISON"),
						new Requirement(Capability.LIMIT, null, null, null, limit, "GLOBAL", "ORDERING")));
	}

	private SemanticBlueprint.TimeRangeSelection relativeTime(String expression, String start, String end) {
		return SemanticBlueprint.TimeRangeSelection.builder()
			.modelCode("pay_order")
			.timeColumn("pay_time")
			.relativeExpression(expression)
			.startInclusive(start)
			.endExclusive(end)
			.build();
	}

}
