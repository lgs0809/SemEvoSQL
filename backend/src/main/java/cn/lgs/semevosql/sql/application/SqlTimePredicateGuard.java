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

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Recognizes predicates, not strings/comments/formatting that happen to mention a time column. */
final class SqlTimePredicateGuard {
    static boolean hasTimeFilter(String sql, Collection<String> columns) {
        if (columns == null || columns.isEmpty()) return false;
        Set<String> names = columns.stream().filter(java.util.Objects::nonNull)
                .map(SqlTimePredicateGuard::name).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        for (DbType dialect : new DbType[]{DbType.postgresql, DbType.mysql}) {
            try {
                var statements = SQLUtils.parseStatements(sql, dialect);
                boolean[] found = {false};
                for (var statement : statements) statement.accept(new SQLASTVisitorAdapter() {
                    @Override public boolean visit(SQLSelectQueryBlock block) {
                        found[0] |= constrains(block.getWhere(), names);
                        return true;
                    }
                });
                return found[0];
            } catch (RuntimeException invalidDialect) { /* The SQL execution guard separately rejects invalid SQL. */ }
        }
        return false;
    }
    private static boolean constrains(SQLExpr expression, Set<String> names) {
        if (expression instanceof SQLBinaryOpExpr binary) {
            var op = binary.getOperator();
            if (op == SQLBinaryOperator.BooleanAnd)
                return constrains(binary.getLeft(), names) || constrains(binary.getRight(), names);
            if (op == SQLBinaryOperator.BooleanOr)
                return constrains(binary.getLeft(), names) && constrains(binary.getRight(), names);
            if (Set.of(SQLBinaryOperator.Equality, SQLBinaryOperator.GreaterThan, SQLBinaryOperator.GreaterThanOrEqual,
                    SQLBinaryOperator.LessThan, SQLBinaryOperator.LessThanOrEqual).contains(op))
                return column(binary.getLeft(), names) && value(binary.getRight())
                        || column(binary.getRight(), names) && value(binary.getLeft());
        }
        if (expression instanceof SQLBetweenExpr between)
            return !between.isNot() && column(between.getTestExpr(), names)
                    && value(between.getBeginExpr()) && value(between.getEndExpr());
        if (expression instanceof SQLInListExpr in)
            return !in.isNot() && column(in.getExpr(), names) && !in.getTargetList().isEmpty()
                    && in.getTargetList().stream().allMatch(SqlTimePredicateGuard::value);
        return false;
    }
    private static boolean value(SQLExpr expression) {
        if (expression instanceof SQLCastExpr cast) {
            // PostgreSQL's ::timestamp and standard CAST retain a constant boundary.
            // Do not treat a column, function, NULL or unrelated type conversion as a bound.
            String type = cast.getDataType() == null ? "" : cast.getDataType().getName().toLowerCase(Locale.ROOT);
            return Set.of("date", "timestamp", "timestamptz", "datetime").contains(type)
                    && value(cast.getExpr());
        }
        return expression != null && !(expression instanceof SQLNullExpr)
                && (expression instanceof SQLLiteralExpr || expression instanceof SQLVariantRefExpr);
    }
    private static boolean column(SQLExpr expression, Set<String> names) {
        return expression instanceof SQLIdentifierExpr id && names.contains(name(id.getName()))
                || expression instanceof SQLPropertyExpr property && names.contains(name(property.getName()));
    }
    private static String name(String value) {
        String normalized = value.replace("\"", "").replace("`", "").trim().toLowerCase(Locale.ROOT);
        return normalized.substring(normalized.lastIndexOf('.') + 1);
    }
}
