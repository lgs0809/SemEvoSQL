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
package cn.lgs.semevosql.sql.application;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Applies deterministic business constraints that are explicitly present in a typed
 * semantic plan. Missing constraint types are ignored rather than inferred.
 */
@Component
public class BlueprintSqlConstraintValidator {

	private static final Pattern PRE_AGGREGATION_STRUCTURE = Pattern.compile("(?is)\\bwith\\b|\\bjoin\\s*\\(");

	private static final Pattern AGGREGATION_FUNCTION = Pattern
		.compile("(?i)\\b(sum|count|avg|min|max|stddev|variance)\\s*\\(");

	private static final Pattern AGGREGATE_EXPRESSION = Pattern
		.compile("(?i)^(sum|count|avg|min|max)\\((distinct)?(.+)\\)$");

	private static final Pattern SINGLE_VALUE_STRING_IN = Pattern.compile(
			"(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_$]*)\\s+IN\\s*\\(\\s*'((?:''|[^'])*)'\\s*\\)\\s*$");

	private static final Set<String> MANDATORY_RULE_TYPES = Set.of("mandatory_filter", "row_filter", "security_filter",
			"data_scope", "required_predicate", "business_filter");

	public ValidationResult validate(String sql, SemanticBlueprint plan) {
		return validate(sql, List.of(), plan);
	}

	public ValidationResult validate(String sql, List<?> parameters, SemanticBlueprint plan) {
		if (plan == null) {
			return ValidationResult.rejected(List.of("Semantic Blueprint is missing"), List.of());
		}
		if (sql == null || sql.isBlank()) {
			return ValidationResult.rejected(List.of("SQL is blank"), List.of());
		}

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		String validationSql = bindParametersForValidation(sql, parameters == null ? List.of() : parameters, errors);
		if (!errors.isEmpty()) {
			return ValidationResult.rejected(errors, warnings);
		}
		String canonicalSql = canonicalExpression(validationSql);

		validateMetrics(canonicalSql, plan.getMetrics(), errors);
		validateDimensions(validationSql, canonicalSql, plan.getDimensions(), errors);
		validateRelationships(canonicalSql, plan.getRelationships(), errors);
		validateRules(canonicalSql, plan.getRules(), errors, warnings);
		validatePreAggregation(sql, plan.getPreAggregationModelCodes(), errors);

		return errors.isEmpty() ? ValidationResult.accepted(warnings) : ValidationResult
			.rejected(List.copyOf(new LinkedHashSet<>(errors)), List.copyOf(new LinkedHashSet<>(warnings)));
	}

	private String bindParametersForValidation(String sql, List<?> parameters, List<String> errors) {
		StringBuilder expanded = new StringBuilder(sql.length() + parameters.size() * 8);
		int parameterIndex = 0;
		char quote = 0;
		for (int index = 0; index < sql.length(); index++) {
			char current = sql.charAt(index);
			if (quote != 0) {
				expanded.append(current);
				if (current == quote) {
					if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
						expanded.append(sql.charAt(++index));
					}
					else {
						quote = 0;
					}
				}
				continue;
			}
			if (current == '\'' || current == '"' || current == '`') {
				quote = current;
				expanded.append(current);
				continue;
			}
			if (current != '?') {
				expanded.append(current);
				continue;
			}
			if (parameterIndex >= parameters.size()) {
				errors.add("SQL contains an unbound parameter placeholder");
				return sql;
			}
			expanded.append(renderValidationLiteral(parameters.get(parameterIndex++)));
		}
		if (parameterIndex != parameters.size()) {
			errors.add("SQL parameter count does not match placeholders");
			return sql;
		}
		return expanded.toString();
	}

	private String renderValidationLiteral(Object value) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		return "'" + value.toString().replace("'", "''") + "'";
	}

	private void validateMetrics(String canonicalSql, List<SemanticBlueprint.MetricSelection> metrics,
			List<String> errors) {
		for (SemanticBlueprint.MetricSelection metric : safe(metrics)) {
			boolean conditionalAggregate = containsConditionalMetricAggregate(canonicalSql, metric);
			if (!containsExpression(canonicalSql, canonicalMetricExpression(metric)) && !conditionalAggregate) {
				errors.add("SQL does not use the published metric expression: " + metric.getMetricCode());
			}
			if (hasText(metric.getFilterExpression()) && !containsExpression(canonicalSql, metric.getFilterExpression())
					&& !conditionalAggregate) {
				errors.add("SQL omits the published metric filter: " + metric.getMetricCode());
			}
		}
	}

	private boolean containsConditionalMetricAggregate(String canonicalSql, SemanticBlueprint.MetricSelection metric) {
		if (!hasText(metric.getExpression()) || !hasText(metric.getFilterExpression())) {
			return false;
		}
		var matcher = AGGREGATE_EXPRESSION.matcher(canonicalMetricExpression(metric));
		if (!matcher.matches()) {
			return false;
		}
		String function = matcher.group(1);
		String distinct = matcher.group(2) == null ? "" : matcher.group(2);
		String argument = matcher.group(3);
		String condition = canonicalExpression(metric.getFilterExpression());
		String prefix = function + "(" + distinct + "casewhen" + condition + "then" + argument;
		if ("sum".equals(function)) {
			return canonicalSql.contains(prefix + "else0end)");
		}
		return canonicalSql.contains(prefix + "end)");
	}

	private String canonicalMetricExpression(SemanticBlueprint.MetricSelection metric) {
		String expression = canonicalExpression(metric.getExpression());
		if (expression.isBlank() || AGGREGATE_EXPRESSION.matcher(expression).matches()) {
			return expression;
		}
		return switch (normalizeValue(metric.getAggregation())) {
			case "sum", "count", "avg", "min", "max" ->
				normalizeValue(metric.getAggregation()) + "(" + expression + ")";
			case "count_distinct" -> "count(distinct" + expression + ")";
			default -> expression;
		};
	}

	private void validateDimensions(String sql, String canonicalSql,
			List<SemanticBlueprint.DimensionSelection> dimensions, List<String> errors) {
		for (SemanticBlueprint.DimensionSelection dimension : safe(dimensions)) {
			if (hasText(dimension.getExpression())) {
				if (!containsExpression(canonicalSql, dimension.getExpression())) {
					errors.add("SQL omits the selected dimension expression: " + dimension.getDimensionCode());
				}
			}
			else if (hasText(dimension.getColumnName()) && !containsIdentifier(sql, dimension.getColumnName())) {
				errors.add("SQL omits the selected dimension column: " + dimension.getDimensionCode());
			}
		}
	}

	private void validateRelationships(String canonicalSql, List<SemanticBlueprint.RelationshipSelection> relationships,
			List<String> errors) {
		for (SemanticBlueprint.RelationshipSelection relationship : safe(relationships)) {
			if (!containsExpression(canonicalSql, relationship.getJoinCondition())) {
				errors.add("SQL does not use the published join condition: " + relationship.getRelationshipCode());
			}
		}
	}

	private void validateRules(String canonicalSql, List<SemanticBlueprint.RuleSelection> rules, List<String> errors,
			List<String> warnings) {
		for (SemanticBlueprint.RuleSelection rule : safe(rules)) {
			boolean mandatory = MANDATORY_RULE_TYPES.contains(normalizeValue(rule.getRuleType()));
			if (!mandatory) {
				continue;
			}
			if (!containsMandatoryRule(canonicalSql, rule.getExpression())) {
				errors.add("SQL violates mandatory published rule: " + rule.getRuleCode());
			}
		}
		if (!safe(rules).isEmpty() && errors.isEmpty()) {
			warnings.add("All mandatory published rules were found in the SQL text");
		}
	}

	private void validatePreAggregation(String sql, List<String> preAggregationModelCodes, List<String> errors) {
		if (safe(preAggregationModelCodes).isEmpty()) {
			return;
		}
		if (!PRE_AGGREGATION_STRUCTURE.matcher(sql).find() || !AGGREGATION_FUNCTION.matcher(sql).find()
				|| !sql.toLowerCase(Locale.ROOT).contains("group by")) {
			errors.add("Fan-out protection requires CTE/derived-table pre-aggregation with GROUP BY for models: "
					+ String.join(", ", preAggregationModelCodes));
		}
	}

	private boolean containsMandatoryRule(String canonicalSql, String expression) {
		if (containsExpression(canonicalSql, expression)) {
			return true;
		}
		if (!hasText(expression)) {
			return true;
		}
		var matcher = SINGLE_VALUE_STRING_IN.matcher(expression);
		if (!matcher.matches()) {
			return false;
		}
		String identifier = matcher.group(1);
		String value = matcher.group(2).replace("''", "'");
		String equivalentEquality = identifier + " = '" + value.replace("'", "''") + "'";
		return containsExpression(canonicalSql, equivalentEquality);
	}

	private boolean containsExpression(String canonicalSql, String expression) {
		if (!hasText(expression)) {
			return true;
		}
		String canonicalConstraint = canonicalExpression(expression);
		return !canonicalConstraint.isBlank() && canonicalSql.contains(canonicalConstraint);
	}

	private boolean containsIdentifier(String sql, String identifier) {
		if (!hasText(identifier)) {
			return true;
		}
		String unquotedSql = sql.replace('`', ' ').replace('"', ' ').replace('[', ' ').replace(']', ' ');
		Pattern identifierPattern = Pattern
			.compile("(?i)(?<![a-zA-Z0-9_$])" + Pattern.quote(identifier) + "(?![a-zA-Z0-9_$])");
		return identifierPattern.matcher(unquotedSql).find();
	}

	private String canonicalExpression(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase(Locale.ROOT)
			.replace("`", "")
			.replace("\"", "")
			.replace("[", "")
			.replace("]", "")
			.replaceAll("(?i)(?<![a-zA-Z0-9_$])[a-zA-Z_][a-zA-Z0-9_$]*\\.", "")
			.replaceAll("\\s+", "")
			.replace(";", "");
	}

	private String normalizeValue(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {

		public static ValidationResult accepted(List<String> warnings) {
			return new ValidationResult(true, List.of(), List.copyOf(warnings));
		}

		public static ValidationResult rejected(List<String> errors, List<String> warnings) {
			return new ValidationResult(false, List.copyOf(errors), List.copyOf(warnings));
		}
	}

}
