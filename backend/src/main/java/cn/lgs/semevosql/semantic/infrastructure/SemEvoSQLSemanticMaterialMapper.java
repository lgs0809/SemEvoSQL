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

import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemEvoSQLSemanticMaterialMapper {

	@Insert("""
			INSERT INTO qw_semantic_material
			(project_id, project_version_id, document_type, material_category, lifecycle, material_type, source_type,
			 source_material_id, source_name, original_filename, media_type, file_path, file_size, source_location,
			 datasource_id, content_hash, content, status, parse_summary, error_message, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{documentType}, COALESCE(#{materialCategory}, 'OTHER'),
			 COALESCE(#{lifecycle}, 'UNKNOWN'), #{materialType}, #{sourceType}, #{sourceMaterialId}, #{sourceName},
			 #{originalFilename}, #{mediaType}, #{filePath}, #{fileSize},
			 #{sourceLocation}, #{datasourceId}, #{contentHash}, #{content}, #{status}, #{parseSummary}, #{errorMessage},
			 #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insert(SemanticMaterial material);

	@Update("""
			UPDATE qw_semantic_material
			SET document_type = #{documentType}, material_category = COALESCE(#{materialCategory}, material_category),
			lifecycle = COALESCE(#{lifecycle}, lifecycle), material_type = #{materialType}, source_type = #{sourceType},
			source_material_id = #{sourceMaterialId},
			source_name = #{sourceName}, original_filename = #{originalFilename},
			media_type = #{mediaType}, file_path = #{filePath}, file_size = #{fileSize}, source_location = #{sourceLocation},
			datasource_id = #{datasourceId}, content_hash = #{contentHash}, content = #{content}, status = #{status},
			parse_summary = #{parseSummary}, error_message = #{errorMessage}, update_time = #{updateTime}
			WHERE id = #{id}
			""")
	int update(SemanticMaterial material);

	@Delete("DELETE FROM qw_semantic_material WHERE id = #{materialId}")
	int delete(Long materialId);

	@Select("""
			SELECT * FROM qw_semantic_material
			WHERE project_version_id = #{projectVersionId} AND content_hash = #{contentHash}
			""")
	SemanticMaterial findByHash(@Param("projectVersionId") Long projectVersionId,
			@Param("contentHash") String contentHash);

	@Select("SELECT * FROM qw_semantic_material WHERE id = #{materialId}")
	SemanticMaterial findById(Long materialId);

	@Select("""
			SELECT id, project_id, project_version_id, document_type, material_category, lifecycle, material_type,
			source_type, source_material_id, source_name, original_filename, media_type, file_path, file_size,
			source_location, datasource_id, content_hash, status, parse_summary, error_message, create_time, update_time
			FROM qw_semantic_material
			WHERE project_version_id = #{projectVersionId}
			ORDER BY create_time ASC, id ASC
			""")
	List<SemanticMaterial> findByVersion(Long projectVersionId);

	@Select("""
			SELECT * FROM qw_semantic_material
			WHERE project_version_id = #{projectVersionId}
			ORDER BY create_time ASC, id ASC
			""")
	List<SemanticMaterial> findByVersionWithContent(Long projectVersionId);

	@Insert("""
			INSERT INTO qw_semantic_material_attempt
			(material_id, attempt_no, status, content_hash, source_location, extraction_model, parse_summary, error_message,
			 start_time, finish_time, create_time)
			VALUES
			(#{materialId}, #{attemptNo}, #{status}, #{contentHash}, #{sourceLocation}, #{extractionModel}, #{parseSummary},
			 #{errorMessage}, #{startTime}, #{finishTime}, #{createTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertAttempt(SemanticMaterialAttempt attempt);

	@Update("""
			UPDATE qw_semantic_material_attempt
			SET status = #{status}, parse_summary = #{parseSummary}, error_message = #{errorMessage},
			finish_time = #{finishTime}
			WHERE id = #{id}
			""")
	int updateAttempt(SemanticMaterialAttempt attempt);

	@Select("SELECT * FROM qw_semantic_material_attempt WHERE id = #{attemptId}")
	SemanticMaterialAttempt findAttemptById(Long attemptId);

	@Select("SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM qw_semantic_material_attempt WHERE material_id = #{materialId}")
	int findNextAttemptNo(Long materialId);

	@Select("""
			SELECT * FROM qw_semantic_material_attempt
			WHERE material_id = #{materialId}
			ORDER BY attempt_no ASC, id ASC
			""")
	List<SemanticMaterialAttempt> findAttempts(Long materialId);

	@Insert("""
			INSERT INTO qw_semantic_asset_provenance
			(project_id, project_version_id, material_id, attempt_id, asset_type, asset_key, asset_fingerprint,
			 disposition, conflict_gap_key, confidence, source_location, extraction_model, evidence, create_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{materialId}, #{attemptId}, #{assetType}, #{assetKey},
			 #{assetFingerprint}, #{disposition}, #{conflictGapKey}, #{confidence}, #{sourceLocation},
			 #{extractionModel}, #{evidence}, #{createTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertProvenance(SemanticAssetProvenance provenance);

	@Select("""
			SELECT * FROM qw_semantic_asset_provenance
			WHERE material_id = #{materialId}
			ORDER BY attempt_id ASC, asset_type ASC, asset_key ASC, id ASC
			""")
	List<SemanticAssetProvenance> findProvenanceByMaterial(Long materialId);

	@Select("""
			SELECT DISTINCT provenance.conflict_gap_key
			FROM qw_semantic_asset_provenance provenance
			JOIN qw_semantic_material_attempt attempt ON attempt.id = provenance.attempt_id
			WHERE provenance.project_version_id = #{projectVersionId}
			  AND provenance.disposition = 'CONFLICT'
			  AND provenance.conflict_gap_key IS NOT NULL
			  AND attempt.status IN ('APPLIED', 'REVIEW_REQUIRED')
			  AND attempt.attempt_no = (
			      SELECT MAX(latest.attempt_no)
			      FROM qw_semantic_material_attempt latest
			      WHERE latest.material_id = attempt.material_id
			        AND latest.status IN ('APPLIED', 'REVIEW_REQUIRED')
			  )
			ORDER BY provenance.conflict_gap_key
			""")
	List<String> findActiveConflictGapKeys(Long projectVersionId);

	@Insert("""
			INSERT INTO qw_semantic_asset_provenance
			(project_id, project_version_id, material_id, attempt_id, asset_type, asset_key, asset_fingerprint,
			 disposition, conflict_gap_key, confidence, source_location, extraction_model, evidence, create_time)
			SELECT #{projectId}, #{projectVersionId}, #{targetMaterialId}, #{targetAttemptId}, asset_type, asset_key,
			       asset_fingerprint, disposition, conflict_gap_key, confidence, source_location, extraction_model,
			       evidence, create_time
			FROM qw_semantic_asset_provenance
			WHERE attempt_id = #{sourceAttemptId}
			""")
	int cloneProvenance(@Param("sourceAttemptId") Long sourceAttemptId, @Param("targetAttemptId") Long targetAttemptId,
			@Param("targetMaterialId") Long targetMaterialId, @Param("projectId") Long projectId,
			@Param("projectVersionId") Long projectVersionId);

}
