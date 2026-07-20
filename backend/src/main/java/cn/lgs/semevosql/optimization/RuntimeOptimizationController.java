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
package cn.lgs.semevosql.optimization;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.optimization.RuntimeOptimizationService.ReviewCommand;
import cn.lgs.semevosql.optimization.RuntimeOptimizationService.ShadowCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class RuntimeOptimizationController {

	private final RuntimeOptimizationService service;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping("/projects/{projectId}/runtime-optimization/candidates")
	public List<Map<String, Object>> candidates(@PathVariable Long projectId,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return service.list(projectId, status, limit);
	}

	@GetMapping("/runtime-optimization/candidates/{candidateId}")
	public Map<String, Object> candidate(@PathVariable String candidateId) {
		return service.evaluation(candidateId);
	}

	@PostMapping("/runtime-optimization/candidates/{candidateId}/shadow")
	public Map<String, Object> shadow(@PathVariable String candidateId, @RequestBody ShadowCommand command) {
		return service.recordShadow(candidateId, command);
	}

	@PostMapping("/runtime-optimization/candidates/{candidateId}/approve")
	public Map<String, Object> approve(@PathVariable String candidateId, @RequestBody CommentRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal,
				"runtime-optimization-approve:" + candidateId);
		return service.approve(candidateId, new ReviewCommand(null, request.comment()), operator);
	}

	@PostMapping("/runtime-optimization/candidates/{candidateId}/reject")
	public Map<String, Object> reject(@PathVariable String candidateId, @RequestBody CommentRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal,
				"runtime-optimization-reject:" + candidateId);
		return service.reject(candidateId, new ReviewCommand(null, request.comment()), operator);
	}

	@PostMapping("/runtime-optimization/candidates/{candidateId}/enable")
	public Map<String, Object> enable(@PathVariable String candidateId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return service.enable(candidateId,
				operatorResolver.resolve(headers, principal, "runtime-optimization-enable:" + candidateId));
	}

	@PostMapping("/runtime-optimization/candidates/{candidateId}/disable")
	public Map<String, Object> disable(@PathVariable String candidateId, @RequestParam String reason,
			@RequestParam(defaultValue = "false") boolean degraded, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return service.disable(candidateId, reason, degraded,
				operatorResolver.resolve(headers, principal, "runtime-optimization-disable:" + candidateId));
	}

	public record CommentRequest(String comment) {
	}

}
