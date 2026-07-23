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
package cn.lgs.semevosql.service.nl2sql;

import cn.lgs.semevosql.dto.prompt.SqlGenerationDTO;
import cn.lgs.semevosql.dto.schema.SchemaDTO;
import cn.lgs.semevosql.prompt.SemanticSqlPromptContract;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.JsonParseUtil;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GovernedSqlGenerationTest {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void initialAndFailedSqlUseSystemContractAndRemainingRunDeadline(boolean repair) {
		var llm = mock(LlmService.class);
		var output = Flux.just(ChatResponseUtil.createPureResponse("SELECT METRIC('invoices.invoice_count') FROM invoices"));
		when(llm.callWithin(anyString(), anyString(), any(Duration.class))).thenReturn(output);
		when(llm.toStringFlux(output)).thenReturn(Flux.just("verified test output"));
		var schema = new SchemaDTO();
		schema.setTable(List.of());
		var plan = SemanticBlueprint.builder()
			.models(List.of(SemanticBlueprint.ModelSelection.builder().modelCode("invoices").build()))
			.metrics(List.of(SemanticBlueprint.MetricSelection.builder().modelCode("invoices")
				.metricCode("invoice_count").expression("COUNT(*)").build()))
			.build();
		var input = SqlGenerationDTO.builder().query("按月统计发票占比").dialect("postgresql").schemaDTO(schema)
			.semanticModel(SemanticSqlPromptContract.render(plan)).semanticPlan("{}")
			.executionDescription("按业务口径分组，保留确认的输出列").evidence("无")
			.sql(repair ? "SELECT COUNT(*) FROM public.invoices" : null)
			.exceptionMessage(repair ? "SQL does not use the published metric expression: invoice_count" : null)
			.runDeadlineEpochMillis(System.currentTimeMillis() + 60000).build();
		assertThat(new Nl2SqlServiceImpl(llm, mock(JsonParseUtil.class)).generateSql(input).collectList().block())
			.containsExactly("verified test output");
		var system = ArgumentCaptor.forClass(String.class);
		var remaining = ArgumentCaptor.forClass(Duration.class);
		verify(llm).callWithin(system.capture(), anyString(), remaining.capture());
		verify(llm, never()).callUserWithin(anyString(), any(Duration.class));
		assertThat(system.getValue()).contains("METRIC('invoices.invoice_count')", "Frozen Semantic SQL lowering contract");
		assertThat(remaining.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60));
		if (repair) {
			assertThat(system.getValue()).contains("本节点始终生成 Semantic SQL", "SELECT COUNT(*) FROM public.invoices");
		}
	}

}
