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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.semantic.application.OfflineCatalogImportService;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Product imports files; it does not run an initializer agent. */
@RestController
@RequestMapping("/api/semevosql/projects/{projectId}/versions/{versionId}/offline-catalog")
@RequiredArgsConstructor
public class OfflineCatalogImportController {
    private final OfflineCatalogImportService imports;
    private final OperatorContext.Resolver operators;
    @GetMapping("/source-schema")
    public Mono<JsonNode> source(@PathVariable Long projectId,@PathVariable Long versionId,@RequestHeader HttpHeaders headers,Principal principal) {
        return Mono.fromCallable(()->imports.export(projectId,versionId,operators.resolve(headers,principal,"catalog-export"))).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/preview")
    public Mono<OfflineCatalogImportService.Preview> preview(@PathVariable Long projectId,@PathVariable Long versionId,@RequestBody String raw,@RequestHeader HttpHeaders headers,Principal principal) {
        return Mono.fromCallable(()->imports.preview(projectId,versionId,raw,operators.resolve(headers,principal,"catalog-preview"))).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/{importId}/commit")
    public Mono<OfflineCatalogImportService.Receipt> commit(@PathVariable Long projectId,@PathVariable Long versionId,@PathVariable String importId,@RequestHeader HttpHeaders headers,Principal principal) {
        return Mono.fromCallable(()->imports.commit(projectId,versionId,importId,operators.resolve(headers,principal,"catalog-import"))).subscribeOn(Schedulers.boundedElastic());
    }
}
