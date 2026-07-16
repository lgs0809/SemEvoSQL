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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;

public record SemanticMaterialParseResult(SemanticCatalogSnapshot catalogPatch, List<SemanticGap> gaps,
		List<BusinessQueryScenarioDraft> scenarios, boolean reviewRequired, String summary) {

	public SemanticMaterialParseResult(SemanticCatalogSnapshot catalogPatch, List<SemanticGap> gaps,
			boolean reviewRequired, String summary) {
		this(catalogPatch, gaps, List.of(), reviewRequired, summary);
	}

	public SemanticMaterialParseResult {
		gaps = gaps == null ? List.of() : List.copyOf(gaps);
		scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
	}

	public static SemanticMaterialParseResult applied(SemanticCatalogSnapshot patch, String summary) {
		return new SemanticMaterialParseResult(patch, List.of(), List.of(), false, summary);
	}

	public static SemanticMaterialParseResult review(List<SemanticGap> gaps, String summary) {
		return new SemanticMaterialParseResult(null, List.copyOf(gaps), List.of(), true, summary);
	}

}
