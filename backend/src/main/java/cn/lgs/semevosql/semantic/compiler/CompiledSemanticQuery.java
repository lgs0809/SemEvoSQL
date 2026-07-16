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

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.List;

public record CompiledSemanticQuery(List<CompiledSourceQuery> sources, SemanticBlueprint.MergePlan mergePlan,
		SemanticBlueprint.ExpectedResultShape expectedResult, String compilerVersion) {

	public record CompiledSourceQuery(Integer datasourceId, SqlDialect dialect, String sql, List<Object> parameters,
			List<String> physicalTables, String resultShapeHash) {
	}

}
