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
package cn.lgs.semevosql.semantic.retrieval;

import java.util.Set;

/** Hard retrieval filters are only populated from already-governed runtime facts. */
public record SemanticRetrievalScope(Integer datasourceId, Set<String> modelCodes,
		Set<SemanticRetrievalDocument.DocumentType> documentTypes, Set<String> assetKeys) {

	public SemanticRetrievalScope {
		modelCodes = modelCodes == null ? Set.of() : Set.copyOf(modelCodes);
		documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);
		assetKeys = assetKeys == null ? Set.of() : Set.copyOf(assetKeys);
	}

	public static SemanticRetrievalScope all() {
		return new SemanticRetrievalScope(null, Set.of(), Set.of(), Set.of());
	}

	public boolean matches(SemanticRetrievalDocument document) {
		return (datasourceId == null || datasourceId.equals(document.datasourceId()))
				&& (modelCodes.isEmpty() || modelCodes.contains(document.modelCode()))
				&& (documentTypes.isEmpty() || documentTypes.contains(document.documentType()))
				&& (assetKeys.isEmpty() || assetKeys.contains(document.assetKey()));
	}

}
