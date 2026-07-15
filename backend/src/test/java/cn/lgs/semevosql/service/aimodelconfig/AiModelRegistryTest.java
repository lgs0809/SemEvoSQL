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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.semantic.retrieval.RerankModel;
import org.junit.jupiter.api.Test;

class AiModelRegistryTest {

	@Test
	void missingRerankConfigurationFailsClearly() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configDataService = mock(ModelConfigDataService.class);
		when(configDataService.getActiveConfigByType(ModelType.RERANK)).thenReturn(null);
		AiModelRegistry registry = new AiModelRegistry(modelFactory, configDataService);

		assertThatThrownBy(registry::currentRerankModel)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RERANK");
		verify(configDataService).getActiveConfigByType(ModelType.RERANK);
		verifyNoInteractions(modelFactory);
	}

	@Test
	void activeRerankModelIsCachedUntilRefresh() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDataService configDataService = mock(ModelConfigDataService.class);
		ModelConfigDTO config = mock(ModelConfigDTO.class);
		RerankModel rerankModel = mock(RerankModel.class);
		when(configDataService.getActiveConfigByType(ModelType.RERANK)).thenReturn(config);
		when(modelFactory.createRerankModel(config)).thenReturn(rerankModel);
		AiModelRegistry registry = new AiModelRegistry(modelFactory, configDataService);

		assertThat(registry.currentRerankModel()).isSameAs(rerankModel);
		assertThat(registry.currentRerankModel()).isSameAs(rerankModel);
		verify(configDataService).getActiveConfigByType(ModelType.RERANK);
		verify(modelFactory).createRerankModel(config);

		registry.refreshRerank();
		assertThat(registry.currentRerankModel()).isSameAs(rerankModel);
		verify(configDataService, times(2)).getActiveConfigByType(ModelType.RERANK);
		verify(modelFactory, times(2)).createRerankModel(config);
	}

}
