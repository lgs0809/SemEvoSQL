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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One persisted cross-version Query Case rebind result. */
public record QueryCaseRebindResult(String id, String sourceCaseId, Long targetVersionId, String targetCatalogHash,
		String targetCaseId, String status, Map<String, Object> attributes) {

	public QueryCaseRebindResult {
		attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
	}

	public Map<String, Object> toMap() {
		return new LinkedHashMap<>(attributes);
	}

}
