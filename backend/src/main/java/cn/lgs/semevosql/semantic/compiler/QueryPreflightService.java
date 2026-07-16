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
package cn.lgs.semevosql.semantic.compiler;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves LLM-generated Semantic SQL against the frozen semantic catalog without touching the
 * database. Semantic models are materialized as system-owned CTEs, while the model-authored SQL
 * structure (CTEs, windows, subqueries, unions, etc.) remains intact.
 */
@Component
public class QueryPreflightService {

	private static final Pattern CTE_PATTERN = Pattern
		.compile("(?i)(?:\\bwith\\b|,)\\s*([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)\\s+as\\s*\\(");

	private static final Pattern WITH_RECURSIVE_PREFIX = Pattern.compile("(?is)^\\s*with\\s+recursive\\s+");

	private static final Pattern WITH_PREFIX = Pattern.compile("(?is)^\\s*with\\s+");

	private static final Pattern METRIC_CALL = Pattern
		.compile("(?i)\\bMETRIC\\s*\\(\\s*'([^']+)'\\s*\\)");

	private static final Pattern DOUBLE_AGGREGATED_METRIC = Pattern
		.compile("(?is)\\b(?:sum|count|avg|min|max)\\s*\\(\\s*METRIC\\s*\\(");

	private static final Pattern RELATIONSHIP_CALL = Pattern.compile(
			"(?i)\\bRELATIONSHIP\\s*\\(\\s*'([^']+)'(?:\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*)?\\)");

	private static final Pattern MODEL_BINDING = Pattern.compile(
			"(?i)\\b(?:from|join)\\s+([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?)(?:\\s+(?:as\\s+)?([`\"\\[]?[a-zA-Z_][a-zA-Z0-9_$]*[`\"\\]]?))?");

	private static final Pattern SINGLE_AGGREGATE = Pattern
		.compile("(?is)^\\s*(sum|count|avg|min|max)\\s*\\(\\s*(distinct\\s+)?(.+)\\)\\s*$");

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

	private static final Pattern QUALIFIED_IDENTIFIER = Pattern
		.compile("([A-Za-z_][A-Za-z0-9_$]*)\\.([A-Za-z_][A-Za-z0-9_$]*)");

	private static final Set<String> SQL_WORDS = Set.of("sum", "count", "avg", "min", "max", "distinct", "case", "when",
			"then", "else", "end", "coalesce", "nullif", "cast", "as", "date", "datetime", "timestamp", "interval",
			"decimal", "numeric", "int", "integer", "bigint", "double", "float", "round", "floor", "ceil", "abs",
			"lower", "upper", "trim", "substring", "concat", "extract", "date_trunc", "date_format", "and", "or", "not",
			"in", "between", "like", "is", "null", "true", "false");

	private static final Set<String> SQL_CLAUSE_WORDS = Set.of("where", "join", "inner", "left", "right", "full", "cross",
			"on", "group", "order", "having", "limit", "union", "intersect", "except", "window", "qualify", "offset",
			"fetch");

	public PreflightResult preflight(String semanticSql, SemanticCatalogSnapshot catalog, SemanticBlueprint semanticPlan,
			Integer datasourceId, String dialect) {
		if (semanticSql == null || semanticSql.isBlank()) {
			throw new QueryPreflightException("SEMANTIC_SQL_EMPTY", "Semantic SQL cannot be blank");
		}
		if (catalog == null) {
			throw new QueryPreflightException("SEMANTIC_CATALOG_MISSING", "Frozen semantic catalog is unavailable");
		}
		String normalizedSemanticSql = semanticSql.toLowerCase(Locale.ROOT);
		if (normalizedSemanticSql.contains("__qw_internal_") || normalizedSemanticSql.contains("__qw_model_")) {
			throw new QueryPreflightException("SEMANTIC_RESERVED_IDENTIFIER",
					"Semantic SQL cannot reference SemEvoSQL internal model/field identifiers");
		}

		String dbType = resolveDbType(dialect);
		if (DOUBLE_AGGREGATED_METRIC.matcher(semanticSql).find()) {
			throw new QueryPreflightException("SEMANTIC_METRIC_DOUBLE_AGGREGATION",
					"METRIC(...) already represents the published aggregation and must not be wrapped in another aggregate");
		}
		SQLStatement statement = parseSingleSelect(semanticSql, dbType, dialect);
        validatePagination((SQLSelectStatement)statement,semanticPlan);
		validateConfirmedOutputAliases((SQLSelectStatement) statement, semanticPlan);
		Set<String> cteNames = extractCteNames(semanticSql);
		Set<String> referencedTables = extractReferencedTables(statement, dbType);

		Map<String, SemanticCatalogSnapshot.Model> modelsByCode = enabledModels(catalog, datasourceId).stream()
			.collect(Collectors.toMap(model -> normalizeIdentifier(model.getModelCode()), model -> model, (left, right) -> left,
					LinkedHashMap::new));
		Map<String, SemanticCatalogSnapshot.Model> modelsByPhysicalTable = enabledModels(catalog, datasourceId).stream()
			.collect(Collectors.toMap(model -> normalizeQualifiedIdentifier(model.getPhysicalTable()), model -> model,
					(left, right) -> left, LinkedHashMap::new));

		Set<String> semanticModelCodes = new LinkedHashSet<>();
		Set<String> passthroughPhysicalTables = new LinkedHashSet<>();
		List<String> unknownTables = new ArrayList<>();
		for (String referencedTable : referencedTables) {
			String normalized = normalizeQualifiedIdentifier(referencedTable);
			String unqualified = unqualifiedName(normalized);
			if (normalized.equals(unqualified) && cteNames.contains(unqualified)) {
				continue;
			}
			// Logical model and CTE names are unqualified. A physical schema must
			// never disappear during resolution just because its table has the same name.
			SemanticCatalogSnapshot.Model model = modelsByCode.get(normalized);
			if (model != null) {
				semanticModelCodes.add(model.getModelCode());
				continue;
			}
			SemanticCatalogSnapshot.Model physicalModel = modelsByPhysicalTable.get(normalized);
			if (physicalModel == null) {
				physicalModel = modelsByPhysicalTable.values()
					.stream()
					.filter(candidate -> unqualifiedName(normalizeQualifiedIdentifier(candidate.getPhysicalTable())).equals(unqualified))
					.findFirst()
					.orElse(null);
			}
			if (physicalModel != null) {
				passthroughPhysicalTables.add(physicalModel.getPhysicalTable());
				continue;
			}
			unknownTables.add(referencedTable);
		}
		if (!unknownTables.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_MODEL_NOT_FOUND",
					"Semantic SQL references models outside the frozen semantic catalog: " + String.join(", ", unknownTables)
							+ ". Available models: " + String.join(", ", modelsByCode.keySet()));
		}
		if (!passthroughPhysicalTables.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_PHYSICAL_BYPASS_FORBIDDEN",
					"Semantic SQL must query governed model codes instead of physical tables: "
							+ String.join(", ", passthroughPhysicalTables));
		}

		validateAgainstPinnedPlan(semanticModelCodes, passthroughPhysicalTables, semanticPlan, modelsByCode);
		ModelBindings bindings = resolveModelBindings(semanticSql, semanticModelCodes, modelsByCode);
		String governedSql = rewriteRelationshipCalls(semanticSql, catalog, semanticPlan, bindings, dialect);
		governedSql = rewriteMetricCalls(governedSql, catalog, semanticPlan, bindings, dialect);
		statement = parseSingleSelect(governedSql, dbType, dialect);
		validateDirectModelColumns(statement, dbType, semanticModelCodes, catalog, bindings, cteNames);
		List<String> warnings = nullableColumnWarnings(statement, dbType, semanticModelCodes, catalog, bindings, cteNames);
		for (String modelCode : semanticModelCodes) {
			if (cteNames.contains(normalizeIdentifier(modelCode))) {
				throw new QueryPreflightException("SEMANTIC_MODEL_SHADOWED",
						"User SQL defines a CTE that shadows governed semantic model: " + modelCode);
			}
		}

		if (semanticModelCodes.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_MODEL_REQUIRED",
					"Semantic SQL must reference at least one governed model from the pinned Semantic Blueprint");
		}

		List<String> cteDefinitions = semanticModelCodes.stream()
			.map(modelsByCode::get)
			.filter(Objects::nonNull)
			.map(model -> renderModelCte(model, catalog, dialect))
			.toList();
		String physicalBodySql = rewriteModelSources(governedSql, modelsByCode, dialect);
		String physicalSql = injectModelCtes(physicalBodySql, cteDefinitions);
		Set<String> physicalTables = semanticModelCodes.stream()
			.map(code -> modelsByCode.get(normalizeIdentifier(code)))
			.filter(Objects::nonNull)
			.flatMap(model -> model.physicalTables().stream())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		physicalTables.addAll(passthroughPhysicalTables);
		return new PreflightResult(physicalSql, Set.copyOf(semanticModelCodes), Set.copyOf(physicalTables), false, warnings);
	}

    private void validatePagination(SQLSelectStatement statement,SemanticBlueprint plan) {
        if(plan==null) return;
        try { cn.lgs.semevosql.semantic.domain.QueryPagination.validate(plan); }
        catch(IllegalArgumentException invalid) {throw new QueryPreflightException("SEMANTIC_PAGINATION_INVALID",invalid.getMessage());}
        var select=statement.getSelect();
        var query=select.getQuery();
        com.alibaba.druid.sql.ast.SQLLimit limit=select.getLimit();
        var order=select.getOrderBy();
        if(query instanceof com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock block) {
            if(limit==null) limit=block.getLimit();
            if(order==null) order=block.getOrderBy();
        } else if(query instanceof com.alibaba.druid.sql.ast.statement.SQLUnionQuery union) {
            if(limit==null) limit=union.getLimit();
            if(order==null) order=union.getOrderBy();
        }
        var actualOffset=limit==null?select.getOffset():limit.getOffset();
        long expected=cn.lgs.semevosql.semantic.domain.QueryPagination.offset(plan);
        long actual=actualOffset==null?0:paginationNumber(actualOffset);
        if(actual!=expected) throw new QueryPreflightException("SEMANTIC_PAGINATION_MISMATCH","SQL OFFSET differs from the confirmed query plan");
        if(expected>0) {
            if(order==null || order.getItems().isEmpty()) throw new QueryPreflightException("SEMANTIC_PAGINATION_ORDER_REQUIRED","Pagination requires outer query ordering");
            var count=limit==null?select.getRowCount():limit.getRowCount();
            if(count==null || paginationNumber(count)!=(plan.getLimit()==null?100:plan.getLimit()))
                throw new QueryPreflightException("SEMANTIC_PAGINATION_MISMATCH","SQL page size differs from the confirmed query plan");
        }
    }
    private long paginationNumber(com.alibaba.druid.sql.ast.SQLExpr value) {
        if(value instanceof com.alibaba.druid.sql.ast.expr.SQLIntegerExpr integer) {
            try {long n=new java.math.BigInteger(integer.getNumber().toString()).longValueExact();if(n>=0)return n;}
            catch(ArithmeticException ignored) { /* rejected below */ }
        }
        throw new QueryPreflightException("SEMANTIC_PAGINATION_INVALID","SQL pagination requires nonnegative signed 64-bit integers");
    }

	private SQLStatement parseSingleSelect(String sql, String dbType, String dialect) {
		List<SQLStatement> statements;
		try {
			statements = SQLUtils.parseStatements(sql.trim(), dbType);
		}
		catch (RuntimeException ex) {
			throw new QueryPreflightException("SEMANTIC_SQL_PARSE_ERROR",
					"Semantic SQL cannot be parsed for dialect " + dialect + ": " + ex.getMessage(), ex);
		}
		if (statements.size() != 1 || !(statements.get(0) instanceof SQLSelectStatement)) {
			throw new QueryPreflightException("SEMANTIC_SQL_NOT_SELECT",
					"Query Preflight accepts exactly one SELECT statement");
		}
		return statements.get(0);
	}

	private Collection<SemanticCatalogSnapshot.Model> enabledModels(SemanticCatalogSnapshot catalog, Integer datasourceId) {
		return catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> datasourceId == null || Objects.equals(datasourceId, model.getDatasourceId()))
			.toList();
	}

	private ModelBindings resolveModelBindings(String semanticSql, Set<String> semanticModelCodes,
			Map<String, SemanticCatalogSnapshot.Model> modelsByCode) {
		Map<String, String> aliasToModel = new LinkedHashMap<>();
		Map<String, List<String>> modelToAliases = new LinkedHashMap<>();
		for (String modelCode : semanticModelCodes) {
			modelToAliases.put(normalizeIdentifier(modelCode), new ArrayList<>());
		}
		Matcher matcher = MODEL_BINDING.matcher(semanticSql);
		while (matcher.find()) {
			String table = normalizeIdentifier(matcher.group(1));
			SemanticCatalogSnapshot.Model model = modelsByCode.get(table);
			if (model == null) {
				continue;
			}
			String alias = normalizeIdentifier(matcher.group(2));
			if (alias.isBlank() || SQL_CLAUSE_WORDS.contains(alias)) {
				alias = table;
			}
			String previous = aliasToModel.putIfAbsent(alias, table);
			if (previous != null && !previous.equals(table)) {
				throw new QueryPreflightException("SEMANTIC_ALIAS_AMBIGUOUS",
						"Semantic SQL alias '" + alias + "' refers to more than one governed model");
			}
			modelToAliases.computeIfAbsent(table, ignored -> new ArrayList<>());
			if (!modelToAliases.get(table).contains(alias)) {
				modelToAliases.get(table).add(alias);
			}
		}
		for (String modelCode : semanticModelCodes) {
			String normalized = normalizeIdentifier(modelCode);
			if (modelToAliases.getOrDefault(normalized, List.of()).isEmpty()) {
				aliasToModel.putIfAbsent(normalized, normalized);
				modelToAliases.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(normalized);
			}
		}
		Map<String, List<String>> immutableAliases = new LinkedHashMap<>();
		modelToAliases.forEach((model, aliases) -> immutableAliases.put(model, List.copyOf(aliases)));
		return new ModelBindings(Map.copyOf(aliasToModel), Map.copyOf(immutableAliases));
	}

	private String rewriteMetricCalls(String sql, SemanticCatalogSnapshot catalog, SemanticBlueprint semanticPlan,
			ModelBindings bindings, String dialect) {
		Matcher matcher = METRIC_CALL.matcher(sql);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			ResolvedMetric resolved = resolveMetric(matcher.group(1), catalog, semanticPlan, bindings);
			String rendered = renderMetricExpression(resolved.metric(), resolved.alias(), catalog, dialect);
			matcher.appendReplacement(output, Matcher.quoteReplacement("(" + rendered + ")"));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private ResolvedMetric resolveMetric(String reference, SemanticCatalogSnapshot catalog, SemanticBlueprint semanticPlan,
			ModelBindings bindings) {
		if (semanticPlan == null || semanticPlan.getMetrics() == null || semanticPlan.getMetrics().isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_METRIC_OUTSIDE_PLAN",
					"Semantic SQL references METRIC(...) but the pinned Semantic Blueprint selected no metric");
		}
		String normalizedReference = normalizeQualifiedIdentifier(reference);
		String qualifier = qualifier(normalizedReference);
		String metricCode = unqualifiedName(normalizedReference);
		List<SemanticBlueprint.MetricSelection> candidates = semanticPlan.getMetrics()
			.stream()
			.filter(metric -> metricCode.equals(normalizeIdentifier(metric.getMetricCode())))
			.toList();
		String modelCode = "";
		String alias = "";
		if (!qualifier.isBlank()) {
			modelCode = bindings.aliasToModel().getOrDefault(qualifier, qualifier);
			alias = qualifier;
			String resolvedModel = modelCode;
			candidates = candidates.stream()
				.filter(metric -> resolvedModel.equals(normalizeIdentifier(metric.getModelCode())))
				.toList();
		}
		if (candidates.size() != 1) {
			throw new QueryPreflightException(candidates.isEmpty() ? "SEMANTIC_METRIC_NOT_FOUND" : "SEMANTIC_METRIC_AMBIGUOUS",
					"Metric reference '" + reference + "' resolved to " + candidates.size()
							+ " metrics in the pinned Semantic Blueprint");
		}
		SemanticBlueprint.MetricSelection metric = candidates.get(0);
		modelCode = normalizeIdentifier(metric.getModelCode());
		// A METRIC qualifier can be a logical model or a SQL alias. A model name
        // resolves only through a unique actual binding; explicit aliases keep precedence.
		if (alias.isBlank() || modelCode.equals(alias) && !bindings.aliasToModel().containsKey(alias)) {
			alias = uniqueAlias(modelCode, bindings);
		}
		String resolvedAliasModel = bindings.aliasToModel().get(alias);
		if (!modelCode.equals(resolvedAliasModel)) {
			throw new QueryPreflightException("SEMANTIC_METRIC_MODEL_MISMATCH",
					"Metric '" + metric.getMetricCode() + "' belongs to model " + metric.getModelCode()
							+ " but was referenced through alias " + alias);
		}
		String publishedModelCode = modelCode;
		boolean published = catalog.getMetrics()
			.stream()
			.filter(candidate -> candidate.getStatus() == SemanticAssetStatus.ENABLED)
			.anyMatch(candidate -> publishedModelCode.equals(normalizeIdentifier(candidate.getModelCode()))
					&& metricCode.equals(normalizeIdentifier(candidate.getMetricCode())));
		if (!published) {
			throw new QueryPreflightException("SEMANTIC_METRIC_NOT_PUBLISHED",
					"Pinned metric is no longer enabled in the frozen semantic catalog: " + reference);
		}
		return new ResolvedMetric(metric, alias);
	}

	private String renderMetricExpression(SemanticBlueprint.MetricSelection metric, String alias,
			SemanticCatalogSnapshot catalog, String dialect) {
		if (!hasText(metric.getExpression())) {
			throw new QueryPreflightException("SEMANTIC_METRIC_EXPRESSION_MISSING",
					"Published metric has no expression: " + metric.getMetricCode());
		}
		Matcher aggregate = SINGLE_AGGREGATE.matcher(metric.getExpression());
		String function = null;
		boolean distinct = false;
		String argument = metric.getExpression();
		if (aggregate.matches()) {
			function = aggregate.group(1).toUpperCase(Locale.ROOT);
			distinct = aggregate.group(2) != null;
			argument = aggregate.group(3).trim();
		}
		else {
			String aggregation = Objects.toString(metric.getAggregation(), "").trim().toUpperCase(Locale.ROOT);
			if ("COUNT_DISTINCT".equals(aggregation)) {
				function = "COUNT";
				distinct = true;
			}
			else if (Set.of("SUM", "COUNT", "AVG", "MIN", "MAX").contains(aggregation)) {
				function = aggregation;
			}
		}

		String renderedArgument = "*".equals(argument.trim()) ? "*"
				: renderPublishedExpression(argument, metric.getModelCode(), alias, catalog, dialect, ExpressionUse.AGGREGATION);
		if (function == null) {
			if (hasText(metric.getFilterExpression())) {
				throw new QueryPreflightException("SEMANTIC_METRIC_FILTER_WITHOUT_AGGREGATION",
						"Filtered metric requires an explicit published aggregation: " + metric.getMetricCode());
			}
			return renderedArgument;
		}
		String condition = hasText(metric.getFilterExpression())
				? renderPublishedExpression(metric.getFilterExpression(), metric.getModelCode(), alias, catalog, dialect,
						ExpressionUse.FILTER)
				: "";
		if (condition.isBlank()) {
			return function + "(" + (distinct ? "DISTINCT " : "") + renderedArgument + ")";
		}
		String conditionalArgument = "*".equals(renderedArgument) ? "1" : renderedArgument;
		String otherwise = "SUM".equals(function) ? " ELSE 0" : "";
		return function + "(" + (distinct ? "DISTINCT " : "") + "CASE WHEN " + condition + " THEN "
				+ conditionalArgument + otherwise + " END)";
	}

	private String rewriteRelationshipCalls(String sql, SemanticCatalogSnapshot catalog, SemanticBlueprint semanticPlan,
			ModelBindings bindings, String dialect) {
		Matcher matcher = RELATIONSHIP_CALL.matcher(sql);
		StringBuffer output = new StringBuffer();
		int replacements = 0;
		while (matcher.find()) {
			SemanticBlueprint.RelationshipSelection relationship = requirePinnedRelationship(matcher.group(1), catalog,
					semanticPlan);
			String sourceAlias = hasText(matcher.group(2)) ? normalizeIdentifier(matcher.group(2))
					: uniqueAlias(normalizeIdentifier(relationship.getSourceModelCode()), bindings);
			String targetAlias = hasText(matcher.group(3)) ? normalizeIdentifier(matcher.group(3))
					: uniqueAlias(normalizeIdentifier(relationship.getTargetModelCode()), bindings);
			assertAliasModel(sourceAlias, relationship.getSourceModelCode(), bindings);
			assertAliasModel(targetAlias, relationship.getTargetModelCode(), bindings);
			validateJoinType(sql, matcher.start(), relationship);
			String condition = renderRelationshipCondition(relationship, sourceAlias, targetAlias, catalog, dialect);
			matcher.appendReplacement(output, Matcher.quoteReplacement("(" + condition + ")"));
			replacements++;
		}
		matcher.appendTail(output);
		if (replacements == 0 && hasSemanticModelJoin(sql, bindings)) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_REQUIRED",
					"Direct joins between governed semantic models must use ON RELATIONSHIP('published_relationship_code')");
		}
		return output.toString();
	}

	private SemanticBlueprint.RelationshipSelection requirePinnedRelationship(String relationshipCode,
			SemanticCatalogSnapshot catalog, SemanticBlueprint semanticPlan) {
		String normalizedCode = normalizeIdentifier(relationshipCode);
		if (semanticPlan == null || semanticPlan.getRelationships() == null) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_OUTSIDE_PLAN",
					"No relationship is selected by the pinned Semantic Blueprint");
		}
		List<SemanticBlueprint.RelationshipSelection> matches = semanticPlan.getRelationships()
			.stream()
			.filter(relationship -> normalizedCode.equals(normalizeIdentifier(relationship.getRelationshipCode())))
			.toList();
		if (matches.size() != 1) {
			throw new QueryPreflightException(matches.isEmpty() ? "SEMANTIC_RELATIONSHIP_NOT_FOUND"
					: "SEMANTIC_RELATIONSHIP_AMBIGUOUS", "Relationship reference '" + relationshipCode + "' resolved to "
							+ matches.size() + " relationships in the pinned Semantic Blueprint");
		}
		SemanticBlueprint.RelationshipSelection relationship = matches.get(0);
		boolean published = catalog.getRelationships()
			.stream()
			.filter(candidate -> candidate.getStatus() == SemanticAssetStatus.ENABLED)
			.anyMatch(candidate -> normalizedCode.equals(normalizeIdentifier(candidate.getRelationshipCode()))
					&& normalizeIdentifier(relationship.getSourceModelCode()).equals(normalizeIdentifier(candidate.getSourceModelCode()))
					&& normalizeIdentifier(relationship.getTargetModelCode()).equals(normalizeIdentifier(candidate.getTargetModelCode())));
		if (!published) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_NOT_PUBLISHED",
					"Pinned relationship is no longer enabled in the frozen semantic catalog: " + relationshipCode);
		}
		return relationship;
	}

	private String renderRelationshipCondition(SemanticBlueprint.RelationshipSelection relationship, String sourceAlias,
			String targetAlias, SemanticCatalogSnapshot catalog, String dialect) {
		String condition = Objects.toString(relationship.getJoinCondition(), "").trim();
		if (condition.isBlank()) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_CONDITION_MISSING",
					"Published relationship has no join condition: " + relationship.getRelationshipCode());
		}
		Matcher matcher = QUALIFIED_IDENTIFIER.matcher(condition);
		StringBuffer output = new StringBuffer();
		int replacements = 0;
		while (matcher.find()) {
			String model = normalizeIdentifier(matcher.group(1));
			String column = matcher.group(2);
			String alias;
			if (model.equals(normalizeIdentifier(relationship.getSourceModelCode()))) {
				alias = sourceAlias;
			}
			else if (model.equals(normalizeIdentifier(relationship.getTargetModelCode()))) {
				alias = targetAlias;
			}
			else {
				throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_MODEL_MISMATCH",
						"Published relationship condition references an unexpected model: " + matcher.group(1));
			}
			SemanticCatalogSnapshot.Column governedColumn = requireGovernedColumn(model, column, catalog, ExpressionUse.FILTER);
			matcher.appendReplacement(output,
					Matcher.quoteReplacement(alias + "." + quoteIdentifier(semanticFieldName(governedColumn), dialect)));
			replacements++;
		}
		matcher.appendTail(output);
		if (replacements == 0) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_CONDITION_UNSUPPORTED",
					"Relationship join condition must use governed model.column references: " + condition);
		}
		return output.toString();
	}

	private String renderPublishedExpression(String expression, String modelCode, String alias,
			SemanticCatalogSnapshot catalog, String dialect, ExpressionUse use) {
		String rendered = expression;
		Matcher qualified = QUALIFIED_IDENTIFIER.matcher(rendered);
		StringBuffer qualifiedOutput = new StringBuffer();
		while (qualified.find()) {
			if (insideSingleQuotedLiteral(rendered, qualified.start())) {
				qualified.appendReplacement(qualifiedOutput, Matcher.quoteReplacement(qualified.group()));
				continue;
			}
			String qualifier = normalizeIdentifier(qualified.group(1));
			if (!qualifier.equals(normalizeIdentifier(modelCode))) {
				throw new QueryPreflightException("SEMANTIC_EXPRESSION_CROSS_MODEL",
						"Published expression for model " + modelCode + " references model " + qualified.group(1));
			}
			SemanticCatalogSnapshot.Column column = requireGovernedColumn(modelCode, qualified.group(2), catalog, use);
			qualified.appendReplacement(qualifiedOutput,
					Matcher.quoteReplacement(alias + "." + quoteIdentifier(semanticFieldName(column), dialect)));
		}
		qualified.appendTail(qualifiedOutput);
		rendered = qualifiedOutput.toString();

		Matcher identifiers = IDENTIFIER.matcher(rendered);
		StringBuffer output = new StringBuffer();
		while (identifiers.find()) {
			String token = identifiers.group();
			String normalized = normalizeIdentifier(token);
			if (insideSingleQuotedLiteral(rendered, identifiers.start()) || SQL_WORDS.contains(normalized)
					|| nextNonWhitespaceIsParen(rendered, identifiers.end()) || nextNonWhitespaceIsDot(rendered, identifiers.end())
					|| isAlreadyQualified(rendered, identifiers.start())) {
				identifiers.appendReplacement(output, Matcher.quoteReplacement(token));
				continue;
			}
			SemanticCatalogSnapshot.Column column = findGovernedColumn(modelCode, token, catalog);
			if (column == null) {
				throw new QueryPreflightException("SEMANTIC_EXPRESSION_COLUMN_NOT_FOUND",
						"Published expression references unknown governed column " + modelCode + "." + token);
			}
			assertColumnUse(column, use);
			identifiers.appendReplacement(output,
					Matcher.quoteReplacement(alias + "." + quoteIdentifier(semanticFieldName(column), dialect)));
		}
		identifiers.appendTail(output);
		return output.toString();
	}

	private void validateDirectModelColumns(SQLStatement statement, String dbType, Set<String> semanticModelCodes,
			SemanticCatalogSnapshot catalog, ModelBindings bindings, Set<String> cteNames) {
		if (semanticModelCodes.isEmpty()) {
			return;
		}
		// SchemaStatVisitor reports COUNT(*) as a column named '*'. It counts rows,
		// whereas a projection wildcard would expose fields outside the governed plan.
		statement.accept(new SQLASTVisitorAdapter() {
			@Override
			public boolean visit(SQLAllColumnExpr all) {
				assertCountWildcard(all.getParent());
				return false;
			}

			@Override
			public boolean visit(SQLPropertyExpr property) {
				if ("*".equals(property.getName())) {
					assertCountWildcard(property.getParent());
				}
				return true;
			}
		});
		Map<String, Set<String>> fieldsByModel = new LinkedHashMap<>();
		for (String modelCode : semanticModelCodes) {
			String normalizedModel = normalizeIdentifier(modelCode);
			Set<String> fields = new LinkedHashSet<>();
			catalog.getColumns()
				.stream()
				.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(column -> normalizedModel.equals(normalizeIdentifier(column.getModelCode())))
				.filter(column -> !Boolean.FALSE.equals(column.getAllowProjection()) || !Boolean.FALSE.equals(column.getAllowFilter())
						|| !Boolean.FALSE.equals(column.getAllowAggregation()))
				.map(this::semanticFieldName)
				.filter(this::hasText)
				.map(this::normalizeIdentifier)
				.forEach(fields::add);
			catalog.getDimensions()
				.stream()
				.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(dimension -> normalizedModel.equals(normalizeIdentifier(dimension.getModelCode())))
				.map(SemanticCatalogSnapshot.Dimension::getDimensionCode)
				.filter(this::hasText)
				.map(this::normalizeIdentifier)
				.forEach(fields::add);
			fieldsByModel.put(normalizedModel, fields);
		}

		SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(DbType.of(dbType));
		statement.accept(visitor);
		for (var column : visitor.getColumns()) {
			String table = normalizeIdentifier(column.getTable());
			String model = bindings.aliasToModel().getOrDefault(table, table);
			String field = normalizeIdentifier(column.getName());
			if (field.isBlank() || "*".equals(field)) {
				continue;
			}
			Set<String> availableFields = fieldsByModel.get(model);
			if (availableFields != null) {
				if (!availableFields.contains(field)) {
					throw new QueryPreflightException("SEMANTIC_COLUMN_NOT_FOUND",
							"Semantic model '" + model + "' has no governed field '" + column.getName()
									+ "'. Available fields: " + String.join(", ", availableFields));
				}
				continue;
			}
			if (!table.isBlank() || !cteNames.isEmpty()) {
				continue;
			}
			List<String> candidates = fieldsByModel.entrySet()
				.stream()
				.filter(entry -> entry.getValue().contains(field))
				.map(Map.Entry::getKey)
				.toList();
			if (candidates.size() > 1) {
				throw new QueryPreflightException("SEMANTIC_COLUMN_AMBIGUOUS",
						"Unqualified semantic field '" + column.getName() + "' exists in models " + candidates
								+ "; qualify it with the model alias");
			}
			if (candidates.isEmpty()) {
				throw new QueryPreflightException("SEMANTIC_COLUMN_NOT_FOUND",
						"Unqualified field is not present in the governed models: " + column.getName());
			}
		}
	}

	private void assertCountWildcard(com.alibaba.druid.sql.ast.SQLObject parent) {
		if (!(parent instanceof SQLAggregateExpr aggregate) || !"COUNT".equalsIgnoreCase(aggregate.getMethodName())) {
			throw new QueryPreflightException("SEMANTIC_PROJECTION_WILDCARD_FORBIDDEN",
					"Select explicit governed fields; only COUNT may use a wildcard");
		}
	}

	private List<String> nullableColumnWarnings(SQLStatement statement, String dbType, Set<String> semanticModelCodes,
			SemanticCatalogSnapshot catalog, ModelBindings bindings, Set<String> cteNames) {
		if (semanticModelCodes.isEmpty()) {
			return List.of();
		}
		Map<String, Map<String, SemanticCatalogSnapshot.Column>> columnsByModel = new LinkedHashMap<>();
		for (String modelCode : semanticModelCodes) {
			String normalizedModel = normalizeIdentifier(modelCode);
			Map<String, SemanticCatalogSnapshot.Column> fields = new LinkedHashMap<>();
			catalog.getColumns()
				.stream()
				.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
				.filter(column -> normalizedModel.equals(normalizeIdentifier(column.getModelCode())))
				.filter(column -> Boolean.TRUE.equals(column.getNullable()))
				.forEach(column -> fields.put(normalizeIdentifier(semanticFieldName(column)), column));
			columnsByModel.put(normalizedModel, fields);
		}

		Set<String> warnings = new LinkedHashSet<>();
		SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(DbType.of(dbType));
		statement.accept(visitor);
		for (var columnRef : visitor.getColumns()) {
			String table = normalizeIdentifier(columnRef.getTable());
			String field = normalizeIdentifier(columnRef.getName());
			if (field.isBlank()) {
				continue;
			}
			String model = bindings.aliasToModel().getOrDefault(table, table);
			SemanticCatalogSnapshot.Column nullableColumn = columnsByModel.getOrDefault(model, Map.of()).get(field);
			if (nullableColumn == null && table.isBlank() && cteNames.isEmpty()) {
				List<SemanticCatalogSnapshot.Column> candidates = columnsByModel.values()
					.stream()
					.map(fields -> fields.get(field))
					.filter(Objects::nonNull)
					.toList();
				if (candidates.size() == 1) {
					nullableColumn = candidates.get(0);
				}
			}
			if (nullableColumn == null) {
				continue;
			}
			warnings.add("NULLABLE_COLUMN_REFERENCED model=" + nullableColumn.getModelCode() + " column="
					+ nullableColumn.getColumnName() + " role=" + Objects.toString(nullableColumn.getRole(), "UNKNOWN"));
		}
		return List.copyOf(warnings);
	}

	private boolean hasSemanticModelJoin(String sql, ModelBindings bindings) {
		String normalized = sql.toLowerCase(Locale.ROOT).replace('`', ' ').replace('"', ' ').replace('[', ' ').replace(']', ' ');
		return bindings.modelToAliases()
			.keySet()
			.stream()
			.anyMatch(model -> normalized.matches("(?s).*\\bjoin\\s+" + Pattern.quote(model) + "\\b.*"));
	}

	private String uniqueAlias(String modelCode, ModelBindings bindings) {
		List<String> aliases = bindings.modelToAliases().getOrDefault(normalizeIdentifier(modelCode), List.of());
		if (aliases.size() != 1) {
			throw new QueryPreflightException("SEMANTIC_MODEL_ALIAS_AMBIGUOUS",
					"Model " + modelCode + " has " + aliases.size()
							+ " SQL bindings; use METRIC('alias.metric') or RELATIONSHIP('code','sourceAlias','targetAlias')");
		}
		return aliases.get(0);
	}

	private void assertAliasModel(String alias, String expectedModelCode, ModelBindings bindings) {
		String actual = bindings.aliasToModel().get(normalizeIdentifier(alias));
		if (!normalizeIdentifier(expectedModelCode).equals(actual)) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_ALIAS_MISMATCH",
					"Alias " + alias + " does not refer to relationship model " + expectedModelCode);
		}
	}

	private void validateJoinType(String sql, int relationshipCallStart,
			SemanticBlueprint.RelationshipSelection relationship) {
		String prefix = sql.substring(Math.max(0, relationshipCallStart - 160), relationshipCallStart).toLowerCase(Locale.ROOT);
		int joinIndex = prefix.lastIndexOf("join");
		if (joinIndex < 0) {
			throw new QueryPreflightException("SEMANTIC_RELATIONSHIP_NOT_IN_JOIN",
					"RELATIONSHIP(...) must be used in a JOIN ON clause");
		}
		String joinPrefix = prefix.substring(Math.max(0, joinIndex - 24), joinIndex).trim();
		String actual = joinPrefix.endsWith("left") || joinPrefix.endsWith("left outer") ? "LEFT"
				: joinPrefix.endsWith("right") || joinPrefix.endsWith("right outer") ? "RIGHT"
						: joinPrefix.endsWith("full") || joinPrefix.endsWith("full outer") ? "FULL"
								: joinPrefix.endsWith("cross") ? "CROSS" : "INNER";
		String expected = Objects.toString(relationship.getJoinType(), "INNER").trim().toUpperCase(Locale.ROOT)
			.replace(" OUTER", "");
		if (!expected.isBlank() && !expected.equals(actual)) {
			throw new QueryPreflightException("SEMANTIC_JOIN_TYPE_MISMATCH",
					"Relationship " + relationship.getRelationshipCode() + " requires " + expected + " JOIN but Semantic SQL uses "
							+ actual + " JOIN");
		}
	}

	private SemanticCatalogSnapshot.Column requireGovernedColumn(String modelCode, String columnName,
			SemanticCatalogSnapshot catalog, ExpressionUse use) {
		SemanticCatalogSnapshot.Column column = findGovernedColumn(modelCode, columnName, catalog);
		if (column == null) {
			throw new QueryPreflightException("SEMANTIC_EXPRESSION_COLUMN_NOT_FOUND",
					"Governed column does not exist: " + modelCode + "." + columnName);
		}
		assertColumnUse(column, use);
		return column;
	}

	private SemanticCatalogSnapshot.Column findGovernedColumn(String modelCode, String columnName,
			SemanticCatalogSnapshot catalog) {
		String normalizedModel = normalizeIdentifier(modelCode);
		String normalizedColumn = normalizeIdentifier(columnName);
		return catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> normalizedModel.equals(normalizeIdentifier(column.getModelCode())))
			.filter(column -> normalizedColumn.equals(normalizeIdentifier(column.getColumnName())))
			.findFirst()
			.orElse(null);
	}

	private String semanticFieldName(SemanticCatalogSnapshot.Column column) {
		return Boolean.FALSE.equals(column.getAllowProjection()) ? "__qw_internal_" + column.getColumnName()
				: column.getColumnName();
	}

	private void assertColumnUse(SemanticCatalogSnapshot.Column column, ExpressionUse use) {
		if (use == ExpressionUse.AGGREGATION && Boolean.FALSE.equals(column.getAllowAggregation())) {
			throw new QueryPreflightException("SEMANTIC_COLUMN_AGGREGATION_DENIED",
					"Column governance denies aggregation: " + column.getModelCode() + "." + column.getColumnName());
		}
		if (use == ExpressionUse.FILTER && Boolean.FALSE.equals(column.getAllowFilter())) {
			throw new QueryPreflightException("SEMANTIC_COLUMN_FILTER_DENIED",
					"Column governance denies filtering/join use: " + column.getModelCode() + "." + column.getColumnName());
		}
	}

	private boolean insideSingleQuotedLiteral(String value, int position) {
		boolean quoted = false;
		for (int index = 0; index < Math.min(position, value.length()); index++) {
			if (value.charAt(index) == '\'') {
				if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
					index++;
					continue;
				}
				quoted = !quoted;
			}
		}
		return quoted;
	}

	private boolean nextNonWhitespaceIsParen(String value, int end) {
		int index = end;
		while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
			index++;
		}
		return index < value.length() && value.charAt(index) == '(';
	}

	private boolean nextNonWhitespaceIsDot(String value, int end) {
		int index = end;
		while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
			index++;
		}
		return index < value.length() && value.charAt(index) == '.';
	}

	private boolean isAlreadyQualified(String value, int start) {
		int index = start - 1;
		while (index >= 0 && Character.isWhitespace(value.charAt(index))) {
			index--;
		}
		return index >= 0 && (value.charAt(index) == '.' || value.charAt(index) == '`' || value.charAt(index) == '"');
	}

	private void validateAgainstPinnedPlan(Set<String> semanticModelCodes, Set<String> passthroughPhysicalTables,
			SemanticBlueprint semanticPlan, Map<String, SemanticCatalogSnapshot.Model> modelsByCode) {
		if (semanticPlan == null || semanticPlan.getModels() == null || semanticPlan.getModels().isEmpty()) {
			return;
		}
		Set<String> allowedModelCodes = semanticPlan.getModels()
			.stream()
			.map(SemanticBlueprint.ModelSelection::getModelCode)
			.filter(Objects::nonNull)
			.map(this::normalizeIdentifier)
			.collect(Collectors.toSet());
		Set<String> allowedPhysicalTables = semanticPlan.getModels()
			.stream()
			.map(SemanticBlueprint.ModelSelection::getPhysicalTable)
			.filter(Objects::nonNull)
			.map(this::normalizeQualifiedIdentifier)
			.collect(Collectors.toSet());
		List<String> outsidePlan = semanticModelCodes.stream()
			.filter(code -> !allowedModelCodes.contains(normalizeIdentifier(code)))
			.toList();
		if (!outsidePlan.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_MODEL_OUTSIDE_PLAN",
					"Semantic SQL references models not selected by the pinned Semantic Blueprint: "
							+ String.join(", ", outsidePlan));
		}
		List<String> physicalOutsidePlan = passthroughPhysicalTables.stream()
			.filter(table -> !allowedPhysicalTables.contains(normalizeQualifiedIdentifier(table)))
			.toList();
		if (!physicalOutsidePlan.isEmpty()) {
			throw new QueryPreflightException("PHYSICAL_TABLE_OUTSIDE_PLAN",
					"Physical SQL passthrough references tables not selected by the pinned Semantic Blueprint: "
							+ String.join(", ", physicalOutsidePlan));
		}
		for (String modelCode : semanticModelCodes) {
			if (!modelsByCode.containsKey(normalizeIdentifier(modelCode))) {
				throw new QueryPreflightException("SEMANTIC_MODEL_NOT_FOUND", "Unknown semantic model: " + modelCode);
			}
		}
	}

	private String rewriteModelSources(String sql, Map<String, SemanticCatalogSnapshot.Model> modelsByCode, String dialect) {
		Matcher matcher = MODEL_BINDING.matcher(sql);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String tableToken = matcher.group(1);
			String normalizedTable = normalizeIdentifier(tableToken);
			SemanticCatalogSnapshot.Model model = modelsByCode.get(normalizedTable);
			if (model == null) {
				matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			String rawMatch = matcher.group();
			int relativeTableStart = matcher.start(1) - matcher.start();
			String clausePrefix = rawMatch.substring(0, relativeTableStart);
			String aliasToken = matcher.group(2);
			String normalizedAlias = normalizeIdentifier(aliasToken);
			String internalSource = quoteIdentifier(internalModelCteName(model.getModelCode()), dialect);
			String replacement;
			if (!hasText(aliasToken) || SQL_CLAUSE_WORDS.contains(normalizedAlias)) {
				String semanticAlias = quoteIdentifier(model.getModelCode(), dialect);
				replacement = clausePrefix + internalSource + " AS " + semanticAlias
						+ (hasText(aliasToken) ? " " + aliasToken : "");
			}
			else {
				replacement = clausePrefix + internalSource + " AS " + aliasToken;
			}
			matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private String internalModelCteName(String modelCode) {
		return "__qw_model_" + normalizeIdentifier(modelCode);
	}

	private String renderModelCte(SemanticCatalogSnapshot.Model model, SemanticCatalogSnapshot catalog, String dialect) {
		LinkedHashMap<String, String> projections = new LinkedHashMap<>();
		catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> model.getModelCode().equals(column.getModelCode()))
			.filter(column -> !Boolean.FALSE.equals(column.getAllowProjection()) || !Boolean.FALSE.equals(column.getAllowFilter())
					|| !Boolean.FALSE.equals(column.getAllowAggregation()))
			.forEach(column -> projections.putIfAbsent(semanticFieldName(column), hasText(column.getExpression())
					? column.getExpression() : quoteIdentifier(column.getColumnName(), dialect)));
		catalog.getDimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> model.getModelCode().equals(dimension.getModelCode()))
			.filter(dimension -> hasText(dimension.getDimensionCode()))
			.forEach(dimension -> projections.putIfAbsent(dimension.getDimensionCode(), hasText(dimension.getExpression())
					? dimension.getExpression() : quoteIdentifier(dimension.getColumnName(), dialect)));
		if (projections.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_MODEL_HAS_NO_COLUMNS",
					"Semantic model has no projectable governed columns: " + model.getModelCode());
		}
		String selectList = projections.entrySet()
			.stream()
			.map(entry -> entry.getValue() + " AS " + quoteIdentifier(entry.getKey(), dialect))
			.collect(Collectors.joining(",\n        "));
		return quoteIdentifier(internalModelCteName(model.getModelCode()), dialect) + " AS (\n    SELECT\n        " + selectList
				+ "\n    FROM " + GovernedModelSourceRenderer.relation(model, SqlDialect.from(dialect)) + " AS __qw_row\n)";
	}

	private String injectModelCtes(String semanticSql, List<String> cteDefinitions) {
		String definitions = String.join(",\n", cteDefinitions);
		Matcher recursive = WITH_RECURSIVE_PREFIX.matcher(semanticSql);
		if (recursive.find()) {
			return semanticSql.substring(0, recursive.end()) + definitions + ",\n" + semanticSql.substring(recursive.end());
		}
		Matcher with = WITH_PREFIX.matcher(semanticSql);
		if (with.find()) {
			return semanticSql.substring(0, with.end()) + definitions + ",\n" + semanticSql.substring(with.end());
		}
		return "WITH " + definitions + "\n" + semanticSql.trim();
	}

	private void validateConfirmedOutputAliases(SQLSelectStatement statement, SemanticBlueprint plan) {
		if (plan == null || plan.getResultContract() == null || plan.getResultContract().outputMeasures().isEmpty()) {
			return;
		}
		var query = statement.getSelect().getQuery();
		// SQL set operations expose the first branch's output names; CTEs do not change the final SELECT scope.
		while (query instanceof com.alibaba.druid.sql.ast.statement.SQLUnionQuery union) {
			query = union.getLeft();
		}
		Set<String> outputs = new LinkedHashSet<>();
		if (query instanceof com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock block) {
			for (var item : block.getSelectList()) {
				String name = item.getAlias();
				if (!hasText(name) && item.getExpr() instanceof com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr identifier) {
					name = identifier.getName();
				}
				if (!hasText(name) && item.getExpr() instanceof SQLPropertyExpr property) {
					name = property.getName();
				}
				if (hasText(name)) {
					outputs.add(stripQuotes(name));
				}
			}
		}
		var missing = plan.getResultContract().outputMeasures().stream()
			.map(cn.lgs.semevosql.semantic.domain.SemanticResultContract.OutputMeasure::outputCode)
			.filter(code -> !outputs.contains(code)).toList();
		if (!missing.isEmpty()) {
			throw new QueryPreflightException("SEMANTIC_RESULT_OUTPUT_MISSING",
					"Final SELECT must use these exact frozen confirmed-output aliases, including revision suffixes: "
							+ String.join(", ", missing) + ". Step instructions or failed SQL cannot rename them.");
		}
	}

	private Set<String> extractReferencedTables(SQLStatement statement, String dbType) {
		SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(DbType.of(dbType));
		statement.accept(visitor);
		Set<String> tables = new LinkedHashSet<>();
		for (TableStat.Name name : visitor.getTables().keySet()) {
			String normalized = normalizeQualifiedIdentifier(name.getName());
			if (!normalized.isBlank()) {
				tables.add(normalized);
			}
		}
		return tables;
	}

	private Set<String> extractCteNames(String sql) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = CTE_PATTERN.matcher(sql);
		while (matcher.find()) {
			names.add(normalizeIdentifier(matcher.group(1)));
		}
		return names;
	}

	private String resolveDbType(String dialect) {
		String normalized = dialect == null ? "" : dialect.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mysql" -> "mysql";
			case "postgresql", "postgres", "hologress" -> "postgresql";
			case "sqlserver", "sql_server", "mssql" -> "sqlserver";
			case "oracle", "dameng" -> "oracle";
			case "hive" -> "hive";
			case "sqlite" -> "sqlite";
			case "h2" -> "h2";
			default -> throw new QueryPreflightException("UNSUPPORTED_DIALECT",
					"Unsupported SQL dialect for Query Preflight: " + dialect);
		};
	}

	private String quoteQualifiedIdentifier(String value, String dialect) {
		if (!hasText(value)) {
			return value;
		}
		return java.util.Arrays.stream(value.split("\\."))
			.map(part -> quoteIdentifier(stripQuotes(part), dialect))
			.collect(Collectors.joining("."));
	}

	private String quoteIdentifier(String value, String dialect) {
		String identifier = stripQuotes(value);
		String normalizedDialect = dialect == null ? "" : dialect.trim().toLowerCase(Locale.ROOT);
		return switch (normalizedDialect) {
			case "mysql" -> "`" + identifier.replace("`", "``") + "`";
			case "sqlserver", "sql_server", "mssql" -> "[" + identifier.replace("]", "]]" ) + "]";
			case "hive" -> identifier;
			default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
		};
	}

	private String normalizeQualifiedIdentifier(String value) {
		if (value == null) {
			return "";
		}
		return java.util.Arrays.stream(value.trim().split("\\."))
			.map(this::normalizeIdentifier)
			.filter(part -> !part.isBlank())
			.collect(Collectors.joining("."));
	}

	private String normalizeIdentifier(String value) {
		return stripQuotes(value).toLowerCase(Locale.ROOT);
	}

	private String stripQuotes(String value) {
		if (value == null) {
			return "";
		}
		String result = value.trim();
		while (result.length() >= 2 && ((result.startsWith("`") && result.endsWith("`"))
				|| (result.startsWith("\"") && result.endsWith("\""))
				|| (result.startsWith("[") && result.endsWith("]")))) {
			result = result.substring(1, result.length() - 1).trim();
		}
		return result;
	}

	private String unqualifiedName(String value) {
		int dot = value.lastIndexOf('.');
		return dot < 0 ? value : value.substring(dot + 1);
	}

	private String qualifier(String value) {
		int dot = value.lastIndexOf('.');
		return dot < 0 ? "" : value.substring(0, dot);
	}

	private enum ExpressionUse {
		AGGREGATION,
		FILTER
	}

	private record ModelBindings(Map<String, String> aliasToModel, Map<String, List<String>> modelToAliases) {
	}

	private record ResolvedMetric(SemanticBlueprint.MetricSelection metric, String alias) {
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record PreflightResult(String physicalSql, Set<String> semanticModelCodes, Set<String> physicalTables,
			boolean legacyPhysicalPassthrough, List<String> warnings) {
	}

}
