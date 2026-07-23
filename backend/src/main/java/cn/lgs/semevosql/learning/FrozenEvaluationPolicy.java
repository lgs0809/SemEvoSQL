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
package cn.lgs.semevosql.learning;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps explicitly isolated evaluation requests out of reusable learning assets. */
@Component
public class FrozenEvaluationPolicy {

    private Set<Long> projectIds = Set.of();

    @Value("${semevosql.evaluation.frozen-project-ids:}")
    public void setProjectIds(String ids) {
        projectIds = Arrays.stream(ids.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .map(Long::valueOf).peek(id -> {
                if (id <= 0) throw new IllegalArgumentException("Frozen evaluation project ids must be positive");
            }).collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowsLearning(Long projectId) {
        return projectId == null || !projectIds.contains(projectId);
    }
}
