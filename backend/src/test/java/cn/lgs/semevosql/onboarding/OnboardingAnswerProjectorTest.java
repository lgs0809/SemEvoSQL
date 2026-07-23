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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OnboardingAnswerProjectorTest {

	@Test
	void metricDefinitionUpsertsIntoGovernedCatalog() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
		OnboardingAnswerProjector projector = new OnboardingAnswerProjector(jdbc);

		projector.project(12L, 18L, OnboardingCategory.METRIC_DEFINITION,
				"{\"modelCode\":\"pay_order\",\"metricCode\":\"paid_amount\",\"businessName\":\"支付金额\",\"expression\":\"paid_amount\",\"aggregation\":\"SUM\"}",
				"onboarding-test");

		verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
	}

	@Test
	void existingModelAnswerUsesSingleUpdate() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
		OnboardingAnswerProjector projector = new OnboardingAnswerProjector(jdbc);

		projector.project(12L, 18L, OnboardingCategory.MODEL_BUSINESS_NAME,
				"{\"modelCode\":\"pay_order\",\"businessName\":\"支付订单\"}", "catalog");

		verify(jdbc).update(anyString(), any(Object[].class));
	}

	@Test
	void fixtureGoldenCaseRequiresDatasetVersionBeforeWriting() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		OnboardingAnswerProjector projector = new OnboardingAnswerProjector(jdbc);

		assertThatThrownBy(() -> projector.project(12L, 18L, OnboardingCategory.GOLDEN_QUESTION,
				"{\"caseCode\":\"golden-1\",\"question\":\"统计订单数\",\"replayMode\":\"FIXTURE\",\"expected\":{}}",
				"golden"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("datasetVersion");
	}

}
