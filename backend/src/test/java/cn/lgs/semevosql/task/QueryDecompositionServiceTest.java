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
package cn.lgs.semevosql.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.lgs.semevosql.exception.ModelOutputInvalidException;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryDecompositionServiceTest {
    private QueryDecompositionService service(String response) {
        var client=mock(SemanticDocumentExtractionClient.class);
        when(client.complete(eq(ModelCallPurpose.QUERY_DECOMPOSITION),anyString(),anyString()))
            .thenReturn(new ModelCallResult("test",ModelCallPurpose.QUERY_DECOMPOSITION,response,1,1));
        return new QueryDecompositionService(client);
    }

    @Test void independentResultsHaveDurableQuestionsAndDependencies() {
        var answer=service("""
            {"requestType":"DATA_QUERY","needsTodo":true,"tasks":[{"question":"一月支付金额总计","dependsOn":[]},{"question":"一月每日支付金额趋势","dependsOn":[1]}]}
            """).analyze("一月总计和每日趋势分别给我");
        assertThat(answer.needsTodo()).isTrue();
        assertThat(answer.tasks()).hasSize(2);
        assertThat(answer.tasks().get(0).question()).isEqualTo("一月支付金额总计");
        assertThat(answer.tasks().get(1).dependencies()).containsExactly("task-1");
    }

    @Test void validSimpleAndNonDataRequestsRemainSupported() {
        assertThat(service("{\"requestType\":\"DATA_QUERY\",\"needsTodo\":false,\"tasks\":[]}").analyze("一月金额").needsTodo()).isFalse();
        assertThat(service("{\"requestType\":\"NON_DATA_QUERY\",\"needsTodo\":false,\"tasks\":[]}").analyze("你好").requestType())
            .isEqualTo(QueryDecompositionService.RequestType.NON_DATA_QUERY);
    }

    @Test void definitionManagementHasNoDataTaskAndRetainsItsOwnType() {
        assertThat(service("{\"requestType\":\"SEMANTIC_UPDATE\",\"needsTodo\":false,\"tasks\":[]}")
            .analyze("计算方法不变，只允许项目分享").requestType()).isEqualTo(QueryDecompositionService.RequestType.SEMANTIC_UPDATE);
    }

    @ParameterizedTest @ValueSource(strings={
        "{}", "not json",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":\"false\",\"tasks\":[]}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":true,\"tasks\":[{\"title\":\"总计\"},{\"title\":\"趋势\"}]}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":true,\"tasks\":[]}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":true,\"tasks\":[{\"question\":\"总计\",\"dependsOn\":[]}]}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":false,\"tasks\":[],\"needsTodo\":true}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":false,\"tasks\":[]} {}",
        "{\"requestType\":\"DATA_QUERY\",\"needsTodo\":true,\"tasks\":[{\"question\":\"总计\",\"dependsOn\":[]},{\"question\":\"趋势\",\"dependsOn\":[1.5]}]}"
    })
    void invalidAnalysisNeverFallsBackToSingleGoal(String output) {
        assertThatThrownBy(()->service(output).analyze("两份结果"))
            .isInstanceOf(ModelOutputInvalidException.class);
    }

    @Test void exhaustedTransportFailureIsPreservedInsteadOfLosingTasks() {
        var client=mock(SemanticDocumentExtractionClient.class);
        var failure=new IllegalStateException("provider unavailable");
        when(client.complete(eq(ModelCallPurpose.QUERY_DECOMPOSITION),anyString(),anyString())).thenThrow(failure);
        assertThatThrownBy(()->new QueryDecompositionService(client).analyze("总计和趋势"))
            .isSameAs(failure);
    }
}
