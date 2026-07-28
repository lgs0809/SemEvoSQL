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
package cn.lgs.semevosql.service.aimodelconfig;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.properties.ModelClientProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ModelReadinessMonitorTest {

	@Test
	void staleSuccessfulValidationIsRecheckedAndRefreshed() {
		ModelConfigDataService data = mock(ModelConfigDataService.class);
		ModelConfigOpsService ops = mock(ModelConfigOpsService.class);
		ModelClientProperties properties = properties();
		ModelReadinessMonitor monitor = new ModelReadinessMonitor(data, ops, properties);
		ModelConfigDTO stale = model(5, "PASSED", LocalDateTime.now().minusHours(8));
		ModelConfigDTO effective = model(5, "PASSED", stale.getLastValidationTime());
		when(data.getConfigForTest(5)).thenReturn(effective);

		monitor.refreshIfStale(stale);

		verify(ops).testConnection(effective);
		verify(data).recordValidation(5, true);
	}

	@Test
	void failedReadinessProbePersistsUnavailableEvidence() {
		ModelConfigDataService data = mock(ModelConfigDataService.class);
		ModelConfigOpsService ops = mock(ModelConfigOpsService.class);
		ModelReadinessMonitor monitor = new ModelReadinessMonitor(data, ops, properties());
		ModelConfigDTO stale = model(5, "FAILED", LocalDateTime.now().minusMinutes(1));
		ModelConfigDTO effective = model(5, "FAILED", stale.getLastValidationTime());
		when(data.getConfigForTest(5)).thenReturn(effective);
		doThrow(new IllegalStateException("provider unavailable")).when(ops).testConnection(effective);

		monitor.refreshIfStale(stale);

		verify(data).recordValidation(5, false);
	}

	@Test
	void freshSuccessfulValidationDoesNotCreateProviderTraffic() {
		ModelConfigDataService data = mock(ModelConfigDataService.class);
		ModelConfigOpsService ops = mock(ModelConfigOpsService.class);
		ModelReadinessMonitor monitor = new ModelReadinessMonitor(data, ops, properties());

		monitor.refreshIfStale(model(5, "PASSED", LocalDateTime.now().minusMinutes(5)));

		verifyNoInteractions(data, ops);
	}

	private ModelClientProperties properties() {
		ModelClientProperties properties = new ModelClientProperties();
		properties.setValidationFreshness(Duration.ofHours(6));
		return properties;
	}

	private ModelConfigDTO model(int id, String status, LocalDateTime validatedAt) {
		return ModelConfigDTO.builder()
			.id(id)
			.modelType("CHAT")
			.validationStatus(status)
			.lastValidationTime(validatedAt)
			.build();
	}
}
