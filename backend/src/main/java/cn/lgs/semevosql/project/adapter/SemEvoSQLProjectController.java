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
package cn.lgs.semevosql.project.adapter;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectDatasourceBindingService;
import cn.lgs.semevosql.project.application.ProjectDatasourceBindingService.BindingDefinition;
import cn.lgs.semevosql.project.application.ProjectHealthApplicationService;
import cn.lgs.semevosql.project.application.ProjectHealthApplicationService.ProjectHealthSummaryView;
import cn.lgs.semevosql.project.application.ProjectHealthApplicationService.ProjectHealthView;
import cn.lgs.semevosql.project.application.ProjectInitializationApplicationService;
import cn.lgs.semevosql.project.application.ProjectInitializationApplicationService.ProjectInitializationView;
import cn.lgs.semevosql.project.application.ProjectReleaseCenterApplicationService;
import cn.lgs.semevosql.project.application.ProjectReleaseCenterApplicationService.ReleaseCenterView;
import cn.lgs.semevosql.project.domain.ProjectDatasourceBinding;
import cn.lgs.semevosql.project.domain.ProjectVersionCreationMode;
import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class SemEvoSQLProjectController {

	private final ProjectInitializationApplicationService initializationService;

	private final ProjectDatasourceBindingService datasourceBindingService;

	private final ProjectHealthApplicationService healthService;

	private final ProjectReleaseCenterApplicationService releaseCenterService;

	private final OperatorContext.Resolver operatorResolver;

	@PostMapping("/projects")
	public ProjectInitializationView createProject(@Valid @RequestBody CreateProjectRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		List<BindingDefinition> datasourceBindings = request.datasourceBindings() == null ? List.of() : request
			.datasourceBindings()
			.stream()
			.map(binding -> new BindingDefinition(binding.datasourceId(), binding.domainCode(), binding.domainName(),
					binding.responsibility(), binding.priority(), binding.exposedTables()))
			.toList();
		return initializationService.createProject(request.projectCode(), request.name(), request.businessDomain(),
				request.description(), request.firstVersionNumber(), request.source(), datasourceBindings,
				operatorResolver.resolve(headers, principal, "project-create:" + request.projectCode()));
	}

	@GetMapping("/projects")
	public List<SemanticProject> listProjects(@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.listProjects(operatorResolver.resolve(headers, principal, "project-list"));
	}

	@GetMapping("/projects/health-summary")
	public List<ProjectHealthSummaryView> listProjectHealthSummary(@RequestHeader HttpHeaders headers,
			Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "project-health-summary");
		return initializationService.listProjects(operator)
			.stream()
			.map(project -> healthService.getSummary(project.getId(), operator))
			.toList();
	}

	@GetMapping("/projects/{projectId}")
	public ProjectInitializationView getProject(@PathVariable Long projectId) {
		return initializationService.getProject(projectId);
	}

	@GetMapping("/projects/{projectId}/health")
	public ProjectHealthView getProjectHealth(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return healthService.getHealth(projectId,
				operatorResolver.resolve(headers, principal, "project-health:" + projectId));
	}

	@GetMapping("/projects/{projectId}/release-center")
	public ReleaseCenterView getReleaseCenter(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return releaseCenterService.getReleaseCenter(projectId,
				operatorResolver.resolve(headers, principal, "project-release-center:" + projectId));
	}

	@GetMapping("/projects/{projectId}/versions")
	public List<SemanticProjectVersion> listVersions(@PathVariable Long projectId) {
		return initializationService.listVersions(projectId);
	}

	@GetMapping("/projects/{projectId}/versions/{versionId}")
	public SemanticProjectVersion getVersion(@PathVariable Long projectId, @PathVariable Long versionId) {
		return initializationService.getVersion(projectId, versionId);
	}

	@GetMapping("/projects/{projectId}/versions/{versionId}/datasources")
	public List<ProjectDatasourceBinding> listDatasourceBindings(@PathVariable Long projectId,
			@PathVariable Long versionId) {
		return datasourceBindingService.listBindings(projectId, versionId);
	}

	@PutMapping("/projects/{projectId}/versions/{versionId}/datasources/{datasourceId}")
	public ProjectDatasourceBinding saveDatasourceBinding(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Integer datasourceId, @Valid @RequestBody SaveDatasourceBindingRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return datasourceBindingService.saveBinding(projectId, versionId, datasourceId, request.domainCode(),
				request.domainName(), request.responsibility(), request.priority(), request.exposedTables(),
				operatorResolver.resolve(headers, principal,
						"project-datasource-save:" + versionId + ":" + datasourceId));
	}

	@DeleteMapping("/projects/{projectId}/versions/{versionId}/datasources/{datasourceId}")
	public void deleteDatasourceBinding(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Integer datasourceId, @RequestHeader HttpHeaders headers, Principal principal) {
		datasourceBindingService.deleteBinding(projectId, versionId, datasourceId, operatorResolver.resolve(headers,
				principal, "project-datasource-delete:" + versionId + ":" + datasourceId));
	}

	@PostMapping("/projects/{projectId}/versions")
	public ProjectInitializationView createDraftVersion(@PathVariable Long projectId,
			@Valid @RequestBody CreateVersionRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.createDraftVersion(projectId, request.versionNumber(), request.creationMode(),
				request.parentVersionId(), request.source(),
				operatorResolver.resolve(headers, principal, "project-version-create:" + projectId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/initialize")
	public ProjectInitializationView initializeVersion(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody InitializeVersionRequest request, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return initializationService.initializeVersion(projectId, versionId, request.initializationModelId(),
				operatorResolver.resolve(headers, principal, "project-version-initialize:" + versionId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/gaps")
	public SemanticGap addGap(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody AddGapRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.addGap(projectId, versionId, request.gapType(), request.question(),
				request.recommendation(), request.evidence(), request.impactScope(), request.priority(),
				operatorResolver.resolve(headers, principal, "semantic-gap-add:" + versionId));
	}

	@GetMapping("/projects/{projectId}/versions/{versionId}/initialization/next-gap")
	public SemanticGap nextGap(@PathVariable Long projectId, @PathVariable Long versionId) {
		return initializationService.nextGap(projectId, versionId).orElse(null);
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/analysis/start")
	public ProjectInitializationView startAnalysis(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.startAnalysis(projectId, versionId,
				operatorResolver.resolve(headers, principal, "project-analysis-start:" + versionId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/analysis/complete")
	public ProjectInitializationView completeAnalysis(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.completeAnalysis(projectId, versionId,
				operatorResolver.resolve(headers, principal, "project-analysis-complete:" + versionId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/analysis/fail")
	public ProjectInitializationView failAnalysis(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody FailAnalysisRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.failAnalysis(projectId, versionId, request.error(),
				operatorResolver.resolve(headers, principal, "project-analysis-fail:" + versionId));
	}

	@PostMapping("/gaps/{gapId}/resolve")
	public SemanticGap resolveGap(@PathVariable Long gapId, @Valid @RequestBody ResolveGapRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.resolveGap(gapId, request.answer(),
				operatorResolver.resolve(headers, principal, "semantic-gap-resolve:" + gapId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/validate")
	public Mono<ProjectInitializationView> validateVersion(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "project-version-validate:" + versionId);
		return Mono.fromCallable(() -> initializationService.validateVersion(projectId, versionId, operator))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/publish")
	public ProjectInitializationView publishVersion(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.publishVersion(projectId, versionId,
				operatorResolver.resolve(headers, principal, "project-version-publish:" + versionId));
	}

	@PostMapping("/projects/{projectId}/versions/{versionId}/activate")
	public ProjectInitializationView activateVersion(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return initializationService.activateVersion(projectId, versionId,
				operatorResolver.resolve(headers, principal, "project-version-activate:" + versionId));
	}

	public record CreateProjectRequest(@NotBlank String projectCode, @NotBlank String name,
			@NotBlank String businessDomain, String description,
			@NotBlank @Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$") String firstVersionNumber,
			String source, @NotEmpty List<@Valid InitialDatasourceBindingRequest> datasourceBindings) {
	}

	public record InitialDatasourceBindingRequest(@NotNull Integer datasourceId, @NotBlank String domainCode,
			@NotBlank String domainName, @NotBlank String responsibility, @PositiveOrZero Integer priority,
			@NotEmpty List<@NotBlank String> exposedTables) {
	}

	public record CreateVersionRequest(
			@NotBlank @Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$") String versionNumber,
			@NotNull ProjectVersionCreationMode creationMode, Long parentVersionId, String source) {
	}

	public record InitializeVersionRequest(@NotNull Long initializationModelId) {
	}

	public record SaveDatasourceBindingRequest(@NotBlank String domainCode, @NotBlank String domainName,
			@NotBlank String responsibility, @PositiveOrZero Integer priority,
			@NotEmpty List<@NotBlank String> exposedTables) {
	}

	public record AddGapRequest(@NotBlank String gapType, @NotBlank String question, String recommendation,
			String evidence, String impactScope, Integer priority) {
	}

	public record ResolveGapRequest(@NotBlank String answer) {
	}

	public record FailAnalysisRequest(@NotBlank String error) {
	}

}
