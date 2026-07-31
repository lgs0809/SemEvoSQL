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

import cn.lgs.semevosql.semantic.compiler.CompiledSemanticQuery.CompiledSourceQuery;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Deterministic compiler for governed common analytics. */
@Service
public class SemanticSqlCompiler {

	public static final String COMPILER_VERSION = "semantic-sql-compiler-v2";

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

	private static final Pattern QUALIFIED_IDENTIFIER = Pattern
		.compile("([A-Za-z_][A-Za-z0-9_$]*)\\.([A-Za-z_][A-Za-z0-9_$]*)");

	private static final Pattern AGGREGATE_CALL = Pattern
		.compile("(?is)^\\s*(sum|count|avg|min|max)\\s*\\(\\s*(distinct\\s+)?(.+)\\)\\s*$");

	private static final Set<String> SQL_WORDS = Set.of("sum", "count", "avg", "min", "max", "distinct", "case", "when",
			"then", "else", "end", "coalesce", "nullif", "cast", "as", "date", "datetime", "decimal", "int", "integer",
			"bigint", "double", "float", "round", "floor", "ceil", "abs", "lower", "upper", "trim", "substring",
			"concat", "and", "or", "not", "in", "between", "like", "is", "null", "true", "false");

	public CompiledSemanticQuery compile(SemanticBlueprint plan, SemanticCatalogSnapshot catalog,
			Map<Integer, SqlDialect> dialects, Clock clock, ZoneId defaultZone) {
		Objects.requireNonNull(plan, "semantic blueprint is required");
		Objects.requireNonNull(catalog, "semantic catalog is required");
		if (!plan.isExecutable()) {
			throw new IllegalArgumentException(
					"Semantic Blueprint is not executable: " + String.join("; ", plan.getValidationErrors()));
		}
		assertDeterministicCapability(plan, catalog, dialects);
		CompileContext context = context(plan, catalog);
		List<SemanticBlueprint.SourceSubPlan> sources = sourcePlans(plan, context);
		if (sources.isEmpty()) {
			throw new IllegalArgumentException("Semantic Blueprint has no source plan");
		}
		List<CompiledSourceQuery> compiled = new ArrayList<>();
		for (SemanticBlueprint.SourceSubPlan source : sources) {
			SqlDialect dialect = resolveDialect(plan, source, dialects);
			compiled.add(compileSource(plan, source, dialect, context, clock == null ? Clock.systemUTC() : clock,
					defaultZone == null ? ZoneId.systemDefault() : defaultZone));
		}
		return new CompiledSemanticQuery(List.copyOf(compiled), plan.getMergePlan(), plan.getExpectedResult(),
				COMPILER_VERSION);
	}

	public CompiledSemanticQuery compile(SemanticBlueprint plan, SemanticCatalogSnapshot catalog, SqlDialect dialect) {
		Map<Integer, SqlDialect> dialects = plan.getSourceSubPlans()
			.stream()
			.filter(source -> source.getDatasourceId() != null)
			.collect(Collectors.toMap(SemanticBlueprint.SourceSubPlan::getDatasourceId, source -> dialect,
					(left, right) -> left));
		return compile(plan, catalog, dialects, Clock.systemUTC(), ZoneId.of("UTC"));
	}

	public CompiledSourceQuery compileForDatasource(SemanticBlueprint plan, SemanticCatalogSnapshot catalog,
			Integer datasourceId, SqlDialect dialect, Clock clock, ZoneId defaultZone) {
		Objects.requireNonNull(datasourceId, "datasourceId is required");
		if (!plan.isExecutable()) {
			throw new IllegalArgumentException(
					"Semantic Blueprint is not executable: " + String.join("; ", plan.getValidationErrors()));
		}
		assertDeterministicCapability(plan, catalog, Map.of(datasourceId, dialect));
		CompileContext context = context(plan, catalog);
		SemanticBlueprint.SourceSubPlan source = sourcePlans(plan, context).stream()
			.filter(candidate -> Objects.equals(candidate.getDatasourceId(), datasourceId))
			.findFirst()
			.orElseThrow(
					() -> new IllegalArgumentException("Semantic Blueprint has no source plan for datasource " + datasourceId));
		return compileSource(plan, source, dialect, context, clock == null ? Clock.systemUTC() : clock,
				defaultZone == null ? ZoneId.systemDefault() : defaultZone);
	}

	private void assertDeterministicCapability(SemanticBlueprint plan, SemanticCatalogSnapshot catalog,
			Map<Integer, SqlDialect> dialects) {
		LoweringCapabilityProbe.Decision decision = LoweringCapabilityProbe.probe(plan, catalog, dialects);
		if (decision.status() == LoweringCapabilityProbe.Status.INVALID) {
			throw new IllegalArgumentException(decision.reason());
		}
		if (decision.status() == LoweringCapabilityProbe.Status.REQUIRES_GENERATION) {
			throw new ConstrainedGenerationRequiredException(decision.reason());
		}
	}

	private CompiledSourceQuery compileSource(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source,
			SqlDialect dialect, CompileContext context, Clock clock, ZoneId defaultZone) {
		Set<String> sourceModels = new LinkedHashSet<>(source.getModelCodes());
		if (sourceModels.isEmpty()) {
			sourceModels.addAll(context.modelsByCode()
				.values()
				.stream()
				.filter(model -> Objects.equals(model.getDatasourceId(), source.getDatasourceId()))
				.map(SemanticCatalogSnapshot.Model::getModelCode)
				.toList());
		}
		List<SemanticCatalogSnapshot.Model> models = sourceModels.stream()
			.map(context.modelsByCode()::get)
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Model::getModelCode))
			.toList();
		if (models.isEmpty()) {
			throw new IllegalArgumentException(
					"Source plan contains no published semantic model: " + source.getDatasourceId());
		}
		Map<String, String> aliases = aliases(models);
		List<SemanticBlueprint.ProjectionSelection> projections = plan.getProjections()
			.stream()
			.filter(projection -> sourceModels.contains(projection.getModelCode()))
			.toList();
		if (projections.isEmpty()) {
			throw new ConstrainedGenerationRequiredException(
					"Source " + source.getDatasourceId() + " has no governed deterministic projection");
		}
		List<SemanticBlueprint.MetricSelection> sourceMetrics = plan.getMetrics()
			.stream()
			.filter(metric -> sourceModels.contains(metric.getModelCode()))
			.toList();
		boolean conditionalMetricFilters = requiresConditionalMetricFilters(sourceMetrics);
		List<Object> parameters = new ArrayList<>();
		List<String> select = new ArrayList<>(projections.stream()
			.map(projection -> compileProjection(projection, dialect, aliases, context, sourceMetrics,
					conditionalMetricFilters, parameters))
			.toList());
		InternalMergeKey internalMergeKey = internalMergeKey(plan, sourceModels, context);
		if (internalMergeKey != null) {
			select.add(compileInternalMergeKey(internalMergeKey, dialect, aliases, context));
		}
		SemanticCatalogSnapshot.Model base = models.get(0);
		StringBuilder fromAndJoins = new StringBuilder(qualifiedTable(base.getPhysicalTable(), dialect)).append(' ')
			.append(aliases.get(base.getModelCode()));
		Set<String> joined = new LinkedHashSet<>();
		joined.add(base.getModelCode());
		appendJoins(fromAndJoins, models, joined, sourceModels, dialect, aliases, context, plan.getRelationships());

		List<String> conditions = new ArrayList<>();
		for (SemanticBlueprint.FilterSelection filter : plan.getFilters()) {
			if (sourceModels.contains(filter.getModelCode())) {
				conditions.add(compileFilter(filter, dialect, aliases, context, parameters));
			}
		}
		if (plan.getTimeRange() != null && sourceModels.contains(plan.getTimeRange().getModelCode())) {
			conditions.addAll(
					compileTimeRange(plan.getTimeRange(), dialect, aliases, context, parameters, clock, defaultZone));
		}
		Set<String> appliedMetricFilters = new LinkedHashSet<>();
		for (SemanticBlueprint.MetricSelection metric : sourceMetrics) {
			String filterKey = metric.getModelCode() + "\n" + metric.getFilterExpression();
			if (!conditionalMetricFilters && StringUtils.hasText(metric.getFilterExpression())
					&& appliedMetricFilters.add(filterKey)) {
				conditions.add(renderPublishedBooleanExpression(metric.getFilterExpression(), metric.getModelCode(),
						dialect, aliases, context, parameters));
			}
		}
		List<String> groupBy = new ArrayList<>(plan.getGroupBy()
			.stream()
			.filter(group -> sourceModels.contains(group.getModelCode()))
			.map(group -> compileGroup(group, dialect, aliases, context))
			.distinct()
			.toList());
		if (internalMergeKey != null && !sourceMetrics.isEmpty()) {
			String expression = compileInternalMergeKeyExpression(internalMergeKey, dialect, aliases, context);
			if (!groupBy.contains(expression)) {
				groupBy.add(expression);
			}
		}
		List<String> projectionAliases = projections.stream()
			.map(SemanticBlueprint.ProjectionSelection::getAlias)
			.filter(StringUtils::hasText)
			.toList();
		List<String> orderBy = plan.getOrderBy()
			.stream()
			.map(order -> compileOrder(order, dialect, projectionAliases))
			.toList();
		int limit = Math.max(1, Math.min(plan.getLimit() == null ? 100 : plan.getLimit(), 10000));
		if (plan.getExpectedResult() != null && plan.getExpectedResult().getMaxRows() != null) {
			limit = Math.min(limit, Math.max(1, plan.getExpectedResult().getMaxRows()));
		}
		if (internalMergeKey != null && plan.getMergePlan() != null && plan.getMergePlan().getMaxRows() != null) {
			limit = Math.max(limit, Math.min(Math.max(1, plan.getMergePlan().getMaxRows()), 10000));
		}
		SqlSelectAst ast = new SqlSelectAst(select, fromAndJoins.toString(), conditions, groupBy, orderBy, limit);
		List<String> physicalTables = models.stream().map(SemanticCatalogSnapshot.Model::getPhysicalTable).toList();
		String resultShapeHash = hash(Map.of("aliases", projectionAliases, "source", source.getDatasourceId(), "tables",
				physicalTables, "limit", limit));
		return new CompiledSourceQuery(source.getDatasourceId(), dialect, ast.render(), List.copyOf(parameters), physicalTables,
				resultShapeHash);
	}

	private String compileProjection(SemanticBlueprint.ProjectionSelection projection, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context, List<SemanticBlueprint.MetricSelection> sourceMetrics,
			boolean conditionalMetricFilters, List<Object> parameters) {
		if (!aliases.containsKey(projection.getModelCode())) {
			throw new IllegalArgumentException(
					"Projection model is not part of the source: " + projection.getModelCode());
		}
		if (StringUtils.hasText(projection.getColumnName())) {
			SemanticCatalogSnapshot.Column column = requireColumn(context, projection.getModelCode(),
					projection.getColumnName());
			if (!Boolean.TRUE.equals(column.getAllowProjection())) {
				throw new IllegalArgumentException("Column governance denies projection: "
						+ key(projection.getModelCode(), projection.getColumnName()));
			}
		}
		boolean aggregation = "METRIC".equalsIgnoreCase(projection.getProjectionType());
		String expression = "TIME_BUCKET".equalsIgnoreCase(projection.getProjectionType())
				? compileTimeBucket(projection.getModelCode(), projection.getColumnName(), projection.getTimeBucketGranularity(),
					dialect, aliases, context)
				: renderExpression(firstText(projection.getExpression(), projection.getColumnName()),
					projection.getModelCode(), dialect, aliases, context, aggregation, !aggregation);
		if (aggregation && conditionalMetricFilters) {
			SemanticBlueprint.MetricSelection metric = sourceMetrics.stream()
				.filter(candidate -> Objects.equals(candidate.getModelCode(), projection.getModelCode()))
				.filter(candidate -> Objects.equals(candidate.getMetricCode(), projection.getAlias()))
				.findFirst()
				.orElse(null);
			if (metric != null && StringUtils.hasText(metric.getFilterExpression())) {
				String condition = renderPublishedBooleanExpression(metric.getFilterExpression(), metric.getModelCode(),
						dialect, aliases, context, parameters);
				expression = conditionalAggregate(expression, condition);
			}
		}
		String alias = safeAlias(projection.getAlias(), projection.getColumnName());
		return expression + " AS " + dialect.quote(alias);
	}

	private String compileGroup(SemanticBlueprint.GroupSelection group, SqlDialect dialect, Map<String, String> aliases,
			CompileContext context) {
		if (StringUtils.hasText(group.getTimeBucketGranularity())) {
			return compileTimeBucket(group.getModelCode(), group.getColumnName(), group.getTimeBucketGranularity(), dialect,
					aliases, context);
		}
		return renderExpression(firstText(group.getExpression(), group.getColumnName()), group.getModelCode(), dialect,
				aliases, context, false, true);
	}

	private InternalMergeKey internalMergeKey(SemanticBlueprint plan, Set<String> sourceModels, CompileContext context) {
		if (plan.getMergePlan() == null || plan.getSourceSubPlans() == null || plan.getSourceSubPlans().size() < 2) {
			return null;
		}
		for (String inputKey : List.of(Objects.toString(plan.getMergePlan().getLeftInputKey(), ""),
				Objects.toString(plan.getMergePlan().getRightInputKey(), ""))) {
			if (!StringUtils.hasText(inputKey)) {
				continue;
			}
			SemanticCatalogSnapshot.Dimension dimension = context.dimensionsByCode().get(inputKey);
			if (dimension != null && dimension.getStatus() == SemanticAssetStatus.ENABLED
					&& sourceModels.contains(dimension.getModelCode()) && StringUtils.hasText(dimension.getColumnName())) {
				requireColumn(context, dimension.getModelCode(), dimension.getColumnName());
				return new InternalMergeKey(dimension.getModelCode(), dimension.getColumnName(), inputKey);
			}
		}
		return null;
	}

	private String compileInternalMergeKey(InternalMergeKey key, SqlDialect dialect, Map<String, String> aliases,
			CompileContext context) {
		return compileInternalMergeKeyExpression(key, dialect, aliases, context) + " AS " + dialect.quote(key.alias());
	}

	private String compileInternalMergeKeyExpression(InternalMergeKey key, SqlDialect dialect, Map<String, String> aliases,
			CompileContext context) {
		requireColumn(context, key.modelCode(), key.columnName());
		String tableAlias = aliases.get(key.modelCode());
		if (!StringUtils.hasText(tableAlias)) {
			throw new IllegalArgumentException("Internal merge key model is not part of source: " + key.modelCode());
		}
		return tableAlias + "." + dialect.quote(key.columnName());
	}

	private String compileTimeBucket(String modelCode, String columnName, String granularity, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context) {
		SemanticCatalogSnapshot.Column column = requireColumn(context, modelCode, columnName);
		if (!Boolean.TRUE.equals(column.getAllowProjection())) {
			throw new IllegalArgumentException(
					"Column governance denies time bucket projection: " + key(modelCode, columnName));
		}
		String alias = aliases.get(modelCode);
		if (alias == null) {
			throw new IllegalArgumentException("Time bucket model is not present in source: " + modelCode);
		}
		return dialect.timeBucket(alias + "." + dialect.quote(columnName), granularity);
	}

	private boolean requiresConditionalMetricFilters(List<SemanticBlueprint.MetricSelection> metrics) {
		return metrics.stream()
			.map(metric -> Objects.toString(metric.getFilterExpression(), "").trim())
			.distinct()
			.limit(2)
			.count() > 1;
	}

	private String conditionalAggregate(String expression, String condition) {
		Matcher matcher = AGGREGATE_CALL.matcher(expression);
		if (!matcher.matches()) {
			throw new ConstrainedGenerationRequiredException(
					"Filtered metric requires a supported aggregate expression");
		}
		String function = matcher.group(1).toUpperCase(Locale.ROOT);
		String distinct = Objects.toString(matcher.group(2), "");
		String argument = matcher.group(3).trim();
		if ("COUNT".equals(function) && "*".equals(argument)) {
			argument = "1";
		}
		String otherwise = "SUM".equals(function) ? " ELSE 0" : "";
		return function + "(" + distinct + "CASE WHEN " + condition + " THEN " + argument + otherwise + " END)";
	}

	private void appendJoins(StringBuilder sql, List<SemanticCatalogSnapshot.Model> models, Set<String> joined,
			Set<String> sourceModels, SqlDialect dialect, Map<String, String> aliases, CompileContext context,
			List<SemanticBlueprint.RelationshipSelection> relationships) {
		List<SemanticBlueprint.RelationshipSelection> pending = new ArrayList<>(relationships.stream()
			.filter(relationship -> sourceModels.contains(relationship.getSourceModelCode())
					&& sourceModels.contains(relationship.getTargetModelCode()))
			.toList());
		while (joined.size() < models.size()) {
			SemanticBlueprint.RelationshipSelection selected = pending.stream()
				.filter(relationship -> joined.contains(relationship.getSourceModelCode())
						^ joined.contains(relationship.getTargetModelCode()))
				.findFirst()
				.orElseThrow(() -> new ConstrainedGenerationRequiredException(
						"Published relationship path cannot deterministically connect all selected models"));
			boolean forward = joined.contains(selected.getSourceModelCode());
			String targetCode = forward ? selected.getTargetModelCode() : selected.getSourceModelCode();
			SemanticCatalogSnapshot.Model target = context.modelsByCode().get(targetCode);
			String joinType = traversalJoinType(selected.getJoinType(), forward);
			sql.append(' ')
				.append(joinType)
				.append(" JOIN ")
				.append(qualifiedTable(target.getPhysicalTable(), dialect))
				.append(' ')
				.append(aliases.get(targetCode))
				.append(" ON ")
				.append(renderJoinCondition(selected.getJoinCondition(), dialect, aliases, context));
			joined.add(targetCode);
			pending.remove(selected);
		}
	}

	private String compileFilter(SemanticBlueprint.FilterSelection filter, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context, List<Object> parameters) {
		SemanticCatalogSnapshot.Column column = requireColumn(context, filter.getModelCode(), filter.getColumnName());
		if (!Boolean.TRUE.equals(column.getAllowFilter())) {
			throw new IllegalArgumentException(
					"Column governance denies filtering: " + key(filter.getModelCode(), filter.getColumnName()));
		}
		String left = renderExpression(firstText(filter.getExpression(), filter.getColumnName()), filter.getModelCode(),
				dialect, aliases, context, false, false);
		String operator = Objects.toString(filter.getOperator(), "EQ").toUpperCase(Locale.ROOT);
		return switch (operator) {
			case "EQ" -> parameter(left + " = ?", filter.getValue(), parameters);
			case "NE", "NOT_EQ" -> parameter(left + " <> ?", filter.getValue(), parameters);
			case "GT" -> parameter(left + " > ?", filter.getValue(), parameters);
			case "GTE" -> parameter(left + " >= ?", filter.getValue(), parameters);
			case "LT" -> parameter(left + " < ?", filter.getValue(), parameters);
			case "LTE" -> parameter(left + " <= ?", filter.getValue(), parameters);
			case "LIKE" -> parameter(left + " LIKE ?", filter.getValue(), parameters);
			case "IS_NULL" -> left + " IS NULL";
			case "IS_NOT_NULL" -> left + " IS NOT NULL";
			case "IN", "NOT_IN" -> compileIn(left, operator, filter.getValue(), parameters);
			case "BETWEEN" -> compileBetween(left, filter.getValue(), parameters);
			default -> throw new IllegalArgumentException("Unsupported Semantic Blueprint filter operator: " + operator);
		};
	}

	private List<String> compileTimeRange(SemanticBlueprint.TimeRangeSelection range, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context, List<Object> parameters, Clock clock,
			ZoneId defaultZone) {
		SemanticCatalogSnapshot.Column column = requireColumn(context, range.getModelCode(), range.getTimeColumn());
		if (!Boolean.TRUE.equals(column.getAllowFilter())) {
			throw new IllegalArgumentException(
					"Column governance denies time filtering: " + key(range.getModelCode(), range.getTimeColumn()));
		}
		String expression = renderExpression(range.getTimeColumn(), range.getModelCode(), dialect, aliases, context,
				false, false);
		LocalDateTime start;
		LocalDateTime end;
		if (StringUtils.hasText(range.getStartInclusive()) && StringUtils.hasText(range.getEndExclusive())) {
			start = LocalDateTime.parse(range.getStartInclusive());
			end = LocalDateTime.parse(range.getEndExclusive());
		}
		else {
			ZoneId zone = StringUtils.hasText(range.getTimeZone()) && !"SYSTEM".equalsIgnoreCase(range.getTimeZone())
					? ZoneId.of(range.getTimeZone()) : defaultZone;
			TimeBounds bounds = relativeBounds(range.getRelativeExpression(), ZonedDateTime.now(clock.withZone(zone)));
			start = bounds.start();
			end = bounds.end();
		}
		parameters.add(start);
		parameters.add(end);
		return List.of(expression + " >= ?", expression + " < ?");
	}

	private TimeBounds relativeBounds(String relative, ZonedDateTime now) {
		LocalDate today = now.toLocalDate();
		return switch (Objects.toString(relative, "")) {
			case "CURRENT_DAY" -> bounds(today, today.plusDays(1));
			case "PREVIOUS_DAY" -> bounds(today.minusDays(1), today);
			case "CURRENT_WEEK" -> {
				LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				yield bounds(start, start.plusWeeks(1));
			}
			case "PREVIOUS_WEEK" -> {
				LocalDate end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				yield bounds(end.minusWeeks(1), end);
			}
			case "CURRENT_MONTH" -> {
				LocalDate start = today.withDayOfMonth(1);
				yield bounds(start, start.plusMonths(1));
			}
			case "PREVIOUS_MONTH" -> {
				LocalDate end = today.withDayOfMonth(1);
				yield bounds(end.minusMonths(1), end);
			}
			case "CURRENT_YEAR" -> {
				LocalDate start = today.withDayOfYear(1);
				yield bounds(start, start.plusYears(1));
			}
			case "PREVIOUS_YEAR" -> {
				LocalDate end = today.withDayOfYear(1);
				yield bounds(end.minusYears(1), end);
			}
			default ->
				throw new ConstrainedGenerationRequiredException("Unsupported relative time expression: " + relative);
		};
	}

	private TimeBounds bounds(LocalDate start, LocalDate end) {
		return new TimeBounds(start.atStartOfDay(), end.atStartOfDay());
	}

	private String compileOrder(SemanticBlueprint.OrderSelection order, SqlDialect dialect,
			Collection<String> projectionAliases) {
		if (!projectionAliases.contains(order.getExpression())) {
			throw new IllegalArgumentException(
					"ORDER BY must reference a governed projection alias: " + order.getExpression());
		}
		String direction = "ASC".equalsIgnoreCase(order.getDirection()) ? "ASC" : "DESC";
		String nulls = Objects.toString(order.getNulls(), "").toUpperCase(Locale.ROOT);
		String compiled = dialect.quote(order.getExpression()) + " " + direction;
		if ((dialect == SqlDialect.POSTGRESQL || dialect == SqlDialect.H2)
				&& List.of("FIRST", "LAST").contains(nulls)) {
			compiled += " NULLS " + nulls;
		}
		return compiled;
	}

	private String compileIn(String left, String operator, Object value, List<Object> parameters) {
		if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
			throw new IllegalArgumentException(operator + " requires a non-empty collection value");
		}
		if (collection.size() > 1000) {
			throw new IllegalArgumentException(operator + " accepts at most 1000 values");
		}
		parameters.addAll(collection);
		return left + ("NOT_IN".equals(operator) ? " NOT IN (" : " IN (")
				+ collection.stream().map(ignored -> "?").collect(Collectors.joining(", ")) + ")";
	}

	private String compileBetween(String left, Object value, List<Object> parameters) {
		if (!(value instanceof List<?> values) || values.size() != 2) {
			throw new IllegalArgumentException("BETWEEN requires exactly two values");
		}
		parameters.add(values.get(0));
		parameters.add(values.get(1));
		return left + " BETWEEN ? AND ?";
	}

	private String parameter(String sql, Object value, List<Object> parameters) {
		parameters.add(value);
		return sql;
	}

	private String renderExpression(String expression, String modelCode, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context, boolean aggregationRequired,
			boolean projectionContext) {
		if (!StringUtils.hasText(expression) || unsafeExpression(expression)) {
			throw new ConstrainedGenerationRequiredException("Unsafe or empty published expression: " + expression);
		}
		String alias = aliases.get(modelCode);
		if (alias == null) {
			throw new IllegalArgumentException("Expression model is not present in source: " + modelCode);
		}
		String rendered = replaceQualifiedIdentifiers(expression, dialect, aliases, context, aggregationRequired,
				projectionContext);
		Matcher matcher = IDENTIFIER.matcher(rendered);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String token = matcher.group();
			String lower = token.toLowerCase(Locale.ROOT);
			int start = matcher.start();
			if (insideQuotedIdentifier(rendered, start, dialect) || SQL_WORDS.contains(lower)
					|| aliases.containsValue(token) || token.matches("t\\d+")) {
				matcher.appendReplacement(output, Matcher.quoteReplacement(token));
				continue;
			}
			SemanticCatalogSnapshot.Column column = context.columnsByKey().get(key(modelCode, token));
			if (column == null) {
				throw new ConstrainedGenerationRequiredException(
						"Published expression references an unknown governed column: " + key(modelCode, token));
			}
			assertColumnUse(column, aggregationRequired, projectionContext);
			matcher.appendReplacement(output, Matcher.quoteReplacement(alias + "." + dialect.quote(token)));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private String replaceQualifiedIdentifiers(String expression, SqlDialect dialect, Map<String, String> aliases,
			CompileContext context, boolean aggregationRequired, boolean projectionContext) {
		Matcher matcher = QUALIFIED_IDENTIFIER.matcher(expression);
		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			String qualifier = matcher.group(1);
			String columnName = matcher.group(2);
			String modelCode = resolveModelCode(qualifier, context);
			if (modelCode == null || !aliases.containsKey(modelCode)) {
				throw new ConstrainedGenerationRequiredException(
						"Expression references a model outside the source: " + qualifier);
			}
			SemanticCatalogSnapshot.Column column = requireColumn(context, modelCode, columnName);
			assertColumnUse(column, aggregationRequired, projectionContext);
			matcher.appendReplacement(result,
					Matcher.quoteReplacement(aliases.get(modelCode) + "." + dialect.quote(columnName)));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private String renderPublishedBooleanExpression(String expression, String modelCode, SqlDialect dialect,
			Map<String, String> aliases, CompileContext context, List<Object> parameters) {
		ParameterizedExpression parameterized = parameterizePublishedStringLiterals(expression);
		if (!parameterized.sql().matches("[A-Za-z0-9_.$?(),\\s=<>!+*/-]+")) {
			throw new ConstrainedGenerationRequiredException("Published metric filter contains unsupported syntax");
		}
		assertFilterColumnsAllowed(parameterized.sql(), modelCode, aliases, context);
		String rendered = renderExpression(parameterized.sql(), modelCode, dialect, aliases, context, false, false);
		parameters.addAll(parameterized.parameters());
		return rendered;
	}

	private ParameterizedExpression parameterizePublishedStringLiterals(String expression) {
		if (!StringUtils.hasText(expression) || expression.contains("\"")) {
			throw new ConstrainedGenerationRequiredException("Published metric filter contains unsupported quoting");
		}
		StringBuilder sql = new StringBuilder(expression.length());
		List<Object> parameters = new ArrayList<>();
		for (int index = 0; index < expression.length(); index++) {
			char current = expression.charAt(index);
			if (current != '\'') {
				sql.append(current);
				continue;
			}
			StringBuilder value = new StringBuilder();
			boolean closed = false;
			while (++index < expression.length()) {
				char quoted = expression.charAt(index);
				if (quoted != '\'') {
					value.append(quoted);
					continue;
				}
				if (index + 1 < expression.length() && expression.charAt(index + 1) == '\'') {
					value.append('\'');
					index++;
					continue;
				}
				closed = true;
				break;
			}
			if (!closed) {
				throw new ConstrainedGenerationRequiredException("Published metric filter has an unclosed literal");
			}
			parameters.add(value.toString());
			sql.append('?');
		}
		return new ParameterizedExpression(sql.toString(), List.copyOf(parameters));
	}

	private void assertFilterColumnsAllowed(String expression, String modelCode, Map<String, String> aliases,
			CompileContext context) {
		Matcher matcher = IDENTIFIER.matcher(expression);
		while (matcher.find()) {
			String token = matcher.group();
			if (SQL_WORDS.contains(token.toLowerCase(Locale.ROOT)) || aliases.containsValue(token)
					|| context.modelsByCode().containsKey(token)
					|| context.modelCodeByPhysicalTable().containsKey(token)) {
				continue;
			}
			SemanticCatalogSnapshot.Column column = context.columnsByKey().get(key(modelCode, token));
			if (column != null && !Boolean.TRUE.equals(column.getAllowFilter())) {
				throw new IllegalArgumentException("Column governance denies filter: " + key(modelCode, token));
			}
		}
	}

	private String renderJoinCondition(String expression, SqlDialect dialect, Map<String, String> aliases,
			CompileContext context) {
		if (!StringUtils.hasText(expression) || unsafeExpression(expression)) {
			throw new ConstrainedGenerationRequiredException("Unsafe published relationship condition");
		}
		Matcher matcher = QUALIFIED_IDENTIFIER.matcher(expression);
		StringBuffer result = new StringBuffer();
		int replacements = 0;
		while (matcher.find()) {
			String modelCode = resolveModelCode(matcher.group(1), context);
			if (modelCode == null || !aliases.containsKey(modelCode)) {
				throw new ConstrainedGenerationRequiredException(
						"Relationship condition references a model outside the source: " + matcher.group(1));
			}
			requireColumn(context, modelCode, matcher.group(2));
			matcher.appendReplacement(result,
					Matcher.quoteReplacement(aliases.get(modelCode) + "." + dialect.quote(matcher.group(2))));
			replacements++;
		}
		matcher.appendTail(result);
		if (replacements < 2 || !result.toString().matches("[A-Za-z0-9_`\".$()\\s=<>!+*/-]+")) {
			throw new ConstrainedGenerationRequiredException("Relationship condition cannot be proven safe");
		}
		return result.toString();
	}

	private void assertColumnUse(SemanticCatalogSnapshot.Column column, boolean aggregationRequired,
			boolean projectionContext) {
		if (projectionContext && !Boolean.TRUE.equals(column.getAllowProjection())) {
			throw new IllegalArgumentException(
					"Column governance denies projection: " + key(column.getModelCode(), column.getColumnName()));
		}
		if (aggregationRequired && !Boolean.TRUE.equals(column.getAllowAggregation())) {
			throw new IllegalArgumentException(
					"Column governance denies aggregation: " + key(column.getModelCode(), column.getColumnName()));
		}
	}

	private boolean unsafeExpression(String expression) {
		String lower = expression.toLowerCase(Locale.ROOT);
		return expression.contains(";") || lower.contains("--") || lower.contains("/*") || lower.contains("*/")
				|| lower.matches(".*\\b(insert|update|delete|drop|alter|truncate|grant|revoke|call|execute)\\b.*");
	}

	private boolean insideQuotedIdentifier(String expression, int position, SqlDialect dialect) {
		char quote = dialect == SqlDialect.MYSQL || dialect == SqlDialect.CLICKHOUSE ? '`' : '"';
		return position > 0 && expression.charAt(position - 1) == quote;
	}

	private String resolveModelCode(String qualifier, CompileContext context) {
		if (context.modelsByCode().containsKey(qualifier)) {
			return qualifier;
		}
		return context.modelCodeByPhysicalTable().get(qualifier);
	}

	private SemanticCatalogSnapshot.Column requireColumn(CompileContext context, String modelCode, String columnName) {
		SemanticCatalogSnapshot.Column column = context.columnsByKey().get(key(modelCode, columnName));
		if (column == null || column.getStatus() != SemanticAssetStatus.ENABLED) {
			throw new IllegalArgumentException("Published semantic column not found: " + key(modelCode, columnName));
		}
		return column;
	}

	private CompileContext context(SemanticBlueprint plan, SemanticCatalogSnapshot catalog) {
		Map<String, SemanticCatalogSnapshot.Model> models = catalog.getModels()
			.stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode, Function.identity()));
		Map<String, String> modelByTable = models.values()
			.stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getPhysicalTable,
					SemanticCatalogSnapshot.Model::getModelCode, (left, right) -> left));
		Map<String, SemanticCatalogSnapshot.Column> columns = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(column -> key(column.getModelCode(), column.getColumnName()), Function.identity(),
					(left, right) -> left));
		Map<String, SemanticCatalogSnapshot.Dimension> dimensions = catalog.getDimensions()
			.stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> StringUtils.hasText(dimension.getDimensionCode()))
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Dimension::getDimensionCode, Function.identity(),
					(left, right) -> left));
		for (SemanticBlueprint.ModelSelection model : plan.getModels()) {
			if (!models.containsKey(model.getModelCode())) {
				throw new IllegalArgumentException(
						"Semantic Blueprint references a non-published model: " + model.getModelCode());
			}
		}
		return new CompileContext(Map.copyOf(models), Map.copyOf(modelByTable), Map.copyOf(columns), Map.copyOf(dimensions));
	}

	private List<SemanticBlueprint.SourceSubPlan> sourcePlans(SemanticBlueprint plan, CompileContext context) {
		if (plan.getSourceSubPlans() != null && !plan.getSourceSubPlans().isEmpty()) {
			return plan.getSourceSubPlans();
		}
		Map<Integer, List<String>> byDatasource = plan.getModels()
			.stream()
			.collect(Collectors.groupingBy(SemanticBlueprint.ModelSelection::getDatasourceId, LinkedHashMap::new,
					Collectors.mapping(SemanticBlueprint.ModelSelection::getModelCode, Collectors.toList())));
		return byDatasource.entrySet()
			.stream()
			.map(entry -> SemanticBlueprint.SourceSubPlan.builder()
				.datasourceId(entry.getKey())
				.modelCodes(entry.getValue())
				.physicalTables(entry.getValue()
					.stream()
					.map(context.modelsByCode()::get)
					.filter(Objects::nonNull)
					.map(SemanticCatalogSnapshot.Model::getPhysicalTable)
					.toList())
				.build())
			.toList();
	}

	private SqlDialect resolveDialect(SemanticBlueprint plan, SemanticBlueprint.SourceSubPlan source,
			Map<Integer, SqlDialect> dialects) {
		if (dialects != null && source.getDatasourceId() != null && dialects.containsKey(source.getDatasourceId())) {
			return dialects.get(source.getDatasourceId());
		}
		if (StringUtils.hasText(plan.getDialect())) {
			return SqlDialect.from(plan.getDialect());
		}
		throw new IllegalArgumentException("SQL dialect is required for datasource " + source.getDatasourceId());
	}

	private Map<String, String> aliases(List<SemanticCatalogSnapshot.Model> models) {
		Map<String, String> aliases = new LinkedHashMap<>();
		for (int index = 0; index < models.size(); index++) {
			aliases.put(models.get(index).getModelCode(), "t" + index);
		}
		return aliases;
	}

	private String qualifiedTable(String value, SqlDialect dialect) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Physical table is required");
		}
		return java.util.Arrays.stream(value.split("\\.")).map(dialect::quote).collect(Collectors.joining("."));
	}

	private String traversalJoinType(String value, boolean forward) {
		String joinType = safeJoinType(value);
		if (forward || "INNER".equals(joinType)) {
			return joinType;
		}
		return "LEFT".equals(joinType) ? "RIGHT" : "LEFT";
	}

	private String safeJoinType(String value) {
		return switch (Objects.toString(value, "INNER").toUpperCase(Locale.ROOT)) {
			case "INNER", "JOIN" -> "INNER";
			case "LEFT", "LEFT JOIN" -> "LEFT";
			case "RIGHT", "RIGHT JOIN" -> "RIGHT";
			default -> throw new ConstrainedGenerationRequiredException("Unsupported published join type: " + value);
		};
	}

	private String safeAlias(String alias, String fallback) {
		String value = StringUtils.hasText(alias) ? alias : fallback;
		if (!StringUtils.hasText(value) || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
			throw new IllegalArgumentException("Unsafe projection alias: " + value);
		}
		return value;
	}

	private String firstText(String first, String second) {
		return StringUtils.hasText(first) ? first : second;
	}

	private String key(String modelCode, String columnName) {
		return Objects.toString(modelCode, "") + "::" + Objects.toString(columnName, "");
	}

	private String hash(Object value) {
		try {
			byte[] bytes = JsonUtil.getObjectMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder result = new StringBuilder(64);
			for (byte item : digest) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to hash compiled result shape", ex);
		}
	}

	private record CompileContext(Map<String, SemanticCatalogSnapshot.Model> modelsByCode,
			Map<String, String> modelCodeByPhysicalTable, Map<String, SemanticCatalogSnapshot.Column> columnsByKey,
			Map<String, SemanticCatalogSnapshot.Dimension> dimensionsByCode) {
	}

	private record InternalMergeKey(String modelCode, String columnName, String alias) {
	}

	private record ParameterizedExpression(String sql, List<Object> parameters) {
	}

	private record TimeBounds(LocalDateTime start, LocalDateTime end) {
	}

	public static class ConstrainedGenerationRequiredException extends IllegalStateException {

		public ConstrainedGenerationRequiredException(String message) {
			super(message);
		}

	}

}
