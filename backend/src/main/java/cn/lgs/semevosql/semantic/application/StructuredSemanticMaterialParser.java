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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.project.domain.SemanticGap;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialType;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class StructuredSemanticMaterialParser {

	private static final Pattern SEMEVOSQL_BLOCK = Pattern
		.compile("(?is)```\\s*(semevosql-json|semevosql-yaml)\\s*\\R(.*?)```");

	private final ObjectMapper jsonMapper = JsonUtil.getObjectMapper();

	private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

	public boolean hasEmbeddedCatalogBlock(String content) {
		return content != null && SEMEVOSQL_BLOCK.matcher(content).find();
	}

	public SemanticMaterialParseResult parse(Long projectId, Long projectVersionId, String contentHash,
			SemanticMaterialType type, String content) throws Exception {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Semantic material content cannot be blank");
		}
		return switch (type) {
			case JSON -> parseCatalog(jsonMapper, content, "JSON semantic catalog patch");
			case YAML -> parseCatalog(yamlMapper, content, "YAML semantic catalog patch");
			case MARKDOWN -> parseMarkdown(projectId, projectVersionId, contentHash, content);
			default -> throw new IllegalArgumentException("Unsupported structured material type: " + type);
		};
	}

	private SemanticMaterialParseResult parseMarkdown(Long projectId, Long projectVersionId, String contentHash,
			String content) throws Exception {
		Matcher matcher = SEMEVOSQL_BLOCK.matcher(content);
		if (!matcher.find()) {
			SemanticGap gap = SemanticGap.openWithKey(projectId, projectVersionId,
					"material:" + contentHash + ":markdown-review", "UNSTRUCTURED_MATERIAL_REVIEW",
					"该 Markdown 未包含 semevosql-json 或 semevosql-yaml 结构块。请确认其中哪些内容应转为指标、维度、关系或规则。",
					"在 Markdown 中增加 ```semevosql-yaml 结构块，或通过 Semantic Catalog API 提交。", "材料已保留，但非结构化文字不会自动成为业务事实。",
					"material:" + contentHash, 200);
			return SemanticMaterialParseResult.review(List.of(gap),
					"Markdown stored as evidence; structured semantic review is required");
		}
		String blockType = matcher.group(1);
		String blockContent = matcher.group(2);
		ObjectMapper mapper = "semevosql-json".equalsIgnoreCase(blockType) ? jsonMapper : yamlMapper;
		return parseCatalog(mapper, blockContent, "Embedded " + blockType + " semantic catalog patch");
	}

	private SemanticMaterialParseResult parseCatalog(ObjectMapper mapper, String content, String source)
			throws Exception {
		SemanticCatalogSnapshot patch = mapper.readValue(content, SemanticCatalogSnapshot.class);
		if (patch == null) {
			throw new IllegalArgumentException("Semantic catalog material produced an empty payload");
		}
		int assetCount = safeSize(patch.getModels()) + safeSize(patch.getColumns()) + safeSize(patch.getMetrics())
				+ safeSize(patch.getDimensions()) + safeSize(patch.getRelationships()) + safeSize(patch.getGrains())
				+ safeSize(patch.getEnumValues()) + safeSize(patch.getRules());
		if (assetCount == 0) {
			throw new IllegalArgumentException("Semantic catalog patch does not contain any asset");
		}
		return SemanticMaterialParseResult.applied(patch, source + ", assets=" + assetCount);
	}

	private int safeSize(List<?> values) {
		return values == null ? 0 : values.size();
	}

}
