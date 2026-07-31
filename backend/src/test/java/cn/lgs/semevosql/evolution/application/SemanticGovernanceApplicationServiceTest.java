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
package cn.lgs.semevosql.evolution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SemanticGovernanceApplicationServiceTest {

	@Test
	void changeSetDetailsUseTheCurrentReplayResultSchema() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		SemanticChangeSetApplicationService changeSets = mock(SemanticChangeSetApplicationService.class);
		ChangeSet changeSet = mock(ChangeSet.class);
		when(changeSets.get("change-1")).thenReturn(changeSet);
		when(changeSets.items("change-1")).thenReturn(List.of());
		when(jdbc.queryForList(anyString(), eq("change-1"))).thenReturn(List.of(Map.of("status", "PASSED")));
		SemanticGovernanceApplicationService service = new SemanticGovernanceApplicationService(jdbc, null, null,
				changeSets, null, null, null);

		var result = service.changeSet("change-1");

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).queryForList(sql.capture(), eq("change-1"));
		assertThat(sql.getValue()).contains("baseline_json", "candidate_json", "proof_json")
			.doesNotContain("expected_json", "actual_json");
		assertThat(result.replayResults()).hasSize(1);
	}
}
