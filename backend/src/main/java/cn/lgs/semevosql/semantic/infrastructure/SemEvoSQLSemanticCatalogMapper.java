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

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SemEvoSQLSemanticCatalogMapper {
    @Select("SELECT status FROM qw_project_version WHERE project_id=#{projectId} AND id=#{versionId} FOR UPDATE")
    String lockCatalogVersion(@Param("projectId") Long projectId,@Param("versionId") Long versionId);


	@Delete("DELETE FROM qw_semantic_enum_value WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteEnumValues(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_rule WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteRules(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_relationship WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteRelationships(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_metric WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteMetrics(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_dimension WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteDimensions(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_grain WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteGrains(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_column WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteColumns(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_model WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Insert("""
			INSERT INTO qw_semantic_model
			(project_id, project_version_id, datasource_id, model_code, physical_table, business_name, model_type, description,
			 evidence, status, create_time, update_time, source_json, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{datasourceId}, #{modelCode}, #{physicalTable}, #{businessName},
			 #{modelType}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime}, CAST(#{sourceJson} AS jsonb), #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertModel(SemanticCatalogSnapshot.Model model);

	@Insert("""
			INSERT INTO qw_semantic_column
			(project_id, project_version_id, model_code, column_name, business_name, data_type, role, expression,
			 synonyms, description, nullable_flag, sensitivity_level, masking_policy, allow_aggregation, allow_filter,
			 allow_projection, allow_export, allow_send_to_llm, evidence, status, create_time, update_time, unit, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{modelCode}, #{columnName}, #{businessName}, #{dataType}, #{role},
			 #{expression}, #{synonyms}, #{description}, #{nullable}, #{sensitivityLevel}, #{maskingPolicy},
			 #{allowAggregation}, #{allowFilter}, #{allowProjection}, #{allowExport}, #{allowSendToLlm}, #{evidence},
			 #{status}, #{createTime}, #{updateTime}, #{unit}, #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertColumn(SemanticCatalogSnapshot.Column column);

	@Insert("""
			INSERT INTO qw_semantic_metric
			(project_id, project_version_id, model_code, metric_code, business_name, expression, aggregation, unit,
			 time_column, filter_expression, additive_type, description, evidence, status, create_time, update_time,
             minimum_value,maximum_value,minimum_inclusive,maximum_inclusive, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{modelCode}, #{metricCode}, #{businessName}, #{expression},
			 #{aggregation}, #{unit}, #{timeColumn}, #{filterExpression}, #{additiveType}, #{description}, #{evidence},
			 #{status}, #{createTime}, #{updateTime}, #{minimumValue}, #{maximumValue}, #{minimumInclusive}, #{maximumInclusive}, #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertMetric(SemanticCatalogSnapshot.Metric metric);

	@Insert("""
			INSERT INTO qw_semantic_dimension
			(project_id, project_version_id, model_code, dimension_code, business_name, column_name, expression,
			 dimension_type, hierarchy, description, evidence, status, create_time, update_time, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{modelCode}, #{dimensionCode}, #{businessName}, #{columnName},
			 #{expression}, #{dimensionType}, #{hierarchy}, #{description}, #{evidence}, #{status}, #{createTime},
			 #{updateTime}, #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertDimension(SemanticCatalogSnapshot.Dimension dimension);

	@Insert("""
			INSERT INTO qw_semantic_relationship
			(project_id, project_version_id, relationship_code, source_model_code, target_model_code, cardinality,
			 join_type, join_condition, description, evidence, status, create_time, update_time, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{relationshipCode}, #{sourceModelCode}, #{targetModelCode},
			 #{cardinality}, #{joinType}, #{joinCondition}, #{description}, #{evidence}, #{status}, #{createTime},
			 #{updateTime}, #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertRelationship(SemanticCatalogSnapshot.Relationship relationship);

	@Insert("""
			INSERT INTO qw_semantic_grain
			(project_id, project_version_id, model_code, grain_code, key_columns, time_column, uniqueness_rule,
			 description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{grainCode},
			 to_jsonb(string_to_array(CAST(#{keyColumns} AS TEXT), ',')), #{timeColumn},
			 #{uniquenessRule}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertGrain(SemanticCatalogSnapshot.Grain grain);

	@Insert("""
			INSERT INTO qw_semantic_enum_value
			(project_id, project_version_id, model_code, column_name, value_code, business_name, aliases, description,
			 sort_order, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{columnName}, #{valueCode}, #{businessName},
			 to_jsonb(string_to_array(NULLIF(CAST(#{aliases} AS TEXT), ''), ',')),
			 #{description}, #{sortOrder}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertEnumValue(SemanticCatalogSnapshot.EnumValue value);

	@Insert("""
			INSERT INTO qw_semantic_rule
			(project_id, project_version_id, model_code, rule_code, rule_type, business_name, expression, severity,
			 description, evidence, status, create_time, update_time, retrieval_json)
            VALUES
            (#{projectId}, #{projectVersionId}, #{modelCode}, #{ruleCode}, #{ruleType}, #{businessName},
			 #{expression}, #{severity}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime}, #{retrievalJson})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertRule(SemanticCatalogSnapshot.Rule rule);

	@Select("SELECT * FROM qw_semantic_model WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY model_code")
	List<SemanticCatalogSnapshot.Model> findModels(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, column_name, business_name, data_type, role,
			expression, synonyms, description, unit, nullable_flag AS nullable, sensitivity_level, masking_policy,
			allow_aggregation, allow_filter, allow_projection, allow_export, allow_send_to_llm, evidence, status, retrieval_json,
			create_time, update_time
			FROM qw_semantic_column
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, column_name
			""")
	List<SemanticCatalogSnapshot.Column> findColumns(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_metric WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY metric_code")
	List<SemanticCatalogSnapshot.Metric> findMetrics(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_dimension WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY dimension_code")
	List<SemanticCatalogSnapshot.Dimension> findDimensions(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_relationship WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY relationship_code")
	List<SemanticCatalogSnapshot.Relationship> findRelationships(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, grain_code,
			       CASE
			           WHEN jsonb_typeof(key_columns) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(key_columns) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(key_columns) = 'string' THEN key_columns #>> '{}'
			           ELSE key_columns::text
			       END AS key_columns,
			       time_column, uniqueness_rule, description, evidence, status, create_time, update_time
			FROM qw_semantic_grain
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, grain_code
			""")
	List<SemanticCatalogSnapshot.Grain> findGrains(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, column_name, value_code, business_name,
			       CASE
			           WHEN aliases IS NULL THEN NULL
			           WHEN jsonb_typeof(aliases) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(aliases) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(aliases) = 'string' THEN aliases #>> '{}'
			           ELSE aliases::text
			       END AS aliases,
			       description, sort_order, evidence, status, create_time, update_time
			FROM qw_semantic_enum_value
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, column_name, sort_order, id
			""")
	List<SemanticCatalogSnapshot.EnumValue> findEnumValues(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_rule WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY rule_code")
	List<SemanticCatalogSnapshot.Rule> findRules(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_model WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach> ORDER BY model_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Model> findModelsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT id, project_id, project_version_id, model_code, column_name, business_name, data_type, role,
			expression, synonyms, description, unit, nullable_flag AS nullable, sensitivity_level, masking_policy,
			allow_aggregation, allow_filter, allow_projection, allow_export, allow_send_to_llm, evidence, status, retrieval_json,
			create_time, update_time
			FROM qw_semantic_column
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
			ORDER BY model_code, column_name
        </script>
        """)
    List<SemanticCatalogSnapshot.Column> findColumnsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_metric WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach> ORDER BY metric_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Metric> findMetricsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_dimension WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach> ORDER BY dimension_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Dimension> findDimensionsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_relationship WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND (source_model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach> AND target_model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>) ORDER BY relationship_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Relationship> findRelationshipsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT id, project_id, project_version_id, model_code, grain_code,
			       CASE
			           WHEN jsonb_typeof(key_columns) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(key_columns) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(key_columns) = 'string' THEN key_columns #>> '{}'
			           ELSE key_columns::text
			       END AS key_columns,
			       time_column, uniqueness_rule, description, evidence, status, create_time, update_time
			FROM qw_semantic_grain
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
			ORDER BY model_code, grain_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Grain> findGrainsForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT id, project_id, project_version_id, model_code, column_name, value_code, business_name,
			       CASE
			           WHEN aliases IS NULL THEN NULL
			           WHEN jsonb_typeof(aliases) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(aliases) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(aliases) = 'string' THEN aliases #>> '{}'
			           ELSE aliases::text
			       END AS aliases,
			       description, sort_order, evidence, status, create_time, update_time
			FROM qw_semantic_enum_value
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
			ORDER BY model_code, column_name, sort_order, id
        </script>
        """)
    List<SemanticCatalogSnapshot.EnumValue> findEnumValuesForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_rule WHERE project_id = #{projectId} AND project_version_id = #{versionId}
 AND status = 'ENABLED' AND (model_code IS NULL OR model_code = '' OR model_code IN <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>) ORDER BY rule_code
        </script>
        """)
    List<SemanticCatalogSnapshot.Rule> findRulesForModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId, @Param("modelCodes") java.util.Set<String> modelCodes);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_model WHERE project_id=#{projectId} AND project_version_id=#{versionId}
        AND status='ENABLED' AND physical_table IN
        <foreach collection="tables" item="table" open="(" separator="," close=")">#{table}</foreach>
        ORDER BY model_code LIMIT #{limit}
        </script>
        """)
    List<SemanticCatalogSnapshot.Model> findModelsForTables(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
        @Param("tables") java.util.Set<String> tables, @Param("limit") int limit);

    @Select("""
        <script>
        SELECT * FROM qw_semantic_relationship WHERE project_id=#{projectId} AND project_version_id=#{versionId}
        AND status='ENABLED' AND (source_model_code IN
        <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
        OR target_model_code IN
        <foreach collection="modelCodes" item="code" open="(" separator="," close=")">#{code}</foreach>)
        ORDER BY relationship_code LIMIT #{limit}
        </script>
        """)
    List<SemanticCatalogSnapshot.Relationship> findRelationshipsTouching(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
        @Param("modelCodes") java.util.Set<String> modelCodes, @Param("limit") int limit);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT catalog_hash FROM qw_project_version WHERE project_id=#{projectId} AND id=#{versionId}")
    String authoritativeCatalogHash(@Param("projectId") Long projectId, @Param("versionId") Long versionId);


    @Select("""
        SELECT id,project_id,project_version_id,datasource_id,model_code,physical_table,business_name,model_type,status,source_json
        FROM qw_semantic_model WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
        ORDER BY model_code LIMIT #{limit}
        """)
    List<SemanticCatalogSnapshot.Model> findEnabledModelSummaries(@Param("projectId") Long projectId,@Param("versionId") Long versionId,@Param("limit") int limit);

    @Select("SELECT DISTINCT datasource_id FROM qw_semantic_model WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'")
    java.util.Set<Integer> enabledDatasourceIds(@Param("projectId") Long projectId,@Param("versionId") Long versionId);

    @Select("""
        SELECT DISTINCT name FROM qw_semantic_model m
        CROSS JOIN LATERAL (
            SELECT m.physical_table AS name WHERE m.source_json IS NULL
            UNION ALL
            SELECT item->>'schema'||'.'||(item->>'table') AS name
            FROM jsonb_array_elements(COALESCE(m.source_json->'tables','[]'::jsonb)) item
        ) sources
        WHERE m.project_id=#{projectId} AND m.project_version_id=#{versionId} AND m.status='ENABLED'
        """)
    java.util.Set<String> enabledPhysicalTables(@Param("projectId") Long projectId,@Param("versionId") Long versionId);
    @Select("""
        <script><choose>
        <when test="assetType == 'MODEL'">
          SELECT model_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_model WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (model_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'METRIC'">
          SELECT metric_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_metric WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (metric_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'DIMENSION'">
          SELECT dimension_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_dimension WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (dimension_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
          UNION ALL
          SELECT projection.asset_key, binding.model_code, NULL::text AS related_model_code
          FROM qw_semantic_model_asset_binding binding
          JOIN qw_semantic_definition_revision definition ON definition.project_id=binding.project_id
            AND definition.definition_code=binding.definition_code AND definition.revision=binding.definition_revision
          JOIN qw_semantic_model model ON model.project_id=binding.project_id
            AND model.project_version_id=binding.project_version_id AND model.model_code=binding.model_code
          CROSS JOIN LATERAL (SELECT 'a_' || substr(encode(sha256(convert_to(
            '[' || to_json(binding.model_code)::text || ',' || to_json(binding.binding_code)::text || ']', 'UTF8')), 'hex'),1,32) AS asset_key) projection
          WHERE binding.project_id=#{projectId} AND binding.project_version_id=#{versionId}
            AND binding.asset_type='ATTRIBUTE' AND definition.definition_format IN ('AST_V1_2','LEGACY_PROJECTION') AND model.status='ENABLED'
            AND projection.asset_key IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'RELATIONSHIP'">
          SELECT relationship_code AS asset_key, source_model_code AS model_code, target_model_code AS related_model_code
          FROM qw_semantic_relationship WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (relationship_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'GRAIN'">
          SELECT grain_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_grain WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (grain_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'RULE'">
          SELECT rule_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_rule WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (rule_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'ENUM_VALUE'">
          SELECT model_code || ':' || column_name || ':' || value_code AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_enum_value WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (model_code || ':' || column_name || ':' || value_code) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'COLUMN'">
          SELECT model_code || ':' || column_name AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_column WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND (model_code || ':' || column_name) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <when test="assetType == 'TIME_COLUMN'">
          SELECT model_code || ':' || column_name AS asset_key, model_code AS model_code, NULL::text AS related_model_code
          FROM qw_semantic_column WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          AND role='TIME'
          AND (model_code || ':' || column_name) IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </when>
        <otherwise>SELECT NULL::text AS asset_key, NULL::text AS model_code, NULL::text AS related_model_code WHERE FALSE</otherwise>
        </choose></script>
        """)
    List<cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.AssetOwner> findAssetOwners(
        @Param("projectId") Long projectId, @Param("versionId") Long versionId,
        @Param("assetType") String assetType, @Param("keys") java.util.Set<String> keys);

    @Select("""
        SELECT * FROM qw_semantic_rule WHERE project_id=#{projectId} AND project_version_id=#{versionId}
        AND status='ENABLED' AND (model_code IS NULL OR model_code='') ORDER BY rule_code
        """)
    List<SemanticCatalogSnapshot.Rule> findGlobalRules(@Param("projectId") Long projectId, @Param("versionId") Long versionId);
    @Select("""
        <script>
        SELECT model_code,business_name,model_type,status FROM qw_semantic_model
        WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
        <if test="afterModelCode != null">AND model_code &gt; #{afterModelCode}</if>
        ORDER BY model_code LIMIT #{limit}
        </script>
        """)
    List<SemanticCatalogSnapshot.Model> findModelSummaryPage(@Param("projectId") Long projectId,
        @Param("versionId") Long versionId,@Param("afterModelCode") String afterModelCode,@Param("limit") int limit);
    @Select("""
        <script>
        SELECT a.id,a.asset_key,a.model_code,a.business_name,a.term_code FROM (
          <choose>
          <when test="type == 'MODEL'">
            SELECT id,model_code AS asset_key,model_code,business_name,model_code AS term_code
            FROM qw_semantic_model WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          </when>
          <when test="type == 'COLUMN' or type == 'TIME_COLUMN'">
            SELECT id,model_code || ':' || column_name AS asset_key,model_code,business_name,column_name AS term_code
            FROM qw_semantic_column WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
            <if test="type == 'TIME_COLUMN'">AND role='TIME'</if>
          </when>
          <when test="type == 'METRIC'">
            SELECT id,metric_code AS asset_key,model_code,business_name,metric_code AS term_code
            FROM qw_semantic_metric WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          </when>
          <when test="type == 'DIMENSION'">
            SELECT id,dimension_code AS asset_key,model_code,business_name,dimension_code AS term_code
            FROM qw_semantic_dimension WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          </when>
          <when test="type == 'ENUM_VALUE'">
            SELECT id,model_code || ':' || column_name || ':' || value_code AS asset_key,model_code,business_name,value_code AS term_code
            FROM qw_semantic_enum_value WHERE project_id=#{projectId} AND project_version_id=#{versionId} AND status='ENABLED'
          </when>
          <otherwise>
            SELECT NULL::bigint AS id,NULL::text AS asset_key,NULL::text AS model_code,NULL::text AS business_name,NULL::text AS term_code WHERE FALSE
          </otherwise>
          </choose>
        ) a JOIN qw_semantic_model m ON m.project_id=#{projectId} AND m.project_version_id=#{versionId}
          AND m.model_code=a.model_code AND m.status='ENABLED'
        WHERE a.id &gt; #{afterId}
        <if test="query != null and query != ''">
          AND (POSITION(LOWER(#{query}) IN LOWER(COALESCE(a.business_name,''))) &gt; 0
            OR POSITION(LOWER(#{query}) IN LOWER(a.asset_key)) &gt; 0)
        </if>
        ORDER BY a.id LIMIT #{limit}
        </script>
        """)
    List<cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository.BindingTerm> findBindingTermPage(
        @Param("projectId") Long projectId,@Param("versionId") Long versionId,@Param("type") String type,
        @Param("afterId") long afterId,@Param("limit") int limit,@Param("query") String query);


    @Delete("DELETE FROM qw_semantic_model_asset_binding WHERE project_id=#{projectId} AND project_version_id=#{versionId}")
    int deleteSharedBindings(Long projectId,Long versionId);
    @Delete("DELETE FROM qw_semantic_version_definition WHERE project_id=#{projectId} AND project_version_id=#{versionId}")
    int deleteSharedDefinitionLinks(Long projectId,Long versionId);
    @Delete("DELETE FROM qw_semantic_version_dictionary WHERE project_id=#{projectId} AND project_version_id=#{versionId}")
    int deleteSharedDictionaryLinks(Long projectId,Long versionId);
    @Insert("INSERT INTO qw_semantic_definition_revision(project_id,definition_code,revision,asset_type,definition_json,content_hash) VALUES(#{project},#{code},#{revision},#{type},CAST(#{json} AS JSONB),#{hash}) ON CONFLICT DO NOTHING")
    int insertSharedDefinition(java.util.Map<String,Object> row);
    @Select("SELECT content_hash FROM qw_semantic_definition_revision WHERE project_id=#{project} AND definition_code=#{code} AND revision=#{revision}")
    String sharedDefinitionHash(java.util.Map<String,Object> row);
    @Insert("INSERT INTO qw_semantic_version_definition(project_id,project_version_id,definition_code,definition_revision) VALUES(#{project},#{version},#{code},#{revision})")
    int linkSharedDefinition(java.util.Map<String,Object> row);
    @Insert("INSERT INTO qw_semantic_enum_dictionary_revision(project_id,dictionary_code,revision,metadata_json,content_hash) VALUES(#{project},#{code},#{revision},CAST(#{json} AS JSONB),#{hash}) ON CONFLICT DO NOTHING")
    int insertSharedDictionary(java.util.Map<String,Object> row);
    @Select("SELECT content_hash FROM qw_semantic_enum_dictionary_revision WHERE project_id=#{project} AND dictionary_code=#{code} AND revision=#{revision}")
    String sharedDictionaryHash(java.util.Map<String,Object> row);
    @Insert("INSERT INTO qw_semantic_enum_dictionary_entry(project_id,dictionary_code,revision,value_key,ordinal_no,entry_json) VALUES(#{project},#{code},#{revision},#{value},#{ordinal},CAST(#{json} AS JSONB)) ON CONFLICT DO NOTHING")
    int insertSharedDictionaryEntry(java.util.Map<String,Object> row);
    @Insert("INSERT INTO qw_semantic_version_dictionary(project_id,project_version_id,dictionary_code,dictionary_revision) VALUES(#{project},#{version},#{code},#{revision})")
    int linkSharedDictionary(java.util.Map<String,Object> row);
    @Insert("""
        INSERT INTO qw_semantic_model_asset_binding(project_id,project_version_id,model_code,binding_code,
            definition_code,definition_revision,asset_type,asset_key,dictionary_code,dictionary_revision,binding_json)
        VALUES(#{project},#{version},#{model},#{code},#{definition},#{revision},#{type},#{asset},#{dictionary},#{dictionaryRevision},CAST(#{json} AS JSONB))
        """)
    int insertSharedBinding(java.util.Map<String,Object> row);
    @Select("""
        <script>SELECT b.binding_json::text FROM qw_semantic_model_asset_binding b JOIN qw_semantic_definition_revision d
            ON d.project_id=b.project_id AND d.definition_code=b.definition_code AND d.revision=b.definition_revision
        WHERE b.project_id=#{project} AND b.project_version_id=#{version} AND d.definition_format='AST_V1_2'
        <if test="models != null">AND b.model_code IN
            <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach></if>
        ORDER BY b.model_code,b.binding_code</script>
        """)
    java.util.List<String> findSharedBindings(@Param("project") Long project,@Param("version") Long version,@Param("models") java.util.Set<String> models);
    @Select("""
        <script>SELECT d.definition_json::text FROM qw_semantic_version_definition v JOIN qw_semantic_definition_revision d
            ON d.project_id=v.project_id AND d.definition_code=v.definition_code AND d.revision=v.definition_revision
        WHERE v.project_id=#{project} AND v.project_version_id=#{version} AND d.definition_format='AST_V1_2'
        <if test="models != null">AND EXISTS(SELECT 1 FROM qw_semantic_model_asset_binding b WHERE b.project_id=v.project_id
            AND b.project_version_id=v.project_version_id AND b.definition_code=v.definition_code AND b.definition_revision=v.definition_revision
            AND b.model_code IN <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach>)</if>
        ORDER BY d.definition_code,d.revision</script>
        """)
    java.util.List<String> findSharedDefinitions(@Param("project") Long project,@Param("version") Long version,@Param("models") java.util.Set<String> models);
    @Select("""
        <script>SELECT (d.metadata_json || jsonb_build_object('entries',(SELECT jsonb_agg(e.entry_json ORDER BY e.ordinal_no)
            FROM qw_semantic_enum_dictionary_entry e WHERE e.project_id=d.project_id AND e.dictionary_code=d.dictionary_code AND e.revision=d.revision)))::text
        FROM qw_semantic_version_dictionary v JOIN qw_semantic_enum_dictionary_revision d
            ON d.project_id=v.project_id AND d.dictionary_code=v.dictionary_code AND d.revision=v.dictionary_revision
        WHERE v.project_id=#{project} AND v.project_version_id=#{version}
        <if test="models != null">AND EXISTS(SELECT 1 FROM qw_semantic_model_asset_binding b WHERE b.project_id=v.project_id
            AND b.project_version_id=v.project_version_id AND b.dictionary_code=v.dictionary_code AND b.dictionary_revision=v.dictionary_revision
            AND b.model_code IN <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach>)</if>
        ORDER BY d.dictionary_code,d.revision</script>
        """)
    java.util.List<String> findSharedDictionaries(@Param("project") Long project,@Param("version") Long version,@Param("models") java.util.Set<String> models);

    @Select("SELECT qw_register_legacy_model_assets(#{projectId},#{versionId})")
    String registerLegacyAssets(Long projectId,Long versionId);

    @Select("""
        <script>SELECT (b.binding_json || jsonb_build_object('assetType',b.asset_type,'assetKey',b.asset_key,
            'definitionJson',d.definition_json))::text
        FROM qw_semantic_model_asset_binding b JOIN qw_semantic_definition_revision d
            ON d.project_id=b.project_id AND d.definition_code=b.definition_code AND d.revision=b.definition_revision
        WHERE b.project_id=#{project} AND b.project_version_id=#{version} AND d.definition_format='LEGACY_PROJECTION'
        <if test="models != null">AND b.model_code IN
            <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach></if>
        ORDER BY b.model_code,b.binding_code</script>
        """)
    java.util.List<String> findLegacyBindings(@Param("project") Long project,@Param("version") Long version,@Param("models") java.util.Set<String> models);
}
