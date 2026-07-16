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
package cn.lgs.semevosql.semantic.infrastructure;

import cn.lgs.semevosql.semantic.domain.ScenarioResolution;
import cn.lgs.semevosql.semantic.domain.ScenarioResolutionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisScenarioResolutionRepository implements ScenarioResolutionRepository {

	private final SemEvoSQLScenarioResolutionMapper mapper;

	@Override
	public Optional<ScenarioResolution> findByScenario(Long scenarioId) {
		return Optional.ofNullable(mapper.findByScenario(scenarioId));
	}

	@Override
	public List<ScenarioResolution> findByVersion(Long projectVersionId) {
		return mapper.findByVersion(projectVersionId);
	}

	@Override
	public void save(ScenarioResolution resolution) {
		mapper.save(resolution);
	}

	@Override
	public void updateManualBindings(Long scenarioId, String manualBindingsJson) {
		if (mapper.updateManualBindings(scenarioId, manualBindingsJson) != 1) {
			throw new IllegalArgumentException("Scenario resolution not found: " + scenarioId);
		}
	}

}
