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
package cn.lgs.semevosql.learning;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Non-authoritative hints recalled from governed historical query cases. Every hinted
 * asset must still be rebound to the current Catalog and pass normal plan validation;
 * this object never authorizes direct reuse of historical SQL.
 */
public record QueryCaseHints(Set<String> modelCodes, Set<String> metricCodes, Set<String> dimensionCodes,
		Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
		List<EnumBindingHint> enumBindings, List<FilterBindingHint> filterBindings, List<AssetBindingHint> assetBindings,
		TimeBindingHint timeBinding, boolean strictAssetBinding, String intentType, List<String> sourceExampleIds,
		double confidence, Map<String, Double> componentScores, ResultCompositionHint resultComposition) {

	public QueryCaseHints {
		modelCodes = Set.copyOf(modelCodes == null ? Set.of() : modelCodes);
		metricCodes = Set.copyOf(metricCodes == null ? Set.of() : metricCodes);
		dimensionCodes = Set.copyOf(dimensionCodes == null ? Set.of() : dimensionCodes);
		grainCodes = Set.copyOf(grainCodes == null ? Set.of() : grainCodes);
		relationshipCodes = Set.copyOf(relationshipCodes == null ? Set.of() : relationshipCodes);
		ruleCodes = Set.copyOf(ruleCodes == null ? Set.of() : ruleCodes);
		enumBindings = List.copyOf(enumBindings == null ? List.of() : enumBindings);
		filterBindings = List.copyOf(filterBindings == null ? List.of() : filterBindings);
		assetBindings = List.copyOf(assetBindings == null ? List.of() : assetBindings);
		sourceExampleIds = List.copyOf(sourceExampleIds == null ? List.of() : sourceExampleIds);
		componentScores = Map.copyOf(componentScores == null ? Map.of() : componentScores);
	}

	public QueryCaseHints(Set<String> modelCodes, Set<String> metricCodes, Set<String> dimensionCodes,
			Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
			List<EnumBindingHint> enumBindings, List<FilterBindingHint> filterBindings,
			List<AssetBindingHint> assetBindings, TimeBindingHint timeBinding, boolean strictAssetBinding, String intentType,
			List<String> sourceExampleIds, double confidence, Map<String, Double> componentScores) {
		this(modelCodes, metricCodes, dimensionCodes, grainCodes, relationshipCodes, ruleCodes, enumBindings, filterBindings,
				assetBindings, timeBinding, strictAssetBinding, intentType, sourceExampleIds, confidence, componentScores, null);
	}

	public QueryCaseHints(Set<String> modelCodes, Set<String> metricCodes, Set<String> dimensionCodes,
			Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
			List<EnumBindingHint> enumBindings, String intentType, List<String> sourceExampleIds, double confidence,
			Map<String, Double> componentScores) {
		this(modelCodes, metricCodes, dimensionCodes, grainCodes, relationshipCodes, ruleCodes, enumBindings, List.of(),
				List.of(), null, false, intentType, sourceExampleIds, confidence, componentScores);
	}

	public QueryCaseHints(Set<String> modelCodes, Set<String> metricCodes, Set<String> dimensionCodes,
			Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
			List<EnumBindingHint> enumBindings, TimeBindingHint timeBinding, boolean strictAssetBinding,
			String intentType, List<String> sourceExampleIds, double confidence, Map<String, Double> componentScores) {
		this(modelCodes, metricCodes, dimensionCodes, grainCodes, relationshipCodes, ruleCodes, enumBindings, List.of(),
				List.of(), timeBinding, strictAssetBinding, intentType, sourceExampleIds, confidence, componentScores);
	}

	public QueryCaseHints(Set<String> modelCodes, Set<String> metricCodes, Set<String> dimensionCodes,
			Set<String> grainCodes, Set<String> relationshipCodes, Set<String> ruleCodes,
			List<EnumBindingHint> enumBindings, List<AssetBindingHint> assetBindings, TimeBindingHint timeBinding,
			boolean strictAssetBinding, String intentType, List<String> sourceExampleIds, double confidence,
			Map<String, Double> componentScores) {
		this(modelCodes, metricCodes, dimensionCodes, grainCodes, relationshipCodes, ruleCodes, enumBindings, List.of(),
				assetBindings, timeBinding, strictAssetBinding, intentType, sourceExampleIds, confidence, componentScores);
	}

	public static QueryCaseHints empty() {
		return new QueryCaseHints(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of(), null,
				List.of(), 0, Map.of());
	}

	public boolean emptyHints() {
		return modelCodes.isEmpty() && metricCodes.isEmpty() && dimensionCodes.isEmpty() && grainCodes.isEmpty()
				&& relationshipCodes.isEmpty() && ruleCodes.isEmpty() && enumBindings.isEmpty() && filterBindings.isEmpty()
				&& assetBindings.isEmpty() && timeBinding == null && resultComposition == null;
	}

	public record EnumBindingHint(String rawText, String modelCode, String columnName, String valueCode,
			String sourceExampleId, double confidence) {
	}

	/** Current-query literal predicate selected by the governed semantic planner. */
	public record FilterBindingHint(String rawText, String modelCode, String columnName, String operator, Object value,
			String sourceId, double confidence) {
	}

	/** Phrase-scoped binding used by runtime QUERY/USER/PROJECT aliases. */
	public record AssetBindingHint(String rawText, String assetType, String assetKey, String modelCode, String sourceId,
			double confidence) {
	}

	/** Current-query execution composition selected by the semantic planner. */
	public record ResultCompositionHint(String type, String calculationExpression) {
	}

	public record TimeBindingHint(String rawText, String modelCode, String columnName, String sourceExampleId,
			double confidence, String groupGranularity, String startInclusive, String endExclusive) {

        public TimeBindingHint {
            if ((startInclusive == null) != (endExclusive == null))
                throw new IllegalArgumentException("A time interval requires both startInclusive and endExclusive");
            if (startInclusive != null) {
                Object start = cn.lgs.semevosql.semantic.domain.TemporalFilterValue.normalize("timestamp", startInclusive);
                Object end = cn.lgs.semevosql.semantic.domain.TemporalFilterValue.normalize("timestamp", endExclusive);
                if (!(start instanceof java.time.LocalDateTime left) || !(end instanceof java.time.LocalDateTime right)
                        || !left.isBefore(right))
                    throw new IllegalArgumentException("Time interval requires increasing ISO local date/time boundaries");
                startInclusive = left.toString();
                endExclusive = right.toString();
            }
        }

        public TimeBindingHint(String rawText, String modelCode, String columnName, String sourceExampleId,
                double confidence, String groupGranularity) {
            this(rawText, modelCode, columnName, sourceExampleId, confidence, groupGranularity, null, null);
        }

		public TimeBindingHint(String rawText, String modelCode, String columnName, String sourceExampleId,
				double confidence) {
			this(rawText, modelCode, columnName, sourceExampleId, confidence, null, null, null);
		}
	}

}
