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

import cn.lgs.semevosql.evolution.domain.SemanticVersionCause;
import cn.lgs.semevosql.evolution.domain.SemanticVersionLevel;
import cn.lgs.semevosql.evolution.domain.SemanticVersionNumber;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticProjectVersion {

	private Long id;

	private Long projectId;

	private Integer versionNo;

	private String versionNumber;

	private Integer semanticMajor;

	private Integer semanticMinor;

	private Integer semanticPatch;

	private SemanticVersionLevel versionLevel;

	private SemanticVersionCause versionCause;

	private String semanticStateHash;

	private Long corpusRevisionId;

	private ProjectVersionStatus status;

	private Long parentVersionId;

	private ProjectVersionCreationMode creationMode;

	private Long initializationModelId;

	private InitializationAnalysisStatus analysisStatus;

	private String analysisError;

	private String source;

	private String evidence;

	private String catalogHash;

	private String releaseReport;

	private LocalDateTime createTime;

	private LocalDateTime validatedTime;

	private LocalDateTime publishedTime;

	private LocalDateTime activatedTime;

	private LocalDateTime deactivatedTime;

	@Builder.Default
	private Long revision = 0L;

	public static SemanticProjectVersion firstDraft(Long projectId, String versionNumber, String source) {
		SemanticVersionNumber semanticVersion = SemanticVersionNumber.parse(versionNumber);
		return SemanticProjectVersion.builder()
			.projectId(projectId)
			.versionNo(1)
			.versionNumber(versionNumber)
			.semanticMajor(semanticVersion.major())
			.semanticMinor(semanticVersion.minor())
			.semanticPatch(semanticVersion.patch())
			.versionLevel(SemanticVersionLevel.INITIAL)
			.versionCause(SemanticVersionCause.INITIALIZATION)
			.status(ProjectVersionStatus.DRAFT)
			.creationMode(ProjectVersionCreationMode.BLANK)
			.analysisStatus(InitializationAnalysisStatus.PENDING)
			.source(source)
			.createTime(LocalDateTime.now())
			.build();
	}

	public static SemanticProjectVersion nextDraft(Long projectId, Integer versionNo, String versionNumber,
			ProjectVersionCreationMode creationMode, Long parentVersionId, String source) {
		if (versionNo == null || versionNo < 2) {
			throw new IllegalArgumentException("Next project version number must be at least 2");
		}
		SemanticVersionNumber semanticVersion = SemanticVersionNumber.parse(versionNumber);
		if (creationMode == null) {
			throw new IllegalArgumentException("Project version creation mode is required");
		}
		if (creationMode == ProjectVersionCreationMode.CLONE && parentVersionId == null) {
			throw new IllegalArgumentException("CLONE project version requires parentVersionId");
		}
		if (creationMode == ProjectVersionCreationMode.BLANK && parentVersionId != null) {
			throw new IllegalArgumentException("BLANK project version cannot declare parentVersionId");
		}
		return SemanticProjectVersion.builder()
			.projectId(projectId)
			.versionNo(versionNo)
			.versionNumber(versionNumber)
			.semanticMajor(semanticVersion.major())
			.semanticMinor(semanticVersion.minor())
			.semanticPatch(semanticVersion.patch())
			.status(ProjectVersionStatus.DRAFT)
			.parentVersionId(parentVersionId)
			.creationMode(creationMode)
			.analysisStatus(InitializationAnalysisStatus.PENDING)
			.source(source)
			.createTime(LocalDateTime.now())
			.build();
	}

	public void setCatalogHash(String catalogHash) {
		this.catalogHash = catalogHash;
		this.semanticStateHash = catalogHash;
	}

	public void configureInitializationModel(Long modelId) {
		assertMutable();
		if (modelId == null || modelId <= 0) {
			throw new IllegalArgumentException("initializationModelId must be positive");
		}
		this.initializationModelId = modelId;
	}

	public void startAnalysis() {
		assertMutable();
		if (status != ProjectVersionStatus.DRAFT || (analysisStatus != InitializationAnalysisStatus.PENDING
				&& analysisStatus != InitializationAnalysisStatus.FAILED)) {
			throw new IllegalStateException("Only a pending or failed DRAFT version can start analysis");
		}
		analysisStatus = InitializationAnalysisStatus.RUNNING;
		analysisError = null;
	}

	public void completeAnalysis() {
		assertMutable();
		if (status != ProjectVersionStatus.DRAFT || analysisStatus != InitializationAnalysisStatus.RUNNING) {
			throw new IllegalStateException("Only a running DRAFT analysis can be completed");
		}
		analysisStatus = InitializationAnalysisStatus.COMPLETED;
		analysisError = null;
	}

	public void failAnalysis(String error) {
		assertMutable();
		if (status != ProjectVersionStatus.DRAFT || analysisStatus != InitializationAnalysisStatus.RUNNING) {
			throw new IllegalStateException("Only a running DRAFT analysis can fail");
		}
		analysisStatus = InitializationAnalysisStatus.FAILED;
		analysisError = error;
	}

	public void validateVersion() {
		assertMutable();
		if (status != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Only a DRAFT project version can be validated");
		}
		if (analysisStatus != InitializationAnalysisStatus.COMPLETED) {
			throw new IllegalStateException("Project initialization analysis must be completed before validation");
		}
		status = ProjectVersionStatus.VALIDATED;
		validatedTime = LocalDateTime.now();
	}

	public void publishVersion() {
		assertMutable();
		if (status != ProjectVersionStatus.VALIDATED) {
			throw new IllegalStateException("Only a VALIDATED project version can be published");
		}
		if (analysisStatus != InitializationAnalysisStatus.COMPLETED) {
			throw new IllegalStateException("Project initialization analysis must be completed before publication");
		}
		status = ProjectVersionStatus.PUBLISHED;
		publishedTime = LocalDateTime.now();
	}

	public void assertMutable() {
		if (status == ProjectVersionStatus.PUBLISHED || status == ProjectVersionStatus.ARCHIVED) {
			throw new IllegalStateException("Published or archived project versions are immutable");
		}
	}

	private static void validateVersionNumber(String versionNumber) {
		if (versionNumber == null || !versionNumber.matches("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")) {
			throw new IllegalArgumentException("versionNumber must use x.x.x format");
		}
	}

}
