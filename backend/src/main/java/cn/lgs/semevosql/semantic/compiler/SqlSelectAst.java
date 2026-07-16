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

import java.util.List;

/**
 * Structural SQL SELECT AST used between Semantic Blueprint and dialect rendering.
 *
 * <p>Expressions are already governance-validated before entering this object; the AST owns SQL
 * clause ordering and prevents the compiler from interleaving ad-hoc StringBuilder mutations.
 */
public record SqlSelectAst(List<String> projections, String fromAndJoins, List<String> predicates,
		List<String> groupBy, List<String> orderBy, int limit, long offset) {
    public SqlSelectAst(List<String> projections,String fromAndJoins,List<String> predicates,List<String> groupBy,List<String> orderBy,int limit) {
        this(projections,fromAndJoins,predicates,groupBy,orderBy,limit,0L);
    }

	public SqlSelectAst {
		projections = List.copyOf(projections == null ? List.of() : projections);
		predicates = List.copyOf(predicates == null ? List.of() : predicates);
		groupBy = List.copyOf(groupBy == null ? List.of() : groupBy);
		orderBy = List.copyOf(orderBy == null ? List.of() : orderBy);
		if (projections.isEmpty()) {
			throw new IllegalArgumentException("SQL AST requires at least one projection");
		}
		if (fromAndJoins == null || fromAndJoins.isBlank()) {
			throw new IllegalArgumentException("SQL AST requires a FROM relation");
		}
        limit = Math.max(1, limit);
        if(offset<0) throw new IllegalArgumentException("SQL offset must be nonnegative");
        Math.addExact(offset,limit);
	}

	public String render() {
		StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", projections)).append(" FROM ")
			.append(fromAndJoins);
		if (!predicates.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", predicates));
		}
		if (!groupBy.isEmpty()) {
			sql.append(" GROUP BY ").append(String.join(", ", groupBy));
		}
		if (!orderBy.isEmpty()) {
			sql.append(" ORDER BY ").append(String.join(", ", orderBy));
		}
		sql.append(" LIMIT ").append(limit);
        if(offset>0) sql.append(" OFFSET ").append(offset);
        return sql.toString();
	}
}
