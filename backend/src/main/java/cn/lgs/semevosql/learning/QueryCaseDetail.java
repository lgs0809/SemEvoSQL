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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete Query Case view assembled by the repository. */
public record QueryCaseDetail(QueryCaseSummary summary, List<QueryCaseAssetReference> assetReferences,
		List<QueryCaseRebindResult> rebinds, QueryCaseQualityProof qualityProof) {

	public QueryCaseDetail {
		assetReferences = assetReferences == null ? List.of() : List.copyOf(assetReferences);
		rebinds = rebinds == null ? List.of() : List.copyOf(rebinds);
		qualityProof = qualityProof == null ? new QueryCaseQualityProof(Map.of()) : qualityProof;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> value = new LinkedHashMap<>(summary.toMap());
		value.put("assetReferences", assetReferences.stream().map(QueryCaseAssetReference::toMap).toList());
		value.put("rebinds", rebinds.stream().map(QueryCaseRebindResult::toMap).toList());
		return value;
	}

}
