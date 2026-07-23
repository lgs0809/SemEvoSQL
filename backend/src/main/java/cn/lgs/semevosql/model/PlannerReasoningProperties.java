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
package cn.lgs.semevosql.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Native reasoning configuration for governed semantic-planner calls. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "semevosql.planner.reasoning")
public class PlannerReasoningProperties {

	/** Product default. Benchmarks can explicitly request the baseline profile per call. */
	private boolean enabled = true;

	/** Provider-native reasoning effort. No reasoning text is requested or persisted. */
	private String effort = "medium";

	/** Optional model override used only by semantic-planning calls. */
	private String modelOverride;

	/** Retry the same governed request without reasoning options if the provider rejects them. */
	private boolean downgradeOnUnsupported = true;

}
