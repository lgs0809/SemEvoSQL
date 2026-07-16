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
import cn.lgs.semevosql.util.JsonUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Extracts relationship observations from historical SQL without promoting them to
 * published semantic facts. Historical SQL is evidence that a query shape existed, not a
 * reason to make business users review Catalog completeness. Candidate signals are
 * persisted as evidence by ingestion and only become Grill-Me questions when a concrete
 * mined/runtime scenario actually needs an unresolved relationship.
 */
@Component
public class HistoricalSqlSemanticMaterialParser {

	private static final Pattern QUALIFIED_EQUALITY = Pattern.compile(
			"(?is)([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)\\.([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)\\s*=\\s*"
					+ "([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)\\.([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)");

	private static final Pattern TABLE_ALIAS = Pattern
		.compile("(?is)\\b(from|join)\\s+((?:[`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?\\.)?"
				+ "[`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)"
				+ "(?:\\s+(?:as\\s+)?([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?))?");

	public SemanticMaterialParseResult parse(Long projectId, Long projectVersionId, String contentHash, String sql,
			SemanticCatalogSnapshot catalog) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("Historical SQL content cannot be blank");
		}
		Map<String, String> physicalTableToModel = new LinkedHashMap<>();
		for (SemanticCatalogSnapshot.Model model : safe(catalog == null ? null : catalog.getModels())) {
			if (model.getPhysicalTable() != null) {
				physicalTableToModel.put(normalizeQualifiedName(model.getPhysicalTable()), model.getModelCode());
				physicalTableToModel.put(unqualified(normalizeQualifiedName(model.getPhysicalTable())),
						model.getModelCode());
			}
		}

		Map<String, String> aliasToTable = extractAliases(sql);
		List<RelationshipCandidate> candidates = extractCandidates(sql, aliasToTable, physicalTableToModel);
		List<SemanticGap> gaps = new ArrayList<>();
		Set<String> uniqueCandidateKeys = new LinkedHashSet<>();
		for (RelationshipCandidate candidate : candidates) {
			String candidateKey = candidate.sourceModelCode() + ":" + candidate.sourceColumn() + "="
					+ candidate.targetModelCode() + ":" + candidate.targetColumn();
			if (!uniqueCandidateKeys.add(candidateKey)) {
				continue;
			}
			String encodedCandidate = encodeCandidate(candidate);
			gaps.add(SemanticGap.openWithKey(projectId, projectVersionId,
					"material:" + contentHash + ":relationship:" + Integer.toHexString(candidateKey.hashCode()),
					"HISTORICAL_SQL_RELATIONSHIP_CANDIDATE",
					"历史 SQL 观察到关系 " + candidate.sourceTable() + "." + candidate.sourceColumn() + " = "
							+ candidate.targetTable() + "." + candidate.targetColumn() + "。",
					"仅作为关系候选 Evidence；只有具体业务场景需要且现有语义仍无法确定时才进入确认。", "该关系来自历史 SQL，只能证明曾被使用，不能自动证明基数、JOIN 类型或业务正确性。",
					encodedCandidate, 80));
		}

		return new SemanticMaterialParseResult(null, gaps, List.of(), false,
				"Historical SQL parsed: relationshipCandidates=" + candidates.size());
	}

	private Map<String, String> extractAliases(String sql) {
		Map<String, String> aliases = new LinkedHashMap<>();
		Matcher matcher = TABLE_ALIAS.matcher(sql);
		while (matcher.find()) {
			String table = normalizeQualifiedName(matcher.group(2));
			String alias = normalizeIdentifier(matcher.group(3));
			aliases.put(unqualified(table), table);
			if (!alias.isBlank() && !isSqlKeyword(alias)) {
				aliases.put(alias, table);
			}
		}
		return aliases;
	}

	private List<RelationshipCandidate> extractCandidates(String sql, Map<String, String> aliasToTable,
			Map<String, String> physicalTableToModel) {
		List<RelationshipCandidate> candidates = new ArrayList<>();
		Matcher matcher = QUALIFIED_EQUALITY.matcher(sql);
		while (matcher.find()) {
			String leftQualifier = normalizeIdentifier(matcher.group(1));
			String leftColumn = normalizeIdentifier(matcher.group(2));
			String rightQualifier = normalizeIdentifier(matcher.group(3));
			String rightColumn = normalizeIdentifier(matcher.group(4));
			String leftTable = aliasToTable.getOrDefault(leftQualifier, leftQualifier);
			String rightTable = aliasToTable.getOrDefault(rightQualifier, rightQualifier);
			String leftModel = modelForTable(leftTable, physicalTableToModel);
			String rightModel = modelForTable(rightTable, physicalTableToModel);
			if (leftModel == null || rightModel == null || leftModel.equals(rightModel)) {
				continue;
			}
			candidates
				.add(new RelationshipCandidate(leftModel, rightModel, leftTable, rightTable, leftColumn, rightColumn));
		}
		return candidates;
	}

	private String modelForTable(String table, Map<String, String> physicalTableToModel) {
		String normalized = normalizeQualifiedName(table);
		String model = physicalTableToModel.get(normalized);
		return model == null ? physicalTableToModel.get(unqualified(normalized)) : model;
	}

	private String encodeCandidate(RelationshipCandidate candidate) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(candidate);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to encode historical SQL relationship candidate", ex);
		}
	}

	private String normalizeQualifiedName(String value) {
		if (value == null) {
			return "";
		}
		String[] parts = value.trim().split("\\.");
		List<String> normalized = new ArrayList<>();
		for (String part : parts) {
			String identifier = normalizeIdentifier(part);
			if (!identifier.isBlank()) {
				normalized.add(identifier);
			}
		}
		return String.join(".", normalized);
	}

	private String normalizeIdentifier(String value) {
		if (value == null) {
			return "";
		}
		String result = value.trim();
		if (result.length() >= 2 && ((result.startsWith("`") && result.endsWith("`"))
				|| (result.startsWith("\"") && result.endsWith("\""))
				|| (result.startsWith("[") && result.endsWith("]")))) {
			result = result.substring(1, result.length() - 1);
		}
		return result.toLowerCase(Locale.ROOT);
	}

	private String unqualified(String table) {
		int index = table.lastIndexOf('.');
		return index < 0 ? table : table.substring(index + 1);
	}

	private boolean isSqlKeyword(String value) {
		return Set
			.of("on", "where", "left", "right", "inner", "outer", "full", "cross", "join", "group", "order", "limit",
					"union", "having")
			.contains(value);
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public record RelationshipCandidate(String sourceModelCode, String targetModelCode, String sourceTable,
			String targetTable, String sourceColumn, String targetColumn) {
	}

}
