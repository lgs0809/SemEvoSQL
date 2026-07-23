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

import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiSourcePolicySnapshot {

	private Long projectId;

	private Long projectVersionId;

	@Builder.Default
	private List<LogicalColumnBinding> logicalBindings = new ArrayList<>();

	@Builder.Default
	private List<AuthorityRule> authorityRules = new ArrayList<>();

	@Builder.Default
	private List<FreshnessPolicy> freshnessPolicies = new ArrayList<>();

	@Builder.Default
	private List<CrossSourceRelationship> crossSourceRelationships = new ArrayList<>();

	@Builder.Default
	private List<MergePolicy> mergePolicies = new ArrayList<>();

	public enum LogicalAssetType {

		MODEL, ATTRIBUTE, METRIC, DIMENSION

	}

	public enum SourceRole {

		AUTHORITATIVE, REPLICA, SNAPSHOT, DERIVED, FALLBACK

	}

	public enum FreshnessType {

		REALTIME, NEAR_REALTIME, BATCH, SNAPSHOT

	}

	public enum MergeType {

		LOOKUP_ENRICHMENT, AGGREGATION_MERGE, UNION, IDENTITY_STITCHING, SEQUENTIAL_DEPENDENCY, DERIVED_CALCULATION,
		SCALAR_COMPOSITION

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class LogicalColumnBinding {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String logicalEntityCode;

		private String logicalAttributeCode;

		private Integer datasourceId;

		private String modelCode;

		private String columnName;

		private String expression;

		private String transformRule;

		private String grainCode;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class AuthorityRule {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private LogicalAssetType logicalAssetType;

		private String logicalAssetCode;

		private Integer datasourceId;

		private SourceRole sourceRole;

		private Integer priority;

		private Boolean allowFallback;

		private String conditionExpression;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FreshnessPolicy {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private Integer datasourceId;

		private String businessDateField;

		private String timeZone;

		private FreshnessType freshnessType;

		private Integer latencyMinutes;

		private String availableUntilRule;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CrossSourceRelationship {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String relationshipCode;

		private Integer leftDatasourceId;

		private String leftModelCode;

		private String leftKey;

		private Integer rightDatasourceId;

		private String rightModelCode;

		private String rightKey;

		private RelationshipCardinality cardinality;

		private String transformRule;

		private String nullPolicy;

		private String uniquenessRule;

		private Integer confidence;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MergePolicy {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String policyCode;

		private MergeType mergeType;

		private String relationshipCode;

		private String leftInputKey;

		private String rightInputKey;

		private String outputKey;

		private String inputGrain;

		private String nullPolicy;

		private String duplicatePolicy;

		private Integer maxRows;

		private String partialFailurePolicy;

		private String calculationExpression;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

}
