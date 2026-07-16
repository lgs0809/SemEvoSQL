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
package cn.lgs.semevosql.semantic.adapter;

import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.IngestionResult;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.MaterialView;
import cn.lgs.semevosql.semantic.domain.MaterialCategory;
import cn.lgs.semevosql.semantic.domain.MaterialLifecycle;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/semantic-materials")
@RequiredArgsConstructor
public class SemanticMaterialController {

	private final SemanticMaterialIngestionService ingestionService;

	@PostMapping
	public Mono<IngestionResult> ingest(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody SemanticMaterialRequest request) {
		return Mono
			.fromCallable(
					() -> ingestionService.ingest(projectId, versionId, request.materialCategory(), request.lifecycle(),
							request.materialType(), request.sourceName(), request.datasourceId(), request.content()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping
	public List<MaterialView> list(@PathVariable Long projectId, @PathVariable Long versionId) {
		return ingestionService.list(projectId, versionId);
	}

	@GetMapping("/{materialId}")
	public MaterialView get(@PathVariable Long projectId, @PathVariable Long versionId, @PathVariable Long materialId) {
		return ingestionService.get(projectId, versionId, materialId);
	}

	public record SemanticMaterialRequest(MaterialCategory materialCategory, MaterialLifecycle lifecycle,
			@NotNull SemanticMaterialType materialType, @Size(max = 500) String sourceName, @NotBlank String content,
			Integer datasourceId) {

		public SemanticMaterialRequest(SemanticMaterialType materialType, String sourceName, String content,
				Integer datasourceId) {
			this(null, null, materialType, sourceName, content, datasourceId);
		}
	}

}
