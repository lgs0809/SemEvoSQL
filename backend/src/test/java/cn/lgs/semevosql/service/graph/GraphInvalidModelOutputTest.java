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
package cn.lgs.semevosql.service.graph;

import static org.assertj.core.api.Assertions.*;
import cn.lgs.semevosql.exception.ModelOutputInvalidException;
import org.junit.jupiter.api.Test;

class GraphInvalidModelOutputTest {
    @Test
    void invalidOutputRetainsItsPublicCategoryThroughGraphWrappers() {
        var invalid = new ModelOutputInvalidException("private model text", new IllegalArgumentException("from POST https://private.invalid"));
        var wrapped = new RuntimeException("graph wrapper", invalid);
        assertThat(GraphFailureClassifier.errorCode(wrapped)).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(GraphFailureClassifier.publicMessage(wrapped)).isEqualTo("模型返回结果格式无效，请重试。");
        assertThat(GraphFailureClassifier.recoverableModelFailure(wrapped)).isFalse();
    }
}
