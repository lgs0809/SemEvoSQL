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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmark.Stage;
import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmarkRunner.BenchmarkCase;
import cn.lgs.semevosql.benchmark.SelfEvolutionBenchmarkRunner.StagePlan;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelfEvolutionBenchmarkRunnerTest {

	private final SelfEvolutionBenchmarkRunner runner = new SelfEvolutionBenchmarkRunner(null, null, null, null, null,
			null);

	@Test
	void stagesMustUseIsolatedProjectsSoExperienceCannotLeakAcrossAblations() {
		List<StagePlan> stages = List.of(new StagePlan(Stage.COLD, "cold", 11L, List.of()),
				new StagePlan(Stage.FULL_EVOLUTION, "evolved", 11L, List.of("warm-up")));
		List<BenchmarkCase> heldOut = List.of(new BenchmarkCase("case-1", "query", Set.of()));

		assertThatThrownBy(() -> runner.runExperiment(stages, heldOut, Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("isolated project IDs");
	}

	@Test
	void benchmarkCasesRequireStableIdentityAndQuestionText() {
		assertThatThrownBy(() -> new BenchmarkCase("", "query", Set.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BenchmarkCase("case-1", " ", Set.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void experimentRejectsEmptyHeldOutSetBeforeExecutingAnyRun() {
		List<StagePlan> stages = List.of(new StagePlan(Stage.COLD, "cold", 11L, List.of()));

		assertThatThrownBy(() -> runner.runExperiment(stages, List.of(), Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Held-out benchmark cases");
	}
}
