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

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergeType;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportGeneratorNodeTest {

	private final ReportGeneratorNode node = new ReportGeneratorNode(null);

	@Test
	void governedReportFactsDoNotExposeInternalPlanIdentifiers() {
		SemanticBlueprint blueprint = scalarBlueprint();

		String context = node.buildUserRequirementsAndPlan(
				"分别统计 Market Pay 和 Golden Order 两个数据源的订单数量，并比较两边订单数量的差值", null, blueprint);

		assertThat(context).contains("支付订单", "业务数据源 2", "订单数");
		assertThat(context).doesNotContain("Semantic Blueprint", "pay_order", "qw_bench_order", "order_count",
				"qw_bench_order_count", "datasourceId", "modelCode", "metricCode");
	}

	@Test
	void governedExecutionResultsUseBusinessLabelsInsteadOfInternalColumns() {
		SemanticBlueprint blueprint = scalarBlueprint();
		Map<String, String> executionResults = new LinkedHashMap<>();
		executionResults.put("step_1",
				"{\"column\":[\"order_count\",\"qw_bench_order_count\",\"gap\"],\"data\":[{\"order_count\":\"3\",\"qw_bench_order_count\":\"5\",\"gap\":\"2\"}],\"errorMsg\":null}");

		String context = node.buildAnalysisStepsAndData(null, executionResults, Map.of(), blueprint);

		assertThat(context).contains("执行结果 1", "支付订单 · 订单数", "业务指标", "差值", "\"3\"", "\"5\"", "\"2\"");
		assertThat(context).doesNotContain("step_1", "pay_order", "qw_bench_order", "order_count",
				"qw_bench_order_count", "gap", "执行SQL", "使用工具");
	}

	@Test
	void fallbackReportRemainsAvailableWhenNarrativeModelReturnsNothing() {
		SemanticBlueprint blueprint = scalarBlueprint();
		Map<String, String> executionResults = new LinkedHashMap<>();
		executionResults.put("1", "{\"column\":[\"订单数\"],\"data\":[{\"订单数\":\"3\"}]}");

		String report = node.fallbackReport(executionResults, blueprint);

		assertThat(report).contains("查询已完成", "结果 1", "订单数", "3");
	}

	@Test
	void fallbackReportDoesNotClaimDataWhenExecutionResultIsMissing() {
		assertThat(node.fallbackReport(Map.of(), scalarBlueprint())).isEqualTo("查询已完成，但没有返回可展示的数据。");
	}

	private SemanticBlueprint scalarBlueprint() {
		return SemanticBlueprint.builder()
				.canonicalQuery("分别统计两个数据源的订单数量并比较差值")
				.models(List.of(
						SemanticBlueprint.ModelSelection.builder().modelCode("pay_order").physicalTable("pay_order")
								.businessName("支付订单").datasourceId(1).build(),
						SemanticBlueprint.ModelSelection.builder().modelCode("qw_bench_order").physicalTable("qw_bench_order")
								.businessName("qw_bench_order").datasourceId(2).build()))
				.metrics(List.of(
						SemanticBlueprint.MetricSelection.builder().metricCode("order_count").modelCode("pay_order")
								.businessName("订单数").expression("COUNT(DISTINCT order_id)").aggregation("COUNT_DISTINCT")
								.unit("单").build(),
						SemanticBlueprint.MetricSelection.builder().metricCode("qw_bench_order_count")
								.modelCode("qw_bench_order").businessName("qw_bench_order count").expression("order_id")
								.aggregation("COUNT_DISTINCT").unit("count").build()))
				.mergePlan(SemanticBlueprint.MergePlan.builder().mergeType(MergeType.SCALAR_COMPOSITION)
						.calculationExpression("gap=ABS(order_count-qw_bench_order_count)").build())
				.executable(true).build();
	}
}
