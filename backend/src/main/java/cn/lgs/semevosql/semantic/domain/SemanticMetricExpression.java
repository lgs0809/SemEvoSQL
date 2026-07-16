/*
 * Copyright 2026 the original author or authors.
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

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One rendering contract for row aggregates and complete controlled metric expressions. */
public final class SemanticMetricExpression {
    public static String render(String expression,String aggregation) {
        if(expression==null || expression.isBlank())throw new IllegalArgumentException("Metric expression is required");
        String mode=aggregation==null?"":aggregation.trim().toUpperCase(Locale.ROOT);
        // EXPRESSION is protocol metadata, not an executable SQL function. Its AST already contains all aggregation.
        if(mode.isEmpty() || mode.equals("NONE") || mode.equals("EXPRESSION"))return expression;
        var aggregate=new AtomicBoolean();
        SQLUtils.toSQLExpr(expression,DbType.mysql).accept(new SQLASTVisitorAdapter() {
            @Override public boolean visit(SQLAggregateExpr node){aggregate.set(true);return true;}
        });
        if(aggregate.get())return expression;
        return mode.equals("COUNT_DISTINCT")?"COUNT(DISTINCT "+expression+")":mode+"("+expression+")";
    }
    private SemanticMetricExpression() {}
}
