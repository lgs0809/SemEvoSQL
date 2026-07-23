/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.operations;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionGoldenReplayTimeBoundaryTest {
	@Test
	void acceptsEquivalentIsoLocalTimePrecision() {
		assertThat(ProductionGoldenReplayRunner.sameTimeBoundary("2026-01-01T00:00:00", "2026-01-01T00:00")).isTrue();
		assertThat(ProductionGoldenReplayRunner.sameTimeBoundary("2026-02-01T00:00:00.000", "2026-02-01T00:00")).isTrue();
	}

	@Test
	void rejectsDifferentHalfOpenBoundsAndNonzeroPrecision() {
		assertThat(ProductionGoldenReplayRunner.sameTimeBoundary("2026-02-01T00:00:00", "2026-01-31T23:59:59")).isFalse();
		assertThat(ProductionGoldenReplayRunner.sameTimeBoundary("2026-01-01T00:00:00.001", "2026-01-01T00:00")).isFalse();
	}

	@Test
	void rejectsMissingInvalidAndOffsetTimeOutsideCompilerContract() {
		for (String invalid : new String[] { null, "", "2026-01-01", "invalid", "2026-01-01T00:00Z" }) {
			assertThat(ProductionGoldenReplayRunner.sameTimeBoundary("2026-01-01T00:00:00", invalid)).isFalse();
			assertThat(ProductionGoldenReplayRunner.sameTimeBoundary(invalid, invalid)).isFalse();
		}
	}
}
