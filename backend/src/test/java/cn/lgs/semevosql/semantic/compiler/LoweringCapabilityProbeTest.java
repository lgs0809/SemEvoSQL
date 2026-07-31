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
package cn.lgs.semevosql.semantic.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.ComputationIntent;
import cn.lgs.semevosql.semantic.domain.ComputationIntent.Capability;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LoweringCapabilityProbeTest {

	@Test
	void supportedIntentUsesDeterministicGenerator() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.compilerMode("DETERMINISTIC")
			.computationIntent(new ComputationIntent(Set.of(Capability.AGGREGATION, Capability.TIME_BUCKET)))
			.executable(true)
			.validationErrors(List.of())
			.build();

		LoweringCapabilityProbe.Decision decision = LoweringCapabilityProbe.probe(plan);

		assertThat(decision.status()).isEqualTo(LoweringCapabilityProbe.Status.SUPPORTED);
	}

	@Test
	void unsupportedButValidIntentRequiresSemanticSqlGeneration() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.compilerMode("DETERMINISTIC")
			.computationIntent(new ComputationIntent(Set.of(Capability.PERIOD_COMPARISON)))
			.executable(true)
			.validationErrors(List.of())
			.build();

		LoweringCapabilityProbe.Decision decision = LoweringCapabilityProbe.probe(plan);

		assertThat(decision.status()).isEqualTo(LoweringCapabilityProbe.Status.REQUIRES_GENERATION);
		assertThat(decision.unsupportedCapabilities()).containsExactly(Capability.PERIOD_COMPARISON);
	}

	@Test
	void invalidSemanticPlanNeverFallsThroughToModelGeneration() {
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.compilerMode("DETERMINISTIC")
			.computationIntent(new ComputationIntent(Set.of(Capability.WINDOW_ANALYTICS)))
			.executable(false)
			.validationErrors(List.of("relationship is not governed"))
			.build();

		LoweringCapabilityProbe.Decision decision = LoweringCapabilityProbe.probe(plan);

		assertThat(decision.status()).isEqualTo(LoweringCapabilityProbe.Status.INVALID);
	}
}
