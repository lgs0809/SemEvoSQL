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
package cn.lgs.semevosql.semantic.domain;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticMaterial {

	private Long id;

	private Long projectId;

	private Long projectVersionId;

	private ProjectDocumentType documentType;

	private MaterialCategory materialCategory;

	private MaterialLifecycle lifecycle;

	private SemanticMaterialType materialType;

	private SemanticMaterialSourceType sourceType;

	private Long sourceMaterialId;

	private String sourceName;

	private String originalFilename;

	private String mediaType;

	private String filePath;

	private Long fileSize;

	private String sourceLocation;

	private Integer datasourceId;

	private String contentHash;

	private String content;

	private SemanticMaterialStatus status;

	private String parseSummary;

	private String errorMessage;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

}
