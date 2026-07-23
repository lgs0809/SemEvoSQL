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
package cn.lgs.semevosql.multisource;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MultiSourceMergeEngine {

	public ResultSetBO merge(MergePolicy policy, List<ResultSetBO> inputs) {
		if (policy == null || policy.getMergeType() == null) {
			throw new IllegalArgumentException("A merge policy is required");
		}
		List<ResultSetBO> safeInputs = inputs == null ? List.of() : inputs.stream().filter(Objects::nonNull).toList();
		if (safeInputs.isEmpty()) {
			return ResultSetBO.builder().column(List.of()).data(List.of()).build();
		}
		ResultSetBO result = switch (policy.getMergeType()) {
			case UNION -> union(safeInputs, policy);
			case LOOKUP_ENRICHMENT, IDENTITY_STITCHING -> keyedJoin(safeInputs, policy);
			case AGGREGATION_MERGE -> aggregate(safeInputs, policy);
			case SEQUENTIAL_DEPENDENCY -> copy(safeInputs.get(safeInputs.size() - 1));
			case DERIVED_CALCULATION -> derived(safeInputs, policy);
			case SCALAR_COMPOSITION -> scalarComposition(safeInputs, policy);
		};
		int maxRows = policy.getMaxRows() == null ? 10_000 : policy.getMaxRows();
		if (result.getData() != null && result.getData().size() > maxRows) {
			throw new IllegalStateException("Merged result exceeds configured maxRows: " + maxRows);
		}
		return result;
	}

	private ResultSetBO union(List<ResultSetBO> inputs, MergePolicy policy) {
		Set<String> columns = new LinkedHashSet<>();
		inputs.forEach(input -> columns.addAll(safeColumns(input)));
		List<Map<String, String>> rows = new ArrayList<>();
		Set<String> fingerprints = new LinkedHashSet<>();
		for (ResultSetBO input : inputs) {
			for (Map<String, String> row : safeRows(input)) {
				Map<String, String> normalized = normalizeRow(columns, row);
				if (!"KEEP_ALL".equalsIgnoreCase(policy.getDuplicatePolicy())) {
					String fingerprint = normalized.toString();
					if (!fingerprints.add(fingerprint)) {
						continue;
					}
				}
				rows.add(normalized);
			}
		}
		return ResultSetBO.builder().column(new ArrayList<>(columns)).data(rows).build();
	}

	private ResultSetBO keyedJoin(List<ResultSetBO> inputs, MergePolicy policy) {
		if (inputs.size() < 2) {
			return copy(inputs.get(0));
		}
		String leftKey = required(policy.getLeftInputKey(), "leftInputKey");
		String rightKey = required(policy.getRightInputKey(), "rightInputKey");
		String outputKey = hasText(policy.getOutputKey()) ? policy.getOutputKey() : leftKey;
		ResultSetBO current = copy(inputs.get(0));
		for (int inputIndex = 1; inputIndex < inputs.size(); inputIndex++) {
			ResultSetBO right = inputs.get(inputIndex);
			if (!safeColumns(current).contains(leftKey)) {
				throw new IllegalStateException("Left merge input is missing required key column: " + leftKey);
			}
			if (!safeColumns(right).contains(rightKey)) {
				throw new IllegalStateException("Right merge input is missing required key column: " + rightKey);
			}
			Map<String, List<Map<String, String>>> rightIndex = new LinkedHashMap<>();
			for (Map<String, String> row : safeRows(right)) {
				String key = row.get(rightKey);
				if (hasText(key)) {
					rightIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
				}
			}
			Set<String> columns = new LinkedHashSet<>(safeColumns(current));
			columns.add(outputKey);
			for (String column : safeColumns(right)) {
				columns.add(columns.contains(column) && !column.equals(rightKey) ? "right_" + column : column);
			}
			List<Map<String, String>> mergedRows = new ArrayList<>();
			for (Map<String, String> left : safeRows(current)) {
				List<Map<String, String>> matches = rightIndex.get(left.get(leftKey));
				if (matches == null || matches.isEmpty()) {
					if (!"DROP".equalsIgnoreCase(policy.getNullPolicy())) {
						mergedRows.add(normalizeRow(columns, left));
					}
					continue;
				}
				if (matches.size() > 1 && "ERROR".equalsIgnoreCase(policy.getDuplicatePolicy())) {
					throw new IllegalStateException("Merge key is not unique on right input: " + left.get(leftKey));
				}
				for (Map<String, String> match : matches) {
					Map<String, String> merged = new LinkedHashMap<>(left);
					merged.put(outputKey, left.get(leftKey));
					for (Map.Entry<String, String> entry : match.entrySet()) {
						if (entry.getKey().equals(rightKey)) {
							continue;
						}
						String target = merged.containsKey(entry.getKey()) ? "right_" + entry.getKey() : entry.getKey();
						merged.put(target, entry.getValue());
					}
					mergedRows.add(normalizeRow(columns, merged));
					if ("FIRST".equalsIgnoreCase(policy.getDuplicatePolicy())) {
						break;
					}
				}
			}
			current = ResultSetBO.builder().column(new ArrayList<>(columns)).data(mergedRows).build();
		}
		return current;
	}

	private ResultSetBO aggregate(List<ResultSetBO> inputs, MergePolicy policy) {
		String groupKey = hasText(policy.getOutputKey()) ? policy.getOutputKey()
				: required(policy.getLeftInputKey(), "leftInputKey/outputKey");
		Set<String> columns = new LinkedHashSet<>();
		inputs.forEach(input -> columns.addAll(safeColumns(input)));
		columns.add(groupKey);
		Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
		for (ResultSetBO input : inputs) {
			for (Map<String, String> row : safeRows(input)) {
				String key = row.get(groupKey);
				Map<String, String> target = grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
				target.put(groupKey, key);
				for (Map.Entry<String, String> entry : row.entrySet()) {
					if (entry.getKey().equals(groupKey)) {
						continue;
					}
					BigDecimal incoming = decimal(entry.getValue());
					BigDecimal existing = decimal(target.get(entry.getKey()));
					if (incoming != null) {
						target.put(entry.getKey(),
								(existing == null ? BigDecimal.ZERO : existing).add(incoming)
									.stripTrailingZeros()
									.toPlainString());
					}
					else if (!target.containsKey(entry.getKey())) {
						target.put(entry.getKey(), entry.getValue());
					}
				}
			}
		}
		List<Map<String, String>> rows = grouped.values().stream().map(row -> normalizeRow(columns, row)).toList();
		return ResultSetBO.builder().column(new ArrayList<>(columns)).data(rows).build();
	}

	private ResultSetBO derived(List<ResultSetBO> inputs, MergePolicy policy) {
		ResultSetBO union = union(inputs, policy);
		if (!hasText(policy.getCalculationExpression())) {
			return union;
		}
		String[] assignment = policy.getCalculationExpression().split("=", 2);
		if (assignment.length != 2) {
			throw new IllegalArgumentException("Derived calculation must be an assignment: output=left-right");
		}
		String output = assignment[0].trim();
		String expression = assignment[1].trim();
		String operator = expression.contains("-") ? "-" : expression.contains("+") ? "+" : null;
		if (operator == null) {
			throw new IllegalArgumentException("Only addition and subtraction are supported for derived calculation");
		}
		String[] operands = expression.split("\\" + operator, 2);
		if (operands.length != 2) {
			throw new IllegalArgumentException("Invalid derived calculation expression");
		}
		Set<String> columns = new LinkedHashSet<>(safeColumns(union));
		columns.add(output);
		List<Map<String, String>> rows = safeRows(union).stream().map(row -> {
			Map<String, String> calculated = new LinkedHashMap<>(row);
			BigDecimal left = decimal(row.get(operands[0].trim()));
			BigDecimal right = decimal(row.get(operands[1].trim()));
			if (left != null && right != null) {
				BigDecimal value = "+".equals(operator) ? left.add(right) : left.subtract(right);
				calculated.put(output, value.stripTrailingZeros().toPlainString());
			}
			return normalizeRow(columns, calculated);
		}).toList();
		return ResultSetBO.builder().column(new ArrayList<>(columns)).data(rows).build();
	}

	private ResultSetBO scalarComposition(List<ResultSetBO> inputs, MergePolicy policy) {
		Set<String> columns = new LinkedHashSet<>();
		Map<String, String> row = new LinkedHashMap<>();
		for (ResultSetBO input : inputs) {
			List<Map<String, String>> rows = safeRows(input);
			if (rows.size() != 1) {
				throw new IllegalStateException(
						"Scalar composition requires exactly one row from each source; actual rows=" + rows.size());
			}
			for (String column : safeColumns(input)) {
				if (!columns.add(column) && row.containsKey(column)) {
					throw new IllegalStateException("Scalar composition has duplicate output column: " + column);
				}
				row.put(column, rows.get(0).get(column));
			}
		}
		if (hasText(policy.getCalculationExpression())) {
			applyCalculation(row, columns, policy.getCalculationExpression());
		}
		return ResultSetBO.builder().column(new ArrayList<>(columns)).data(List.of(normalizeRow(columns, row))).build();
	}

	private void applyCalculation(Map<String, String> row, Set<String> columns, String calculationExpression) {
		String[] assignment = calculationExpression.split("=", 2);
		if (assignment.length != 2 || !hasText(assignment[0]) || !hasText(assignment[1])) {
			throw new IllegalArgumentException("Scalar calculation must be an assignment");
		}
		String output = assignment[0].trim();
		String expression = assignment[1].trim();
		boolean absolute = expression.regionMatches(true, 0, "ABS(", 0, 4) && expression.endsWith(")");
		if (absolute) {
			expression = expression.substring(4, expression.length() - 1).trim();
		}
		String operator = expression.contains("-") ? "-" : expression.contains("+") ? "+" : null;
		if (operator == null) {
			throw new IllegalArgumentException("Only addition and subtraction are supported for scalar calculation");
		}
		String[] operands = expression.split("\\" + operator, 2);
		if (operands.length != 2) {
			throw new IllegalArgumentException("Invalid scalar calculation expression");
		}
		BigDecimal left = decimal(row.get(operands[0].trim()));
		BigDecimal right = decimal(row.get(operands[1].trim()));
		if (left == null || right == null) {
			throw new IllegalStateException("Scalar calculation operands must both be numeric result columns");
		}
		BigDecimal value = "+".equals(operator) ? left.add(right) : left.subtract(right);
		if (absolute) {
			value = value.abs();
		}
		columns.add(output);
		row.put(output, value.stripTrailingZeros().toPlainString());
	}

	private ResultSetBO copy(ResultSetBO input) {
		List<Map<String, String>> rows = safeRows(input).stream()
			.<Map<String, String>>map(row -> new LinkedHashMap<>(row))
			.toList();
		return ResultSetBO.builder()
			.column(new ArrayList<>(safeColumns(input)))
			.data(rows)
			.errorMsg(input.getErrorMsg())
			.build();
	}

	private Map<String, String> normalizeRow(Set<String> columns, Map<String, String> row) {
		Map<String, String> normalized = new LinkedHashMap<>();
		for (String column : columns) {
			normalized.put(column, row.get(column));
		}
		return normalized;
	}

	private List<String> safeColumns(ResultSetBO input) {
		return input.getColumn() == null ? List.of() : input.getColumn();
	}

	private List<Map<String, String>> safeRows(ResultSetBO input) {
		return input.getData() == null ? List.of() : input.getData();
	}

	private BigDecimal decimal(String value) {
		if (!hasText(value)) {
			return null;
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String required(String value, String field) {
		if (!hasText(value)) {
			throw new IllegalArgumentException(field + " is required for merge type");
		}
		return value;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
