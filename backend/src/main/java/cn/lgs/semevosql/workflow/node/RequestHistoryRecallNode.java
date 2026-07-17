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
package cn.lgs.semevosql.workflow.node;

import cn.lgs.semevosql.learning.QueryCaseHistoryService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The complete enhanced request recalls historical request structure before Todo analysis. */
@Component
@RequiredArgsConstructor
public class RequestHistoryRecallNode implements NodeAction {
    private final QueryCaseHistoryService history;
    @Override public Map<String,Object> apply(OverAllState state) { return history.recallRequest(state); }
}
