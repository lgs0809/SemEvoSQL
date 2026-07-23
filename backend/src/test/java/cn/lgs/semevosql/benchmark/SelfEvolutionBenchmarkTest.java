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
package cn.lgs.semevosql.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmark.Observation;
import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmark.Stage;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfEvolutionBenchmarkTest {

	@Test
	void benchmarkQuantifiesLearningWithoutAllowingScopeContamination() {
		var cold = SelfEvolutionBenchmark.evaluate(Stage.COLD, List.of(
				observation(false, false, 1, 2, 0, 0, 0, 0, 1, 900, 800),
				observation(true, true, 1, 1, 0, 0, 0, 0, 1, 700, 650)));
		var evolved = SelfEvolutionBenchmark.evaluate(Stage.FULL_EVOLUTION, List.of(
				observation(true, true, 0, 0, 0, 0, 1, 1, 0, 220, 120),
				observation(true, true, 0, 0, 0, 0, 1, 1, 0, 210, 110)));

		var delta = SelfEvolutionBenchmark.compare(cold, evolved);

		assertThat(delta.taskSuccessRateDelta()).isPositive();
		assertThat(delta.semanticResolutionAccuracyDelta()).isPositive();
		assertThat(delta.clarificationRateDelta()).isNegative();
		assertThat(delta.retryRateDelta()).isNegative();
		assertThat(delta.llmSqlGenerationRateDelta()).isNegative();
		assertThat(delta.averageLatencyMsDelta()).isNegative();
		assertThat(delta.averageTokenCountDelta()).isNegative();
		assertThat(delta.scopeSafe()).isTrue();
	}

	@Test
	void anyCrossUserContaminationFailsScopeSafetyInvariant() {
		var contaminated = SelfEvolutionBenchmark.evaluate(Stage.SCOPED_BINDING,
				List.of(observation(true, true, 0, 0, 0, 1, 1, 0, 0, 100, 10)));

		assertThat(contaminated.scopeSafe()).isFalse();
		assertThat(contaminated.crossUserContaminationRate()).isEqualTo(1.0d);
	}

	private Observation observation(boolean success, boolean semanticCorrect, int clarification, int retry,
			int wrongRecall, int contamination, int usefulRecall, int patternReuse, int llmSql, long latency,
			long tokens) {
		return new Observation(success, semanticCorrect, clarification, retry, wrongRecall, contamination, usefulRecall,
				patternReuse, llmSql, latency, tokens);
	}
}
