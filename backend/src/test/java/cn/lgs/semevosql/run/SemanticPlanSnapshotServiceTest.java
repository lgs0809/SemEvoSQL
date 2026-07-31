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
package cn.lgs.semevosql.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SemanticPlanSnapshotServiceTest {

	@Test
	void runtimeSemanticPlanEventIsAuthoritativeOverAdmissionSnapshot() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		ExecutionSnapshotService snapshots = mock(ExecutionSnapshotService.class);
		when(jdbc.queryForList(anyString(), eq(String.class), eq("run-1")))
			.thenReturn(List.of("{\"canonicalQuery\":\"runtime-plan\",\"executable\":true}"));
		SemanticPlanSnapshotService service = new SemanticPlanSnapshotService(jdbc, snapshots);

		Optional<SemanticBlueprint> resolved = service.latest("run-1");

		assertThat(resolved).isPresent();
		assertThat(resolved.orElseThrow().getCanonicalQuery()).isEqualTo("runtime-plan");
		verifyNoInteractions(snapshots);
	}

	@Test
	void admissionSnapshotIsUsedOnlyWhenNoRuntimePlanExists() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		ExecutionSnapshotService snapshots = mock(ExecutionSnapshotService.class);
		when(jdbc.queryForList(anyString(), eq(String.class), eq("run-1")))
			.thenReturn(List.of(), List.of("admission-json"));
		SemanticBlueprint admissionPlan = SemanticBlueprint.builder()
			.canonicalQuery("admission-plan")
			.executable(true)
			.build();
		ExecutionSnapshot admission = new ExecutionSnapshot(ExecutionSnapshot.CURRENT_SCHEMA_VERSION, null, null, null,
				null, null, null, admissionPlan, null, false, true, "compatibility");
		when(snapshots.readTyped("admission-json")).thenReturn(Optional.of(admission));
		SemanticPlanSnapshotService service = new SemanticPlanSnapshotService(jdbc, snapshots);

		Optional<SemanticBlueprint> resolved = service.latest("run-1");

		assertThat(resolved).isPresent();
		assertThat(resolved.orElseThrow().getCanonicalQuery()).isEqualTo("admission-plan");
	}
}
