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

import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemEvoSQLEvidenceScenarioMapper {

	@Insert("""
			INSERT INTO qw_project_evidence
			(project_id, project_version_id, material_id, attempt_id, evidence_type, subject_key, evidence_hash,
			 payload_json, confidence, source_location, extraction_model, create_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{materialId}, #{attemptId}, #{evidenceType}, #{subjectKey}, #{evidenceHash},
			 CAST(#{payloadJson} AS jsonb), #{confidence}, #{sourceLocation}, #{extractionModel}, #{createTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertEvidence(ProjectEvidence evidence);

	@Select("SELECT * FROM qw_project_evidence WHERE attempt_id = #{attemptId} ORDER BY id")
	List<ProjectEvidence> findEvidenceByAttempt(Long attemptId);

	@Select("""
			SELECT * FROM qw_project_evidence
			WHERE project_version_id = #{projectVersionId}
			ORDER BY material_id, attempt_id, id
			""")
	List<ProjectEvidence> findEvidenceByVersion(Long projectVersionId);

	@Select("""
			SELECT e.*
			FROM qw_project_evidence e
			JOIN qw_semantic_material m ON m.id = e.material_id
			JOIN qw_semantic_material_attempt a ON a.id = e.attempt_id
			JOIN (
			    SELECT material_id, MAX(attempt_no) AS latest_attempt_no
			    FROM qw_semantic_material_attempt
			    WHERE status IN ('PARSED', 'APPLIED', 'REVIEW_REQUIRED')
			    GROUP BY material_id
			) latest ON latest.material_id = a.material_id AND latest.latest_attempt_no = a.attempt_no
			WHERE e.project_version_id = #{projectVersionId}
			  AND COALESCE(m.lifecycle, 'CURRENT') <> 'DEPRECATED'
			ORDER BY e.material_id, e.attempt_id, e.id
			""")
	List<ProjectEvidence> findActiveEvidenceByVersion(Long projectVersionId);

	@Insert("""
			INSERT INTO qw_project_evidence
			(project_id, project_version_id, material_id, attempt_id, evidence_type, subject_key, evidence_hash,
			 payload_json, confidence, source_location, extraction_model, create_time)
			SELECT #{projectId}, #{projectVersionId}, #{targetMaterialId}, #{targetAttemptId}, evidence_type, subject_key,
			       evidence_hash, payload_json, confidence, source_location, extraction_model, create_time
			FROM qw_project_evidence WHERE attempt_id = #{sourceAttemptId}
			""")
	int cloneAttemptEvidence(@Param("sourceAttemptId") Long sourceAttemptId,
			@Param("targetAttemptId") Long targetAttemptId, @Param("targetMaterialId") Long targetMaterialId,
			@Param("projectId") Long projectId, @Param("projectVersionId") Long projectVersionId);

	@Insert("""
			INSERT INTO qw_business_query_scenario
			(project_id, project_version_id, scenario_code, business_name, description, requirement_json, importance, status,
			 source_material_id, source_attempt_id, source_location, confidence, scenario_fingerprint, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{scenarioCode}, #{businessName}, #{description},
			 CAST(#{requirementJson} AS jsonb), #{importance}, #{status}, #{sourceMaterialId}, #{sourceAttemptId},
			 #{sourceLocation}, #{confidence}, #{scenarioFingerprint}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertScenario(BusinessQueryScenario scenario);

	@Update("""
			UPDATE qw_business_query_scenario
			SET business_name = #{businessName}, description = #{description}, requirement_json = CAST(#{requirementJson} AS jsonb),
			    importance = #{importance}, confidence = #{confidence}, update_time = #{updateTime}
			WHERE id = #{id}
			""")
	int updateScenario(BusinessQueryScenario scenario);

	@Select("SELECT * FROM qw_business_query_scenario WHERE id = #{scenarioId}")
	BusinessQueryScenario findScenarioById(Long scenarioId);

	@Select("""
			SELECT * FROM qw_business_query_scenario
			WHERE project_version_id = #{projectVersionId} AND scenario_fingerprint = #{scenarioFingerprint}
			""")
	BusinessQueryScenario findScenarioByFingerprint(@Param("projectVersionId") Long projectVersionId,
			@Param("scenarioFingerprint") String scenarioFingerprint);

	@Select("""
			SELECT * FROM qw_business_query_scenario
			WHERE project_version_id = #{projectVersionId}
			ORDER BY status, importance, scenario_code, id
			""")
	List<BusinessQueryScenario> findScenariosByVersion(Long projectVersionId);

	@Select("""
			SELECT * FROM qw_business_query_scenario
			WHERE project_version_id = #{projectVersionId} AND status = 'ACTIVE'
			ORDER BY importance, scenario_code, id
			""")
	List<BusinessQueryScenario> findActiveScenariosByVersion(Long projectVersionId);

	@Update("""
			UPDATE qw_business_query_scenario scenario
			SET status = CASE
			    WHEN EXISTS (
			        SELECT 1 FROM qw_project_evidence evidence
			        JOIN qw_semantic_material material ON material.id = evidence.material_id
			        WHERE evidence.project_version_id = scenario.project_version_id
			          AND evidence.evidence_type = 'BUSINESS_QUERY_SCENARIO'
			          AND evidence.subject_key = scenario.scenario_fingerprint
			          AND material.lifecycle IN ('CURRENT','UNKNOWN')
			          AND evidence.attempt_id = (
			              SELECT attempt.id FROM qw_semantic_material_attempt attempt
			              WHERE attempt.material_id = material.id
			                AND attempt.status IN ('APPLIED','REVIEW_REQUIRED')
			              ORDER BY attempt.attempt_no DESC LIMIT 1
			          )
			    ) THEN 'ACTIVE'
			    WHEN EXISTS (
			        SELECT 1 FROM qw_project_evidence evidence
			        JOIN qw_semantic_material material ON material.id = evidence.material_id
			        WHERE evidence.project_version_id = scenario.project_version_id
			          AND evidence.evidence_type = 'BUSINESS_QUERY_SCENARIO'
			          AND evidence.subject_key = scenario.scenario_fingerprint
			          AND material.lifecycle = 'HISTORICAL'
			          AND evidence.attempt_id = (
			              SELECT attempt.id FROM qw_semantic_material_attempt attempt
			              WHERE attempt.material_id = material.id
			                AND attempt.status IN ('APPLIED','REVIEW_REQUIRED')
			              ORDER BY attempt.attempt_no DESC LIMIT 1
			          )
			    ) THEN 'HISTORICAL'
			    ELSE 'DEPRECATED'
			END,
			update_time = CURRENT_TIMESTAMP
			WHERE scenario.project_version_id = #{projectVersionId}
			""")
	int reconcileScenarioStatuses(Long projectVersionId);

	@Insert("""
			INSERT INTO qw_business_query_scenario
			(project_id, project_version_id, scenario_code, business_name, description, requirement_json, importance, status,
			 source_material_id, source_attempt_id, source_location, confidence, scenario_fingerprint, create_time, update_time)
			SELECT #{projectId}, #{projectVersionId}, scenario_code, business_name, description, requirement_json, importance,
			       status, #{targetMaterialId}, #{targetAttemptId}, source_location, confidence, scenario_fingerprint,
			       create_time, CURRENT_TIMESTAMP
			FROM qw_business_query_scenario source
			WHERE source.source_attempt_id = #{sourceAttemptId}
			  AND NOT EXISTS (
			      SELECT 1 FROM qw_business_query_scenario target
			      WHERE target.project_version_id = #{projectVersionId}
			        AND target.scenario_fingerprint = source.scenario_fingerprint
			  )
			""")
	int cloneAttemptScenarios(@Param("sourceAttemptId") Long sourceAttemptId,
			@Param("targetAttemptId") Long targetAttemptId, @Param("targetMaterialId") Long targetMaterialId,
			@Param("projectId") Long projectId, @Param("projectVersionId") Long projectVersionId);

}
