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
package cn.lgs.semevosql.semantic.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.application.ScenarioResolutionService.BindingCandidate;
import cn.lgs.semevosql.semantic.application.ScenarioResolutionService.ResolvedBinding;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioBindingMatcherTest {

	private final ScenarioBindingMatcher matcher = new ScenarioBindingMatcher();

	@Test
	void scoringIsDeterministicAndOnlyBestTiesSurvive() {
		assertThat(matcher.matchScore("支付金额", "支付金额")).isEqualTo(100);
		assertThat(matcher.matchScore("订单支付金额", "支付金额")).isEqualTo(80);
		assertThat(matcher.matchScore("订单", "退款金额")).isZero();

		BindingCandidate exact = new BindingCandidate("METRIC", "paid_amount", "支付金额", "pay_order", "支付金额", 100,
				"");
		BindingCandidate weakerDuplicate = new BindingCandidate("METRIC", "paid_amount", "支付金额", "pay_order", "支付金额", 80,
				"");
		BindingCandidate tied = new BindingCandidate("METRIC", "gmv", "成交金额", "pay_order", "成交金额", 100, "");

		assertThat(matcher.bestCandidates(List.of(weakerDuplicate, exact, tied)))
			.extracting(BindingCandidate::assetKey)
			.containsExactly("gmv", "paid_amount");
	}

	@Test
	void entityNameCanResolveOnlyOneGovernedPrimaryGrainCountMetric() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.models(List.of(SemanticCatalogSnapshot.Model.builder()
				.modelCode("pay_order")
				.physicalTable("pay_order")
				.businessName("订单")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.grains(List.of(SemanticCatalogSnapshot.Grain.builder()
				.modelCode("pay_order")
				.grainCode("order_grain")
				.keyColumns("order_id")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.metrics(List.of(
				SemanticCatalogSnapshot.Metric.builder()
					.modelCode("pay_order")
					.metricCode("order_count")
					.businessName("订单数")
					.expression("order_id")
					.aggregation("COUNT_DISTINCT")
					.status(SemanticAssetStatus.ENABLED)
					.build(),
				SemanticCatalogSnapshot.Metric.builder()
					.modelCode("pay_order")
					.metricCode("paid_amount")
					.businessName("支付金额")
					.expression("paid_amount")
					.aggregation("SUM")
					.status(SemanticAssetStatus.ENABLED)
					.build()))
			.build();

		assertThat(matcher.metricCandidates("订单", catalog, List.of()))
			.singleElement()
			.satisfies(candidate -> {
				assertThat(candidate.assetKey()).isEqualTo("order_count");
				assertThat(candidate.score()).isEqualTo(95);
			});
	}

	@Test
	void selectedMetricTimeColumnWinsOverOtherTimeCandidates() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.metrics(List.of(SemanticCatalogSnapshot.Metric.builder()
				.modelCode("pay_order")
				.metricCode("paid_amount")
				.timeColumn("pay_time")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.columns(List.of(
				SemanticCatalogSnapshot.Column.builder()
					.modelCode("pay_order")
					.columnName("create_time")
					.businessName("创建时间")
					.role(SemanticColumnRole.TIME)
					.status(SemanticAssetStatus.ENABLED)
					.build(),
				SemanticCatalogSnapshot.Column.builder()
					.modelCode("pay_order")
					.columnName("pay_time")
					.businessName("支付时间")
					.role(SemanticColumnRole.TIME)
					.status(SemanticAssetStatus.ENABLED)
					.build()))
			.build();
		ResolvedBinding metric = new ResolvedBinding("measure-1", "MEASURE", "支付金额", "METRIC", "paid_amount",
				"支付金额", "pay_order", "AUTO");

		assertThat(matcher.timeCandidates(catalog, List.of(metric), Set.of("pay_order"), List.of(), "时间"))
			.singleElement()
			.satisfies(candidate -> {
				assertThat(candidate.assetKey()).isEqualTo("pay_order:pay_time");
				assertThat(candidate.score()).isEqualTo(100);
			});
	}

	@Test
	void ambiguousEqualScoreCandidatesRemainVisibleForClarification() {
		SemanticCatalogSnapshot catalog = SemanticCatalogSnapshot.builder()
			.metrics(List.of(
				SemanticCatalogSnapshot.Metric.builder()
					.modelCode("pay_order")
					.metricCode("paid_amount")
					.businessName("金额")
					.status(SemanticAssetStatus.ENABLED)
					.build(),
				SemanticCatalogSnapshot.Metric.builder()
					.modelCode("refund_order")
					.metricCode("refund_amount")
					.businessName("金额")
					.status(SemanticAssetStatus.ENABLED)
					.build()))
			.build();

		assertThat(matcher.metricCandidates("金额", catalog, List.of()))
			.extracting(BindingCandidate::assetKey)
			.containsExactly("paid_amount", "refund_amount");
	}

}
