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
package cn.lgs.semevosql.project.domain;

import java.time.LocalDateTime;
import java.util.List;

public interface ProjectVersionReleaseGate {

	ReleaseReport validate(Long projectId, Long sourceVersionId, Long targetVersionId);

	record ReleaseReport(Long projectId, Long sourceVersionId, Long targetVersionId, String catalogHash, boolean passed,
			List<String> breakingChanges, List<String> warnings, List<String> schemaDrift, List<String> fanOutRisks,
			int replayTotal, int replayPassed, int replayFailed, boolean safetyPassed, int scenarioPreflightTotal,
			int scenarioPreflightPassed, int scenarioPreflightFailed, List<String> scenarioPreflightFailures,
			LocalDateTime generatedAt) {

		public ReleaseReport(Long projectId, Long sourceVersionId, Long targetVersionId, String catalogHash,
				boolean passed, List<String> breakingChanges, List<String> warnings, List<String> schemaDrift,
				List<String> fanOutRisks, int replayTotal, int replayPassed, int replayFailed, boolean safetyPassed,
				LocalDateTime generatedAt) {
			this(projectId, sourceVersionId, targetVersionId, catalogHash, passed, breakingChanges, warnings,
					schemaDrift, fanOutRisks, replayTotal, replayPassed, replayFailed, safetyPassed, 0, 0, 0, List.of(),
					generatedAt);
		}
	}

}
