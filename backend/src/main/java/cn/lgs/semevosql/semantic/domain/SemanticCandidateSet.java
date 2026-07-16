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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable governed candidate slice presented to semantic planning.
 *
 * <p>This object is deliberately distinct from the final semantic binding. Retrieval decides what
 * the model is allowed to see; the planner may only select assets contained in this set. Keeping
 * the boundary explicit makes retrieval misses distinguishable from planner selection errors.
 */
public record SemanticCandidateSet(Long projectId, Long projectVersionId, String catalogHash,
		Set<String> physicalTables, List<SemanticCatalogSnapshot.Model> models,
		List<SemanticCatalogSnapshot.Metric> metrics, List<SemanticCatalogSnapshot.Dimension> dimensions,
		List<SemanticCatalogSnapshot.EnumValue> enumValues, List<SemanticCatalogSnapshot.Rule> querySelectableRules,
		List<SemanticCatalogSnapshot.Rule> mandatoryGovernanceRules,
		List<SemanticCatalogSnapshot.Rule> planningPolicies,
		List<SemanticCatalogSnapshot.Relationship> relationships, List<SemanticCatalogSnapshot.Grain> grains,
		List<SemanticCatalogSnapshot.Column> timeColumns, List<SemanticCatalogSnapshot.Column> filterableColumns,
		List<RetrievalEvidence> retrievalEvidence, List<SemanticBlueprint.BindingDependency> confirmedDefinitions,
        List<ProjectSuggestion> projectSuggestions) {

    public SemanticCandidateSet(Long projectId, Long projectVersionId, String catalogHash, Set<String> physicalTables,
            List<SemanticCatalogSnapshot.Model> models, List<SemanticCatalogSnapshot.Metric> metrics,
            List<SemanticCatalogSnapshot.Dimension> dimensions, List<SemanticCatalogSnapshot.EnumValue> enumValues,
            List<SemanticCatalogSnapshot.Rule> querySelectableRules, List<SemanticCatalogSnapshot.Rule> mandatoryGovernanceRules,
            List<SemanticCatalogSnapshot.Rule> planningPolicies, List<SemanticCatalogSnapshot.Relationship> relationships,
            List<SemanticCatalogSnapshot.Grain> grains, List<SemanticCatalogSnapshot.Column> timeColumns,
            List<SemanticCatalogSnapshot.Column> filterableColumns, List<RetrievalEvidence> retrievalEvidence,
            List<SemanticBlueprint.BindingDependency> confirmedDefinitions) {
        this(projectId,projectVersionId,catalogHash,physicalTables,models,metrics,dimensions,enumValues,querySelectableRules,
            mandatoryGovernanceRules,planningPolicies,relationships,grains,timeColumns,filterableColumns,retrievalEvidence,confirmedDefinitions,List.of());
    }

    public record ProjectSuggestion(long candidateId,int contentRevision,String businessName,String completeDefinition,
            String modelCode,String representation) { }

    public SemanticCandidateSet withProjectSuggestions(List<ProjectSuggestion> suggestions) {
        return new SemanticCandidateSet(projectId,projectVersionId,catalogHash,physicalTables,models,metrics,dimensions,enumValues,
            querySelectableRules,mandatoryGovernanceRules,planningPolicies,relationships,grains,timeColumns,filterableColumns,
            retrievalEvidence,confirmedDefinitions,suggestions);
    }

    public SemanticCandidateSet(Long projectId,Long projectVersionId,String catalogHash,Set<String> physicalTables,
            List<SemanticCatalogSnapshot.Model> models,List<SemanticCatalogSnapshot.Metric> metrics,List<SemanticCatalogSnapshot.Dimension> dimensions,
            List<SemanticCatalogSnapshot.EnumValue> enumValues,List<SemanticCatalogSnapshot.Rule> querySelectableRules,
            List<SemanticCatalogSnapshot.Rule> mandatoryGovernanceRules,List<SemanticCatalogSnapshot.Rule> planningPolicies,
            List<SemanticCatalogSnapshot.Relationship> relationships,List<SemanticCatalogSnapshot.Grain> grains,
            List<SemanticCatalogSnapshot.Column> timeColumns,List<SemanticCatalogSnapshot.Column> filterableColumns,List<RetrievalEvidence> retrievalEvidence) {
        this(projectId,projectVersionId,catalogHash,physicalTables,models,metrics,dimensions,enumValues,querySelectableRules,
            mandatoryGovernanceRules,planningPolicies,relationships,grains,timeColumns,filterableColumns,retrievalEvidence,List.of());
    }

	public SemanticCandidateSet {
		physicalTables = Set.copyOf(physicalTables == null ? Set.of() : physicalTables);
		models = List.copyOf(models == null ? List.of() : models);
		metrics = List.copyOf(metrics == null ? List.of() : metrics);
		dimensions = List.copyOf(dimensions == null ? List.of() : dimensions);
		enumValues = List.copyOf(enumValues == null ? List.of() : enumValues);
		querySelectableRules = List.copyOf(querySelectableRules == null ? List.of() : querySelectableRules);
		mandatoryGovernanceRules = List.copyOf(mandatoryGovernanceRules == null ? List.of() : mandatoryGovernanceRules);
		planningPolicies = List.copyOf(planningPolicies == null ? List.of() : planningPolicies);
		relationships = List.copyOf(relationships == null ? List.of() : relationships);
		grains = List.copyOf(grains == null ? List.of() : grains);
		timeColumns = List.copyOf(timeColumns == null ? List.of() : timeColumns);
		filterableColumns = List.copyOf(filterableColumns == null ? List.of() : filterableColumns);
		retrievalEvidence = List.copyOf(retrievalEvidence == null ? List.of() : retrievalEvidence);
        confirmedDefinitions=List.copyOf(confirmedDefinitions==null?List.of():confirmedDefinitions);
        projectSuggestions=List.copyOf(projectSuggestions==null?List.of():projectSuggestions);
	}

	public boolean empty() {
		return models.isEmpty();
	}

	public Set<String> modelCodes() {
		return models.stream().map(SemanticCatalogSnapshot.Model::getModelCode).collect(java.util.stream.Collectors.toSet());
	}

	public Map<String, RetrievalEvidence> evidenceByAssetKey() {
		Map<String, RetrievalEvidence> result = new LinkedHashMap<>();
		for (RetrievalEvidence evidence : retrievalEvidence) {
			result.putIfAbsent(evidence.assetKey(), evidence);
		}
		return Map.copyOf(result);
	}

	public record RetrievalEvidence(String documentType, String assetType, String assetKey, String modelCode,
			String physicalTable, double rrfScore, Map<String, Integer> channelRanks,
			Map<String, Double> channelScores) {

		public RetrievalEvidence {
			channelRanks = Map.copyOf(channelRanks == null ? Map.of() : channelRanks);
			channelScores = Map.copyOf(channelScores == null ? Map.of() : channelScores);
		}
	}
}
