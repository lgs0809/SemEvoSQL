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
 * Separate allow-listed DSL for governed multi-source policy evolution. Keeping this
 * contract separate prevents ordinary semantic assets from silently changing source
 * authority, freshness, cross-source joins or merge failure semantics.
 */
public record MultiSourcePolicyPatch(int schemaVersion, Long sourceVersionId, String sourceCatalogHash,
		List<Operation> operations) {

	public MultiSourcePolicyPatch {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported multi-source policy patch schemaVersion: " + schemaVersion);
		}
		operations = List.copyOf(operations == null ? List.of() : operations);
		if (operations.isEmpty()) {
			throw new IllegalArgumentException("Multi-source policy patch must contain at least one operation");
		}
	}

	public record Operation(OperationType operation, PolicyAssetType assetType, String assetKey,
			String expectedCurrentFingerprint, Map<String, Object> values, List<String> evidenceCaseIds) {

		public Operation {
			if (operation == null || assetType == null || assetKey == null || assetKey.isBlank()) {
				throw new IllegalArgumentException("operation, assetType and assetKey are required");
			}
			values = Map.copyOf(values == null ? Map.of() : values);
			evidenceCaseIds = List.copyOf(evidenceCaseIds == null ? List.of() : evidenceCaseIds);
		}
	}

	public enum OperationType {

		ADD, UPDATE

	}

	public enum PolicyAssetType {

		LOGICAL_COLUMN_BINDING, AUTHORITY_RULE, FRESHNESS_POLICY, CROSS_SOURCE_RELATIONSHIP, MERGE_POLICY

	}

}
