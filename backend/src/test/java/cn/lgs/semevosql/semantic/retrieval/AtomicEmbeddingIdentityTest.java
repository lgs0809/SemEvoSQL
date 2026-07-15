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
package cn.lgs.semevosql.semantic.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

class AtomicEmbeddingIdentityTest {
    static final String MODEL = "synthetic-actual-model";
    static final String REVISION = "1".repeat(40);
    final List<String> texts = List.of("完整原文含义", "second");
    final List<float[]> vectors = List.of(new float[]{0.25f, 0.5f}, new float[]{-0.5f, 0.25f});

    @Test void checksumsMatchIndependentPythonUtf8AndLittleEndianFloat32Receipts() {
        // Independently calculated with hashlib.sha256 and struct.pack('<ff', .25, .5).
        assertEquals("ab2a21ab5e1262d555eea5678035e8fe3e76542c6b474cc04a16a39d4aa02645",
            AtomicEmbeddingIdentity.vectorSha(new float[]{0.25f, 0.5f}));
        assertEquals("b262577ea7f2d0fe756259446b75aeff5afab65373c0951f8e48505b18750eee",
            AtomicEmbeddingIdentity.textSha("whole input"));
    }

    @Test void completeAtomicResponsePreservesOriginalIndexAndFullProfileForEachStoredVector() throws Exception {
        var envelope = AtomicEmbeddingFixture.response(AtomicEmbeddingFixture.profile(MODEL, 2, REVISION), texts, vectors);
        var identity = AtomicEmbeddingIdentity.response(envelope, texts, vectors, MODEL, 2);
        assertEquals(1, JsonUtil.getObjectMapper().readTree(identity.proof(1)).path("inputs").get(0).path("index").asInt());
        assertTrue(AtomicEmbeddingIdentity.matchesStored(identity.proof(1), identity.profile(), texts.get(1), vectors.get(1)));
        assertFalse(AtomicEmbeddingIdentity.matchesStored(identity.proof(0), identity.profile(), texts.get(1), vectors.get(1)));
    }

    @Test void missingTamperedRevisionModelDimensionAndTokenPipelineCannotCertifyAProfile() {
        var envelope = AtomicEmbeddingFixture.profile(MODEL, 2, REVISION);
        for (String key : List.of("loaded_revision", "tokenizer_sha256", "preprocessor_sha256", "pipeline_sha256", "packages")) {
            var invalid = envelope.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("profile")).remove(key);
            assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.profile(invalid, MODEL, 2));
        }
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.profile(envelope, "other-model", 2));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.profile(envelope, MODEL, 3));
        var invalid = envelope.deepCopy(); invalid.put("profile_sha256", "d".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.profile(invalid, MODEL, 2));
    }

    @Test void profileAloneNeverBackfillsAnUnknownHistoricalInputOrVector() {
        var scalar = AtomicEmbeddingFixture.profile(MODEL, 2, REVISION);
        var profile = AtomicEmbeddingIdentity.profile(scalar, MODEL, 2);
        assertFalse(AtomicEmbeddingIdentity.matchesStored(scalar.toString(), profile, texts.get(0), vectors.get(0)));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.response(scalar, texts, vectors, MODEL, 2));
    }

    @Test void inputVectorOrderAndCardinalityMustMatchTheSameResponse() {
        var envelope = AtomicEmbeddingFixture.response(AtomicEmbeddingFixture.profile(MODEL, 2, REVISION), texts, vectors);
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.response(envelope,
            List.of("changed", "second"), vectors, MODEL, 2));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.response(envelope, texts,
            List.of(vectors.get(1), vectors.get(0)), MODEL, 2));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.response(envelope,
            List.of(texts.get(0)), List.of(vectors.get(0)), MODEL, 2));
        envelope.path("inputs").get(1).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("inputs").get(1)).put("index", 0);
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.response(envelope, texts, vectors, MODEL, 2));
        assertThrows(IllegalArgumentException.class, () -> AtomicEmbeddingIdentity.vectorSha(new float[]{Float.NaN}));
    }
}
