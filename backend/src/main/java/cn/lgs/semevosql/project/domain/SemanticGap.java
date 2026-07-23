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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticGap {

	private Long id;

	private Long projectId;

	private Long projectVersionId;

	private String gapKey;

	private String gapType;

	private String question;

	private String recommendation;

	private String evidence;

	private String impactScope;

	private Integer priority;

	private SemanticGapStatus status;

	private String answer;

	private String resolvedBy;

	private LocalDateTime createTime;

	private LocalDateTime resolvedTime;

	public static SemanticGap open(Long projectId, Long projectVersionId, String gapType, String question,
			String recommendation, String evidence, String impactScope, Integer priority) {
		return openWithKey(projectId, projectVersionId, null, gapType, question, recommendation, evidence, impactScope,
				priority);
	}

	public static SemanticGap openWithKey(Long projectId, Long projectVersionId, String gapKey, String gapType,
			String question, String recommendation, String evidence, String impactScope, Integer priority) {
		return SemanticGap.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.gapKey(gapKey)
			.gapType(gapType)
			.question(question)
			.recommendation(recommendation)
			.evidence(evidence)
			.impactScope(impactScope)
			.priority(priority == null ? 100 : priority)
			.status(SemanticGapStatus.OPEN)
			.createTime(LocalDateTime.now())
			.build();
	}

	public void resolve(String answer, String resolvedBy) {
		if (status != SemanticGapStatus.OPEN) {
			throw new IllegalStateException("Semantic gap has already been resolved");
		}
		this.answer = answer;
		this.resolvedBy = resolvedBy;
		this.status = SemanticGapStatus.RESOLVED;
		this.resolvedTime = LocalDateTime.now();
	}

	public void reopen() {
		this.status = SemanticGapStatus.OPEN;
		this.answer = null;
		this.resolvedBy = null;
		this.resolvedTime = null;
	}

}
