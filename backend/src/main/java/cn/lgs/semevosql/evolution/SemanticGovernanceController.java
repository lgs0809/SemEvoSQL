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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.application.CorpusRevisionApplicationService.CorpusRevision;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.ChangeSet;
import cn.lgs.semevosql.evolution.application.SemanticChangeSetApplicationService.Status;
import cn.lgs.semevosql.evolution.application.SemanticEvolutionReleaseOrchestrator.ReleaseResult;
import cn.lgs.semevosql.evolution.application.SemanticGovernanceApplicationService;
import cn.lgs.semevosql.evolution.application.SemanticGovernanceApplicationService.ChangeSetView;
import cn.lgs.semevosql.evolution.application.SemanticGovernanceApplicationService.EpisodeDiagnosisView;
import cn.lgs.semevosql.evolution.application.SemanticGovernanceApplicationService.ProjectSemanticReadinessView;
import cn.lgs.semevosql.evolution.application.SemanticGovernanceApplicationService.VersionTimelineView;
import cn.lgs.semevosql.evolution.application.SemanticVersionApplicationService.ActivationResult;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Product-facing governance API for Semantic Versions, Corpus Revisions, ChangeSets and Episodes. */
@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class SemanticGovernanceController {

    private final SemanticGovernanceApplicationService governanceService;

    private final ProjectScopeService projectScope;

    private final OperatorContext.Resolver operatorResolver;

    @GetMapping("/projects/{projectId}/semantic-versions/timeline")
    public VersionTimelineView timeline(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        requireProject(projectId, headers, principal, "semantic-version-timeline");
        return governanceService.timeline(projectId);
    }

    @GetMapping("/projects/{projectId}/corpus-revisions")
    public List<CorpusRevision> corpusRevisions(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        requireProject(projectId, headers, principal, "corpus-revisions");
        return governanceService.corpusRevisions(projectId);
    }

    @GetMapping("/projects/{projectId}/semantic-change-sets")
    public List<ChangeSet> changeSets(@PathVariable Long projectId, @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit, @RequestHeader HttpHeaders headers,
            Principal principal) {
        requireProject(projectId, headers, principal, "semantic-change-sets");
        return governanceService.changeSets(projectId, status, limit);
    }

    @GetMapping("/semantic-change-sets/{changeSetId}")
    public ChangeSetView changeSet(@PathVariable String changeSetId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        Long projectId = projectScope.projectForSemanticChangeSet(changeSetId)
            .orElseThrow(() -> new IllegalArgumentException("SemanticChangeSet not found: " + changeSetId));
        requireProject(projectId, headers, principal, "semantic-change-set:" + changeSetId);
        return governanceService.changeSet(changeSetId);
    }

    @GetMapping("/episodes/{episodeId}/diagnosis")
    public EpisodeDiagnosisView episodeDiagnosis(@PathVariable String episodeId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        Long projectId = projectScope.projectForEpisode(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        requireProject(projectId, headers, principal, "episode-diagnosis:" + episodeId);
        return governanceService.episodeDiagnosis(episodeId);
    }

    @GetMapping("/projects/{projectId}/semantic-readiness")
    public ProjectSemanticReadinessView readiness(@PathVariable Long projectId, @RequestHeader HttpHeaders headers,
            Principal principal) {
        OperatorContext operator = requireProject(projectId, headers, principal, "semantic-readiness");
        return governanceService.readiness(projectId, operator);
    }

    @PostMapping("/semantic-change-sets/{changeSetId}/promote")
    public ReleaseResult promoteMajor(@PathVariable String changeSetId,
            @RequestBody(required = false) GovernanceActionRequest request, @RequestHeader HttpHeaders headers,
            Principal principal) {
        Long projectId = projectScope.projectForSemanticChangeSet(changeSetId)
            .orElseThrow(() -> new IllegalArgumentException("SemanticChangeSet not found: " + changeSetId));
        OperatorContext operator = requireProject(projectId, headers, principal,
				"semantic-major-promote:" + changeSetId);
        return governanceService.promoteMajor(changeSetId, operator.operator(), operator.requestId(), reason(request));
    }

    @PostMapping("/projects/{projectId}/semantic-versions/{versionId}/rollback")
    public ActivationResult rollback(@PathVariable Long projectId, @PathVariable Long versionId,
            @RequestBody(required = false) GovernanceActionRequest request, @RequestHeader HttpHeaders headers,
            Principal principal) {
        OperatorContext operator = requireProject(projectId, headers, principal,
				"semantic-version-rollback:" + versionId);
        return governanceService.rollback(projectId, versionId, operator.operator(), operator.requestId(), reason(request));
    }

    private OperatorContext requireProject(Long projectId, HttpHeaders headers, Principal principal, String source) {
        OperatorContext operator = operatorResolver.resolve(headers, principal, source + ":" + projectId);
        projectScope.requireProject(projectId, operator);
        return operator;
    }

    private String reason(GovernanceActionRequest request) {
        return request == null ? null : request.reason();
    }

    public record GovernanceActionRequest(String reason) {
    }
}
