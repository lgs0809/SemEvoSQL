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

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** Synthetic certificates for infrastructure tests, never real encoder quality evidence. */
public final class AtomicEmbeddingFixture {
    private AtomicEmbeddingFixture() { }
    public static ObjectNode profile(String model, int dimension, String revision) {
        ObjectNode p = JsonUtil.getObjectMapper().createObjectNode();
        p.put("model", model); p.put("loaded_revision", revision); p.put("tokenizer_sha256", "a".repeat(64));
        p.put("preprocessor_sha256", "b".repeat(64)); p.put("pipeline_sha256", "c".repeat(64));
        p.put("max_tokens", 262144); p.set("packages", JsonUtil.getObjectMapper().valueToTree(Map.of(
            "torch", "fixture", "transformers", "fixture", "sentence-transformers", "fixture", "tokenizers", "fixture")));
        p.put("device", "cpu"); p.put("parameter_dtype", "torch.bfloat16"); p.put("cpu_attention", "expanded-kv-fp32");
        p.put("cpu_threads", 8); p.put("interop_threads", 1); p.put("input_type", "document");
        p.put("dimensions", dimension); p.put("normalize", true); p.put("truncation", "reject-over-limit");
        p.put("resize", "prefix-float32-renormalize-if-enabled-v1");
        ObjectNode e = JsonUtil.getObjectMapper().createObjectNode();
        e.put("schema", "qwen-encoding-v1"); e.put("profile_sha256", new CanonicalJson().hash(p)); e.set("profile", p);
        return e;
    }
    public static ObjectNode response(ObjectNode scalar, List<String> texts, List<float[]> vectors) {
        ObjectNode envelope = scalar.deepCopy(); var array = envelope.putArray("inputs");
        for (int i = 0; i < texts.size(); i++) {
            var item = array.addObject(); item.put("index", i);
            item.put("input_sha256", AtomicEmbeddingIdentity.textSha(texts.get(i)));
            item.put("vector_sha256", AtomicEmbeddingIdentity.vectorSha(vectors.get(i)));
        }
        return envelope;
    }
}
