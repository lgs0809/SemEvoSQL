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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergePolicy;
import cn.lgs.semevosql.multisource.MultiSourcePolicySnapshot.MergeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiSourceMergeEngineTest {

	private final MultiSourceMergeEngine engine = new MultiSourceMergeEngine();

	@Test
	void keyedMergeFailsClosedWhenRequiredInputColumnIsMissing() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> engine.merge(policy(), List.of(
						result(List.of("left_metric"), row("left_metric", "3")),
						result(List.of("right_metric"), row("right_metric", "480")))));

		assertTrue(error.getMessage().contains("missing required key column"));
	}

	@Test
	void nullMergeKeysNeverMatchEachOther() {
		ResultSetBO merged = engine.merge(policy(), List.of(
				result(List.of("left_key", "left_metric"), row("left_key", null, "left_metric", "3")),
				result(List.of("right_key", "right_metric"), row("right_key", null, "right_metric", "480"))));

		assertEquals(0, merged.getData().size());
	}

	@Test
	void scalarCompositionCombinesIndependentAggregatesAndCalculatesAbsoluteDifference() {
		MergePolicy scalar = MergePolicy.builder()
			.policyCode("runtime_scalar_composition")
			.mergeType(MergeType.SCALAR_COMPOSITION)
			.maxRows(1)
			.calculationExpression("difference=ABS(left_metric-right_metric)")
			.build();

		ResultSetBO merged = engine.merge(scalar, List.of(result(List.of("left_metric"), row("left_metric", "3")),
				result(List.of("right_metric"), row("right_metric", "5"))));

		assertEquals(List.of("left_metric", "right_metric", "difference"), merged.getColumn());
		assertEquals(1, merged.getData().size());
		assertEquals("3", merged.getData().get(0).get("left_metric"));
		assertEquals("5", merged.getData().get(0).get("right_metric"));
		assertEquals("2", merged.getData().get(0).get("difference"));
	}

	@Test
	void scalarCompositionFailsClosedWhenOneSourceIsNotScalar() {
		MergePolicy scalar = MergePolicy.builder()
			.policyCode("runtime_scalar_composition")
			.mergeType(MergeType.SCALAR_COMPOSITION)
			.maxRows(1)
			.build();
		ResultSetBO nonScalar = ResultSetBO.builder()
			.column(List.of("metric"))
			.data(List.of(row("metric", "1"), row("metric", "2")))
			.build();

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> engine.merge(scalar, List.of(result(List.of("left_metric"), row("left_metric", "3")), nonScalar)));

		assertTrue(error.getMessage().contains("requires exactly one row"));
	}

	private MergePolicy policy() {
		return MergePolicy.builder()
			.policyCode("governed_lookup")
			.mergeType(MergeType.LOOKUP_ENRICHMENT)
			.leftInputKey("left_key")
			.rightInputKey("right_key")
			.outputKey("join_key")
			.nullPolicy("DROP")
			.duplicatePolicy("KEEP_ALL")
			.maxRows(1000)
			.build();
	}

	private ResultSetBO result(List<String> columns, Map<String, String> row) {
		return ResultSetBO.builder().column(columns).data(List.of(row)).build();
	}

	private Map<String, String> row(String... entries) {
		Map<String, String> row = new LinkedHashMap<>();
		for (int index = 0; index < entries.length; index += 2) {
			row.put(entries[index], entries[index + 1]);
		}
		return row;
	}

}
