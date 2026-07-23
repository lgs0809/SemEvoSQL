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
package cn.lgs.semevosql.multisource;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/multi-source-policy")
@RequiredArgsConstructor
public class MultiSourcePolicyController {

	private final MultiSourcePolicyService service;

	private final SemanticCatalogRepository catalogRepository;

	private final OperatorContext.Resolver operatorResolver;

	@GetMapping
	public MultiSourcePolicySnapshot get(@PathVariable Long projectId, @PathVariable Long versionId) {
		return service.get(projectId, versionId);
	}

	@PutMapping
	public MultiSourcePolicySnapshot replace(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestBody MultiSourcePolicySnapshot snapshot, @RequestHeader HttpHeaders headers, Principal principal) {
		return service.replace(projectId, versionId, snapshot,
				operatorResolver.resolve(headers, principal, "multi-source-policy-replace:" + versionId));
	}

	@GetMapping("/violations")
	public List<String> violations(@PathVariable Long projectId, @PathVariable Long versionId) {
		return service.validateForRelease(projectId, versionId, catalogRepository.loadCatalog(projectId, versionId));
	}

}
