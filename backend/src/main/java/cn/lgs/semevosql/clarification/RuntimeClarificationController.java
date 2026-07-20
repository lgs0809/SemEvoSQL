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

import cn.lgs.semevosql.clarification.RuntimeClarificationService.AnswerCommand;
import cn.lgs.semevosql.common.OperatorContext;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpHeaders;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/runs/{runId}/clarification")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class RuntimeClarificationController {

	private final RuntimeClarificationService clarificationService;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping
	public RuntimeClarification get(@PathVariable String runId) {
		return clarificationService.getPending(runId);
	}

	@PostMapping("/{clarificationId}/answer")
	public RuntimeClarification answer(@PathVariable String runId, @PathVariable String clarificationId,
			@Valid @RequestBody AnswerRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "runtime-clarification-answer");
        try {
		return clarificationService.answer(runId, clarificationId,
				new AnswerCommand(request.revision(), request.idempotencyKey(), request.selectedOption(),
						request.customAnswer(), request.scope() == null ? SemanticBindingScope.QUERY : request.scope(),
						operator.operator()),
				operator);
        } catch(org.springframework.web.server.ResponseStatusException stale) {
            if(stale.getStatusCode().value()==409) {
                try {clarificationService.refreshDefinitionChange(runId,clarificationId,request.revision(),operator);}
                catch(RuntimeException refreshFailure) {
                    stale.addSuppressed(refreshFailure);
                    log.warn("Unable to refresh outdated clarification {}",clarificationId,refreshFailure);
                }
            }
            throw stale; // The rejected answer is never automatically applied to a different question.
        }
	}

	public record AnswerRequest(@PositiveOrZero long revision, @NotBlank String idempotencyKey, String selectedOption,
			String customAnswer, SemanticBindingScope scope) {
	}

}
