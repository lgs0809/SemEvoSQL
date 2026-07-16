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

import cn.lgs.semevosql.prompt.PromptConstant;
import cn.lgs.semevosql.operations.UntrustedContentGuard;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SemanticCatalogPromptRenderer {

	private final UntrustedContentGuard contentGuard;

	public String render(SemanticCatalogSnapshot snapshot) {
		StringBuilder content = new StringBuilder();
		for (SemanticCatalogSnapshot.Model model : snapshot.getModels()) {
			if (model.getStatus() != SemanticAssetStatus.ENABLED) {
				continue;
			}
			content.append("## Model ")
				.append(model.getModelCode())
				.append(" -> ")
				.append(model.getPhysicalTable())
				.append(" (")
				.append(nullToEmpty(model.getBusinessName()))
				.append(")\n");
			appendLine(content, "Description", model.getDescription());
			String grains = snapshot.getGrains()
				.stream()
				.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(grain -> model.getModelCode().equals(grain.getModelCode()))
				.map(grain -> grain.getGrainCode() + " keys=[" + nullToEmpty(grain.getKeyColumns()) + "]")
				.collect(Collectors.joining("; "));
			appendLine(content, "Grain", grains);

			content.append("Columns:\n");
			snapshot.getColumns()
				.stream()
				.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(column -> model.getModelCode().equals(column.getModelCode()))
				.filter(column -> !Boolean.FALSE.equals(column.getAllowSendToLlm()))
				.forEach(column -> content.append("- ")
					.append(column.getBusinessName())
					.append(" => ")
					.append(model.getPhysicalTable())
					.append('.')
					.append(column.getColumnName())
					.append(" type=")
					.append(nullToEmpty(column.getDataType()))
					.append(" nullable=")
					.append(column.getNullable() == null ? "unknown" : column.getNullable())
					.append(" role=")
					.append(column.getRole())
					.append(" policy=[projection=")
					.append(!Boolean.FALSE.equals(column.getAllowProjection()))
					.append(",filter=")
					.append(!Boolean.FALSE.equals(column.getAllowFilter()))
					.append(",aggregation=")
					.append(!Boolean.FALSE.equals(column.getAllowAggregation()))
					.append(']')
					.append(hasText(column.getUnit()) ? " unit=" + column.getUnit() : "")
                    .append(hasText(column.getSynonyms()) ? " synonyms=[" + column.getSynonyms() + "]" : "")
					.append(hasText(column.getDescription()) ? " description=" + column.getDescription() : "")
					.append('\n'));

			content.append("Metrics:\n");
			snapshot.getMetrics()
				.stream()
				.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(metric -> model.getModelCode().equals(metric.getModelCode()))
				.forEach(metric -> content.append("- ")
					.append(metric.getMetricCode())
					.append(" (")
					.append(metric.getBusinessName())
					.append(") = ")
					.append(metric.getExpression())
					.append(hasText(metric.getAggregation()) ? " aggregation=" + metric.getAggregation() : "")
					.append(hasText(metric.getFilterExpression()) ? " filter=" + metric.getFilterExpression() : "")
					.append('\n'));

			content.append("Dimensions:\n");
			snapshot.getDimensions()
				.stream()
				.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(dimension -> model.getModelCode().equals(dimension.getModelCode()))
				.forEach(dimension -> content.append("- ")
					.append(dimension.getDimensionCode())
					.append(" (")
					.append(dimension.getBusinessName())
					.append(") => ")
					.append(hasText(dimension.getExpression()) ? dimension.getExpression() : dimension.getColumnName())
					.append('\n'));
			content.append('\n');
		}

		if (!snapshot.getRelationships().isEmpty()) {
			content.append("## Relationships\n");
			snapshot.getRelationships()
				.stream()
				.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
				.forEach(relationship -> content.append("- ")
					.append(relationship.getRelationshipCode())
					.append(": ")
					.append(relationship.getSourceModelCode())
					.append(" -> ")
					.append(relationship.getTargetModelCode())
					.append(" cardinality=")
					.append(relationship.getCardinality())
					.append(" join=")
					.append(relationship.getJoinCondition())
					.append('\n'));
		}

		if (!snapshot.getRules().isEmpty()) {
			content.append("## Business Rules\n");
			snapshot.getRules()
				.stream()
				.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
				.forEach(rule -> content.append("- ")
					.append(rule.getRuleCode())
					.append(" [")
					.append(rule.getRuleType())
					.append("] ")
					.append(rule.getBusinessName())
					.append(": ")
					.append(rule.getExpression())
					.append('\n'));
		}

		Map<String, Object> params = new HashMap<>();
		params.put("semanticModel", contentGuard.wrapEvidence(content.toString()));
		return PromptConstant.getSemanticCatalogPromptTemplate().render(params);
	}

	private void appendLine(StringBuilder content, String label, String value) {
		if (hasText(value)) {
			content.append(label).append(": ").append(value).append('\n');
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
