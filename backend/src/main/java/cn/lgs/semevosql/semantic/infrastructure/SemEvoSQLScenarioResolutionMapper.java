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
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemEvoSQLScenarioResolutionMapper {

	@Select("SELECT * FROM qw_scenario_resolution WHERE scenario_id = #{scenarioId}")
	ScenarioResolution findByScenario(Long scenarioId);

	@Select("""
			SELECT * FROM qw_scenario_resolution
			WHERE project_version_id = #{projectVersionId}
			ORDER BY scenario_id
			""")
	List<ScenarioResolution> findByVersion(Long projectVersionId);

	@Insert("""
			INSERT INTO qw_scenario_resolution
			(scenario_id, project_id, project_version_id, status, resolved_bindings_json, candidate_bindings_json,
			 unresolved_requirements_json, evidence_json, manual_bindings_json, resolution_hash, revision,
			 create_time, update_time)
			VALUES
			(#{scenarioId}, #{projectId}, #{projectVersionId}, #{status}, CAST(#{resolvedBindingsJson} AS jsonb),
			 CAST(#{candidateBindingsJson} AS jsonb), CAST(#{unresolvedRequirementsJson} AS jsonb),
			 CAST(#{evidenceJson} AS jsonb), CAST(#{manualBindingsJson} AS jsonb), #{resolutionHash}, #{revision},
			 #{createTime}, #{updateTime})
			ON CONFLICT (scenario_id) DO UPDATE SET
			 status = EXCLUDED.status,
			 resolved_bindings_json = EXCLUDED.resolved_bindings_json,
			 candidate_bindings_json = EXCLUDED.candidate_bindings_json,
			 unresolved_requirements_json = EXCLUDED.unresolved_requirements_json,
			 evidence_json = EXCLUDED.evidence_json,
			 resolution_hash = EXCLUDED.resolution_hash,
			 revision = qw_scenario_resolution.revision + 1,
			 update_time = EXCLUDED.update_time
			""")
	int save(ScenarioResolution resolution);

	@Update("""
			UPDATE qw_scenario_resolution
			SET manual_bindings_json = CAST(#{manualBindingsJson} AS jsonb), revision = revision + 1,
			    update_time = CURRENT_TIMESTAMP
			WHERE scenario_id = #{scenarioId}
			""")
	int updateManualBindings(@Param("scenarioId") Long scenarioId,
			@Param("manualBindingsJson") String manualBindingsJson);

}
