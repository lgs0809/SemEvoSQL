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
package cn.lgs.semevosql.semantic.adapter;

import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.semevosql.semantic.application.DatabaseSemanticCatalogAnalyzer;
import cn.lgs.semevosql.semantic.application.DatabaseSemanticCatalogAnalyzer.AnalysisResult;
import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintGenerationService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import java.security.Principal;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/semantic-catalog")
@RequiredArgsConstructor
public class SemanticCatalogController {

	private final SemanticCatalogApplicationService catalogService;

	private final SemanticBlueprintGenerationService semanticBlueprintGenerationService;

	private final DatabaseSemanticCatalogAnalyzer databaseAnalyzer;

    private final SemanticCatalogLookupService catalogLookup;
    private final ProjectScopeService projectScope;
    private final OperatorContext.Resolver operatorResolver;

    @GetMapping("/models")
    public Mono<SemanticCatalogLookupService.ModelPage> modelPage(@PathVariable Long projectId,@PathVariable Long versionId,
            @RequestParam String catalogHash,@RequestParam(required=false) String afterModelCode,
            @RequestParam(defaultValue="20") int pageSize,@RequestHeader HttpHeaders headers,Principal principal) {
        return Mono.fromCallable(()->{
            var operator=operatorResolver.resolve(headers,principal,"catalog-summary");
            projectScope.requireProject(projectId,operator);
            var result=catalogLookup.modelPage(projectId,versionId,catalogHash,afterModelCode,pageSize);
            projectScope.requireProject(projectId,operator);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Read-only detail operation; no model invocation or catalog mutation. */
    @PostMapping("/details")
    public Mono<SemanticCatalogSnapshot> details(@PathVariable Long projectId,@PathVariable Long versionId,
            @RequestBody CatalogDetailsRequest request,@RequestHeader HttpHeaders headers,Principal principal) {
        return Mono.fromCallable(()->{
            var operator=operatorResolver.resolve(headers,principal,"catalog-details");
            projectScope.requireProject(projectId,operator);
            var result=catalogLookup.loadAssets(projectId,versionId,request.catalogHash(),request.assets());
            projectScope.requireProject(projectId,operator);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record CatalogDetailsRequest(String catalogHash,List<SemanticCatalogLookupService.AssetRef> assets) {}


	@GetMapping
	public SemanticCatalogSnapshot getCatalog(@PathVariable Long projectId, @PathVariable Long versionId) {
		return catalogService.getCatalog(projectId, versionId);
	}

	@PutMapping
	public SemanticCatalogSnapshot replaceCatalog(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestBody SemanticCatalogSnapshot snapshot) {
		return catalogService.replaceDraftCatalog(projectId, versionId, snapshot);
	}

	@PostMapping("/scan-database")
	public AnalysisResult scanDatabase(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody ScanDatabaseRequest request) throws Exception {
		return databaseAnalyzer.analyze(projectId, versionId, request.datasourceId(), request.tables());
	}

	@GetMapping("/readiness")
	public CatalogReadiness readiness(@PathVariable Long projectId, @PathVariable Long versionId) {
		return catalogService.assess(projectId, versionId);
	}

	@PostMapping({ "/blueprint", "/query-plan" })
	public Mono<SemanticBlueprint> blueprint(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestBody BlueprintRequest request) {
		return Mono.fromCallable(() -> {
			List<String> selectedTables = request.selectedPhysicalTables() == null ? List.of()
					: List.copyOf(request.selectedPhysicalTables());
			QueryCaseHints bindings = semanticBlueprintGenerationService.plan(projectId, versionId, request.canonicalQuery(),
					selectedTables, List.of());
			return catalogService.buildBlueprint(projectId, versionId, request.canonicalQuery(), selectedTables, bindings);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	public record ScanDatabaseRequest(@NotNull Integer datasourceId, List<String> tables) {
	}

	public record BlueprintRequest(String canonicalQuery, List<String> selectedPhysicalTables) {
	}

}
