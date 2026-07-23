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
package cn.lgs.semevosql.correction;

import cn.lgs.semevosql.correction.QueryCorrectionService.BindingCorrectionCommand;
import cn.lgs.semevosql.correction.QueryCorrectionService.CorrectionOptions;
import cn.lgs.semevosql.correction.QueryCorrectionService.CorrectionResult;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.correction.QueryCorrectionService.DefinitionCorrectionCommand;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class QueryCorrectionController {

	private final QueryCorrectionService correctionService;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping("/runs/{runId}/correction-options")
	public Mono<CorrectionOptions> options(@PathVariable String runId, @RequestParam String assetType,
            @RequestParam(required=false) String query, @RequestParam(defaultValue="0") long afterId,
            @RequestParam(defaultValue="50") int pageSize, @RequestHeader HttpHeaders headers, Principal principal) {
        return Mono.fromCallable(() -> correctionService.options(runId,assetType,query,afterId,pageSize,
            operatorResolver.resolve(headers,principal,"query-correction-options"))).subscribeOn(Schedulers.boundedElastic());
    }

	@PostMapping("/projects/{projectId}/conversations/{conversationId}/runs/{runId}/corrections/binding")
	public Mono<CorrectionResult> correctBinding(@PathVariable Long projectId, @PathVariable String conversationId,
			@PathVariable String runId, @Valid @RequestBody BindingCorrectionCommand command,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return Mono.fromCallable(() -> {
			OperatorContext operator = operatorResolver.resolve(headers, principal, "query-binding-correction");
			return correctionService.correctBinding(projectId, conversationId, runId, command, operator);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/projects/{projectId}/conversations/{conversationId}/runs/{runId}/corrections/definition")
	public Mono<SemanticCorrectionProposalService.ProposalResult> correctDefinition(@PathVariable Long projectId,
			@PathVariable String conversationId, @PathVariable String runId,
			@Valid @RequestBody DefinitionCorrectionCommand command, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return Mono.fromCallable(() -> {
			OperatorContext operator = operatorResolver.resolve(headers, principal, "query-definition-correction");
			return correctionService.proposeDefinition(projectId, conversationId, runId, command, operator);
		}).subscribeOn(Schedulers.boundedElastic());
	}

}
