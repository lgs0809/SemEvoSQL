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

import cn.lgs.semevosql.common.OperatorContext;
import java.security.Principal;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight actions for the 10-hit personal preference promotion prompt. */
@RestController
@RequestMapping("/api/semevosql/semantic-preferences")
@RequiredArgsConstructor
public class UserSemanticPreferenceController {

	private final UserSemanticPreferenceService preferenceService;

	private final ProjectSemanticAliasWorkflowService projectAliasWorkflowService;

	private final OperatorContext.Resolver operatorResolver;

	@PostMapping("/{preferenceId}/continue-personal")
	public UserSemanticPreferenceService.UserSemanticPreference continuePersonal(@PathVariable Long preferenceId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "semantic-preference-continue");
		requirePreferenceOwner(preferenceId, operator);
		return preferenceService.continuePersonal(preferenceId);
	}

	@PostMapping("/{preferenceId}/dismiss-upgrade")
	public UserSemanticPreferenceService.UserSemanticPreference dismissUpgrade(@PathVariable Long preferenceId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "semantic-preference-dismiss");
		requirePreferenceOwner(preferenceId, operator);
		return preferenceService.dismissUpgrade(preferenceId);
	}

	@PostMapping("/{preferenceId}/promote-project")
	public ProjectSemanticAliasWorkflowService.PromotionResult promoteProject(@PathVariable Long preferenceId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "semantic-preference-promote");
		return projectAliasWorkflowService.promotePreference(preferenceId, operator);
	}

	private void requirePreferenceOwner(Long preferenceId, OperatorContext operator) {
		var preference = preferenceService.findById(preferenceId)
			.orElseThrow(() -> new IllegalArgumentException("User semantic preference not found: " + preferenceId));
		if (!Objects.equals(preference.userId(), operator.operator())) {
			throw new SecurityException("A personal semantic preference can only be changed by its owner");
		}
	}

}
