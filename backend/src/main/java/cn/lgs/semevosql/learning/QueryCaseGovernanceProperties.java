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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable evidence and quarantine thresholds for governed Query Cases. */
@Data
@ConfigurationProperties(prefix = "semevosql.query-case")
public class QueryCaseGovernanceProperties {

	private int quarantineFailedCount = 3;

	private double quarantineFailureRate = 0.30;

	private int quarantineFailureRateMinRecalls = 5;

	private int quarantineConsecutiveIssueCount = 3;

	private int evolutionMinIndependentConversations = 3;

	private int evolutionMinRootEvidence = 3;

	private double planningPolicyMinDistillationConfidence = 0.70;

	private int stableMappingMinIndependentEvidence = 5;

	private double stableMappingDominantRatio = 0.90;

	private double stableMappingMaxConflictRatio = 0.10;

	private double stableMappingMaxEntropy = 0.50;

}
