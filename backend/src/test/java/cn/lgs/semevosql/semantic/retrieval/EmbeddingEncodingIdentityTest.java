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
package cn.lgs.semevosql.semantic.retrieval;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmbeddingEncodingIdentityTest {
    @Test void equivalentConfigurationIsStableAcrossMapOrderAndRuntimeWrapperChanges() {
        var attributes = EmbeddingEncodingIdentity.attributes("openai-compatible", "qwen", "http://local", "/v1/embeddings", 1024);
        var reordered = new TreeMap<String, Object>(Comparator.reverseOrder()); reordered.putAll(attributes);
        var first = EmbeddingEncodingIdentity.configured("openai-compatible:qwen", attributes);
        assertThat(first).isEqualTo(EmbeddingEncodingIdentity.configured("openai-compatible:qwen", reordered));
        assertThat(first.dimensions()).isEqualTo(1024);
        assertThat(attributes).doesNotContainKeys("password", "apiKey", "implementation");
    }
    @Test void actualEncodingContractChangesInvalidateTheIndex() {
        var base = EmbeddingEncodingIdentity.attributes("p", "qwen", "http://one", "/embeddings", 1024);
        var expected = EmbeddingEncodingIdentity.configured("p:qwen", base);
        for (var change : List.of(Map.entry("baseUrl", (Object) "http://two"), Map.entry("embeddingDimensions", (Object) 2048),
                Map.entry("modelName", (Object) "new"), Map.entry("embeddingsPath", (Object) "/new"))) {
            var altered = new HashMap<String, Object>(base); altered.put(change.getKey(), change.getValue());
            assertThat(EmbeddingEncodingIdentity.configured("p:qwen", altered).version()).isNotEqualTo(expected.version());
        }
    }
}
