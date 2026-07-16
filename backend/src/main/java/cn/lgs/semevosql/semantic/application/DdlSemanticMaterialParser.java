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

import cn.lgs.semevosql.semantic.domain.RelationshipCardinality;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticColumnRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DdlSemanticMaterialParser {

	private static final Pattern CREATE_TABLE_HEADER = Pattern
		.compile("(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)\\s*\\(");

	private static final Pattern TABLE_PRIMARY_KEY = Pattern.compile("(?is)\\bprimary\\s+key\\s*\\(([^)]+)\\)");

	private static final Pattern FOREIGN_KEY = Pattern.compile(
			"(?is)(?:constraint\\s+[^\\s]+\\s+)?foreign\\s+key\\s*\\(([^)]+)\\)\\s+references\\s+([^\\s(]+)\\s*\\(([^)]+)\\)");

	private static final Pattern COLUMN_START = Pattern.compile("^([^\\s]+)\\s+(.+)$", Pattern.DOTALL);

	private static final Pattern TYPE_TERMINATOR = Pattern.compile(
			"(?i)\\s+(not\\s+null|null|default|primary\\s+key|unique|references|comment|constraint|check|generated|collate)\\b");

	public SemanticMaterialParseResult parse(Long projectId, Long projectVersionId, String contentHash,
			Integer datasourceId, String ddl) {
		if (datasourceId == null) {
			throw new IllegalArgumentException("DDL material requires datasourceId");
		}
		List<DdlTable> tables = parseTables(ddl);
		if (tables.isEmpty()) {
			throw new IllegalArgumentException("DDL material does not contain a recognizable CREATE TABLE statement");
		}
		Map<String, String> modelCodeByTable = assignModelCodes(tables);
		List<SemanticCatalogSnapshot.Model> models = new ArrayList<>();
		List<SemanticCatalogSnapshot.Column> columns = new ArrayList<>();
		List<SemanticCatalogSnapshot.Dimension> dimensions = new ArrayList<>();
		List<SemanticCatalogSnapshot.Grain> grains = new ArrayList<>();
		List<SemanticCatalogSnapshot.Relationship> relationships = new ArrayList<>();

		for (DdlTable table : tables) {
			String modelCode = modelCodeByTable.get(normalizeTable(table.name()));
			models.add(SemanticCatalogSnapshot.Model.builder()
				.datasourceId(datasourceId)
				.modelCode(modelCode)
				.physicalTable(table.name())
				.businessName(table.name())
				.evidence("ddl:" + contentHash)
				.status(SemanticAssetStatus.ENABLED)
				.build());
			for (DdlColumn column : table.columns()) {
				SemanticColumnRole role = column.primary() ? SemanticColumnRole.IDENTIFIER : roleForType(column.type());
				columns.add(SemanticCatalogSnapshot.Column.builder()
					.modelCode(modelCode)
					.columnName(column.name())
					.businessName(column.name())
					.dataType(column.type())
					.role(role)
					.evidence("ddl:" + contentHash)
					.status(SemanticAssetStatus.ENABLED)
					.build());
				dimensions.add(SemanticCatalogSnapshot.Dimension.builder()
					.modelCode(modelCode)
					.dimensionCode(modelCode + "_" + toCode(column.name()))
					.businessName(column.name())
					.columnName(column.name())
					.dimensionType(role.name())
					.evidence("ddl:" + contentHash)
					.status(SemanticAssetStatus.ENABLED)
					.build());
			}
			if (!table.primaryKeys().isEmpty()) {
				grains.add(SemanticCatalogSnapshot.Grain.builder()
					.modelCode(modelCode)
					.grainCode(modelCode + "_ddl_primary_key_grain")
					.keyColumns(String.join(",", table.primaryKeys()))
					.uniquenessRule("PRIMARY KEY(" + String.join(",", table.primaryKeys()) + ")")
					.evidence("ddl:" + contentHash)
					.status(SemanticAssetStatus.ENABLED)
					.build());
			}
		}

		Set<String> relationshipCodes = new LinkedHashSet<>();
		for (DdlTable table : tables) {
			String sourceCode = modelCodeByTable.get(normalizeTable(table.name()));
			for (DdlForeignKey foreignKey : table.foreignKeys()) {
				String targetCode = modelCodeByTable.get(normalizeTable(foreignKey.targetTable()));
				if (targetCode == null) {
					continue;
				}
				String baseCode = sourceCode + "_" + toCode(foreignKey.sourceColumn()) + "_to_" + targetCode + "_"
						+ toCode(foreignKey.targetColumn());
				String relationshipCode = uniqueCode(baseCode, relationshipCodes);
				relationships.add(SemanticCatalogSnapshot.Relationship.builder()
					.relationshipCode(relationshipCode)
					.sourceModelCode(sourceCode)
					.targetModelCode(targetCode)
					.cardinality(RelationshipCardinality.MANY_TO_ONE)
					.joinType("LEFT")
					.joinCondition(table.name() + "." + foreignKey.sourceColumn() + " = " + foreignKey.targetTable()
							+ "." + foreignKey.targetColumn())
					.evidence("ddl:" + contentHash)
					.status(SemanticAssetStatus.ENABLED)
					.build());
			}
		}

		SemanticCatalogSnapshot patch = SemanticCatalogSnapshot.builder()
			.models(models)
			.columns(columns)
			.dimensions(dimensions)
			.grains(grains)
			.relationships(relationships)
			.metrics(List.of())
			.enumValues(List.of())
			.rules(List.of())
			.build();
		return new SemanticMaterialParseResult(patch, List.of(), false, "DDL parsed: tables=" + models.size()
				+ ", columns=" + columns.size() + ", relationships=" + relationships.size());
	}

	private List<DdlTable> parseTables(String ddl) {
		if (ddl == null || ddl.isBlank()) {
			return List.of();
		}
		String normalized = ddl.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
		List<DdlTable> tables = new ArrayList<>();
		Matcher matcher = CREATE_TABLE_HEADER.matcher(normalized);
		int searchFrom = 0;
		while (matcher.find(searchFrom)) {
			String tableName = unquote(matcher.group(1));
			int openingParenthesis = matcher.end() - 1;
			int closingParenthesis = matchingParenthesis(normalized, openingParenthesis);
			if (closingParenthesis < 0) {
				throw new IllegalArgumentException("Unclosed CREATE TABLE definition: " + tableName);
			}
			String body = normalized.substring(openingParenthesis + 1, closingParenthesis);
			tables.add(parseTable(tableName, splitTopLevel(body)));
			searchFrom = closingParenthesis + 1;
		}
		return tables;
	}

	private DdlTable parseTable(String tableName, List<String> definitions) {
		List<DdlColumn> columns = new ArrayList<>();
		Set<String> primaryKeys = new LinkedHashSet<>();
		List<DdlForeignKey> foreignKeys = new ArrayList<>();
		for (String definition : definitions) {
			String trimmed = definition.trim();
			if (trimmed.isBlank()) {
				continue;
			}
			Matcher primaryMatcher = TABLE_PRIMARY_KEY.matcher(trimmed);
			if (primaryMatcher.find()) {
				primaryKeys.addAll(splitIdentifiers(primaryMatcher.group(1)));
			}
			Matcher foreignKeyMatcher = FOREIGN_KEY.matcher(trimmed);
			if (foreignKeyMatcher.find()) {
				List<String> sourceColumns = splitIdentifiers(foreignKeyMatcher.group(1));
				List<String> targetColumns = splitIdentifiers(foreignKeyMatcher.group(3));
				String targetTable = unquote(foreignKeyMatcher.group(2));
				for (int index = 0; index < Math.min(sourceColumns.size(), targetColumns.size()); index++) {
					foreignKeys.add(new DdlForeignKey(sourceColumns.get(index), targetTable, targetColumns.get(index)));
				}
				continue;
			}
			String lower = trimmed.toLowerCase(Locale.ROOT);
			if (lower.startsWith("constraint ") || lower.startsWith("primary key") || lower.startsWith("unique ")
					|| lower.startsWith("key ") || lower.startsWith("index ") || lower.startsWith("check ")) {
				continue;
			}
			Matcher columnMatcher = COLUMN_START.matcher(trimmed);
			if (!columnMatcher.find()) {
				continue;
			}
			String columnName = unquote(columnMatcher.group(1));
			String remainder = columnMatcher.group(2).trim();
			Matcher terminator = TYPE_TERMINATOR.matcher(remainder);
			String type = terminator.find() ? remainder.substring(0, terminator.start()).trim() : remainder;
			boolean inlinePrimary = lower.contains("primary key");
			columns.add(new DdlColumn(columnName, type, inlinePrimary));
			if (inlinePrimary) {
				primaryKeys.add(columnName);
			}
		}
		List<DdlColumn> normalizedColumns = columns.stream()
			.map(column -> new DdlColumn(column.name(), column.type(),
					column.primary() || primaryKeys.contains(column.name())))
			.toList();
		return new DdlTable(tableName, normalizedColumns, List.copyOf(primaryKeys), foreignKeys);
	}

	private List<String> splitTopLevel(String body) {
		List<String> definitions = new ArrayList<>();
		int depth = 0;
		int start = 0;
		char quote = 0;
		for (int index = 0; index < body.length(); index++) {
			char value = body.charAt(index);
			if (quote != 0) {
				if (value == quote && (index == 0 || body.charAt(index - 1) != '\\')) {
					quote = 0;
				}
				continue;
			}
			if (value == '\'' || value == '"' || value == '`') {
				quote = value;
			}
			else if (value == '(') {
				depth++;
			}
			else if (value == ')') {
				depth--;
			}
			else if (value == ',' && depth == 0) {
				definitions.add(body.substring(start, index));
				start = index + 1;
			}
		}
		definitions.add(body.substring(start));
		return definitions;
	}

	private int matchingParenthesis(String value, int openingIndex) {
		int depth = 0;
		char quote = 0;
		for (int index = openingIndex; index < value.length(); index++) {
			char current = value.charAt(index);
			if (quote != 0) {
				if (current == quote && (index == 0 || value.charAt(index - 1) != '\\')) {
					quote = 0;
				}
				continue;
			}
			if (current == '\'' || current == '"' || current == '`') {
				quote = current;
			}
			else if (current == '(') {
				depth++;
			}
			else if (current == ')' && --depth == 0) {
				return index;
			}
		}
		return -1;
	}

	private Map<String, String> assignModelCodes(List<DdlTable> tables) {
		Map<String, String> result = new LinkedHashMap<>();
		Set<String> used = new LinkedHashSet<>();
		for (DdlTable table : tables) {
			result.put(normalizeTable(table.name()), uniqueCode(toCode(table.name()), used));
		}
		return result;
	}

	private SemanticColumnRole roleForType(String type) {
		String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
		return normalized.contains("date") || normalized.contains("time") || normalized.contains("timestamp")
				? SemanticColumnRole.TIME : SemanticColumnRole.ATTRIBUTE;
	}

	private List<String> splitIdentifiers(String value) {
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.map(this::unquote)
			.filter(identifier -> !identifier.isBlank())
			.toList();
	}

	private String uniqueCode(String base, Set<String> used) {
		String candidate = base.isBlank() ? "asset" : base;
		int suffix = 2;
		while (!used.add(candidate)) {
			candidate = base + "_" + suffix++;
		}
		return candidate;
	}

	private String toCode(String value) {
		String code = unquote(value).toLowerCase(Locale.ROOT)
			.replaceAll("[^\\p{L}\\p{N}]+", "_")
			.replaceAll("^_+|_+$", "");
		return code.isBlank() ? "asset" : code;
	}

	private String normalizeTable(String value) {
		return unquote(value).toLowerCase(Locale.ROOT);
	}

	private String unquote(String value) {
		if (value == null) {
			return "";
		}
		String result = value.trim();
		if (result.length() >= 2 && ((result.startsWith("`") && result.endsWith("`"))
				|| (result.startsWith("\"") && result.endsWith("\""))
				|| (result.startsWith("[") && result.endsWith("]")))) {
			result = result.substring(1, result.length() - 1);
		}
		return result;
	}

	private record DdlTable(String name, List<DdlColumn> columns, List<String> primaryKeys,
			List<DdlForeignKey> foreignKeys) {
	}

	private record DdlColumn(String name, String type, boolean primary) {
	}

	private record DdlForeignKey(String sourceColumn, String targetTable, String targetColumn) {
	}

}
