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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Immutable, deterministic description of every governed input that can change a
 * SemEvoSQL execution path. The snapshot is persisted on the durable run and must
 * remain byte-for-byte stable for idempotent retries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionSnapshot(int schemaVersion, ProjectSnapshot project, ModelSnapshot model, PromptSnapshot prompts,
		SemanticSnapshot semantic, RuntimeSnapshot runtime, EnvironmentSnapshot environment,
		SemanticBlueprint semanticPlan, String semanticPlanHash, boolean humanReviewEnabled, boolean strictComparable,
		String compatibilityHash) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public record ProjectSnapshot(Long projectId, Long projectVersionId, String catalogHash,
			String datasourceExposureHash) {
	}

	public record ModelSnapshot(Integer modelConfigId, String provider, String modelName, Double temperature,
			Integer maxTokens, String configUpdatedAt, String endpointHash, String configHash) {
	}

	public record PromptSnapshot(Map<String, String> contentHashes, String bundleHash) {
	}

	public record SemanticSnapshot(String authorityPolicyHash, String freshnessPolicyHash, String mergePolicyHash,
			String semanticRetrieverHash, String plannerHash, String sqlCompilerHash) {
	}

	public record RuntimeSnapshot(String runtimeProfileRevision, String runtimeProfileUpdatedAt,
			String graphDefinitionHash, String sqlGuardPolicyHash, String costPolicyHash) {
	}

	public record EnvironmentSnapshot(Map<Integer, String> datasourceDialects, String timezone) {
	}

}
