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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.semantic.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBindingApplicabilityTest {
    private SemanticCatalogSnapshot catalog(SemanticAssetStatus status) {
        return SemanticCatalogSnapshot.builder().metrics(List.of(SemanticCatalogSnapshot.Metric.builder()
            .metricCode("net_sales").businessName("净销售额").status(status).build())).build();
    }
    @Test void explicitPublicNameHasTheSameProtectionAgainstTextAndAssetPersonalNames() {
        assertTrue(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"一月净销售额","TEXT_DEFINITION","销售额","销售额"));
        assertTrue(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"一月净销售额","METRIC","ordered_amount","销售额"));
        assertFalse(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"一月销售额","TEXT_DEFINITION","销售额","销售额"));
    }
    @Test void requestForBothNamesRetainsTheSeparatePersonalMeasure() {
        assertFalse(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"比较销售额与净销售额","TEXT_DEFINITION","销售额","销售额"));
        assertFalse(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"比较净销售额与销售额","TEXT_DEFINITION","销售额","销售额"));
        assertTrue(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"比较一月净销售额与二月净销售额","TEXT_DEFINITION","销售额","销售额"));
    }
    @Test void disabledUnmentionedOrUnrelatedNamesCannotSuppressAConfirmedBinding() {
        assertFalse(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.DISABLED),"净销售额","TEXT_DEFINITION","销售额","销售额"));
        assertFalse(RuntimeBindingApplicability.shadowed(catalog(SemanticAssetStatus.ENABLED),"销售额及订单数","TEXT_DEFINITION","销售额","销售额"));
        assertFalse(RuntimeBindingApplicability.onlyInsideExplicitTerms("成本","成本",List.of("净销售额")));
    }
    @Test void blankUnicodeAndOverlappingOccurrencesFollowTheExistingNameNormalization() {
        assertFalse(RuntimeBindingApplicability.onlyInsideExplicitTerms(null,"销售额",List.of("净销售额")));
        assertFalse(RuntimeBindingApplicability.onlyInsideExplicitTerms("净销售额",null,List.of("净销售额")));
        assertTrue(RuntimeBindingApplicability.onlyInsideExplicitTerms("NET SALES TOTAL","sales",List.of("net sales total")));
        assertTrue(RuntimeBindingApplicability.onlyInsideExplicitTerms("aaaa","aa",List.of("aaa")));
        assertFalse(RuntimeBindingApplicability.onlyInsideExplicitTerms("aaaaandaa","aa",List.of("aaa")));
    }
}
