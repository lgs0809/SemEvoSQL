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
package cn.lgs.semevosql.learning;

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.run.RuntimeMutationScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** Explicit loading of omitted historical details; no public event payload contains the frozen SQL. */
@RestController
@RequestMapping("/api/semevosql/runs/{runId}/case-history")
@RequiredArgsConstructor
public class QueryCaseHistoryController {
    private final QueryCaseHistoryService history;
    private final OperatorContext.Resolver operatorResolver;
    private final RuntimeMutationScopeService scope;

    @GetMapping("/{snapshotId}/{caseId}")
    public JsonNode details(@PathVariable String runId,@PathVariable String snapshotId,@PathVariable String caseId,
            @RequestHeader HttpHeaders headers,Principal principal) {
        var operator=operatorResolver.resolve(headers,principal,"history-read:"+runId);
        scope.requireRun(runId,operator);
        return history.detailsForRun(runId,snapshotId,caseId,operator.operator());
    }
}
