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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Time/call budget for one governed semantic-planning operation, including all repair calls. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "semevosql.planner")
public class SemanticPlanningProperties {

	/** Must remain below the outer interactive Graph silence timeout so planning unwinds first. */
	private long totalBudgetMs = 120_000L;

	/** One initial model call plus at most one governed semantic repair. */
	private int maxModelCalls = 2;

	/** Do not start a repair that has too little time to produce and validate a meaningful response. */
	private long minimumRepairBudgetMs = 5_000L;
}
