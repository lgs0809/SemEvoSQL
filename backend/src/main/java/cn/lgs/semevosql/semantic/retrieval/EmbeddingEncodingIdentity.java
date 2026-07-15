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

import cn.lgs.semevosql.common.json.CanonicalJson;
import java.util.*;

/** Stable encoding identity shared by model documents and query cases; JVM proxy names are not model revisions. */
public record EmbeddingEncodingIdentity(String model, String version, Integer dimensions) {
    public static EmbeddingEncodingIdentity configured(String model, Map<String, Object> attributes) {
        var material = new LinkedHashMap<String, Object>();
        material.put("encodingContract", "configured-embedding-v1");
        material.put("model", model);
        material.put("attributes", attributes);
        var dimension = attributes.get("embeddingDimensions");
        return new EmbeddingEncodingIdentity(model, new CanonicalJson().hash(material),
                dimension instanceof Number value ? value.intValue() : null);
    }

    public static Map<String, Object> attributes(String provider, String model, String baseUrl, String path, Integer dimensions) {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("provider", Objects.toString(provider, ""));
        attributes.put("modelName", Objects.toString(model, ""));
        attributes.put("baseUrl", Objects.toString(baseUrl, ""));
        attributes.put("embeddingsPath", Objects.toString(path, ""));
        if (dimensions != null) attributes.put("embeddingDimensions", dimensions);
        return Collections.unmodifiableMap(attributes);
    }
}
