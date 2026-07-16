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
package cn.lgs.semevosql.workflow.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class SqlExecutionLineage {

	private static final String STEP_PREFIX = "step_";

	private SqlExecutionLineage() {
	}

	static Map<String, String> append(Map<String, String> existing, int step, String sql) {
		if (step < 1) {
			throw new IllegalArgumentException("Execution step must be positive");
		}
		Map<String, String> updated = new LinkedHashMap<>();
		if (existing != null) {
			updated.putAll(existing);
		}
		updated.remove(String.valueOf(step));
		updated.put(stepKey(step), sql);
		return Collections.unmodifiableMap(updated);
	}

	static Map<String, String> restore(Map<String, String> persisted, Map<String, String> existing, int nextStep,
			String sql) {
		if (persisted != null && !persisted.isEmpty()) {
			return Collections.unmodifiableMap(new LinkedHashMap<>(persisted));
		}
		return append(existing, Math.max(1, nextStep - 1), sql);
	}

	static String queryForStep(Map<String, String> lineage, int step) {
		if (lineage == null) return null;
		String canonical = lineage.get(stepKey(step));
		String legacy = lineage.get(String.valueOf(step));
		if (canonical != null && legacy != null && !canonical.equals(legacy)) {
			throw new IllegalStateException("Conflicting SQL execution lineage for step " + step);
		}
		return canonical != null ? canonical : legacy;
	}

	private static String stepKey(int step) {
		return STEP_PREFIX + step;
	}

}
