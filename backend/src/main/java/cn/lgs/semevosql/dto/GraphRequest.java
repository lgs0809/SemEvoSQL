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
package cn.lgs.semevosql.dto;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphRequest {

	/** Project-first runtime identity used by SemEvoSQL callers. */
	private Long projectId;

	/** Internal compatibility identity resolved from the project runtime profile. */
	private String agentId;

	private String threadId;

	private String runId;

	private String requestId;

	private String idempotencyKey;

	/** Stable end-user principal used by SemEvoSQL personal semantic preferences. */
	private String principalId;

	private long afterSequence;

	private String query;

	/** Internal source pin used by the multi-source coordinator. */
	private Integer forcedDatasourceId;

	/** Internal physical table allowlist used by the multi-source coordinator. */
	@Builder.Default
	private List<String> forcedPhysicalTables = List.of();

	private boolean humanFeedback;

	private String humanFeedbackContent;

	private boolean rejectedPlan;

	/** Internal durable recovery mode; clients normally leave this unset. */
	private String recoveryMode;

	/** Legacy advanced-planner recovery payload retained for backward compatibility. */
	private String recoveredPlannerOutput;

	/** Exact approved Semantic Blueprint used when the native human-review checkpoint was lost. */
	private SemanticBlueprint recoveredSemanticPlan;

	/** Process-local marker set only by the durable recovery scanner after lease expiry. */
	@JsonIgnore
	private boolean durableRecoveryTakeover;

	/** Ordered output-producing node passes already visible before the process was lost. */
	@JsonIgnore
	@Builder.Default
	private List<String> durableRecoveryReplayNodeSequence = List.of();

	/** Mutable cursor used only while suppressing at-least-once replay output after takeover. */
	@JsonIgnore
	private int durableRecoveryReplayNodeIndex;

	/** Current replayed node pass whose duplicate stream is being suppressed. */
	@JsonIgnore
	private String durableRecoveryReplayCurrentNode;

}
