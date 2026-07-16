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

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Current deterministic resolution state for one active business query scenario. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioResolution {

	private Long scenarioId;

	private Long projectId;

	private Long projectVersionId;

	private Status status;

	private String resolvedBindingsJson;

	private String candidateBindingsJson;

	private String unresolvedRequirementsJson;

	private String evidenceJson;

	private String manualBindingsJson;

	private String resolutionHash;

	private Long revision;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	public enum Status {

		RESOLVED, AMBIGUOUS, UNSUPPORTED

	}

}
