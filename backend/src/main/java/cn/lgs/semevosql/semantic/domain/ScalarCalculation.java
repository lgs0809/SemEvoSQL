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

import java.util.Set;
import java.util.regex.Pattern;

/** The existing, bounded scalar arithmetic contract, retained in the frozen execution plan. */
public record ScalarCalculation(String outputCode, String leftMetricCode, String operator,
        String rightMetricCode, boolean absolute) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BINARY = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*([+-])\\s*([A-Za-z_][A-Za-z0-9_]*)");

    public ScalarCalculation {
        if (outputCode == null || !IDENTIFIER.matcher(outputCode).matches()
                || leftMetricCode == null || !IDENTIFIER.matcher(leftMetricCode).matches()
                || rightMetricCode == null || !IDENTIFIER.matcher(rightMetricCode).matches()
                || !("+".equals(operator) || "-".equals(operator))) {
            throw new IllegalArgumentException("Invalid bounded scalar calculation");
        }
    }

    public static ScalarCalculation parse(String calculationExpression, Set<String> metricCodes) {
        String normalized = calculationExpression == null ? "" : calculationExpression.replaceAll("\\s+", "");
        String[] assignment = normalized.split("=", 2);
        if (assignment.length != 2 || !IDENTIFIER.matcher(assignment[0]).matches()) {
            throw new IllegalArgumentException("resultComposition.calculationExpression must assign a valid output alias");
        }
        if (metricCodes.contains(assignment[0])) {
            throw new IllegalArgumentException("resultComposition output alias must not overwrite a selected metric");
        }
        String expression = assignment[1];
        boolean absolute = expression.regionMatches(true, 0, "ABS(", 0, 4) && expression.endsWith(")");
        if (absolute) expression = expression.substring(4, expression.length() - 1);
        var matcher = BINARY.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("resultComposition.calculationExpression supports only one binary + or - expression");
        }
        if (!metricCodes.contains(matcher.group(1)) || !metricCodes.contains(matcher.group(3))) {
            throw new IllegalArgumentException("resultComposition.calculationExpression may reference only selected metric codes");
        }
        return new ScalarCalculation(assignment[0], matcher.group(1), matcher.group(2), matcher.group(3), absolute);
    }
}
