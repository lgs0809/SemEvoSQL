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

import cn.lgs.semevosql.semantic.application.ProjectDocumentService;
import cn.lgs.semevosql.semantic.application.ProjectDocumentService.BundleIngestionResult;
import cn.lgs.semevosql.semantic.application.ProjectDocumentService.DeleteResult;
import cn.lgs.semevosql.semantic.application.ProjectDocumentService.DocumentContent;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.AttemptView;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.IngestionResult;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.MaterialView;
import cn.lgs.semevosql.semantic.application.SemanticMaterialIngestionService.ProvenanceView;
import cn.lgs.semevosql.semantic.domain.MaterialCategory;
import cn.lgs.semevosql.semantic.domain.MaterialLifecycle;
import cn.lgs.semevosql.semantic.domain.ProjectDocumentType;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/documents")
@RequiredArgsConstructor
public class ProjectDocumentController {

	private final ProjectDocumentService documentService;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<IngestionResult> upload(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestPart("documentType") ProjectDocumentType documentType,
			@RequestPart(value = "materialCategory", required = false) MaterialCategory materialCategory,
			@RequestPart(value = "lifecycle", required = false) MaterialLifecycle lifecycle,
			@RequestPart(value = "materialType", required = false) SemanticMaterialType materialType,
			@RequestPart(value = "datasourceId", required = false) Integer datasourceId,
			@RequestPart(value = "sourceName", required = false) String sourceName,
			@RequestPart(value = "sourceLocation", required = false) String sourceLocation,
			@RequestPart("file") FilePart file) {
		return documentService.upload(projectId, versionId, documentType, materialCategory, lifecycle, materialType,
				datasourceId, sourceName, sourceLocation, file);
	}

	@PostMapping(value = "/bundle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<BundleIngestionResult> uploadBundle(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestPart(value = "materialCategory", required = false) MaterialCategory materialCategory,
			@RequestPart(value = "lifecycle", required = false) MaterialLifecycle lifecycle,
			@RequestPart(value = "datasourceId", required = false) Integer datasourceId,
			@RequestPart(value = "sourceName", required = false) String sourceName,
			@RequestPart(value = "sourceLocation", required = false) String sourceLocation,
			@RequestPart("file") FilePart file) {
		return documentService.uploadBundle(projectId, versionId, materialCategory, lifecycle, datasourceId, sourceName,
				sourceLocation, file);
	}

	@PostMapping(value = "/inline", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<IngestionResult> ingestInline(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody InlineDocumentRequest request) {
		return Mono
			.fromCallable(() -> documentService.ingestInline(projectId, versionId, request.documentType(),
					request.materialCategory(), request.lifecycle(), request.materialType(), request.datasourceId(),
					request.sourceName(), request.sourceLocation(), request.content()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping
	public List<MaterialView> list(@PathVariable Long projectId, @PathVariable Long versionId) {
		return documentService.list(projectId, versionId);
	}

	@GetMapping("/{documentId}")
	public MaterialView get(@PathVariable Long projectId, @PathVariable Long versionId, @PathVariable Long documentId) {
		return documentService.get(projectId, versionId, documentId);
	}

	@GetMapping("/{documentId}/content")
	public Mono<ResponseEntity<Resource>> content(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Long documentId) {
		return Mono.fromCallable(() -> documentService.content(projectId, versionId, documentId))
			.map(this::downloadResponse)
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/{documentId}/attempts")
	public List<AttemptView> attempts(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Long documentId) {
		return documentService.attempts(projectId, versionId, documentId);
	}

	@GetMapping("/{documentId}/provenance")
	public List<ProvenanceView> provenance(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Long documentId) {
		return documentService.provenance(projectId, versionId, documentId);
	}

	@PostMapping("/{documentId}/reparse")
	public Mono<IngestionResult> reparse(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Long documentId, @Valid @RequestBody ReparseRequest request) {
		return Mono
			.fromCallable(() -> documentService.reparse(projectId, versionId, documentId, request.extractionModel()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@DeleteMapping("/{documentId}")
	public DeleteResult delete(@PathVariable Long projectId, @PathVariable Long versionId,
			@PathVariable Long documentId) {
		return documentService.delete(projectId, versionId, documentId);
	}

	private ResponseEntity<Resource> downloadResponse(DocumentContent content) {
		MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
		if (content.mediaType() != null && !content.mediaType().isBlank()) {
			try {
				mediaType = MediaType.parseMediaType(content.mediaType());
			}
			catch (InvalidMediaTypeException ignored) {
				// Untrusted upload metadata must not break a safe binary download.
			}
		}
		String disposition = ContentDisposition.attachment()
			.filename(content.filename(), StandardCharsets.UTF_8)
			.build()
			.toString();
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header("X-Content-Type-Options", "nosniff")
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition)
			.contentType(mediaType)
			.contentLength(content.contentLength())
			.body(content.resource());
	}

	public record InlineDocumentRequest(@NotNull ProjectDocumentType documentType, MaterialCategory materialCategory,
			MaterialLifecycle lifecycle, @NotNull SemanticMaterialType materialType, @Size(max = 500) String sourceName,
			@Size(max = 1000) String sourceLocation, Integer datasourceId, @NotBlank String content) {

		public InlineDocumentRequest(ProjectDocumentType documentType, SemanticMaterialType materialType,
				String sourceName, String sourceLocation, Integer datasourceId, String content) {
			this(documentType, null, null, materialType, sourceName, sourceLocation, datasourceId, content);
		}
	}

	public record ReparseRequest(@Size(max = 255) String extractionModel) {
	}

}
