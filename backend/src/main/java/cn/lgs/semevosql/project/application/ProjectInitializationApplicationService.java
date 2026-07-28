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
package cn.lgs.semevosql.project.application;

import cn.lgs.semevosql.clarification.ProjectSemanticAliasService;
import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.ProjectVersionPublishedEvent;
import cn.lgs.semevosql.project.domain.InitializationAnalysisStatus;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogCloner;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionCreationMode;
import cn.lgs.semevosql.project.domain.ProjectVersionReleaseGate;
import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticGapResolutionHandler;
import cn.lgs.semevosql.project.domain.SemanticGapStatus;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.application.ProjectVersionActivityService.ActivityType;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.application.ProjectDocumentService;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocumentBuildService;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectInitializationApplicationService implements ApplicationEventPublisherAware {

	private static final Set<String> MULTI_SOURCE_POLICY_GAP_TYPES = Set.of("MISSING_LOGICAL_BINDING",
			"MISSING_AUTHORITY_RULE", "MISSING_FRESHNESS_POLICY", "MISSING_CROSS_SOURCE_RELATIONSHIP",
			"MISSING_MERGE_POLICY");

	private final SemanticProjectRepository repository;

	private final ProjectVersionCatalogReadiness catalogReadiness;

	private final ProjectVersionCatalogCloner catalogCloner;

	private final SemanticGapResolutionHandler gapResolutionHandler;

	private final ProjectVersionReleaseGate releaseGate;

	private final ProjectRuntimeProfileService runtimeProfileService;

	private final ProjectDatasourceBindingService datasourceBindingService;

	private final ProjectDocumentService projectDocumentService;

	private final ProjectSemanticAliasService projectSemanticAliasService;

	private final SemanticRetrievalDocumentBuildService semanticRetrievalDocumentBuildService;

	private final ProjectVersionActivityService versionActivityService;

	private final LocalOperatorService authorization;

	private ApplicationEventPublisher eventPublisher;

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public ProjectInitializationView createProject(String projectCode, String name, String businessDomain,
			String description, String firstVersionNumber, String source, OperatorContext operator) {
		return createProject(projectCode, name, businessDomain, description, firstVersionNumber, source, List.of(),
				operator);
	}

	@Transactional
	public ProjectInitializationView createProject(String projectCode, String name, String businessDomain,
			String description, String firstVersionNumber, String source,
			List<ProjectDatasourceBindingService.BindingDefinition> datasourceBindings, OperatorContext operator) {
		authorization.require(operator, "create Project");
		SemanticProject project = SemanticProject.initialize(projectCode, name, businessDomain, description,
				operator.operator());
		repository.insertProject(project);
		runtimeProfileService.resolveOrCreate(project);
		SemanticProjectVersion version = SemanticProjectVersion.firstDraft(project.getId(), firstVersionNumber, source);
		repository.insertVersion(version);
		datasourceBindingService.saveBindings(project.getId(), version.getId(), datasourceBindings, operator);
		return view(project, version);
	}

	public List<SemanticProject> listProjects(OperatorContext operator) {
		authorization.require(operator, "list local Projects");
		return repository.findProjects();
	}

	public List<SemanticProjectVersion> listVersions(Long projectId) {
		requireProject(projectId);
		return repository.findVersions(projectId);
	}

	public SemanticProjectVersion getVersion(Long projectId, Long versionId) {
		return requireVersion(projectId, versionId);
	}

	@Transactional
	public ProjectInitializationView createDraftVersion(Long projectId, String versionNumber,
			ProjectVersionCreationMode creationMode, Long parentVersionId, String source, OperatorContext operator) {
		authorization.require(operator, "create Project Version draft");
		SemanticProject project = requireProject(projectId);
		if (repository.findVersionByNumber(projectId, versionNumber).isPresent()) {
			throw new IllegalStateException("Project version number already exists: " + versionNumber);
		}
		if (creationMode == ProjectVersionCreationMode.CLONE) {
			requireVersion(projectId, parentVersionId);
		}
		int nextVersionNo = repository.findLatestVersion(projectId).map(SemanticProjectVersion::getVersionNo).orElse(0)
				+ 1;
		SemanticProjectVersion draft = SemanticProjectVersion.nextDraft(projectId, nextVersionNo, versionNumber,
				creationMode, parentVersionId, source);
		repository.insertVersion(draft);
		if (creationMode == ProjectVersionCreationMode.CLONE) {
			catalogCloner.cloneCatalog(projectId, parentVersionId, draft.getId());
			datasourceBindingService.cloneBindings(projectId, parentVersionId, draft.getId(), operator);
			projectDocumentService.cloneDocuments(projectId, parentVersionId, draft.getId());
			projectSemanticAliasService.cloneAliases(projectId, parentVersionId, draft.getId());
		}
		return view(project, draft);
	}

	public ProjectInitializationView getProject(Long projectId) {
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = project.getActiveVersionId() == null
				? repository.findLatestVersion(projectId).orElse(null)
				: repository.findVersion(project.getActiveVersionId()).orElse(null);
		return new ProjectInitializationView(project, version,
				version == null ? 0 : repository.countOpenGaps(projectId, version.getId()),
				version == null ? null : repository.findNextOpenGap(projectId, version.getId()).orElse(null));
	}

	@Transactional
	public SemanticGap addGap(Long projectId, Long versionId, String gapType, String question, String recommendation,
			String evidence, String impactScope, Integer priority, OperatorContext operator) {
		authorization.require(operator, "add Semantic Gap");
		requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		version.assertMutable();
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Semantic gaps can only be added to a DRAFT version");
		}
		SemanticGap gap = SemanticGap.open(projectId, versionId, gapType, question, recommendation, evidence,
				impactScope, priority);
		repository.insertGap(gap);
		return gap;
	}

	public Optional<SemanticGap> nextGap(Long projectId, Long versionId) {
		requireVersion(projectId, versionId);
		return repository.findNextOpenGap(projectId, versionId);
	}

	@Transactional
	public ProjectInitializationView initializeVersion(Long projectId, Long versionId, Long initializationModelId,
			OperatorContext operator) {
		authorization.require(operator, "initialize Project Version");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		version.configureInitializationModel(initializationModelId);
		version.startAnalysis();
		repository.updateVersion(version);
		runtimeProfileService.resolveOrCreate(project);
		return view(project, version);
	}

	@Transactional
	public ProjectInitializationView startAnalysis(Long projectId, Long versionId, OperatorContext operator) {
		authorization.require(operator, "start Project analysis");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		version.startAnalysis();
		repository.updateVersion(version);
		return view(project, version);
	}

	@Transactional
	public ProjectInitializationView completeAnalysis(Long projectId, Long versionId, OperatorContext operator) {
		authorization.require(operator, "complete Project analysis");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		assertNoOpenGaps(projectId, versionId);
		CatalogReadiness readiness = catalogReadiness.assess(projectId, versionId);
		if (!readiness.ready()) {
			throw new IllegalStateException(
					"Semantic catalog is not ready: " + String.join("; ", readiness.violations()));
		}
		version.completeAnalysis();
		repository.updateVersion(version);
		return view(project, version);
	}

	@Transactional
	public ProjectInitializationView failAnalysis(Long projectId, Long versionId, String error,
			OperatorContext operator) {
		authorization.require(operator, "fail Project analysis");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		version.failAnalysis(error);
		repository.updateVersion(version);
		return view(project, version);
	}

	@Transactional
	public SemanticGap resolveGap(Long gapId, String answer, OperatorContext operator) {
		SemanticGap gap = repository.findGap(gapId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic gap not found: " + gapId));
		authorization.require(operator, "resolve Semantic Gap " + gap.getGapType());
		if (gap.getStatus() != SemanticGapStatus.OPEN) {
			return gap;
		}
		SemanticProjectVersion version = requireVersion(gap.getProjectId(), gap.getProjectVersionId());
		version.assertMutable();
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Only gaps in a DRAFT version can be resolved");
		}
		gapResolutionHandler.applyResolution(gap, answer);
		gap.resolve(answer, operator.operator());
		repository.updateGap(gap);
		return gap;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ProjectInitializationView validateVersion(Long projectId, Long versionId, OperatorContext operator) {
		authorization.require(operator, "validate Project Version");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		assertNoOpenGaps(projectId, versionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Only a DRAFT project version can be validated");
		}
		if (version.getAnalysisStatus() != InitializationAnalysisStatus.COMPLETED) {
			throw new IllegalStateException("Project initialization analysis must be completed before validation");
		}
		ProjectVersionReleaseGate.ReleaseReport report = releaseGate.validate(projectId, version.getParentVersionId(),
				versionId);
		if (!report.passed()) {
			String detail = report.scenarioPreflightFailures().isEmpty() ? ""
					: ": " + String.join("; ", report.scenarioPreflightFailures());
			throw new IllegalStateException("Semantic release gate rejected the version" + detail);
		}
		version.setCatalogHash(report.catalogHash());
		semanticRetrievalDocumentBuildService.build(projectId, versionId, report.catalogHash());
		version.setReleaseReport(writeReport(report));
		version.validateVersion();
		repository.updateVersion(version);
		return view(project, version);
	}

	@Transactional
	public ProjectInitializationView publishVersion(Long projectId, Long versionId, OperatorContext operator) {
		authorization.require(operator, "publish Project Version");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		assertNoOpenGaps(projectId, versionId);
		ProjectVersionReleaseGate.ReleaseReport report = releaseGate.validate(projectId, version.getParentVersionId(),
				versionId);
		if (!report.passed()) {
			String detail = report.scenarioPreflightFailures().isEmpty() ? ""
					: ": " + String.join("; ", report.scenarioPreflightFailures());
			throw new IllegalStateException("Semantic release gate rejected the version" + detail);
		}
		if (!report.catalogHash().equals(version.getCatalogHash())) {
			throw new IllegalStateException("Catalog changed after validation; validate the version again");
		}
		semanticRetrievalDocumentBuildService.assertReady(projectId, versionId, version.getCatalogHash());
		version.publishVersion();
		repository.updateVersion(version);
		versionActivityService.record(projectId, versionId, ActivityType.PUBLISHED, operator);
		if (project.getActiveVersionId() == null) {
			activate(project, version, operator);
		}
		if (eventPublisher != null) {
			eventPublisher.publishEvent(new ProjectVersionPublishedEvent(projectId, versionId,
					version.getParentVersionId(), version.getCatalogHash()));
		}
		return view(project, version);
	}

	@Transactional
	public ProjectInitializationView activateVersion(Long projectId, Long versionId, OperatorContext operator) {
		authorization.require(operator, "activate Project Version");
		SemanticProject project = requireProject(projectId);
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		if (version.getStatus() != ProjectVersionStatus.PUBLISHED) {
			throw new IllegalStateException("Only a PUBLISHED project version can be activated");
		}
		if (version.getAnalysisStatus() != InitializationAnalysisStatus.COMPLETED) {
			throw new IllegalStateException("Only a completed project version can be activated");
		}
		assertNoOpenGaps(projectId, versionId);
		if (version.getCatalogHash() == null || version.getCatalogHash().isBlank()) {
			throw new IllegalStateException("Published project version has no validated catalog hash");
		}
		semanticRetrievalDocumentBuildService.assertReady(projectId, versionId, version.getCatalogHash());
		activate(project, version, operator);
		return view(project, version);
	}

	private void activate(SemanticProject project, SemanticProjectVersion version, OperatorContext operator) {
		project.activatePublishedVersion(version.getId());
		repository.updateProject(project);
		versionActivityService.record(project.getId(), version.getId(), ActivityType.ACTIVATED, operator);
		runtimeProfileService.resolveOrCreate(project);
	}

	private String writeReport(ProjectVersionReleaseGate.ReleaseReport report) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(report);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to persist semantic release report", ex);
		}
	}

	private ProjectInitializationView view(SemanticProject project, SemanticProjectVersion version) {
		long openGapCount = repository.countOpenGaps(project.getId(), version.getId());
		SemanticGap nextGap = repository.findNextOpenGap(project.getId(), version.getId()).orElse(null);
		return new ProjectInitializationView(project, version, openGapCount, nextGap);
	}

	private SemanticProject requireProject(Long projectId) {
		return repository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long versionId) {
		if (versionId == null) {
			throw new IllegalArgumentException("Project version id is required");
		}
		SemanticProjectVersion version = repository.findVersion(versionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + versionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	private void assertNoOpenGaps(Long projectId, Long versionId) {
		long openGapCount = repository.countOpenGaps(projectId, versionId);
		if (openGapCount > 0) {
			throw new IllegalStateException("Project initialization is incomplete: " + openGapCount + " gap(s) remain");
		}
	}

	public record ProjectInitializationView(SemanticProject project, SemanticProjectVersion version, long openGapCount,
			SemanticGap nextGap) {
	}

}
