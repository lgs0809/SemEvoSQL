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
package cn.lgs.semevosql.diagnosis;

import cn.lgs.semevosql.common.OperatorContext;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class QueryDiagnosisController {

	private final QueryDiagnosisService diagnosisService;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping("/runs/{runId}/diagnosis")
	public QueryDiagnosisService.DiagnosisView diagnosis(@PathVariable String runId,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return diagnosisService.diagnose(runId,
				operatorResolver.resolve(headers, principal, "query-diagnosis:" + runId));
	}

}
