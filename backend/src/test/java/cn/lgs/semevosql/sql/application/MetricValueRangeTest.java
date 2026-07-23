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

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetricValueRangeTest {
    final SqlResultValidator validator=new SqlResultValidator();
    SemanticBlueprint plan(String unit,NumericValueRange range) {
        return SemanticBlueprint.builder().metrics(List.of(SemanticBlueprint.MetricSelection.builder()
            .modelCode("seed").metricCode("value").aggregation("EXPRESSION").expression("SUM(amount)")
            .unit(unit).numericRange(range).build())).build();
    }
    boolean valid(SemanticBlueprint plan,String value) {
        var row=new HashMap<String,String>();row.put("value",value);
        return validator.validate(ResultSetBO.builder().column(List.of("value")).data(List.of(row)).build(),plan,100).valid();
    }
    NumericValueRange closed(String maximum) {return new NumericValueRange(BigDecimal.ZERO,new BigDecimal(maximum),true,true);}
    @Test void displayUnitsNeverInventBoundsOrRejectNegativeNetAmounts() {
        for(String unit:List.of("%","percent","percentage","yuan")) {
            assertTrue(valid(plan(unit,null),"150"));assertTrue(valid(plan(unit,null),"-20"));
            assertTrue(valid(plan(unit,null),null));
            for(String invalid:List.of("NaN","Infinity","not-a-number"))assertFalse(valid(plan(unit,null),invalid));
        }
    }
    @Test void onlyExplicitBoundsControlOutputScaleAndBoundaryInclusiveness() {
        var percent=plan("%",closed("100"));
        assertTrue(valid(percent,"0"));assertTrue(valid(percent,"100"));
        assertFalse(valid(percent,"-1"));assertFalse(valid(percent,"101"));
        var fraction=plan("%",closed("1"));assertTrue(valid(fraction,"0.25"));assertFalse(valid(fraction,"1.01"));
        var open=plan("ratio",new NumericValueRange(BigDecimal.ZERO,BigDecimal.ONE,false,false));
        assertFalse(valid(open,"0"));assertFalse(valid(open,"1"));assertTrue(valid(open,"0.5"));
        var lower=plan("yuan",new NumericValueRange(new BigDecimal("-50"),null,true,true));
        assertTrue(valid(lower,"100000"));assertFalse(valid(lower,"-51"));
    }
    @Test void restoredOldRunKeepsItsFrozenRangeWhenNewDefinitionChanges() throws Exception {
        var old=plan("%",closed("100"));
        String frozen=JsonUtil.getObjectMapper().writeValueAsString(old);
        old.getMetrics().get(0).setNumericRange(closed("200"));
        var restored=JsonUtil.getObjectMapper().readValue(frozen,SemanticBlueprint.class);
        assertFalse(valid(restored,"150"));assertTrue(valid(old,"150"));
        assertThrows(IllegalArgumentException.class,()->new NumericValueRange(BigDecimal.ONE,BigDecimal.ZERO,true,true));
        assertThrows(IllegalArgumentException.class,()->new NumericValueRange(BigDecimal.ONE,BigDecimal.ONE,false,true));
    }
}
