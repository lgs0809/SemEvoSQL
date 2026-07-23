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
package cn.lgs.semevosql.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingCatalogAdvisorTest {

	@Test
	void catalogSignalsAreDetectedWithoutMutatingOnboardingState() throws Exception {
		SemanticCatalogSnapshot catalog = catalog();
		var columns = catalog.getColumns();

		assertThat(OnboardingCatalogAdvisor.hasBusinessName(catalog.getModels().get(0))).isTrue();
		assertThat(OnboardingCatalogAdvisor.numericColumn(columns.get(1))).isTrue();
		assertThat(OnboardingCatalogAdvisor.enumCandidate(columns.get(3))).isTrue();
		assertThat(OnboardingCatalogAdvisor.logicalDeleteCandidate(columns.get(4))).isTrue();
		assertThat(OnboardingCatalogAdvisor.testDataCandidate(columns.get(5))).isTrue();

		var grain = JsonUtil.getObjectMapper().readTree(OnboardingCatalogAdvisor.grainRecommendation(catalog));
		assertThat(grain.path("modelCode").asText()).isEqualTo("pay_order");
		assertThat(grain.path("keyColumns").findValuesAsText("")).isEmpty();
		assertThat(grain.path("keyColumns").toString()).contains("order_id");

		var time = JsonUtil.getObjectMapper().readTree(OnboardingCatalogAdvisor.timeRecommendation(catalog));
		assertThat(time.path("timeColumn").asText()).isEqualTo("pay_time");
	}

	@Test
	void ruleRecommendationsAreConservativeAndColumnDriven() throws Exception {
		SemanticCatalogSnapshot catalog = catalog();

		var deleteRule = JsonUtil.getObjectMapper()
			.readTree(OnboardingCatalogAdvisor.logicalDeleteRecommendation(catalog));
		assertThat(deleteRule.path("expression").asText()).isEqualTo("is_deleted = 0");

		var testRule = JsonUtil.getObjectMapper().readTree(OnboardingCatalogAdvisor.testDataRecommendation(catalog));
		assertThat(testRule.path("expression").asText()).isEqualTo("is_test = 0");
	}

	@Test
	void recommendationsTolerateMissingBusinessLabels() throws Exception {
		SemanticCatalogSnapshot catalog = catalog();
		catalog.getMetrics().get(0).setBusinessName(null);

		var metrics = JsonUtil.getObjectMapper().readTree(OnboardingCatalogAdvisor.metricRecommendation(catalog));
		assertThat(metrics.get(0).path("businessName").asText()).isEqualTo("paid_amount");
	}

	private SemanticCatalogSnapshot catalog() {
		return SemanticCatalogSnapshot.builder()
			.projectId(12L)
			.projectVersionId(18L)
			.models(List.of(SemanticCatalogSnapshot.Model.builder()
				.modelCode("pay_order")
				.physicalTable("pay_order")
				.businessName("支付订单")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.columns(List.of(
				column("order_id", "varchar", SemanticColumnRole.IDENTIFIER),
				column("paid_amount", "decimal(18,2)", SemanticColumnRole.MEASURE),
				column("pay_time", "timestamp", SemanticColumnRole.TIME),
				column("status_code", "varchar", SemanticColumnRole.DIMENSION),
				column("is_deleted", "int", SemanticColumnRole.DIMENSION),
				column("is_test", "int", SemanticColumnRole.DIMENSION)))
			.metrics(List.of(SemanticCatalogSnapshot.Metric.builder()
				.modelCode("pay_order")
				.metricCode("paid_amount")
				.businessName("支付金额")
				.expression("paid_amount")
				.status(SemanticAssetStatus.ENABLED)
				.build()))
			.build();
	}

	private SemanticCatalogSnapshot.Column column(String name, String dataType, SemanticColumnRole role) {
		return SemanticCatalogSnapshot.Column.builder()
			.modelCode("pay_order")
			.columnName(name)
			.dataType(dataType)
			.role(role)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}

}
