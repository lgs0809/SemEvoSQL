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

import java.util.List;

/** DB-independent requirements that a Semantic Catalog must be able to resolve. */
public record BusinessQueryRequirement(List<String> measures, List<String> attributes, List<String> filters,
		List<String> timeConstraints, List<String> groupings, List<String> sorting, Integer limit, String comparison,
		String expectedShape) {

	public BusinessQueryRequirement {
		measures = copy(measures);
		attributes = copy(attributes);
		filters = copy(filters);
		timeConstraints = copy(timeConstraints);
		groupings = copy(groupings);
		sorting = copy(sorting);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
	}

}
