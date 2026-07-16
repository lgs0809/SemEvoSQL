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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conservative cross-project terminology aliases. The alias only helps select a published
 * Catalog metric; the metric expression, filters and model remain governed by the
 * Catalog.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "semevosql.semantic.matching")
public class SemanticMatchingProperties {

	/**
	 * Optional installation-level aliases. Project-specific terminology belongs in the
	 * Semantic Catalog / learned semantic assets rather than application defaults.
	 */
	private Map<String, List<String>> metricTermAliases = new LinkedHashMap<>();

}
