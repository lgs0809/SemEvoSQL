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

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Authored retrieval aids; they never change a formula, grant permission or assert equivalence. */
public record RetrievalHints(List<String> queryExpressions,List<String> queryContexts) {
    private static final ObjectMapper JSON=JsonUtil.getObjectMapper().copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public RetrievalHints {
        queryExpressions=validate(queryExpressions);
        queryContexts=validate(queryContexts);
    }
    private static List<String> validate(List<String> values) {
        if(values==null || values.size()>16)throw new IllegalArgumentException("Retrieval hints require arrays of at most 16 strings");
        if(values.stream().anyMatch(v->v==null || v.isBlank() || v.length()>300 || v.chars().anyMatch(Character::isISOControl))
                || new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException("Invalid or repeated retrieval hint");
        return List.copyOf(values);
    }
    public static RetrievalHints decode(String json) {
        if(json==null)return null;
        try {return JSON.readValue(json,RetrievalHints.class);}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid retrieval hint representation",invalid);}
    }
    public static String encode(RetrievalHints value) {
        if(value==null)return null;
        try {return JsonUtil.getObjectMapper().writeValueAsString(value);}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid retrieval hint representation",invalid);}
    }
}
