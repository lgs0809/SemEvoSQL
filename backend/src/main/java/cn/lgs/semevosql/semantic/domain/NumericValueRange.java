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

import java.math.BigDecimal;

/** Explicit output-scale bounds. Display units never imply a range. */
public record NumericValueRange(BigDecimal minimum, BigDecimal maximum, boolean minimumInclusive,
        boolean maximumInclusive) {
    public NumericValueRange {
        if(minimum==null && maximum==null)throw new IllegalArgumentException("Numeric range requires a bound");
        if(minimum!=null && maximum!=null) {
            int comparison=minimum.compareTo(maximum);
            if(comparison>0 || (comparison==0 && (!minimumInclusive || !maximumInclusive)))
                throw new IllegalArgumentException("Numeric range is empty or reversed");
        }
    }
    public boolean contains(BigDecimal value) {
        if(minimum!=null) {
            int comparison=value.compareTo(minimum);
            if(comparison<0 || (comparison==0 && !minimumInclusive))return false;
        }
        if(maximum!=null) {
            int comparison=value.compareTo(maximum);
            if(comparison>0 || (comparison==0 && !maximumInclusive))return false;
        }
        return true;
    }
}
