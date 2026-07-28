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
package cn.lgs.semevosql.retention;

import cn.lgs.semevosql.common.LocalOperatorService;
import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.retention.QueryRunRetentionRepository.RetentionBatch;
import cn.lgs.semevosql.retention.QueryRunRetentionRepository.RunArchive;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/operations/retention")
@RequiredArgsConstructor
public class QueryRunRetentionController {

	private final QueryRunRetentionService service;

	private final OperatorContext.Resolver operatorResolver;

	private final LocalOperatorService authorization;

	@PostMapping("/runs")
	public RetentionBatch run(@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestParam(required = false) Boolean dryRun, @RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "run-retention-execute");
		return service.execute(idempotencyKey, dryRun);
	}

	@GetMapping("/runs/{batchId}")
	public RetentionBatch batch(@PathVariable String batchId, @RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "run-retention-batch:" + batchId);
		return service.findBatch(batchId);
	}

	@GetMapping("/archives/{runId}")
	public RunArchive archive(@PathVariable String runId, @RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "run-retention-archive:" + runId);
		return service.findArchive(runId);
	}

	private void require(HttpHeaders headers, Principal principal, String operation) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, operation);
		authorization.require(operator, operation);
	}

}
