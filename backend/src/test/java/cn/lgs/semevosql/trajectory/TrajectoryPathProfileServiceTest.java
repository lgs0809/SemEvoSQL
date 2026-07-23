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

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrajectoryPathProfileServiceTest {

	@Test
	void betterQualityAtNoHigherCostParetoDominatesWeakerPath() {
		Map<String, Object> strong = profile(1.0, 1.0, 1.0, 1.0, 1.0, 100, 500, 0, 0);
		Map<String, Object> weak = profile(0.9, 1.0, 1.0, 1.0, 1.0, 120, 550, 0, 0);

		assertThat(TrajectoryPathProfileService.dominates(strong, weak)).isTrue();
		assertThat(TrajectoryPathProfileService.dominates(weak, strong)).isFalse();
	}

	@Test
	void qualityCostTradeoffDoesNotClaimFalseDominance() {
		Map<String, Object> accurateButSlow = profile(1.0, 1.0, 1.0, 1.0, 1.0, 200, 500, 0, 0);
		Map<String, Object> fasterButLessAccurate = profile(0.9, 1.0, 1.0, 1.0, 1.0, 100, 500, 0, 0);

		assertThat(TrajectoryPathProfileService.dominates(accurateButSlow, fasterButLessAccurate)).isFalse();
		assertThat(TrajectoryPathProfileService.dominates(fasterButLessAccurate, accurateButSlow)).isFalse();
	}

	private Map<String, Object> profile(double correctness, double safety, double coverage, double freshness,
			double stability, long latency, long tokens, double retries, double clarifications) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("correctness_rate", correctness);
		value.put("safety_rate", safety);
		value.put("coverage_rate", coverage);
		value.put("freshness_rate", freshness);
		value.put("stability_rate", stability);
		value.put("avg_latency_ms", latency);
		value.put("avg_token_count", tokens);
		value.put("avg_retry_count", retries);
		value.put("avg_clarification_count", clarifications);
		return value;
	}
}
