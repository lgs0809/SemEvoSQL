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
package cn.lgs.semevosql.onboarding;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.onboarding.ProjectOnboardingApplicationService.AnswerCommand;
import cn.lgs.semevosql.onboarding.ProjectOnboardingApplicationService.ConfirmCommand;
import cn.lgs.semevosql.onboarding.ProjectOnboardingApplicationService.OnboardingView;
import cn.lgs.semevosql.onboarding.ProjectOnboardingApplicationService.QuestionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/onboarding")
@RequiredArgsConstructor
public class ProjectOnboardingController {

	private final ProjectOnboardingApplicationService onboardingService;

	private final OperatorContext.Resolver operatorResolver;

	@PostMapping("/start")
	public OnboardingView start(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody StartRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		String operator = operatorResolver.resolve(headers, principal, "project-onboarding-start:" + versionId)
			.operator();
		return onboardingService.start(projectId, versionId, request.idempotencyKey(), operator);
	}

	@GetMapping
	public OnboardingView get(@PathVariable Long projectId, @PathVariable Long versionId) {
		return onboardingService.get(projectId, versionId);
	}

	@GetMapping("/next-question")
	public OnboardingQuestion nextQuestion(@PathVariable Long projectId, @PathVariable Long versionId) {
		return onboardingService.nextQuestion(projectId, versionId);
	}

	@PostMapping("/questions/{questionId}/answer")
	public OnboardingView answer(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable String questionId, @Valid @RequestBody AnswerRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		String operator = operatorResolver.resolve(headers, principal, "project-onboarding-answer:" + questionId)
			.operator();
		return onboardingService.answer(projectId, versionId, questionId, new AnswerCommand(request.answer(),
				request.answerType(), request.revision(), request.idempotencyKey(), operator));
	}

	@PostMapping("/questions/{questionId}/skip")
	public OnboardingView skip(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable String questionId, @Valid @RequestBody QuestionRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		String operator = operatorResolver.resolve(headers, principal, "project-onboarding-skip:" + questionId)
			.operator();
		return onboardingService.skip(projectId, versionId, questionId,
				new QuestionCommand(request.revision(), request.idempotencyKey(), operator, request.reason()));
	}

	@PostMapping("/questions/{questionId}/not-applicable")
	public OnboardingView notApplicable(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable String questionId, @Valid @RequestBody QuestionRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		String operator = operatorResolver
			.resolve(headers, principal, "project-onboarding-not-applicable:" + questionId)
			.operator();
		return onboardingService.notApplicable(projectId, versionId, questionId,
				new QuestionCommand(request.revision(), request.idempotencyKey(), operator, request.reason()));
	}

	@GetMapping("/conflicts")
	public List<OnboardingConflict> conflicts(@PathVariable Long projectId, @PathVariable Long versionId) {
		return onboardingService.conflicts(projectId, versionId);
	}

	@GetMapping("/summary")
	public OnboardingSummary summary(@PathVariable Long projectId, @PathVariable Long versionId) {
		return onboardingService.summary(projectId, versionId);
	}

	@PostMapping("/confirm")
	public OnboardingSummary confirm(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody ConfirmRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		String operator = operatorResolver.resolve(headers, principal, "project-onboarding-confirm:" + versionId)
			.operator();
		return onboardingService.confirm(projectId, versionId,
				new ConfirmCommand(request.revision(), request.idempotencyKey(), operator));
	}

	public record StartRequest(@NotBlank String idempotencyKey) {
	}

	public record AnswerRequest(@NotBlank String answer, @NotBlank String answerType, @PositiveOrZero long revision,
			@NotBlank String idempotencyKey) {
	}

	public record QuestionRequest(@PositiveOrZero long revision, @NotBlank String idempotencyKey, String reason) {
	}

	public record ConfirmRequest(@PositiveOrZero long revision, @NotBlank String idempotencyKey) {
	}

}
