/*
 * Copyright 2026 the original author or authors.
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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Administrator facts are server scoped; a caller-supplied role never grants approval authority. */
@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/definition-candidates")
public class ProjectDefinitionGovernanceController {
    private final ProjectDefinitionAssessmentRepository repository;
    private final ProjectScopeService scope;
    private final OperatorContext.Resolver operators;
    private final ProjectDefinitionDecisionService decisions;
    private ProjectDefinitionIndexDependencyService indexDependencies;
    private ProjectDefinitionPublicationWorker publicationWorker;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPublicationProgress(ProjectDefinitionIndexDependencyService indexDependencies,ProjectDefinitionPublicationWorker publicationWorker) {
        this.indexDependencies=indexDependencies;this.publicationWorker=publicationWorker;
    }
    public ProjectDefinitionGovernanceController(ProjectDefinitionAssessmentRepository repository,
            ProjectScopeService scope,OperatorContext.Resolver operators,ProjectDefinitionDecisionService decisions) {
        this.repository=repository;this.scope=scope;this.operators=operators;this.decisions=decisions;
    }
    @GetMapping
    public List<Map<String,Object>> list(@PathVariable long projectId,@RequestHeader HttpHeaders headers,Principal principal) {
        var operator=operators.resolve(headers,principal,"project-definition-list");
        scope.requireProject(projectId,operator);
        if(!operators.administrator(operator))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有项目管理员可以查看演进评估");
        return repository.list(projectId);
    }
    @GetMapping("/{candidateId}/contributions")
    public Map<String,Object> contributions(@PathVariable long projectId,@PathVariable long candidateId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
            @RequestHeader HttpHeaders headers,Principal principal) {
        var operator=operators.resolve(headers,principal,"project-definition-contributions");
        scope.requireProject(projectId,operator);
        if(!operators.administrator(operator))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有项目管理员可以查看共享贡献来源");
        return repository.contributionEvidence(projectId,candidateId,offset,limit);
    }
    @PostMapping("/{candidateId}/decisions")
    public Map<String,Object> decide(@PathVariable long projectId,@PathVariable long candidateId,
            @RequestBody ProjectDefinitionDecisionService.Request request,@RequestHeader HttpHeaders headers,Principal principal) {
        return decisions.decide(projectId,candidateId,request,operators.resolve(headers,principal,"project-definition-decision"));
    }
    @PostMapping("/{candidateId}/publication-check")
    public ProjectDefinitionIndexDependencyService.CheckResult checkPublication(@PathVariable long projectId,@PathVariable long candidateId,
            @RequestBody ProjectDefinitionIndexDependencyService.CheckRequest request,@RequestHeader HttpHeaders headers,Principal principal) {
        var operator=operators.resolve(headers,principal,"project-definition-publication-check");
        scope.requireProject(projectId,operator);
        if(!operators.administrator(operator))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有项目管理员可以检查公共发布准备");
        var result=indexDependencies.check(projectId,candidateId,request,operator);
        if(!"NOT_READY".equals(result.status()))publicationWorker.scan();
        return result;
    }
}
