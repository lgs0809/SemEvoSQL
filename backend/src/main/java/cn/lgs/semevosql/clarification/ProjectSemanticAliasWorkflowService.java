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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.project.domain.SemanticProject;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Submits project-wide language aliases into the governed Project Version evolution flow.
 */
@Service
public class ProjectSemanticAliasWorkflowService {

	private final SemanticProjectRepository projectRepository;

	private final ProjectSemanticAliasProposalService proposalService;

	private final UserSemanticPreferenceService preferenceService;

	private final LocalOperatorService authorization;

	private final ProjectScopeService projectScope;

	public ProjectSemanticAliasWorkflowService(SemanticProjectRepository projectRepository,
			ProjectSemanticAliasProposalService proposalService, UserSemanticPreferenceService preferenceService,
			LocalOperatorService authorization, ProjectScopeService projectScope) {
		this.projectRepository = projectRepository;
		this.proposalService = proposalService;
		this.preferenceService = preferenceService;
		this.authorization = authorization;
		this.projectScope = projectScope;
	}

	public PromotionResult proposeAlias(Long projectId, String rawPhrase, String assetType, String assetKey,
			String businessLabel, OperatorContext operator) {
		authorization.require(operator, "propose PROJECT semantic alias");
		projectScope.requireProject(projectId, operator);
		SemanticProject project = projectRepository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
		Long activeVersionId = project.getActiveVersionId();
		if (activeVersionId == null) {
			throw new IllegalStateException("Project has no active version for PROJECT semantic alias proposal");
		}
		var proposal = proposalService.propose(projectId, activeVersionId, rawPhrase, assetType, assetKey,
				businessLabel, operator.operator(), "EXPLICIT_PROJECT_SCOPE");
		return new PromotionResult(activeVersionId, proposal.candidateId(), false);
	}

	public PromotionResult promotePreference(Long preferenceId, OperatorContext operator) {
		var preference = preferenceService.findById(preferenceId)
			.orElseThrow(() -> new IllegalArgumentException("User semantic preference not found: " + preferenceId));
		if (!Objects.equals(preference.userId(), operator.operator())) {
			throw new SecurityException("A personal semantic preference can only be promoted by its owner");
		}
		PromotionResult result = proposeAlias(preference.projectId(), preference.displayPhrase(),
				preference.assetType(), preference.assetKey(), preference.businessLabel(), operator);
		preferenceService.continuePersonal(preferenceId);
		return result;
	}

	public record PromotionResult(Long sourceVersionId, String candidateId, boolean alreadyApplied) {
	}

}
