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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticCatalogSnapshot {

	private Long projectId;

	private Long projectVersionId;

	@Builder.Default
	private List<Model> models = new ArrayList<>();

	@Builder.Default
	private List<Column> columns = new ArrayList<>();

	@Builder.Default
	private List<Metric> metrics = new ArrayList<>();

	@Builder.Default
	private List<Dimension> dimensions = new ArrayList<>();

	@Builder.Default
	private List<Relationship> relationships = new ArrayList<>();

	@Builder.Default
	private List<Grain> grains = new ArrayList<>();

	@Builder.Default
	private List<EnumValue> enumValues = new ArrayList<>();

	@Builder.Default
	private List<Rule> rules = new ArrayList<>();

    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private List<com.fasterxml.jackson.databind.JsonNode> sharedDefinitions = new ArrayList<>();

    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private List<com.fasterxml.jackson.databind.JsonNode> modelBindings = new ArrayList<>();

    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private List<com.fasterxml.jackson.databind.JsonNode> enumDictionaries = new ArrayList<>();

    /** Internal migration identities do not alter old serialized catalogs or frozen hashes. */
    @Builder.Default
    @JsonIgnore
    private List<com.fasterxml.jackson.databind.JsonNode> legacyModelBindings = new ArrayList<>();

    /** Copy for transient planning overlays, including authority metadata intentionally excluded from wire hashes. */
    public SemanticCatalogSnapshot detachedCopy() {
        var copy=cn.lgs.semevosql.util.JsonUtil.getObjectMapper().convertValue(this,SemanticCatalogSnapshot.class);
        copy.setLegacyModelBindings(legacyModelBindings.stream().map(n->(com.fasterxml.jackson.databind.JsonNode)n.deepCopy()).toList());
        return copy;
    }

	public Set<Integer> enabledDatasourceIds() {
		return models.stream()
			.filter(Model::isEnabled)
			.map(Model::getDatasourceId)
			.filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	public Set<String> enabledPhysicalTables() {
		return models.stream()
			.filter(Model::isEnabled)
			.flatMap(model -> model.physicalTables().stream())
			.filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	public SemanticCatalogSnapshot filterByPhysicalTables(Set<String> physicalTables) {
		Set<String> modelCodes = models.stream()
			.filter(Model::isEnabled)
			.filter(model -> physicalTables.contains(model.getPhysicalTable()))
			.map(Model::getModelCode)
			.collect(Collectors.toUnmodifiableSet());
		return SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
            .modelBindings(bindingsForModels(modelCodes))
            .sharedDefinitions(definitionsForModels(modelCodes))
            .enumDictionaries(dictionariesForModels(modelCodes))
            .legacyModelBindings(legacyModelBindings.stream().filter(b->modelCodes.contains(b.path("model").asText())).toList())
			.models(models.stream().filter(model -> modelCodes.contains(model.getModelCode())).toList())
			.columns(columns.stream().filter(column -> modelCodes.contains(column.getModelCode())).toList())
			.metrics(metrics.stream().filter(metric -> modelCodes.contains(metric.getModelCode())).toList())
			.dimensions(dimensions.stream().filter(dimension -> modelCodes.contains(dimension.getModelCode())).toList())
			.relationships(relationships.stream()
				.filter(relationship -> modelCodes.contains(relationship.getSourceModelCode())
						&& modelCodes.contains(relationship.getTargetModelCode()))
				.toList())
			.grains(grains.stream().filter(grain -> modelCodes.contains(grain.getModelCode())).toList())
			.enumValues(enumValues.stream().filter(value -> modelCodes.contains(value.getModelCode())).toList())
			.rules(rules.stream()
				.filter(rule -> rule.getModelCode() == null || modelCodes.contains(rule.getModelCode()))
				.toList())
			.build();
	}

    public List<com.fasterxml.jackson.databind.JsonNode> bindingsForModels(Set<String> modelCodes) {
        return modelBindings.stream().filter(b->modelCodes.contains(b.path("model").asText())).toList();
    }
    public List<com.fasterxml.jackson.databind.JsonNode> definitionsForModels(Set<String> modelCodes) {
        var keys=bindingsForModels(modelCodes).stream().map(b->b.path("definition").asText()+"@"+b.path("definitionRevision").asInt()).collect(Collectors.toSet());
        return sharedDefinitions.stream().filter(d->keys.contains(d.path("code").asText()+"@"+d.path("revision").asInt())).toList();
    }
    public List<com.fasterxml.jackson.databind.JsonNode> dictionariesForModels(Set<String> modelCodes) {
        var keys=bindingsForModels(modelCodes).stream().filter(b->b.has("dictionary")).map(b->b.path("dictionary").path("code").asText()+"@"+b.path("dictionary").path("revision").asInt()).collect(Collectors.toSet());
        return enumDictionaries.stream().filter(d->keys.contains(d.path("code").asText()+"@"+d.path("revision").asInt())).toList();
    }

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Model {
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private Integer datasourceId;

		private String modelCode;

		private String physicalTable;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceJson;

        public void setSourceJson(String value) {
            if(value==null){sourceJson=null;return;}
            try {sourceJson=cn.lgs.semevosql.util.CanonicalJson.write(GovernedModelSource.parse(value));}
            catch(Exception invalid){throw new IllegalArgumentException("Invalid governed source",invalid);}
        }

        @JsonIgnore
        public List<String> physicalTables() {
            return sourceJson==null ? List.of(physicalTable) : GovernedModelSource.parse(sourceJson).physicalTables();
        }

		private String businessName;

		private String modelType;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

		@JsonIgnore
		public boolean isEnabled() {
			return status == SemanticAssetStatus.ENABLED;
		}

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Column {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SemanticDefinitionBinding definitionBinding;

        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String columnName;

		private String businessName;

		private String dataType;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String unit;

		private SemanticColumnRole role;

		private String expression;

		private String synonyms;

		private String description;

		private Boolean nullable;

		@Builder.Default
		private String sensitivityLevel = "PUBLIC";

		@Builder.Default
		private String maskingPolicy = "NONE";

		@Builder.Default
		private Boolean allowAggregation = true;

		@Builder.Default
		private Boolean allowFilter = true;

		@Builder.Default
		private Boolean allowProjection = true;

		@Builder.Default
		private Boolean allowExport = true;

		@Builder.Default
		private Boolean allowSendToLlm = true;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Metric {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SemanticDefinitionBinding definitionBinding;

        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String metricCode;

		private String businessName;

		private String expression;

		private String aggregation;

		private String unit;

		private String timeColumn;

		private String filterExpression;

		private String additiveType;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private java.math.BigDecimal minimumValue;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private java.math.BigDecimal maximumValue;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean minimumInclusive;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean maximumInclusive;

        @JsonIgnore
        public NumericValueRange numericRange() {
            if(minimumValue==null && maximumValue==null) {
                if(minimumInclusive!=null || maximumInclusive!=null)throw new IllegalArgumentException("Range flags require bounds");
                return null;
            }
            if((minimumInclusive!=null && minimumValue==null) || (maximumInclusive!=null && maximumValue==null))
                throw new IllegalArgumentException("Range flags require their corresponding bound");
            return new NumericValueRange(minimumValue,maximumValue,!Boolean.FALSE.equals(minimumInclusive),!Boolean.FALSE.equals(maximumInclusive));
        }

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Dimension {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private SemanticDefinitionBinding definitionBinding;

        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String dimensionCode;

		private String businessName;

		private String columnName;

		private String expression;

		private String dimensionType;

		private String hierarchy;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Relationship {
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String relationshipCode;

		private String sourceModelCode;

		private String targetModelCode;

		private RelationshipCardinality cardinality;

		private String joinType;

		private String joinCondition;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Grain {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String grainCode;

		private String keyColumns;

		private String timeColumn;

		private String uniquenessRule;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class EnumValue {

        /** Explicit dictionary aliases; hydrated from the referenced immutable revision. */
        @Builder.Default
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<String> confirmedAliases = new ArrayList<>();

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String columnName;

		private String valueCode;

		private String businessName;

		private String aliases;

		private String description;

		private Integer sortOrder;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Rule {
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String retrievalJson;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public RetrievalHints getRetrieval() { return RetrievalHints.decode(retrievalJson); }

        public void setRetrieval(RetrievalHints value) { retrievalJson=RetrievalHints.encode(value); }


		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String ruleCode;

		private String ruleType;

		private String businessName;

		private String expression;

		private String severity;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

}
