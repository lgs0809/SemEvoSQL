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

import cn.lgs.semevosql.semantic.domain.BusinessQueryRequirement;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario.Importance;

/**
 * Scenario candidate extracted from one material chunk before persistence/deduplication.
 */
public record BusinessQueryScenarioDraft(String businessName, String description, BusinessQueryRequirement requirement,
		Importance importance, Integer confidence, String evidence) {
}
