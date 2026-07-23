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
import cn.lgs.semevosql.evolution.SemanticEvolutionService.DraftCommand;
import cn.lgs.semevosql.evolution.SemanticEvolutionService.ReplayCommand;
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
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class SemanticEvolutionController {

	private final SemanticEvolutionService service;

	private final OperatorContext.Resolver operatorResolver;

	private final SemanticReplayCoordinator replayCoordinator;

	private final ManualReplayAttestationService attestationService;

	@GetMapping("/projects/{projectId}/semantic-evolution/candidates")
	public List<Map<String, Object>> candidates(@PathVariable Long projectId,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return service.list(projectId, status, limit);
	}

	@GetMapping("/semantic-evolution/candidates/{candidateId}")
	public Map<String, Object> candidate(@PathVariable String candidateId) {
		return service.get(candidateId);
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/patch")
	public Map<String, Object> updatePatch(@PathVariable String candidateId, @RequestBody SemanticPatch patch,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.updatePatch(candidateId, patch,
				operatorResolver.resolve(headers, principal, "semantic-patch-edit:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/patch/preflight")
	public SemanticPatchValidator.ValidationReport preflight(@PathVariable String candidateId,
			@RequestBody(required = false) SemanticPatch patch) {
		return service.preflight(candidateId, patch);
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/policy-patch")
	public Map<String, Object> updatePolicyPatch(@PathVariable String candidateId,
			@RequestBody MultiSourcePolicyPatch patch, @RequestHeader HttpHeaders headers, Principal principal) {
		return service.updatePolicyPatch(candidateId, patch,
				operatorResolver.resolve(headers, principal, "multi-source-policy-patch-edit:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/policy-patch/preflight")
	public MultiSourcePolicyPatchService.ValidationReport preflightPolicy(@PathVariable String candidateId,
			@RequestBody(required = false) MultiSourcePolicyPatch patch) {
		return service.preflightPolicy(candidateId, patch);
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/review")
	public Map<String, Object> review(@PathVariable String candidateId, @RequestBody ReviewRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.review(candidateId,
				new SemanticEvolutionService.ReviewCommand(request.approved(), null, request.comment()),
				operatorResolver.resolve(headers, principal, "semantic-review:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/draft")
	public Map<String, Object> draft(@PathVariable String candidateId, @RequestBody DraftCommand command,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.createDraft(candidateId, command,
				operatorResolver.resolve(headers, principal, "semantic-draft:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/replay")
	public SemanticReplayCoordinator.ReplayRunView replay(@PathVariable String candidateId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.startReplay(candidateId,
				operatorResolver.resolve(headers, principal, "semantic-replay:" + candidateId));
	}

	@GetMapping("/semantic-evolution/replay-runs/{replayRunId}")
	public SemanticReplayCoordinator.ReplayRunView replayRun(@PathVariable String replayRunId) {
		return replayCoordinator.get(replayRunId);
	}

	@GetMapping("/semantic-evolution/replay-runs/{replayRunId}/events")
	public List<cn.lgs.semevosql.run.RunEvent> replayEvents(@PathVariable String replayRunId,
			@RequestParam(defaultValue = "0") long afterSequence, @RequestParam(defaultValue = "200") int limit) {
		return replayCoordinator.events(replayRunId, afterSequence, limit);
	}

	@PostMapping("/semantic-evolution/replay-runs/{replayRunId}/cancel")
	public SemanticReplayCoordinator.ReplayRunView cancelReplay(@PathVariable String replayRunId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return replayCoordinator.cancel(replayRunId,
				operatorResolver.resolve(headers, principal, "semantic-replay-cancel:" + replayRunId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/manual-replay")
	public Map<String, Object> manualReplay(@PathVariable String candidateId, @RequestBody ReplayCommand command,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.recordReplay(candidateId, command,
				operatorResolver.resolve(headers, principal, "semantic-manual-replay:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/attestations")
	public ManualReplayAttestationService.AttestationResult attest(@PathVariable String candidateId,
			@RequestBody ManualReplayAttestationService.AttestationCommand command, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return attestationService.attest(candidateId, command,
				operatorResolver.resolve(headers, principal, "semantic-attestation:" + candidateId));
	}

	@GetMapping("/semantic-evolution/candidates/{candidateId}/attestations")
	public List<Map<String, Object>> attestations(@PathVariable String candidateId) {
		return attestationService.attestations(candidateId);
	}

	@GetMapping("/semantic-evolution/candidates/{candidateId}/release-decisions")
	public List<Map<String, Object>> releaseDecisions(@PathVariable String candidateId) {
		return attestationService.releaseDecisions(candidateId);
	}

	@GetMapping("/semantic-evolution/candidates/{candidateId}/replay-results")
	public List<Map<String, Object>> replayResults(@PathVariable String candidateId) {
		return service.replayResults(candidateId);
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/ready")
	public Map<String, Object> ready(@PathVariable String candidateId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return service.markReadyForPublish(candidateId,
				operatorResolver.resolve(headers, principal, "semantic-ready:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/published")
	public Map<String, Object> published(@PathVariable String candidateId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		return service.acknowledgePublished(candidateId,
				operatorResolver.resolve(headers, principal, "semantic-publish-compensation:" + candidateId));
	}

	@PostMapping("/semantic-evolution/candidates/{candidateId}/stale")
	public Map<String, Object> stale(@PathVariable String candidateId, @RequestParam String reason,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.markStale(candidateId, reason,
				operatorResolver.resolve(headers, principal, "semantic-stale:" + candidateId));
	}

	public record ReviewRequest(boolean approved, String comment) {
	}

}
