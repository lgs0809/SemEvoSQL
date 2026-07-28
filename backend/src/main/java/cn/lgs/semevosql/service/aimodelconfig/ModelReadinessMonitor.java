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

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.properties.ModelClientProperties;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps persisted model readiness evidence fresh without turning actuator health into a remote-provider dependency. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelReadinessMonitor {

	private final ModelConfigDataService dataService;

	private final ModelConfigOpsService opsService;

	private final ModelClientProperties properties;

	private final AtomicBoolean running = new AtomicBoolean();

	@Scheduled(fixedDelayString = "${semevosql.model-client.validation-monitor-delay:15m}",
			initialDelayString = "${semevosql.model-client.validation-monitor-initial-delay:60s}")
	public void refreshStaleActiveModels() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			for (ModelType type : new ModelType[] { ModelType.CHAT, ModelType.EMBEDDING, ModelType.RERANK }) {
				refreshIfStale(dataService.getActiveConfigByType(type));
			}
		}
		finally {
			running.set(false);
		}
	}

	void refreshIfStale(ModelConfigDTO model) {
		if (model == null || model.getId() == null || !stale(model)) {
			return;
		}
		ModelConfigDTO effective = dataService.getConfigForTest(model.getId());
		try {
			opsService.testConnection(effective);
			dataService.recordValidation(model.getId(), true);
			log.info("Refreshed model readiness. type={}, modelId={}", model.getModelType(), model.getId());
		}
		catch (RuntimeException failure) {
			dataService.recordValidation(model.getId(), false);
			log.warn("Model readiness probe failed. type={}, modelId={}, errorType={}", model.getModelType(), model.getId(),
					failure.getClass().getSimpleName());
		}
	}

	private boolean stale(ModelConfigDTO model) {
		if (!"PASSED".equals(model.getValidationStatus()) || model.getLastValidationTime() == null) {
			return true;
		}
		return model.getLastValidationTime().isBefore(LocalDateTime.now().minus(properties.getValidationFreshness()));
	}
}
