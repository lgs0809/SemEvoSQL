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

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticBlueprintEnricherTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "按下单月份统计退款率。只统计2026年1月1日至4月1日之前下单的订单，订单状态不筛选；有至少一笔SUCCESS退款的订单算成功退款订单。",
        "按客户汇总发票金额。只统计三月之前的发票，有至少一条成功付款记录才计入分子。",
        "按月份汇总订单金额。之后两笔退款只算一次，不按退款时间限制。",
        "前三个月按月份汇总订单金额，有至少一笔支付记录的订单计入。",
        "前三十个月按月份汇总订单金额，有至少一笔支付记录的订单计入。"
    })
    void temporalAndExistenceConditionsDoNotTurnAggregatesIntoRankedRows(String query) {
        var metric=SemanticBlueprint.MetricSelection.builder().metricCode("amount").modelCode("orders")
            .expression("SUM(amount)").aggregation("SUM").build();
        var enricher=new SemanticBlueprintEnricher();
        var metrics=enricher.metricsForIntent(query,List.of(metric));
        assertThat(metrics.get(0).getExpression()).isEqualTo("SUM(amount)");
        assertThat(metrics.get(0).getAggregation()).isEqualTo("SUM");
        var details=enricher.enrich(SemanticCatalogSnapshot.builder().columns(List.of()).enumValues(List.of()).build(),
            query,List.of(),metrics,List.of(),List.of());
        assertThat(details.limit()).isEqualTo(100);
        assertThat(details.orderBy()).isEmpty();
        assertThat(details.projections()).noneMatch(p->"IDENTIFIER".equals(p.getProjectionType()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"列出金额最高的三笔订单", "按金额降序返回10条记录", "取金额前五笔订单", "最低的两笔交易", "top3笔订单", "bottom2条记录"})
    void explicitRowRankingStillUsesTheRowValue(String query) {
        var metric=SemanticBlueprint.MetricSelection.builder().metricCode("amount").modelCode("orders")
            .expression("SUM(amount)").aggregation("SUM").build();
        var result=new SemanticBlueprintEnricher().metricsForIntent(query,List.of(metric));
        assertThat(result.get(0).getExpression()).isEqualTo("amount");
        assertThat(result.get(0).getAggregation()).isEqualTo("NONE");
    }

	@Test
	void bucketedTimeBindingSupersedesRawDimensionForSameTimeAxis() {
		QueryCaseHints.TimeBindingHint time = new QueryCaseHints.TimeBindingHint("按月", "orders", "paid_at", null, 1.0,
				"MONTH");
		SemanticBlueprint.DimensionSelection dimension = SemanticBlueprint.DimensionSelection.builder()
			.modelCode("orders")
			.columnName("paid_at")
			.dimensionCode("payment_time")
			.dimensionType("TIME")
			.build();

		assertThat(SemanticBlueprintEnricher.bucketedTimeAxisDimension(time, dimension)).isTrue();
	}

	@Test
	void differentTimeAxisRemainsIndependentDimension() {
		QueryCaseHints.TimeBindingHint time = new QueryCaseHints.TimeBindingHint("按月", "orders", "paid_at", null, 1.0,
				"MONTH");
		SemanticBlueprint.DimensionSelection dimension = SemanticBlueprint.DimensionSelection.builder()
			.modelCode("orders")
			.columnName("created_at")
			.dimensionCode("created_time")
			.dimensionType("TIME")
			.build();

		assertThat(SemanticBlueprintEnricher.bucketedTimeAxisDimension(time, dimension)).isFalse();
	}
}
