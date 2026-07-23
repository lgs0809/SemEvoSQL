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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.common.OperatorContext;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/query-examples")
@RequiredArgsConstructor
public class ValidatedQueryExampleController {

	private final QueryCaseRepository repository;

	private final QueryCaseQuarantineService quarantineService;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable Long projectId,
			@RequestParam(required = false) Long projectVersionId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String rebindStatus,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
		return repository.list(projectId, projectVersionId, status, rebindStatus, limit)
			.stream()
			.map(QueryCaseSummary::toMap)
			.toList();
	}

	@GetMapping("/{exampleId}")
	public Map<String, Object> detail(@PathVariable Long projectId, @PathVariable String exampleId) {
		return repository.detail(projectId, exampleId).toMap();
	}

	@PostMapping("/{exampleId}/quarantine")
	public Map<String, Object> quarantine(@PathVariable Long projectId, @PathVariable String exampleId,
			@RequestBody GovernanceRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return quarantineService
			.quarantine(projectId, exampleId, request.reason(),
					operatorResolver.resolve(headers, principal, "query-case-quarantine:" + exampleId))
			.toMap();
	}

	@PostMapping("/{exampleId}/quarantine/restore")
	public Map<String, Object> restore(@PathVariable Long projectId, @PathVariable String exampleId,
			@RequestBody GovernanceRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return quarantineService
			.restore(projectId, exampleId, request.reason(),
					operatorResolver.resolve(headers, principal, "query-case-restore:" + exampleId))
			.toMap();
	}

	@PostMapping("/{exampleId}/quarantine/reject")
	public Map<String, Object> reject(@PathVariable Long projectId, @PathVariable String exampleId,
			@RequestBody GovernanceRequest request, @RequestHeader HttpHeaders headers, Principal principal) {
		return quarantineService
			.reject(projectId, exampleId, request.reason(),
					operatorResolver.resolve(headers, principal, "query-case-reject:" + exampleId))
			.toMap();
	}

	public record GovernanceRequest(String reason) {
	}

}
