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
package cn.lgs.semevosql.evolution;

import java.util.List;
import java.util.Map;

/**
 * Versioned, deterministic patch contract for governed Semantic Catalog evolution. Patch
 * generation may use an LLM, but application accepts only this allow-listed
 * representation and revalidates every operation against the target Draft.
 */
public record SemanticPatch(int schemaVersion, Long sourceVersionId, String sourceCatalogHash,
		List<Operation> operations) {

	public SemanticPatch {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported semantic patch schemaVersion: " + schemaVersion);
		}
		operations = List.copyOf(operations == null ? List.of() : operations);
		if (operations.isEmpty()) {
			throw new IllegalArgumentException("Semantic patch must contain at least one operation");
		}
	}

	public record Operation(OperationType operation, String assetType, String assetKey,
			String expectedCurrentFingerprint, Map<String, Object> values, List<String> evidenceCaseIds) {

		public Operation {
			if (operation == null || assetType == null || assetType.isBlank() || assetKey == null
					|| assetKey.isBlank()) {
				throw new IllegalArgumentException("operation, assetType and assetKey are required");
			}
			values = Map.copyOf(values == null ? Map.of() : values);
			evidenceCaseIds = List.copyOf(evidenceCaseIds == null ? List.of() : evidenceCaseIds);
		}
	}

	public enum OperationType {

		ADD_COLUMN_SYNONYM, UPDATE_MODEL, UPDATE_COLUMN, ADD_ENUM_ALIAS, ADD_PROJECT_ALIAS, ADD_ENUM_VALUE,
		UPDATE_ENUM_VALUE, ADD_METRIC, UPDATE_METRIC, ADD_DIMENSION, UPDATE_DIMENSION, ADD_RELATIONSHIP,
		UPDATE_RELATIONSHIP, ADD_GRAIN, UPDATE_GRAIN, ADD_RULE, UPDATE_RULE

	}

}
