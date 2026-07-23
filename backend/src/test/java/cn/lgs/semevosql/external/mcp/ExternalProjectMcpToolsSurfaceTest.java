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
package cn.lgs.semevosql.external.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class ExternalProjectMcpToolsSurfaceTest {

    @Test
    void publicMcpSurfaceContainsOnlyStableQueryTools() {
        Set<String> toolNames = Arrays.stream(ExternalProjectMcpTools.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(Tool.class))
            .filter(annotation -> annotation != null)
            .map(Tool::name)
            .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder("query", "query_status");
        assertThat(toolNames).doesNotContain("search_semantics", "get_semantic_context", "validate_query_plan",
                "execute_query_plan", "get_query_result", "add_metric", "publish_semantic_version");
    }
}
