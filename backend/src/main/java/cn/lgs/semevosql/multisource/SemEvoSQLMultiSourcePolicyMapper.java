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
package cn.lgs.semevosql.multisource;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SemEvoSQLMultiSourcePolicyMapper {

	@Delete("DELETE FROM qw_merge_policy WHERE project_version_id = #{versionId}")
	int deleteMergePolicies(@Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_cross_source_relationship WHERE project_version_id = #{versionId}")
	int deleteCrossSourceRelationships(@Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_freshness_policy WHERE project_version_id = #{versionId}")
	int deleteFreshnessPolicies(@Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_authority_rule WHERE project_version_id = #{versionId}")
	int deleteAuthorityRules(@Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_logical_column_binding WHERE project_version_id = #{versionId}")
	int deleteLogicalBindings(@Param("versionId") Long versionId);

	@Insert("""
			INSERT INTO qw_logical_column_binding
			(project_id, project_version_id, logical_entity_code, logical_attribute_code, datasource_id, model_code,
			 column_name, expression, transform_rule, grain_code, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{logicalEntityCode}, #{logicalAttributeCode}, #{datasourceId},
			 #{modelCode}, #{columnName}, #{expression}, #{transformRule}, #{grainCode}, #{evidence}, #{status},
			 #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertLogicalBinding(MultiSourcePolicySnapshot.LogicalColumnBinding binding);

	@Insert("""
			INSERT INTO qw_authority_rule
			(project_id, project_version_id, logical_asset_type, logical_asset_code, datasource_id, source_role,
			 priority, allow_fallback, condition_expression, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{logicalAssetType}, #{logicalAssetCode}, #{datasourceId},
			 #{sourceRole}, #{priority}, #{allowFallback}, #{conditionExpression}, #{evidence}, #{status},
			 #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertAuthorityRule(MultiSourcePolicySnapshot.AuthorityRule rule);

	@Insert("""
			INSERT INTO qw_freshness_policy
			(project_id, project_version_id, datasource_id, business_date_field, time_zone, freshness_type,
			 latency_minutes, available_until_rule, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{datasourceId}, #{businessDateField}, #{timeZone},
			 #{freshnessType}, #{latencyMinutes}, #{availableUntilRule}, #{evidence}, #{status}, #{createTime},
			 #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertFreshnessPolicy(MultiSourcePolicySnapshot.FreshnessPolicy policy);

	@Insert("""
			INSERT INTO qw_cross_source_relationship
			(project_id, project_version_id, relationship_code, left_datasource_id, left_model_code, left_key,
			 right_datasource_id, right_model_code, right_key, cardinality, transform_rule, null_policy,
			 uniqueness_rule, confidence, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{relationshipCode}, #{leftDatasourceId}, #{leftModelCode},
			 #{leftKey}, #{rightDatasourceId}, #{rightModelCode}, #{rightKey}, #{cardinality}, #{transformRule},
			 #{nullPolicy}, #{uniquenessRule}, #{confidence}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertCrossSourceRelationship(MultiSourcePolicySnapshot.CrossSourceRelationship relationship);

	@Insert("""
			INSERT INTO qw_merge_policy
			(project_id, project_version_id, policy_code, merge_type, relationship_code, left_input_key,
			 right_input_key, output_key, input_grain, null_policy, duplicate_policy, max_rows,
			 partial_failure_policy, calculation_expression, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{policyCode}, #{mergeType}, #{relationshipCode}, #{leftInputKey},
			 #{rightInputKey}, #{outputKey}, #{inputGrain}, #{nullPolicy}, #{duplicatePolicy}, #{maxRows},
			 #{partialFailurePolicy}, #{calculationExpression}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertMergePolicy(MultiSourcePolicySnapshot.MergePolicy policy);

	@Select("""
			SELECT * FROM qw_logical_column_binding
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY logical_entity_code, logical_attribute_code, datasource_id
			""")
	List<MultiSourcePolicySnapshot.LogicalColumnBinding> findLogicalBindings(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT * FROM qw_authority_rule
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY logical_asset_type, logical_asset_code, priority, datasource_id
			""")
	List<MultiSourcePolicySnapshot.AuthorityRule> findAuthorityRules(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT * FROM qw_freshness_policy
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY datasource_id
			""")
	List<MultiSourcePolicySnapshot.FreshnessPolicy> findFreshnessPolicies(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT * FROM qw_cross_source_relationship
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY relationship_code
			""")
	List<MultiSourcePolicySnapshot.CrossSourceRelationship> findCrossSourceRelationships(
			@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Select("""
			SELECT * FROM qw_merge_policy
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY policy_code
			""")
	List<MultiSourcePolicySnapshot.MergePolicy> findMergePolicies(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

}
