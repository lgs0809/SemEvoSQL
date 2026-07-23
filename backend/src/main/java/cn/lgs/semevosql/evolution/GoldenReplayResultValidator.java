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
package cn.lgs.semevosql.evolution;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Business-result assertions for Golden Case expected_json. */
@Component
public class GoldenReplayResultValidator {

	public AssertionReport validate(Map<String, Object> expected, ResultSetBO actual, long latencyMs,
			Long estimatedRows) {
		return validate(expected, actual, latencyMs, estimatedRows, GoldenReplayMode.FIXTURE);
	}

	public AssertionReport validate(Map<String, Object> expected, ResultSetBO actual, long latencyMs,
			Long estimatedRows, GoldenReplayMode replayMode) {
		List<String> errors = new ArrayList<>();
		List<String> columns = actual == null || actual.getColumn() == null ? List.of() : actual.getColumn();
		List<Map<String, String>> rows = actual == null || actual.getData() == null ? List.of() : actual.getData();
		assertColumns(expected.get("expectedColumns"), columns, errors);
		assertTypes(expected.get("expectedTypes"), rows, errors);
		assertRowCount(expected, rows.size(), errors);
		assertNonEmpty(expected, rows, errors);
		assertNotNullColumns(expected.get("notNullColumns"), rows, errors);
		assertRanges(expected.get("rangeAssertions"), rows, errors);
		BigDecimal defaultTolerance = decimal(expected.get("numericTolerance"), BigDecimal.ZERO);
		if (replayMode == GoldenReplayMode.FIXTURE) {
			assertScalars(expected.get("scalarAssertions"), rows, defaultTolerance, errors);
			assertSets(expected.get("setAssertions"), rows, defaultTolerance, errors);
			assertRows(expected.get("orderedRows"), rows, true, defaultTolerance, errors);
			assertRows(expected.get("unorderedRows"), rows, false, defaultTolerance, errors);
		}
		else {
			assertLivePolicy(expected, errors);
		}
		long maxLatency = longValue(expected.get("maxLatencyMs"), -1);
		if (maxLatency >= 0 && latencyMs > maxLatency) {
			errors.add("Latency " + latencyMs + "ms exceeds maxLatencyMs " + maxLatency);
		}
		long maxEstimated = longValue(expected.get("maxEstimatedRows"), -1);
		if (maxEstimated >= 0 && estimatedRows != null && estimatedRows > maxEstimated) {
			errors.add("Estimated rows " + estimatedRows + " exceeds maxEstimatedRows " + maxEstimated);
		}
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("replayMode", replayMode.name());
		proof.put("columns", columns);
		proof.put("rowCount", rows.size());
		proof.put("latencyMs", latencyMs);
		proof.put("estimatedRows", estimatedRows);
		proof.put("nullResult", actual == null);
		proof.put("emptyResult", rows.isEmpty());
		proof.put("errors", List.copyOf(errors));
		return new AssertionReport(errors.isEmpty(), List.copyOf(errors), java.util.Collections.unmodifiableMap(proof));
	}

	private void assertLivePolicy(Map<String, Object> expected, List<String> errors) {
		for (String key : List.of("scalarAssertions", "setAssertions", "orderedRows", "unorderedRows")) {
			Object value = expected.get(key);
			if (value instanceof Map<?, ?> map && !map.isEmpty() || value instanceof List<?> list && !list.isEmpty()) {
				errors.add("LIVE replay does not allow strict assertion " + key);
			}
		}
	}

	private void assertNonEmpty(Map<String, Object> expected, List<Map<String, String>> rows, List<String> errors) {
		if (Boolean.TRUE.equals(expected.get("requireNonEmpty")) && rows.isEmpty()) {
			errors.add("LIVE invariant requireNonEmpty failed");
		}
	}

	private void assertNotNullColumns(Object expectation, List<Map<String, String>> rows, List<String> errors) {
		for (String column : strings(expectation)) {
			for (int index = 0; index < rows.size(); index++) {
				if (rows.get(index).get(column) == null) {
					errors.add("Column " + column + " is null at row " + index);
					break;
				}
			}
		}
	}

	private void assertRanges(Object expectation, List<Map<String, String>> rows, List<String> errors) {
		if (!(expectation instanceof Map<?, ?> assertions)) {
			return;
		}
		assertions.forEach((columnValue, rangeValue) -> {
			if (!(rangeValue instanceof Map<?, ?> range)) {
				errors.add("Invalid range assertion for " + columnValue);
				return;
			}
			String column = Objects.toString(columnValue);
			BigDecimal min = decimalOrNull(range.get("min"));
			BigDecimal max = decimalOrNull(range.get("max"));
			for (int index = 0; index < rows.size(); index++) {
				String raw = rows.get(index).get(column);
				if (raw == null) {
					continue;
				}
				BigDecimal actual;
				try {
					actual = new BigDecimal(raw);
				}
				catch (NumberFormatException ex) {
					errors.add("Range assertion requires numeric column " + column + " at row " + index);
					break;
				}
				if (min != null && actual.compareTo(min) < 0) {
					errors.add("Column " + column + " is below min " + min + " at row " + index);
					break;
				}
				if (max != null && actual.compareTo(max) > 0) {
					errors.add("Column " + column + " exceeds max " + max + " at row " + index);
					break;
				}
			}
		});
	}

	private BigDecimal decimalOrNull(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return new BigDecimal(Objects.toString(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private void assertColumns(Object expectation, List<String> columns, List<String> errors) {
		List<String> expected = strings(expectation);
		if (!expected.isEmpty() && !expected.equals(columns)) {
			errors.add("Expected columns " + expected + " but got " + columns);
		}
	}

	private void assertTypes(Object expectation, List<Map<String, String>> rows, List<String> errors) {
		if (!(expectation instanceof Map<?, ?> types) || rows.isEmpty()) {
			return;
		}
		Map<String, String> sample = rows.stream()
			.filter(row -> row.values().stream().anyMatch(Objects::nonNull))
			.findFirst()
			.orElse(rows.get(0));
		types.forEach((column, type) -> {
			String value = sample.get(Objects.toString(column));
			if (value != null && !matchesType(value, Objects.toString(type))) {
				errors.add("Column " + column + " value does not match expected type " + type);
			}
		});
	}

	private void assertRowCount(Map<String, Object> expected, int actual, List<String> errors) {
		long min = longValue(expected.get("rowCountMin"), -1);
		long max = longValue(expected.get("rowCountMax"), -1);
		if (min >= 0 && actual < min) {
			errors.add("Row count " + actual + " is below rowCountMin " + min);
		}
		if (max >= 0 && actual > max) {
			errors.add("Row count " + actual + " exceeds rowCountMax " + max);
		}
	}

	private void assertScalars(Object expectation, List<Map<String, String>> rows, BigDecimal defaultTolerance,
			List<String> errors) {
		if (expectation == null) {
			return;
		}
		if (rows.isEmpty()) {
			errors.add("Scalar assertions require a non-empty result");
			return;
		}
		if (expectation instanceof Map<?, ?> assertions) {
			assertions.forEach((column, expected) -> compare(Objects.toString(expected),
					rows.get(0).get(Objects.toString(column)), defaultTolerance, "scalar " + column, errors));
			return;
		}
		if (expectation instanceof List<?> assertions) {
			for (Object item : assertions) {
				if (!(item instanceof Map<?, ?> assertion)) {
					errors.add("Invalid scalar assertion: " + item);
					continue;
				}
				String column = Objects.toString(assertion.get("column"), "");
				int row = (int) longValue(assertion.get("row"), 0);
				if (row < 0 || row >= rows.size()) {
					errors.add("Scalar assertion row is out of range: " + row);
					continue;
				}
				compare(Objects.toString(assertion.get("value"), null), rows.get(row).get(column),
						decimal(assertion.get("tolerance"), defaultTolerance), "scalar " + column + " row " + row,
						errors);
			}
		}
	}

	private void assertSets(Object expectation, List<Map<String, String>> rows, BigDecimal tolerance,
			List<String> errors) {
		if (!(expectation instanceof Map<?, ?> assertions)) {
			return;
		}
		assertions.forEach((column, expectedValues) -> {
			List<String> expected = strings(expectedValues).stream().sorted().toList();
			List<String> actual = rows.stream()
				.map(row -> row.get(Objects.toString(column)))
				.sorted(Comparator.nullsFirst(String::compareTo))
				.toList();
			if (!listEquals(expected, actual, tolerance)) {
				errors.add("Set assertion failed for " + column + ": expected=" + expected + ", actual=" + actual);
			}
		});
	}

	private void assertRows(Object expectation, List<Map<String, String>> actual, boolean ordered, BigDecimal tolerance,
			List<String> errors) {
		List<Map<String, String>> expected = rowMaps(expectation);
		if (expected.isEmpty() && !(expectation instanceof List<?>)) {
			return;
		}
		List<Map<String, String>> actualCopy = actual.stream()
			.<Map<String, String>>map(row -> new LinkedHashMap<>(row))
			.toList();
		if (ordered) {
			if (!rowsEqual(expected, actualCopy, tolerance)) {
				errors.add("orderedRows assertion failed");
			}
			return;
		}
		List<Map<String, String>> remaining = new ArrayList<>(actualCopy);
		for (Map<String, String> expectedRow : expected) {
			int found = -1;
			for (int index = 0; index < remaining.size(); index++) {
				if (rowEquals(expectedRow, remaining.get(index), tolerance)) {
					found = index;
					break;
				}
			}
			if (found < 0) {
				errors.add("unorderedRows is missing expected row " + expectedRow);
			}
			else {
				remaining.remove(found);
			}
		}
		if (!remaining.isEmpty()) {
			errors.add("unorderedRows contains unexpected rows: " + remaining);
		}
	}

	private boolean rowsEqual(List<Map<String, String>> expected, List<Map<String, String>> actual,
			BigDecimal tolerance) {
		if (expected.size() != actual.size()) {
			return false;
		}
		for (int index = 0; index < expected.size(); index++) {
			if (!rowEquals(expected.get(index), actual.get(index), tolerance)) {
				return false;
			}
		}
		return true;
	}

	private boolean rowEquals(Map<String, String> expected, Map<String, String> actual, BigDecimal tolerance) {
		if (!new LinkedHashSet<>(expected.keySet()).equals(new LinkedHashSet<>(actual.keySet()))) {
			return false;
		}
		for (String column : expected.keySet()) {
			if (!valueEquals(expected.get(column), actual.get(column), tolerance)) {
				return false;
			}
		}
		return true;
	}

	private boolean listEquals(List<String> expected, List<String> actual, BigDecimal tolerance) {
		if (expected.size() != actual.size()) {
			return false;
		}
		for (int index = 0; index < expected.size(); index++) {
			if (!valueEquals(expected.get(index), actual.get(index), tolerance)) {
				return false;
			}
		}
		return true;
	}

	private void compare(String expected, String actual, BigDecimal tolerance, String label, List<String> errors) {
		if (!valueEquals(expected, actual, tolerance)) {
			errors.add(label + " expected=" + expected + ", actual=" + actual + ", tolerance=" + tolerance);
		}
	}

	private boolean valueEquals(String expected, String actual, BigDecimal tolerance) {
		if (expected == null || "null".equalsIgnoreCase(expected)) {
			return actual == null;
		}
		if (actual == null) {
			return false;
		}
		try {
			BigDecimal expectedNumber = new BigDecimal(expected);
			BigDecimal actualNumber = new BigDecimal(actual);
			return expectedNumber.subtract(actualNumber).abs().compareTo(tolerance.abs()) <= 0;
		}
		catch (NumberFormatException ex) {
			return Objects.equals(expected, actual);
		}
	}

	private boolean matchesType(String value, String expectedType) {
		return switch (expectedType.toUpperCase(java.util.Locale.ROOT)) {
			case "NUMBER", "DECIMAL", "INTEGER", "LONG", "DOUBLE" -> isDecimal(value);
			case "BOOLEAN" -> Set.of("true", "false", "0", "1").contains(value.toLowerCase(java.util.Locale.ROOT));
			case "NULL" -> value == null;
			case "STRING" -> true;
			default -> true;
		};
	}

	private boolean isDecimal(String value) {
		try {
			new BigDecimal(value);
			return true;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private List<String> strings(Object value) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		return values.stream().map(item -> item == null ? null : Objects.toString(item)).toList();
	}

	private List<Map<String, String>> rowMaps(Object value) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		List<Map<String, String>> result = new ArrayList<>();
		for (Object item : values) {
			if (!(item instanceof Map<?, ?> row)) {
				continue;
			}
			Map<String, String> normalized = new LinkedHashMap<>();
			row.forEach(
					(key, cell) -> normalized.put(Objects.toString(key), cell == null ? null : Objects.toString(cell)));
			result.add(java.util.Collections.unmodifiableMap(normalized));
		}
		return List.copyOf(result);
	}

	private BigDecimal decimal(Object value, BigDecimal fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return new BigDecimal(Objects.toString(value));
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private long longValue(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? fallback : Long.parseLong(Objects.toString(value));
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	public record AssertionReport(boolean passed, List<String> errors, Map<String, Object> proof) {
	}

}
