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
package cn.lgs.semevosql.semantic.domain;

import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.FreshnessType;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergeType;
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
public class SemanticBlueprint {

	private Long projectId;

	private Long projectVersionId;

	private String canonicalQuery;

	private String dialect;

	@Builder.Default
	private String compilerMode = "DETERMINISTIC";

	@Builder.Default
	private ComputationIntent computationIntent = ComputationIntent.empty();

	@Builder.Default
	private List<BindingDependency> bindingDependencies = new ArrayList<>();

	@Builder.Default
	private List<ProjectionSelection> projections = new ArrayList<>();

	@Builder.Default
	private List<FilterSelection> filters = new ArrayList<>();

	@Builder.Default
	private List<EnumResolution> enumResolutions = new ArrayList<>();

	private TimeRangeSelection timeRange;

	@Builder.Default
	private List<GroupSelection> groupBy = new ArrayList<>();

	@Builder.Default
	private List<OrderSelection> orderBy = new ArrayList<>();

	@Builder.Default
	private Integer limit = 100;

    @Builder.Default
    private Long offset = 0L;

	private ExpectedResultShape expectedResult;

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private SemanticResultContract resultContract;

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ScalarCalculation scalarCalculation;

    /** Output validation and presentation must not treat calculation dependencies as returned measures. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<MetricSelection> getOutputMetrics() {
        if(resultContract==null)return metrics;
        var result=new ArrayList<>(metrics.stream().filter(m ->resultContract.metricCodes().contains(m.getMetricCode())).toList());
        resultContract.outputMeasures().forEach(m->result.add(MetricSelection.builder().metricCode(m.outputCode())
            .businessName(m.businessName()).aggregation("EXPRESSION").build()));
        return List.copyOf(result);
    }


	@Builder.Default
	private List<ModelSelection> models = new ArrayList<>();

	@Builder.Default
	private List<MetricSelection> metrics = new ArrayList<>();

	@Builder.Default
	private List<DimensionSelection> dimensions = new ArrayList<>();

	@Builder.Default
	private List<GrainSelection> grains = new ArrayList<>();

	@Builder.Default
	private List<RelationshipSelection> relationships = new ArrayList<>();

	@Builder.Default
	private List<RuleSelection> rules = new ArrayList<>();

	@Builder.Default
	private List<String> preAggregationModelCodes = new ArrayList<>();

	@Builder.Default
	private List<SourceSubPlan> sourceSubPlans = new ArrayList<>();

	private MergePlan mergePlan;

	@Builder.Default
	private List<FreshnessNotice> freshnessNotices = new ArrayList<>();

	@Builder.Default
	private List<String> validationWarnings = new ArrayList<>();

	@Builder.Default
	private List<String> validationErrors = new ArrayList<>();

	private boolean executable;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class BindingDependency {

		private String phrase;

		private String assetType;

		private String assetKey;

		private String scope;

		private String source;

		private String principalId;

		private Long sourceRecordId;
        private Integer sourceRevision;
        private String sourceContentHash;
        private String dependencyFingerprint;
        private String definitionText;
        private String representationCode;
        private String representationHash;


	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ProjectionSelection {

		private String modelCode;

		private String columnName;

		private String expression;

		private String alias;

		private String projectionType;

		private String timeBucketGranularity;

		private Boolean masked;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FilterSelection {

		private String modelCode;

		private String columnName;

		private String expression;

		private String operator;

		private Object value;

		private String valueType;

		private Boolean required;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class EnumResolution {

		private String modelCode;

		private String columnName;

		private String inputText;

		private String valueCode;

		private String businessName;

		private Double confidence;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TimeRangeSelection {

		private String modelCode;

		private String timeColumn;

		private String startInclusive;

		private String endExclusive;

		private String relativeExpression;

		private String timeZone;

		private String granularity;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class GroupSelection {

		private String modelCode;

		private String columnName;

		private String expression;

		private String alias;

		private String timeBucketGranularity;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OrderSelection {

		private String expression;

		private String direction;

		private String nulls;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ExpectedResultShape {

		@Builder.Default
		private List<String> columns = new ArrayList<>();

		private String grain;

		private Integer maxRows;

		private Boolean tabular;

		private Boolean chartable;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ModelSelection {

		private String modelCode;

		private String physicalTable;

		private String businessName;

		private Integer datasourceId;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MetricSelection {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SemanticDefinitionBinding definitionBinding;


		private String metricCode;

		private String modelCode;

		private String businessName;

		private String expression;

		private String aggregation;

		private String unit;

		private String timeColumn;

		private String filterExpression;

		private String additiveType;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private NumericValueRange numericRange;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class DimensionSelection {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SemanticDefinitionBinding definitionBinding;


		private String dimensionCode;

		private String modelCode;

		private String businessName;

		private String columnName;

		private String expression;

		private String dimensionType;

		private String hierarchy;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class GrainSelection {

		private String grainCode;

		private String modelCode;

		private String keyColumns;

		private String timeColumn;

		private String uniquenessRule;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RelationshipSelection {

		private String relationshipCode;

		private String sourceModelCode;

		private String targetModelCode;

		private RelationshipCardinality cardinality;

		private String joinType;

		private String joinCondition;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RuleSelection {

		private String ruleCode;

		private String modelCode;

		private String ruleType;

		private String businessName;

		private String expression;

		private String severity;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class SourceSubPlan {

		private Integer datasourceId;

		private String domainCode;

		private String responsibility;

		private Integer priority;

		private Integer authorityRank;

		@Builder.Default
		private List<String> modelCodes = new ArrayList<>();

		@Builder.Default
		private List<String> physicalTables = new ArrayList<>();

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FreshnessNotice {

		private Integer datasourceId;

		private String businessDateField;

		private String timeZone;

		private FreshnessType freshnessType;

		private Integer latencyMinutes;

		private String availableUntilRule;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MergePlan {

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

	}

}
