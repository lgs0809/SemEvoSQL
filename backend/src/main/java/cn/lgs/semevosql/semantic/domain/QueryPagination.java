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
package cn.lgs.semevosql.semantic.domain;

/** Shared request contract for both SQL routes; page size does not bound database work. */
public final class QueryPagination {
    private QueryPagination() { }
    public static long offset(SemanticBlueprint plan) { return plan.getOffset()==null?0L:plan.getOffset(); }
    public static void validate(SemanticBlueprint plan) {
        long offset=offset(plan);
        if(offset<0) throw new IllegalArgumentException("Query offset must be nonnegative");
        try { Math.addExact(offset,plan.getLimit()==null?100:plan.getLimit().longValue()); }
        catch(ArithmeticException overflow) {throw new IllegalArgumentException("Query pagination exceeds the supported integer range",overflow);}
        if(offset>0) {
            if(plan.getMergePlan()!=null || plan.getSourceSubPlans().size()>1 || plan.getModels().stream()
                    .map(SemanticBlueprint.ModelSelection::getDatasourceId).filter(java.util.Objects::nonNull).distinct().count()>1)
                throw new IllegalArgumentException("Cross-source global OFFSET is not supported");
            if(plan.getOrderBy().isEmpty())
                throw new IllegalArgumentException("Pagination requires explicit ordering");
        }
    }
}
