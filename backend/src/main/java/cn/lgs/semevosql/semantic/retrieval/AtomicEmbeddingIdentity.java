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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Same-response encoder/input/vector certification. Configuration and later readiness are not certification. */
public record AtomicEmbeddingIdentity(Profile profile, List<Input> inputs) {
    public static final String METADATA_KEY = "semevosql.encoding_identity";
    private static final String SCHEMA = "qwen-encoding-v1";
    private static final CanonicalJson JSON = new CanonicalJson();

    public AtomicEmbeddingIdentity { inputs = List.copyOf(inputs); }

    public record Profile(String sha256, String json, String model, int dimension) { }
    public record Input(int responseIndex, String inputSha256, String vectorSha256) { }

    public static Profile profile(JsonNode envelope, String expectedModel, int expectedDimension) {
        if (!SCHEMA.equals(envelope.path("schema").asText())) invalid();
        var profile = envelope.path("profile");
        String claimed = sha(envelope, "profile_sha256");
        if (!profile.isObject() || !claimed.equals(JSON.hash(profile))) invalid();
        String model = text(profile, "model");
        if (!model.equals(expectedModel) || !text(profile, "loaded_revision").matches("[0-9a-f]{40}")) invalid();
        for (String key : List.of("tokenizer_sha256", "preprocessor_sha256", "pipeline_sha256")) sha(profile, key);
        if (!"document".equals(text(profile, "input_type")) || positive(profile, "dimensions") != expectedDimension
                || positive(profile, "max_tokens") < 1 || positive(profile, "cpu_threads") < 1
                || positive(profile, "interop_threads") < 1 || !profile.path("normalize").isBoolean()
                || !"reject-over-limit".equals(text(profile, "truncation"))
                || !"prefix-float32-renormalize-if-enabled-v1".equals(text(profile, "resize"))) invalid();
        for (String key : List.of("device", "parameter_dtype", "cpu_attention")) text(profile, key);
        for (String key : List.of("torch", "transformers", "sentence-transformers", "tokenizers"))
            text(profile.path("packages"), key);
        ObjectNode scalar = JsonUtil.getObjectMapper().createObjectNode();
        scalar.put("schema", SCHEMA); scalar.put("profile_sha256", claimed); scalar.set("profile", profile);
        return new Profile(claimed, JSON.write(scalar), model, expectedDimension);
    }

    public static AtomicEmbeddingIdentity response(JsonNode envelope, List<String> texts, List<float[]> vectors,
            String model, int dimension) {
        var profile = profile(envelope, model, dimension);
        var array = envelope.path("inputs");
        if (!array.isArray() || array.size() != texts.size() || texts.size() != vectors.size()) invalid();
        var inputs = new ArrayList<Input>();
        for (int i = 0; i < texts.size(); i++) {
            var item = array.get(i);
            if (!item.path("index").isIntegralNumber() || item.path("index").asInt(-1) != i) invalid();
            var input = input(item);
            if (!input.inputSha256().equals(textSha(texts.get(i))) || vectors.get(i).length != dimension
                    || !input.vectorSha256().equals(vectorSha(vectors.get(i)))) invalid();
            inputs.add(input);
        }
        return new AtomicEmbeddingIdentity(profile, inputs);
    }

    /** Persist only this response entry, with its original batch index and complete profile. */
    public String proof(int index) {
        try {
            ObjectNode envelope = (ObjectNode) JsonUtil.getObjectMapper().readTree(profile.json());
            Input input = inputs.get(index);
            var item = envelope.putArray("inputs").addObject();
            item.put("index", input.responseIndex()); item.put("input_sha256", input.inputSha256());
            item.put("vector_sha256", input.vectorSha256());
            return JSON.write(envelope);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    public static boolean matchesStored(String proof, Profile expected, String text, float[] vector) {
        try {
            var envelope = JsonUtil.getObjectMapper().readTree(proof);
            var actual = profile(envelope, expected.model(), expected.dimension());
            var array = envelope.path("inputs");
            if (!actual.equals(expected) || !array.isArray() || array.size() != 1) return false;
            var input = input(array.get(0));
            return input.inputSha256().equals(textSha(text)) && vector.length == expected.dimension()
                && input.vectorSha256().equals(vectorSha(vector));
        } catch (RuntimeException | java.io.IOException unknown) { return false; }
    }

    private static Input input(JsonNode item) {
        if (!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt()
                || item.path("index").asInt(-1) < 0) invalid();
        return new Input(item.path("index").asInt(), sha(item, "input_sha256"), sha(item, "vector_sha256"));
    }

    public static String textSha(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    public static String vectorSha(float[] vector) {
        var bytes = ByteBuffer.allocate(Math.multiplyExact(vector.length, Float.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            if (!Float.isFinite(value)) invalid();
            bytes.putFloat(value);
        }
        return hash(bytes.array());
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) invalid();
        return value.asText();
    }
    private static String sha(JsonNode node, String key) {
        String value = text(node, key); if (!value.matches("[0-9a-f]{64}")) invalid(); return value;
    }
    private static int positive(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) invalid();
        return value.asInt();
    }
    private static void invalid() { throw new IllegalArgumentException("Uncertified atomic embedding identity"); }
}
