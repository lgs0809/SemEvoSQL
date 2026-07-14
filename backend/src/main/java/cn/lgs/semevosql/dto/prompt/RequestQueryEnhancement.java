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
package cn.lgs.semevosql.dto.prompt;

import java.util.List;

/** Request understanding only; no semantic asset creation or execution authorization. */
public record RequestQueryEnhancement(String status, String canonicalQuery, List<String> expandedQueries,
        List<Long> contextTurns, String question, List<String> options) {
    public boolean correction() { return "CONFIRM_CORRECTION".equals(status); }

    public boolean ready() { return "READY".equals(status); }

    public QueryEnhanceOutputDTO queryOutput() {
        if (!ready()) throw new IllegalStateException("Context must be confirmed before using the query");
        var output = new QueryEnhanceOutputDTO();
        output.setCanonicalQuery(canonicalQuery);
        output.setExpandedQueries(expandedQueries);
        return output;
    }
}
