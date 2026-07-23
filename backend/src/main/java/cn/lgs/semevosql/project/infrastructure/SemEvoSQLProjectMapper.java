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
package cn.lgs.semevosql.project.infrastructure;

import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectRuntimeProfile;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemEvoSQLProjectMapper {

	@Insert("""
			INSERT INTO qw_project
			(project_code, name, business_domain, description, status, active_version_id, created_by, create_time, update_time, revision)
			VALUES
			(#{projectCode}, #{name}, #{businessDomain}, #{description}, #{status}, #{activeVersionId}, #{createdBy}, #{createTime}, #{updateTime}, #{revision})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertProject(SemanticProject project);

	@Update("""
			UPDATE qw_project SET name = #{name}, business_domain = #{businessDomain}, description = #{description},
			status = #{status}, active_version_id = #{activeVersionId}, update_time = #{updateTime}, revision = revision + 1
			WHERE id = #{id} AND revision = #{revision}
			""")
	int updateProject(SemanticProject project);

	@Select("SELECT * FROM qw_project ORDER BY create_time DESC")
	List<SemanticProject> findProjects();

	@Select("SELECT * FROM qw_project WHERE id = #{projectId}")
	SemanticProject findProject(Long projectId);

    @Select("SELECT id FROM qw_project WHERE id=#{projectId} FOR UPDATE")
    Long lockProject(Long projectId);

    @Select("SELECT id FROM qw_project_version WHERE project_id=#{projectId} AND id=#{versionId} FOR UPDATE")
    Long lockVersion(@Param("projectId") Long projectId,@Param("versionId") Long versionId);

	@Select("SELECT revision FROM qw_project WHERE id = #{projectId}")
	Long findProjectRevision(Long projectId);

	@Insert("""
			INSERT INTO qw_project_version
			(project_id, version_no, version_number, semantic_major, semantic_minor, semantic_patch, version_level,
			 version_cause, semantic_state_hash, corpus_revision_id, status, parent_version_id, creation_mode,
			 initialization_model_id, analysis_status, analysis_error, source, evidence, catalog_hash, release_report,
			 create_time, validated_time, published_time, activated_time, deactivated_time, revision)
			VALUES
			(#{projectId}, #{versionNo}, #{versionNumber}, #{semanticMajor}, #{semanticMinor}, #{semanticPatch},
			 #{versionLevel}, #{versionCause}, #{semanticStateHash}, #{corpusRevisionId}, #{status}, #{parentVersionId},
			 #{creationMode}, #{initializationModelId}, #{analysisStatus}, #{analysisError}, #{source}, #{evidence},
			 #{catalogHash}, #{releaseReport}, #{createTime}, #{validatedTime}, #{publishedTime}, #{activatedTime},
			 #{deactivatedTime}, #{revision})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertVersion(SemanticProjectVersion version);

	@Update("""
			UPDATE qw_project_version SET semantic_major = #{semanticMajor}, semantic_minor = #{semanticMinor},
			semantic_patch = #{semanticPatch}, version_level = #{versionLevel}, version_cause = #{versionCause},
			semantic_state_hash = #{semanticStateHash}, corpus_revision_id = #{corpusRevisionId}, status = #{status},
			initialization_model_id = #{initializationModelId}, analysis_status = #{analysisStatus},
			analysis_error = #{analysisError}, source = #{source}, evidence = #{evidence}, catalog_hash = #{catalogHash},
			release_report = #{releaseReport}, validated_time = #{validatedTime}, published_time = #{publishedTime},
			activated_time = #{activatedTime}, deactivated_time = #{deactivatedTime}, revision = revision + 1
			WHERE id = #{id} AND revision = #{revision}
			""")
	int updateVersion(SemanticProjectVersion version);

	@Select("""
			SELECT id, project_id, version_no, version_number, semantic_major, semantic_minor, semantic_patch, version_level,
			version_cause, semantic_state_hash, corpus_revision_id, status, parent_version_id, creation_mode,
			initialization_model_id, analysis_status, analysis_error, source, evidence, catalog_hash, release_report,
			create_time, validated_time, published_time, activated_time, deactivated_time, revision
			FROM qw_project_version WHERE id = #{versionId}
			""")
	SemanticProjectVersion findVersion(Long versionId);

	@Select("SELECT revision FROM qw_project_version WHERE id = #{versionId}")
	Long findVersionRevision(Long versionId);

	@Select("""
			SELECT id, project_id, version_no, version_number, semantic_major, semantic_minor, semantic_patch, version_level,
			version_cause, semantic_state_hash, corpus_revision_id, status, parent_version_id, creation_mode,
			initialization_model_id, analysis_status, analysis_error, source, evidence, catalog_hash, release_report,
			create_time, validated_time, published_time, activated_time, deactivated_time, revision
			FROM qw_project_version WHERE project_id = #{projectId} AND version_number = #{versionNumber}
			""")
	SemanticProjectVersion findVersionByNumber(@Param("projectId") Long projectId,
			@Param("versionNumber") String versionNumber);

	@Select("""
			SELECT id, project_id, version_no, version_number, semantic_major, semantic_minor, semantic_patch, version_level,
			version_cause, semantic_state_hash, corpus_revision_id, status, parent_version_id, creation_mode,
			initialization_model_id, analysis_status, analysis_error, source, evidence, catalog_hash, release_report,
			create_time, validated_time, published_time, activated_time, deactivated_time, revision
			FROM qw_project_version WHERE project_id = #{projectId} ORDER BY version_no DESC LIMIT 1
			""")
	SemanticProjectVersion findLatestVersion(Long projectId);

	@Select("""
			SELECT id, project_id, version_no, version_number, semantic_major, semantic_minor, semantic_patch, version_level,
			version_cause, semantic_state_hash, corpus_revision_id, status, parent_version_id, creation_mode,
			initialization_model_id, analysis_status, analysis_error, source, evidence, catalog_hash, release_report,
			create_time, validated_time, published_time, activated_time, deactivated_time, revision
			FROM qw_project_version WHERE project_id = #{projectId} ORDER BY version_no DESC
			""")
	List<SemanticProjectVersion> findVersions(Long projectId);

	@Insert("""
			INSERT INTO qw_project_datasource_binding
			(project_id, project_version_id, datasource_id, domain_code, domain_name, responsibility, priority,
			 create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{datasourceId}, #{domainCode}, #{domainName}, #{responsibility},
			 #{priority}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertDatasourceBinding(ProjectDatasourceBinding binding);

	@Update("""
			UPDATE qw_project_datasource_binding
			SET domain_code = #{domainCode}, domain_name = #{domainName}, responsibility = #{responsibility},
			priority = #{priority}, update_time = #{updateTime}
			WHERE id = #{id}
			""")
	int updateDatasourceBinding(ProjectDatasourceBinding binding);

	@Select("""
			SELECT binding.*, datasource.name AS datasource_name, datasource.type AS datasource_type
			FROM qw_project_datasource_binding binding
			JOIN datasource ON datasource.id = binding.datasource_id
			WHERE binding.project_version_id = #{projectVersionId} AND binding.datasource_id = #{datasourceId}
			""")
	ProjectDatasourceBinding findDatasourceBinding(@Param("projectVersionId") Long projectVersionId,
			@Param("datasourceId") Integer datasourceId);

	@Select("""
			SELECT binding.*, datasource.name AS datasource_name, datasource.type AS datasource_type
			FROM qw_project_datasource_binding binding
			JOIN datasource ON datasource.id = binding.datasource_id
			WHERE binding.project_version_id = #{projectVersionId}
			ORDER BY binding.priority ASC, binding.id ASC
			""")
	List<ProjectDatasourceBinding> findDatasourceBindings(Long projectVersionId);

	@Delete("""
			DELETE FROM qw_project_datasource_binding
			WHERE project_version_id = #{projectVersionId} AND datasource_id = #{datasourceId}
			""")
	int deleteDatasourceBinding(@Param("projectVersionId") Long projectVersionId,
			@Param("datasourceId") Integer datasourceId);

	@Delete("DELETE FROM qw_project_datasource_table WHERE binding_id = #{bindingId}")
	int deleteDatasourceTables(Long bindingId);

	@Insert("""
			INSERT INTO qw_project_datasource_table (binding_id, table_name, create_time)
			VALUES (#{bindingId}, #{tableName}, CURRENT_TIMESTAMP)
			""")
	int insertDatasourceTable(@Param("bindingId") Long bindingId, @Param("tableName") String tableName);

	@Select("""
			SELECT table_name FROM qw_project_datasource_table
			WHERE binding_id = #{bindingId}
			ORDER BY table_name ASC
			""")
	List<String> findDatasourceTables(Long bindingId);

	@Insert("""
			INSERT INTO qw_semantic_gap
			(project_id, project_version_id, gap_key, gap_type, question, recommendation, evidence, impact_scope,
			 priority, status, answer, resolved_by, create_time, resolved_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{gapKey}, #{gapType}, #{question}, #{recommendation}, #{evidence},
			 #{impactScope}, #{priority}, #{status}, #{answer}, #{resolvedBy}, #{createTime}, #{resolvedTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertGap(SemanticGap gap);

	@Update("""
			UPDATE qw_semantic_gap SET status = #{status}, answer = #{answer}, resolved_by = #{resolvedBy},
			resolved_time = #{resolvedTime}
			WHERE id = #{id}
			""")
	int updateGap(SemanticGap gap);

	@Select("SELECT * FROM qw_semantic_gap WHERE id = #{gapId}")
	SemanticGap findGap(Long gapId);

	@Select("""
			SELECT * FROM qw_semantic_gap
			WHERE project_version_id = #{projectVersionId} AND gap_key = #{gapKey}
			""")
	SemanticGap findGapByKey(@Param("projectVersionId") Long projectVersionId, @Param("gapKey") String gapKey);

	@Update("""
			UPDATE qw_semantic_gap
			SET gap_type = #{gapType}, question = #{question}, recommendation = #{recommendation}, evidence = #{evidence},
			impact_scope = #{impactScope}, priority = #{priority}
			WHERE id = #{id}
			""")
	int updateGapDefinition(SemanticGap gap);

	@Select("""
			SELECT * FROM qw_semantic_gap
			WHERE project_id = #{projectId} AND project_version_id = #{projectVersionId} AND status = 'OPEN'
			ORDER BY priority ASC, id ASC LIMIT 1
			""")
	SemanticGap findNextOpenGap(@Param("projectId") Long projectId, @Param("projectVersionId") Long projectVersionId);

	@Select("""
			SELECT * FROM qw_semantic_gap
			WHERE project_id = #{projectId} AND project_version_id = #{projectVersionId} AND status = 'OPEN'
			ORDER BY priority ASC, id ASC
			""")
	List<SemanticGap> findOpenGaps(@Param("projectId") Long projectId,
			@Param("projectVersionId") Long projectVersionId);

	@Select("""
			SELECT * FROM qw_semantic_gap
			WHERE project_version_id = #{projectVersionId} AND status = 'OPEN'
			  AND gap_key LIKE CONCAT(CAST(#{gapKeyPrefix} AS TEXT), '%')
			ORDER BY priority ASC, id ASC
			""")
	List<SemanticGap> findOpenGapsByKeyPrefix(@Param("projectVersionId") Long projectVersionId,
			@Param("gapKeyPrefix") String gapKeyPrefix);

	@Select("""
			SELECT COUNT(*) FROM qw_semantic_gap
			WHERE project_id = #{projectId} AND project_version_id = #{projectVersionId} AND status = 'OPEN'
			""")
	long countOpenGaps(@Param("projectId") Long projectId, @Param("projectVersionId") Long projectVersionId);

	@Insert("""
			INSERT INTO qw_project_runtime_profile
			(project_id, runtime_profile_id, status, revision, create_time, update_time)
			VALUES (#{projectId}, #{runtimeProfileId}, #{status}, #{revision}, #{createTime}, #{updateTime})
			""")
	int insertRuntimeProfile(ProjectRuntimeProfile profile);

	@Update("""
			UPDATE qw_project_runtime_profile
			SET runtime_profile_id = #{runtimeProfileId}, status = #{status}, revision = #{revision},
			    update_time = #{updateTime}
			WHERE project_id = #{projectId}
			""")
	int updateRuntimeProfile(ProjectRuntimeProfile profile);

	@Select("SELECT * FROM qw_project_runtime_profile WHERE project_id = #{projectId}")
	ProjectRuntimeProfile findRuntimeProfileByProject(Long projectId);

}
