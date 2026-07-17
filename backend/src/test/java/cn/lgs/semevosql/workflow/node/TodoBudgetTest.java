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

import static cn.lgs.semevosql.constant.Constant.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.review.*;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.task.*;
import cn.lgs.semevosql.service.graph.checkpoint.DurableGraphStateSerializer;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import org.junit.jupiter.api.Test;

class TodoBudgetTest {
    @Test void nextTodoKeepsWholeRunBudgetAndRoundTripsThroughDurableState() throws Exception {
        var tasks=mock(QueryTaskRepository.class);var runs=mock(QueryRunService.class);
        var node=new TodoBoundaryNode(tasks,runs,mock(RunExecutionFenceService.class));
        var first=new QueryTask("task-1",0,"一月金额",List.of(),QueryTask.TaskStatus.ACTIVE);
        var second=new QueryTask("task-2",1,"二月金额",List.of(),QueryTask.TaskStatus.PENDING);
        when(tasks.active("run")).thenReturn(Optional.of(first));when(tasks.nextRunnable("run")).thenReturn(Optional.of(second));
        var spent=new QueryRepairPolicy.RepairBudget(2,1,1,2,1,5);
        var original=new HashMap<String,Object>(Map.of(RUN_ID,"run",TODO_ENABLED,true,ACTIVE_QUERY,first.question(),
            TYPED_SEMANTIC_PLAN,SemanticBlueprint.builder().executable(true).build(),
            POST_EXECUTION_REVIEW_OUTPUT,PostExecutionReview.deterministicPass(List.of()),QUERY_REPAIR_BUDGET,spent));
        original.put(APPROVED_PLAN_RECOVERY,true);
        original.put(APPROVAL_REQUIRED,true);
        original.put(HUMAN_FEEDBACK_DATA,Map.of("feedback",true));
        original.put(PREFERRED_EXECUTION_PLAN,Map.of("sql","previous-task"));
        original.put(QUERY_PATTERN_ID,"previous-pattern");
        var update=node.apply(new OverAllState(original));original.putAll(update);
        var serializer=new DurableGraphStateSerializer();var restored=serializer.dataFromBytes(serializer.dataToBytes(original));
        assertThat(restored.get(QUERY_REPAIR_BUDGET)).isEqualTo(spent);
        assertThat(restored.get(ACTIVE_QUERY)).isEqualTo(second.question());
        assertThat(restored.get(APPROVED_PLAN_RECOVERY)).isEqualTo(false);
        assertThat(restored.get(HUMAN_REVIEW_ENABLED)).isEqualTo(true);
        assertThat(restored.get(HUMAN_FEEDBACK_DATA)).isEqualTo(Map.of());
        assertThat(restored.get(TYPED_SEMANTIC_PLAN)).isNull();
        assertThat(restored.get(POST_EXECUTION_REVIEW_OUTPUT)).isNull();
        assertThat(restored.get(PREFERRED_EXECUTION_PLAN)).isEqualTo(Map.of());
        assertThat(restored.get(QUERY_PATTERN_ID)).isEqualTo("");
        assertThat(new QueryRepairPolicy().consumeTransition((QueryRepairPolicy.RepairBudget)restored.get(QUERY_REPAIR_BUDGET),
            PostExecutionReview.Decision.RETRY_SQL).allowed()).isFalse();
        verify(tasks).activate("run","task-2");
    }
}
