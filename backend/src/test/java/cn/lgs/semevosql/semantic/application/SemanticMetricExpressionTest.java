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
package cn.lgs.semevosql.semantic.application;
import cn.lgs.semevosql.semantic.domain.SemanticMetricExpression;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SemanticMetricExpressionTest {
    @Test void completeProtocolExpressionsAreNotTreatedAsFunctionNames() {
        for(String expression:java.util.List.of("(SUM(amount) * 1.0 / NULLIF(2, 0))","SUM(amount / 2)","(SUM(amount) / NULLIF(COUNT(*), 0))"))
            assertEquals(expression,SemanticMetricExpression.render(expression,"EXPRESSION"));
    }
    @Test void aggregateDetectionUsesTheSqlAstAcrossParenthesesAndCaseExpressions() {
        for(String expression:java.util.List.of("(SUM(amount) / NULLIF(COUNT(*), 0))","CASE WHEN COUNT(*)=0 THEN NULL ELSE SUM(amount) END"))
            assertEquals(expression,SemanticMetricExpression.render(expression,"SUM"));
        assertEquals("SUM(amount)",SemanticMetricExpression.render("amount","SUM"));
        assertEquals("COUNT(DISTINCT id)",SemanticMetricExpression.render("id","COUNT_DISTINCT"));
    }
}
