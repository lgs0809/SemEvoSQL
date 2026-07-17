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
package cn.lgs.semevosql.workflow.node;

import static cn.lgs.semevosql.constant.Constant.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.bo.schema.ResultBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.clarification.RuntimeClarificationService;
import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.properties.SemEvoSQLProperties;
import cn.lgs.semevosql.review.*;
import cn.lgs.semevosql.run.*;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PostExecutionSqlLineageTest {
    private static final String SQL = "SELECT SUM(amount) * 0.8 AS personal_amount FROM orders";
    private final PostExecutionReviewService review = mock(PostExecutionReviewService.class);
    private final RunNodeEffectService effects = mock(RunNodeEffectService.class);
    private final MultiSourceRunService artifacts = mock(MultiSourceRunService.class);
    private final PostExecutionReviewNode node = new PostExecutionReviewNode(review,new QueryRepairPolicy(),
        mock(RetrievalRepairService.class),mock(RuntimeClarificationService.class),effects,mock(QueryRunService.class),
        new SemEvoSQLProperties(),mock(RunExecutionFenceService.class),artifacts);

    @ParameterizedTest
    @ValueSource(strings={"canonical","legacy"})
    void actualSqlSurvivesCheckpointRoundTripAndDurableReviewReplay(String format) throws Exception {
        var lineage = format.equals("legacy") ? Map.of("1",SQL) : SqlExecutionLineage.append(Map.of(),1,SQL);
        var state = state(lineage);
        when(effects.inputHash(anyString())).thenReturn("exact-input");
        when(effects.completedPayload("","post-execution-review:1","exact-input")).thenReturn(Optional.empty());
        when(review.review(anyString(),any(),eq(SQL),any(),anyInt(),anyString(),any(),any(),anyList(),isNull()))
            .thenReturn(PostExecutionReview.deterministicPass(List.of()));
        node.apply(state);
        var persisted=ArgumentCaptor.forClass(String.class);
        verify(effects).recordCompleted(eq(""),eq(""),eq("post-execution-review:1"),eq("exact-input"),persisted.capture());
        assertThat(PostExecutionReviewNode.readEffect(persisted.getValue()).sql()).isEqualTo(SQL);
        when(effects.completedPayload("","post-execution-review:1","exact-input"))
            .thenReturn(Optional.of(persisted.getValue()));
        node.apply(state);
        verify(review,times(1)).review(anyString(),any(),eq(SQL),any(),anyInt(),anyString(),any(),any(),anyList(),isNull());
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","blank","wrong-step"})
    void missingExecutedSqlCannotReachReviewOrAcceptance(String format) throws Exception {
        Map<String,String> lineage=switch(format) {
            case "blank" -> Map.of("step_1"," ");
            case "wrong-step" -> Map.of("step_2",SQL);
            default -> Map.of();
        };
        assertThatThrownBy(()->node.apply(state(lineage))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Executed SQL is unavailable");
        verifyNoInteractions(review,effects,artifacts);
    }

    @Test void conflictingOldAndNewSqlIsRejectedButReexecutionReplacesOldLineage() throws Exception {
        var mixed=Map.of("1","SELECT 0","step_1",SQL);
        assertThatThrownBy(()->node.apply(state(mixed))).hasMessageContaining("Conflicting SQL execution lineage");
        verifyNoInteractions(review,effects,artifacts);
        assertThat(SqlExecutionLineage.append(mixed,1,"SELECT 2"))
            .containsEntry("step_1","SELECT 2").doesNotContainKey("1");
    }

    @SuppressWarnings("unchecked")
    private OverAllState state(Map<String,String> lineage) throws Exception {
        var set=ResultSetBO.builder().column(List.of("personal_amount"))
            .data(List.of(Map.of("personal_amount","336"))).build();
        var values=new LinkedHashMap<String,Object>();
        values.put(INPUT_KEY,"calculate personal amount");
        values.put(LAST_SQL_EXECUTED_STEP,1);
        values.put(LAST_SQL_RESULT_PAYLOAD,JsonUtil.getObjectMapper().writeValueAsString(ResultBO.builder().resultSet(set).build()));
        values.put(SQL_EXECUTED_QUERY_OUTPUT,lineage);
        values.put(TYPED_SEMANTIC_PLAN,SemanticBlueprint.builder().compilerMode("CONSTRAINED_GENERATION").build());
        // A real checkpoint restores structured state as maps, not necessarily the original DTO instances.
        return new OverAllState(JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(values),Map.class));
    }
}
