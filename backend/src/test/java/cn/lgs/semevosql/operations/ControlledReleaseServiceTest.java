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
package cn.lgs.semevosql.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.operations.SemEvoSQLProductionService.CanaryRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ControlledReleaseServiceTest {

	@Test
	void canaryMustFollowGovernedTrafficSteps() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticCatalogCache cache = mock(SemanticCatalogCache.class);
		ControlledReleaseService service = service(jdbc, cache);
		when(jdbc.queryForList(anyString(), any(Object[].class)))
			.thenReturn(List.of(release(5, 11L, 12L)));

		assertThatThrownBy(() -> service.advanceCanary("release-1", new CanaryRequest(50, 0, 100, 0), operator()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1, 5, 20, 50 and 100");
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void unsafeCanaryAutomaticallyRollsBackToBaseline() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticCatalogCache cache = mock(SemanticCatalogCache.class);
		ControlledReleaseService service = service(jdbc, cache);
		Map<String, Object> active = release(5, 11L, 12L);
		Map<String, Object> rolledBack = release(0, 11L, 12L);
		rolledBack.put("status", "ROLLED_BACK");
		when(jdbc.queryForList(anyString(), any(Object[].class)))
			.thenReturn(List.of(active), List.of(active), List.of(rolledBack));

		Map<String, Object> result = service.advanceCanary("release-1", new CanaryRequest(20, 0.06, 100, 0), operator());

		assertThat(result.get("status")).isEqualTo("ROLLED_BACK");
		verify(cache).invalidate(12L);
		verify(cache).warm(7L, 11L);
	}

	@Test
	void requestAssignmentUsesStableTrafficBucket() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		ControlledReleaseService service = service(jdbc, mock(SemanticCatalogCache.class));
		when(jdbc.queryForList(anyString(), any(Object[].class)))
			.thenReturn(List.of(release(100, 11L, 12L)), List.of(release(0, 11L, 12L)));

		assertThat(service.assignVersion("release-1", "request-a")).isEqualTo(12L);
		assertThat(service.assignVersion("release-1", "request-a")).isEqualTo(11L);
	}

	private ControlledReleaseService service(JdbcTemplate jdbc, SemanticCatalogCache cache) {
		return new ControlledReleaseService(jdbc, cache, new LocalOperatorService());
	}

	private OperatorContext operator() {
		return OperatorContext.system("controlled-release-test");
	}

	private Map<String, Object> release(int traffic, Long baseline, Long candidate) {
		return new java.util.LinkedHashMap<>(Map.of(
				"id", "release-1",
				"project_id", 7L,
				"baseline_version_id", baseline,
				"candidate_version_id", candidate,
				"traffic_percent", traffic,
				"status", traffic == 100 ? "PRODUCTION" : "CANARY"));
	}

}
